package tgengine

import (
	"context"
	"fmt"
	"time"

	"github.com/gotd/td/telegram/peers"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
)

// normalizeChannelID accepts a raw channel id (positive) or a "marked" id as
// used by Bot API / Telethon (e.g. -1001234567890) and returns the raw channel
// id that gotd's InputChannel expects. marked = -(1_000_000_000_000 + channelID).
func normalizeChannelID(v int64) int64 {
	const markPrefix = 1_000_000_000_000
	a := v
	if a < 0 {
		a = -a
	}
	if a > markPrefix {
		a -= markPrefix
	}
	return a
}

// syncDialogs walks all dialogs and applies their entities to the peer manager
// so channel access hashes become known (required to resolve a bare channel id).
func (e *Engine) syncDialogs(ctx context.Context) error {
	var (
		offsetDate int
		offsetID   int
		offsetPeer tg.InputPeerClass = &tg.InputPeerEmpty{}
	)
	for page := 0; page < 100; page++ {
		d, err := e.api.MessagesGetDialogs(ctx, &tg.MessagesGetDialogsRequest{
			OffsetDate: offsetDate,
			OffsetID:   offsetID,
			OffsetPeer: offsetPeer,
			Limit:      100,
		})
		if err != nil {
			return err
		}

		var (
			users    []tg.UserClass
			chats    []tg.ChatClass
			dialogs  []tg.DialogClass
			messages []tg.MessageClass
			slice    bool
		)
		switch v := d.(type) {
		case *tg.MessagesDialogs:
			users, chats, dialogs, messages = v.Users, v.Chats, v.Dialogs, v.Messages
		case *tg.MessagesDialogsSlice:
			users, chats, dialogs, messages, slice = v.Users, v.Chats, v.Dialogs, v.Messages, true
		default:
			return nil
		}

		if err := e.peers.Apply(ctx, users, chats); err != nil {
			return err
		}
		if !slice || len(dialogs) == 0 {
			return nil // got everything
		}

		// Compute offsets for the next page from the last dialog.
		last, ok := dialogs[len(dialogs)-1].(*tg.Dialog)
		if !ok {
			return nil
		}
		offsetID = last.TopMessage
		offsetDate = lastMessageDate(messages, last.TopMessage, offsetDate)
		offsetPeer = inputPeerFor(last.Peer, users, chats)
		if offsetPeer == nil {
			return nil
		}
	}
	return nil
}

func lastMessageDate(messages []tg.MessageClass, topID, fallback int) int {
	for _, m := range messages {
		switch v := m.(type) {
		case *tg.Message:
			if v.ID == topID {
				return v.Date
			}
		case *tg.MessageService:
			if v.ID == topID {
				return v.Date
			}
		}
	}
	return fallback
}

// inputPeerFor builds an InputPeer for a dialog peer using the entities just
// returned, so pagination can continue.
func inputPeerFor(p tg.PeerClass, users []tg.UserClass, chats []tg.ChatClass) tg.InputPeerClass {
	switch peer := p.(type) {
	case *tg.PeerUser:
		for _, u := range users {
			if uu, ok := u.(*tg.User); ok && uu.ID == peer.UserID {
				return &tg.InputPeerUser{UserID: uu.ID, AccessHash: uu.AccessHash}
			}
		}
	case *tg.PeerChat:
		return &tg.InputPeerChat{ChatID: peer.ChatID}
	case *tg.PeerChannel:
		for _, c := range chats {
			if cc, ok := c.(*tg.Channel); ok && cc.ID == peer.ChannelID {
				return &tg.InputPeerChannel{ChannelID: cc.ID, AccessHash: cc.AccessHash}
			}
		}
	}
	return nil
}

// resolveChannel returns the InputChannel for a (possibly marked) channel id.
// It re-syncs dialogs once if the id is not yet known.
func (e *Engine) resolveChannel(ctx context.Context, channelID int64) (tg.InputChannelClass, error) {
	// Fast path: cached InputChannel (access hash is stable for the session).
	e.chanMu.Lock()
	if ch, ok := e.chanCache[channelID]; ok {
		e.chanMu.Unlock()
		return ch, nil
	}
	e.chanMu.Unlock()

	id := normalizeChannelID(channelID)
	input, err := func() (tg.InputChannelClass, error) {
		if ch, err := e.peers.ResolveChannelID(ctx, id); err == nil {
			return ch.InputChannel(), nil
		}
		// Not cached yet: refresh dialogs and retry.
		logI("channel %d not cached, re-syncing dialogs", id)
		if err := e.syncDialogs(ctx); err != nil {
			return nil, fmt.Errorf("dialog sync: %w", err)
		}
		ch, err := e.peers.ResolveChannelID(ctx, id)
		if err != nil {
			return nil, fmt.Errorf("channel %d not accessible (account not a member?): %w", id, err)
		}
		logI("channel %d resolved after re-sync", id)
		return ch.InputChannel(), nil
	}()
	if err != nil {
		return nil, err
	}

	e.chanMu.Lock()
	e.chanCache[channelID] = input
	e.chanMu.Unlock()
	return input, nil
}

