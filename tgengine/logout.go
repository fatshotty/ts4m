package tgengine

import (
	"context"
	"errors"
	"os"
	"time"

	"github.com/gotd/td/telegram/auth"
)

// IsLogoutPending reports remote logout success with local cleanup outstanding.
func (e *Engine) IsLogoutPending() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.logoutPending
}

// Logout revokes this app's session, stops the engine, and removes session data.
// It is safe to retry after cleanup failure; a successful engine is terminal.
func (e *Engine) Logout() error {
	return e.logoutWith(func(ctx context.Context) error {
		e.mu.Lock()
		client := e.client
		e.mu.Unlock()
		if client == nil {
			return errors.New("Telegram is not connected")
		}
		_, err := client.API().AuthLogOut(ctx)
		return err
	}, os.Remove)
}

func (e *Engine) logoutWith(remote func(context.Context) error, remove func(string) error) error {
	e.lifecycleMu.Lock()
	defer e.lifecycleMu.Unlock()
	e.mu.Lock()
	if e.loggedOut {
		e.mu.Unlock()
		return nil
	}
	pending := e.logoutPending
	e.loggingOut = true
	e.mu.Unlock()
	e.setState(StateLoggingOut, "")
	if !pending {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		err := remote(ctx)
		cancel()
		if err != nil && !auth.IsUnauthorized(err) {
			e.mu.Lock()
			e.loggingOut = false
			e.mu.Unlock()
			return errors.New("Unable to log out of Telegram. Check your connection and try again.")
		}
		e.mu.Lock()
		e.logoutPending = true
		e.mu.Unlock()
	}
	e.stopLocked()
	if err := remove(e.session); err != nil && !os.IsNotExist(err) {
		return errors.New("Telegram is disconnected, but local session cleanup failed. Retry logout.")
	}
	e.mu.Lock()
	e.logoutPending, e.loggingOut, e.loggedOut = false, false, true
	e.mu.Unlock()
	e.setState(StateLoggedOut, "")
	return nil
}
