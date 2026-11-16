package tgengine

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
)

// loginCoordinator owns a single authentication attempt. The connection and
// download contexts outlive method changes; prompt inputs do not.
type loginCoordinator struct {
	mu         sync.Mutex
	commands   chan string
	emit       func(string, string)
	running    bool
	current    context.Context
	cancel     context.CancelFunc
	phase      string
	input      chan string
	retryAfter time.Time
}

func newLoginCoordinator(emit func(string, string)) *loginCoordinator {
	return &loginCoordinator{commands: make(chan string, 1), emit: emit}
}

func (c *loginCoordinator) selectMethod(method string) error {
	if method != "" && method != "phone" && method != "qr" {
		return errors.New("unknown login method")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.running {
		return errors.New("login is not awaiting a method")
	}
	if method != "" && time.Now().Before(c.retryAfter) {
		return fmt.Errorf("Telegram requires a wait of %d seconds before retrying", int(time.Until(c.retryAfter).Seconds())+1)
	}
	if method == "" {
		// Back must supersede a selection that has not yet been consumed. The
		// coordinator is the only other receiver, so this never blocks.
		select {
		case <-c.commands:
		default:
		}
	}
	select {
	case c.commands <- method:
		if c.cancel != nil {
			c.cancel()
		}
		c.input = nil
		return nil
	default:
		return errors.New("a login method change is already pending")
	}
}

func (c *loginCoordinator) submit(phase, value string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.current == nil || c.current.Err() != nil || c.input == nil || c.phase != phase {
		return errors.New("login is not awaiting this input")
	}
	if value == "" {
		return errors.New("login input cannot be empty")
	}
	c.input <- value // buffered and consumed at most once
	c.input = nil
	return nil
}

func (c *loginCoordinator) ask(ctx context.Context, phase, message string) (string, error) {
	ch := make(chan string, 1)
	c.mu.Lock()
	if ctx.Err() != nil || c.current != ctx {
		c.mu.Unlock()
		return "", context.Canceled
	}
	c.phase, c.input = phase, ch
	c.mu.Unlock()
	c.emit(phase, message)
	select {
	case <-ctx.Done():
		return "", ctx.Err()
	case value := <-ch:
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		return value, nil
	}
}

func (c *loginCoordinator) run(ctx context.Context, status func(context.Context) (bool, error), phone, qr func(context.Context) error) error {
	authorized, err := status(ctx)
	if err != nil || authorized {
		return err
	}
	c.mu.Lock()
	c.running = true
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		c.running, c.current, c.input, c.cancel = false, nil, nil, nil
		c.mu.Unlock()
	}()
	c.emit(StateNeedLoginMethod, "")
	var result chan error
	stopAttempt := func() error {
		if result == nil {
			return context.Canceled
		}
		c.mu.Lock()
		c.cancel()
		c.input = nil
		c.mu.Unlock()
		err := <-result
		result = nil
		return err
	}
	for {
		select {
		case <-ctx.Done():
			stopAttempt()
			return ctx.Err()
		case method := <-c.commands:
			if result != nil && stopAttempt() == nil {
				return nil
			}
			// A server-side authorization may have completed just before cancellation.
			authorized, err := status(ctx)
			if err != nil {
				return err
			}
			if authorized {
				return nil
			}
			if method == "" {
				c.emit(StateNeedLoginMethod, "")
				continue
			}
			attemptCtx, cancel := context.WithCancel(ctx)
			c.mu.Lock()
			c.current, c.cancel, c.input = attemptCtx, cancel, nil
			c.mu.Unlock()
			result = make(chan error, 1)
			flow := phone
			if method == "qr" {
				flow = qr
			}
			go func(out chan<- error) { out <- flow(attemptCtx) }(result)
		case err := <-result:
			result = nil
			c.mu.Lock()
			c.cancel()
			c.input = nil
			if wait, ok := tgerr.AsFloodWait(err); ok {
				c.retryAfter = time.Now().Add(wait + time.Second)
			}
			c.mu.Unlock()
			if err == nil {
				return nil
			}
			if !errors.Is(err, context.Canceled) {
				c.emit(StateError, loginErrorMessage(err))
			}
		}
	}
}

func loginErrorMessage(err error) string {
	if wait, ok := tgerr.AsFloodWait(err); ok {
		return fmt.Sprintf("Telegram requires a wait of %d seconds before retrying", int(wait.Seconds())+1)
	}
	for _, code := range []string{"PHONE_NUMBER_INVALID", "PHONE_CODE_INVALID", "PHONE_CODE_EXPIRED", "API_ID_INVALID", "PASSWORD_HASH_INVALID", "AUTH_TOKEN_EXPIRED"} {
		if tgerr.Is(err, code) {
			return "Telegram login failed: " + code
		}
	}
	return "Unable to log in to Telegram. Check your connection and try again."
}

// SelectLoginMethod cancels the previous attempt and selects "phone" or "qr".
func (e *Engine) SelectLoginMethod(method string) error { return e.login.selectMethod(method) }

// IsLoginActive distinguishes a retryable auth prompt from a failed connection.
func (e *Engine) IsLoginActive() bool {
	e.login.mu.Lock()
	defer e.login.mu.Unlock()
	return e.login.running
}

// CancelLogin returns an unfinished login to the method selector.
func (e *Engine) CancelLogin() { _ = e.login.selectMethod("") }

// SubmitPhone supplies the current phone prompt without blocking.
func (e *Engine) SubmitPhone(value string) error { return e.login.submit(StateNeedPhone, value) }

// SubmitCode supplies the current code prompt without blocking.
func (e *Engine) SubmitCode(value string) error { return e.login.submit(StateNeedCode, value) }

// SubmitPassword supplies the current 2FA prompt, preserving whitespace.
func (e *Engine) SubmitPassword(value string) error { return e.login.submit(StateNeedPassword, value) }

type bridgeAuth struct{ e *Engine }

func (b bridgeAuth) Phone(ctx context.Context) (string, error) {
	return b.e.login.ask(ctx, StateNeedPhone, "")
}
func (b bridgeAuth) Code(ctx context.Context, _ *tg.AuthSentCode) (string, error) {
	return b.e.login.ask(ctx, StateNeedCode, "")
}
func (b bridgeAuth) Password(ctx context.Context) (string, error) {
	return b.e.login.ask(ctx, StateNeedPassword, "")
}
func (b bridgeAuth) AcceptTermsOfService(context.Context, tg.HelpTermsOfService) error { return nil }
func (b bridgeAuth) SignUp(context.Context) (auth.UserInfo, error) {
	return auth.UserInfo{}, errors.New("signup not supported")
}