// docCall is an in-flight fetchDocument, shared by every caller that asks for
// the same (channelID, msgID) while the first call is still running.
type docCall struct {
	done chan struct{}
	doc  *tg.Document
	err  error
}

// fetchDocument returns the document for a single message id in a channel,
// caching by (channelID, msgID). Concurrent callers for the same key share a
// single channels.getMessages call (single-flight) so a burst of player
// requests can't fan out into N metadata calls and trip a FLOOD_WAIT.
func (e *Engine) fetchDocument(_ context.Context, channelID int64, input tg.InputChannelClass, msgID int) (*tg.Document, error) {
	key := fmt.Sprintf("%d:%d", channelID, msgID)

	e.docMu.Lock()
	if d, ok := e.docs[key]; ok {
		e.docMu.Unlock()
		return d, nil
	}
	if call, ok := e.docInflight[key]; ok {
		// Someone else is already fetching this exact message; wait for it
		// instead of issuing a duplicate (flood-prone) getMessages.
		e.docMu.Unlock()
		<-call.done
		return call.doc, call.err
	}
	call := &docCall{done: make(chan struct{})}
	e.docInflight[key] = call
	e.docMu.Unlock()

	doc, err := e.getMessageDocument(channelID, input, msgID)

	e.docMu.Lock()
	call.doc, call.err = doc, err
	if err == nil {
		e.docs[key] = doc
	}
	delete(e.docInflight, key)
	close(call.done)
	e.docMu.Unlock()

	return doc, err
}

// getMessageDocument performs the actual channels.getMessages call with
// flood-wait and transient-error handling, mirroring getChunk. It runs on the
// engine-lived dlCtx (NOT a per-request context): a metadata fetch must survive
// a player seek/abort that cancels the HTTP request, otherwise the cancellation
// races the single-flight slot and the next request re-issues getMessages,
// extending Telegram's flood timer into a permanent block.
func (e *Engine) getMessageDocument(channelID int64, input tg.InputChannelClass, msgID int) (*tg.Document, error) {
	ctx := e.dlCtx
	const maxAttempts = 6
	for attempt := 0; attempt < maxAttempts; attempt++ {
		res, err := e.api.ChannelsGetMessages(ctx, &tg.ChannelsGetMessagesRequest{
			Channel: input,
			ID:      []tg.InputMessageClass{&tg.InputMessageID{ID: msgID}},
		})
		if err != nil {
			if d, ok := tgerr.AsFloodWait(err); ok {
				logW("getMessages flood wait %v on channel=%d msg=%d (attempt %d)", d, channelID, msgID, attempt)
				select {
				case <-time.After(d):
					continue
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			// Transient connection blip: brief backoff and retry.
			if attempt < maxAttempts-1 {
				logW("getMessages channel=%d msg=%d transient: %v (retry %d)", channelID, msgID, err, attempt)
				select {
				case <-time.After(300 * time.Millisecond):
					continue
				case <-ctx.Done():
					return nil, ctx.Err()
				}
			}
			logE("getMessages channel=%d msg=%d failed: %v", channelID, msgID, err)
			return nil, fmt.Errorf("channels.getMessages msg %d: %w", msgID, err)
		}
		doc, err := documentOf(res)
		if err != nil {
			logE("getMessages channel=%d msg=%d: %v", channelID, msgID, err)
			return nil, fmt.Errorf("msg %d: %w", msgID, err)
		}
		return doc, nil
	}
	return nil, fmt.Errorf("getMessages msg %d: too many retries", msgID)
}

// refreshDocument forces a re-fetch (used when a file reference expires).
func (e *Engine) refreshDocument(ctx context.Context, channelID int64, input tg.InputChannelClass, msgID int) (*tg.Document, error) {
	key := fmt.Sprintf("%d:%d", channelID, msgID)
	e.docMu.Lock()
	delete(e.docs, key)
	e.docMu.Unlock()
	return e.fetchDocument(ctx, channelID, input, msgID)
}

func documentOf(res tg.MessagesMessagesClass) (*tg.Document, error) {
	var msgs []tg.MessageClass
	switch v := res.(type) {
	case *tg.MessagesChannelMessages:
		msgs = v.Messages
	case *tg.MessagesMessages:
		msgs = v.Messages
	default:
		return nil, fmt.Errorf("unexpected getMessages type %T", res)
	}
	if len(msgs) == 0 {
		return nil, fmt.Errorf("message not found")
	}
	m, ok := msgs[0].(*tg.Message)
	if !ok {
		return nil, fmt.Errorf("message is %T", msgs[0])
	}
	md, ok := m.Media.(*tg.MessageMediaDocument)
	if !ok {
		return nil, fmt.Errorf("media is %T, not a document", m.Media)
	}
	doc, ok := md.Document.(*tg.Document)
	if !ok {
		return nil, fmt.Errorf("document is %T (deleted?)", md.Document)
	}
	return doc, nil
}

func documentName(doc *tg.Document) string {
	for _, attr := range doc.Attributes {
		if fn, ok := attr.(*tg.DocumentAttributeFilename); ok {
			return fn.FileName
		}
	}
	return ""
}

var _ = peers.Channel{} // keep peers imported for type references
