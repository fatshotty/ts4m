package main

import (
	"context"
	"fmt"
	"os"
	"strconv"

	"github.com/gotd/td/telegram/peers"
	"github.com/gotd/td/tg"
)

// normalizeChannelID accepts either a raw channel id (positive) or a "marked"
// id as used by Bot API / Telethon (e.g. -1001234567890) and returns the raw
// channel id that gotd's InputChannel expects.
//
// marked = -(1_000_000_000_000 + channelID)
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

// downloadProbe proves the full read path:
//   (channel_id, message_id) -> resolve channel access_hash -> get message ->
//   extract document -> upload.getFile first 1 MiB chunk.
func downloadProbe(ctx context.Context, api *tg.Client, chRaw, msgRaw string) error {
	chParsed, err := strconv.ParseInt(chRaw, 10, 64)
	if err != nil {
		return fmt.Errorf("TG_CHANNEL not an int: %w", err)
	}
	msgID, err := strconv.Atoi(msgRaw)
	if err != nil {
		return fmt.Errorf("TG_MSG not an int: %w", err)
	}

	mgr := peers.Options{}.Build(api)

	// Candidates: the normalized raw id and (as a fallback) the absolute value.
	candidates := []int64{normalizeChannelID(chParsed)}
	if abs := chParsed; abs < 0 {
		if a := -abs; a != candidates[0] {
			candidates = append(candidates, a)
		}
	}

	var channel peers.Channel
	var resolved bool
	pages := 0
	for !resolved && pages < 20 {
		for _, id := range candidates {
			if c, err := mgr.ResolveChannelID(ctx, id); err == nil {
				channel = c
				resolved = true
				fmt.Printf("  resolved channel raw id=%d (from %d)\n", id, chParsed)
				break
			}
		}
		if resolved {
			break
		}
		more, err := applyDialogsPage(ctx, api, mgr, pages == 0)
		if err != nil {
			return fmt.Errorf("dialogs page %d: %w", pages, err)
		}
		pages++
		if !more {
			break
		}
	}
	if !resolved {
		return fmt.Errorf("could not resolve channel %d after %d dialog pages (account not a member?)", chParsed, pages)
	}

	res, err := api.ChannelsGetMessages(ctx, &tg.ChannelsGetMessagesRequest{
		Channel: channel.InputChannel(),
		ID:      []tg.InputMessageClass{&tg.InputMessageID{ID: msgID}},
	})
	if err != nil {
		return fmt.Errorf("channels.getMessages: %w", err)
	}
	doc, name, err := extractDocument(res)
	if err != nil {
		return err
	}

	loc := &tg.InputDocumentFileLocation{
		ID:            doc.ID,
		AccessHash:    doc.AccessHash,
		FileReference: doc.FileReference,
	}
	const oneMiB = 1024 * 1024
	limit := oneMiB
	if doc.Size < int64(limit) {
		// limit must stay a multiple of 4 KiB; round up the document size.
		limit = int((doc.Size + 4095) / 4096 * 4096)
		if limit == 0 {
			limit = 4096
		}
	}
	fr, err := api.UploadGetFile(ctx, &tg.UploadGetFileRequest{
		Location: loc,
		Offset:   0,
		Limit:    limit,
	})
	if err != nil {
		return fmt.Errorf("upload.getFile: %w", err)
	}
	uf, ok := fr.(*tg.UploadFile)
	if !ok {
		return fmt.Errorf("unexpected getFile result type %T (CDN redirect?)", fr)
	}

	fmt.Printf("DOWNLOAD OK ✅  name=%q docID=%d size=%d mime=%q firstChunk=%d bytes\n",
		name, doc.ID, doc.Size, doc.MimeType, len(uf.Bytes))
	return nil
}

// applyDialogsPage fetches one page of dialogs and applies the entities to the
// manager so channel access hashes become known. Returns whether more pages exist.
//
// For simplicity the spike always restarts from the top; a single page of 100
// usually contains the target. The real engine will paginate with offsets.
func applyDialogsPage(ctx context.Context, api *tg.Client, mgr *peers.Manager, _ bool) (bool, error) {
	d, err := api.MessagesGetDialogs(ctx, &tg.MessagesGetDialogsRequest{
		OffsetPeer: &tg.InputPeerEmpty{},
		Limit:      100,
	})
	if err != nil {
		return false, err
	}
	switch v := d.(type) {
	case *tg.MessagesDialogs:
		_ = mgr.Apply(ctx, v.Users, v.Chats)
		return false, nil
	case *tg.MessagesDialogsSlice:
		_ = mgr.Apply(ctx, v.Users, v.Chats)
		return false, nil // spike: single page only
	default:
		return false, nil
	}
}

func extractDocument(res tg.MessagesMessagesClass) (*tg.Document, string, error) {
	var msgs []tg.MessageClass
	switch v := res.(type) {
	case *tg.MessagesChannelMessages:
		msgs = v.Messages
	case *tg.MessagesMessages:
		msgs = v.Messages
	default:
		return nil, "", fmt.Errorf("unexpected getMessages result type %T", res)
	}
	if len(msgs) == 0 {
		return nil, "", fmt.Errorf("message not found")
	}
	m, ok := msgs[0].(*tg.Message)
	if !ok {
		return nil, "", fmt.Errorf("message is %T, not a normal message", msgs[0])
	}
	mediaDoc, ok := m.Media.(*tg.MessageMediaDocument)
	if !ok {
		return nil, "", fmt.Errorf("message media is %T, not a document", m.Media)
	}
	doc, ok := mediaDoc.Document.(*tg.Document)
	if !ok {
		return nil, "", fmt.Errorf("document is %T (deleted?)", mediaDoc.Document)
	}
	name := ""
	for _, attr := range doc.Attributes {
		if fn, ok := attr.(*tg.DocumentAttributeFilename); ok {
			name = fn.FileName
		}
	}
	return doc, name, nil
}

// maybeDownloadProbe runs the read-path probe if TG_CHANNEL/TG_MSG are set.
func maybeDownloadProbe(ctx context.Context, api *tg.Client) {
	ch := os.Getenv("TG_CHANNEL")
	if ch == "" {
		return
	}
	fmt.Println("→ download probe ...")
	if err := downloadProbe(ctx, api, ch, os.Getenv("TG_MSG")); err != nil {
		fmt.Printf("DOWNLOAD PROBE FAILED ❌  %v\n", err)
	}
}
