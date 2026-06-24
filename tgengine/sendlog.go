package tgengine

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/gotd/td/telegram/message"
	"github.com/gotd/td/telegram/uploader"
)

// SendLog uploads the given text as a .txt document to the logged-in user's
// Saved Messages (the "self" chat), so a log can be shipped off the device for
// debugging without adb. name is the file name shown in Telegram. Blocks until
// the upload+send completes (call it off the main thread on the Kotlin side).
func (e *Engine) SendLog(name, text string) error {
	e.mu.Lock()
	api := e.api
	e.mu.Unlock()
	if api == nil {
		return errors.New("engine not ready (not authorized)")
	}
	if name == "" {
		name = "tgstream-log.txt"
	}

	// Bound the operation; run on the engine-lived context so it isn't tied to
	// any HTTP request lifecycle.
	ctx, cancel := context.WithTimeout(e.dlCtx, 60*time.Second)
	defer cancel()

	file, err := uploader.NewUploader(api).FromBytes(ctx, name, []byte(text))
	if err != nil {
		return fmt.Errorf("upload: %w", err)
	}
	// ForceFile so Telegram keeps it as a plain document attachment.
	doc := message.UploadedDocument(file).Filename(name).MIME("text/plain").ForceFile(true)
	if _, err := message.NewSender(api).Self().Media(ctx, doc); err != nil {
		return fmt.Errorf("send: %w", err)
	}
	logI("sent log (%d bytes) to Saved Messages as %q", len(text), name)
	return nil
}
