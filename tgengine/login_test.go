package tgengine

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gotd/td/tgerr"
)

func receiveLoginState(t *testing.T, states <-chan string, want string) {
	t.Helper()
	timer := time.NewTimer(3 * time.Second)
	defer timer.Stop()
	for {
		select {
		case state := <-states:
			if state == want {
				return
			}
		case <-timer.C:
			t.Fatalf("did not receive login state %s", want)
		}
	}
}

func TestLoginRestoresSessionWithoutPrompt(t *testing.T) {
	c := newLoginCoordinator(func(string, string) { t.Error("restored session requested input") })
	err := c.run(context.Background(), func(context.Context) (bool, error) { return true, nil },
		func(context.Context) error { t.Error("phone flow started"); return nil },
		func(context.Context) error { t.Error("QR flow started"); return nil })
	if err != nil {
		t.Fatal(err)
	}
}

func TestCancelSupersedesQueuedLoginSelection(t *testing.T) {
	e := NewEngine(1, "fixture", "unused-test-session")
	// Hold the coordinator before its receive loop to reproduce immediate Back.
	e.login.running = true
	if err := e.SelectLoginMethod("qr"); err != nil {
		t.Fatal(err)
	}
	e.CancelLogin()
	select {
	case command := <-e.login.commands:
		if command != "" {
			t.Fatalf("cancellation was lost behind queued method %q", command)
		}
	default:
		t.Fatal("no cancellation was queued")
	}
}

func TestLoginFloodWaitSurvivesReturningToSelector(t *testing.T) {
	e := NewEngine(1, "fixture", "unused-test-session")
	states := make(chan string, 20)
	e.login = newLoginCoordinator(func(s, _ string) { states <- s })
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- e.login.run(ctx, func(context.Context) (bool, error) { return false, nil }, nil,
			func(context.Context) error { return tgerr.New(420, "FLOOD_WAIT_30") })
	}()
	receiveLoginState(t, states, StateNeedLoginMethod)
	if !e.IsLoginActive() {
		t.Fatal("active coordinator not reported")
	}
	if err := e.SelectLoginMethod("qr"); err != nil {
		t.Fatal(err)
	}
	receiveLoginState(t, states, StateError)
	e.CancelLogin()
	receiveLoginState(t, states, StateNeedLoginMethod)
	if err := e.SelectLoginMethod("phone"); err == nil {
		t.Fatal("retry bypassed the server flood wait")
	}
	cancel()
	<-done
	if e.IsLoginActive() {
		t.Fatal("finished coordinator still active")
	}
}

func TestLoginSwitchJoinsOldAttemptAndRejectsStaleInput(t *testing.T) {
	states := make(chan string, 20)
	c := newLoginCoordinator(func(s, _ string) { states <- s })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	oldExited := make(chan struct{})
	qrStarted := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- c.run(ctx, func(context.Context) (bool, error) { return false, nil },
			func(ctx context.Context) error {
				defer close(oldExited)
				_, err := c.ask(ctx, StateNeedPhone, "")
				return err
			}, func(ctx context.Context) error {
				select {
				case <-oldExited:
				default:
					t.Error("new attempt started before old attempt exited")
				}
				close(qrStarted)
				<-ctx.Done()
				return ctx.Err()
			})
	}()
	receiveLoginState(t, states, StateNeedLoginMethod)
	if err := c.selectMethod("phone"); err != nil {
		t.Fatal(err)
	}
	receiveLoginState(t, states, StateNeedPhone)
	if err := c.selectMethod("qr"); err != nil {
		t.Fatal(err)
	}
	select {
	case <-qrStarted:
	case <-time.After(3 * time.Second):
		t.Fatal("QR did not start")
	}
	if err := c.submit(StateNeedPhone, "+390000000"); err == nil {
		t.Fatal("accepted input from old attempt")
	}
	if err := c.selectMethod(""); err != nil {
		t.Fatal(err)
	}
	receiveLoginState(t, states, StateNeedLoginMethod)
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel: %v", err)
	}
}

func TestLoginRejectsDuplicateInputAndCompletesOnce(t *testing.T) {
	states := make(chan string, 20)
	c := newLoginCoordinator(func(s, _ string) { states <- s })
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var calls atomic.Int32
	input := make(chan string, 1)
	release := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- c.run(ctx, func(context.Context) (bool, error) { return false, nil }, func(ctx context.Context) error {
			calls.Add(1)
			v, err := c.ask(ctx, StateNeedCode, "")
			input <- v
			<-release
			return err
		}, nil)
	}()
	receiveLoginState(t, states, StateNeedLoginMethod)
	if err := c.selectMethod("invalid"); err == nil {
		t.Fatal("invalid method accepted")
	}
	if err := c.selectMethod("phone"); err != nil {
		t.Fatal(err)
	}
	receiveLoginState(t, states, StateNeedCode)
	if err := c.submit(StateNeedCode, "12345"); err != nil {
		t.Fatal(err)
	}
	if err := c.submit(StateNeedCode, "99999"); err == nil {
		t.Fatal("duplicate input accepted")
	}
	if got := <-input; got != "12345" {
		t.Fatalf("input = %q", got)
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatal("authentication completed more than once")
	}
	if err := c.selectMethod("qr"); err == nil {
		t.Fatal("accepted selection after authorization")
	}
}

func TestLoginSwitchKeepsAuthorizationThatCompletedDuringCancel(t *testing.T) {
	states := make(chan string, 20)
	c := newLoginCoordinator(func(s, _ string) { states <- s })
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	var authorized atomic.Bool
	started := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		done <- c.run(ctx, func(context.Context) (bool, error) { return authorized.Load(), nil }, func(ctx context.Context) error {
			close(started)
			<-ctx.Done()
			authorized.Store(true)
			return ctx.Err()
		}, func(context.Context) error { t.Error("started another login despite authorization"); return nil })
	}()
	receiveLoginState(t, states, StateNeedLoginMethod)
	if err := c.selectMethod("phone"); err != nil {
		t.Fatal(err)
	}
	<-started
	if err := c.selectMethod("qr"); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
