package tgengine

import (
	"context"
	"errors"
	"time"

	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/telegram/auth/qrlogin"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
	"rsc.io/qr"
)

// QRLoginListener receives a small QR PNG and its server-provided expiry.
// Images are transient account credentials and must never be logged or saved.
type QRLoginListener interface {
	OnQRCode(pngData []byte, expiresAtUnix int64)
}

// SetQRLoginListener registers the QR display callback.
func (e *Engine) SetQRLoginListener(listener QRLoginListener) {
	e.mu.Lock()
	e.qrListener = listener
	e.mu.Unlock()
}

type qrOperations interface {
	Export(context.Context, ...int64) (qrlogin.Token, error)
	Import(context.Context) (*tg.AuthAuthorization, error)
}

func loginTimer(d time.Duration) (<-chan time.Time, func()) {
	timer := time.NewTimer(d)
	return timer.C, func() { timer.Stop() }
}

// Use the pinned helper's primitives rather than Auth: its initial migration
// and already-accepted-token paths need to complete without waiting for an update.
func runQRFlow(ctx context.Context, q qrOperations, updates <-chan struct{}, show func(context.Context, qrlogin.Token) error, timer func(time.Duration) (<-chan time.Time, func())) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		token, err := q.Export(ctx)
		var migration *qrlogin.MigrationNeededError
		if errors.As(err, &migration) {
			_, err = q.Import(ctx) // Client.QR supplies MigrateTo, including token import.
			return err
		}
		if err != nil {
			return err
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if token.Empty() {
			return nil
		}
		remaining := time.Until(token.Expires())
		if remaining <= 0 {
			return tgerr.New(400, "AUTH_TOKEN_EXPIRED")
		}
		if err := show(ctx, token); err != nil {
			return err
		}
		tick, stop := timer(remaining)
		select {
		case <-ctx.Done():
			stop()
			return ctx.Err()
		case <-updates:
		case <-tick:
		}
		stop()
	}
}

func qrPNG(token qrlogin.Token) ([]byte, error) {
	// rsc.io/qr's Image.At does not apply Scale/quiet-zone coordinates in the
	// pinned version. Its native PNG encoder handles both correctly.
	code, err := qr.Encode(token.URL(), qr.M)
	if err != nil {
		return nil, err
	}
	return code.PNG(), nil
}

func (e *Engine) runQRLogin(ctx context.Context, client *telegram.Client) error {
	err := runQRFlow(ctx, client.QR(), e.loginUpdates, func(ctx context.Context, token qrlogin.Token) error {
		data, err := qrPNG(token)
		if err != nil {
			return err
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		e.setState(StateWaitQR, "")
		e.mu.Lock()
		listener := e.qrListener
		e.mu.Unlock()
		if listener != nil && ctx.Err() == nil {
			listener.OnQRCode(data, token.Expires().Unix())
		}
		return ctx.Err()
	}, loginTimer)
	if tgerr.Is(err, "SESSION_PASSWORD_NEEDED") || errors.Is(err, auth.ErrPasswordAuthNeeded) {
		return passwordLogin(ctx, func(ctx context.Context, message string) (string, error) {
			return e.login.ask(ctx, StateNeedPassword, message)
		}, func(ctx context.Context, password string) error {
			_, err := client.Auth().Password(ctx, password)
			return err
		})
	}
	return err
}

func passwordLogin(ctx context.Context, ask func(context.Context, string) (string, error), check func(context.Context, string) error) error {
	message := ""
	for {
		password, err := ask(ctx, message)
		if err != nil {
			return err
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		err = check(ctx, password)
		if !errors.Is(err, auth.ErrPasswordInvalid) && !tgerr.Is(err, "PASSWORD_HASH_INVALID") {
			return err
		}
		message = "Incorrect password. Try again."
	}
}

// Keep the standard phone/code flow, with the same retry behavior for 2FA.
type phoneAuthClient struct {
	*auth.Client
	e *Engine
}

func (c phoneAuthClient) Password(ctx context.Context, initial string) (*tg.AuthAuthorization, error) {
	var result *tg.AuthAuthorization
	first := true
	err := passwordLogin(ctx, func(ctx context.Context, message string) (string, error) {
		if first {
			first = false
			return initial, nil
		}
		return c.e.login.ask(ctx, StateNeedPassword, message)
	}, func(ctx context.Context, password string) error {
		var err error
		result, err = c.Client.Password(ctx, password)
		return err
	})
	return result, err
}
