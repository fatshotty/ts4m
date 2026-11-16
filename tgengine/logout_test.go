package tgengine

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"github.com/gotd/td/tgerr"
)

func logoutEngine(t *testing.T) *Engine {
	t.Helper()
	path := filepath.Join(t.TempDir(), "tg.session")
	if err := os.WriteFile(path, []byte("test-session"), 0600); err != nil {
		t.Fatal(err)
	}
	e := NewEngine(1, "fixture", path)
	e.ready = true
	return e
}

func TestLogoutWaitsForSessionWriterAndRetiresEngine(t *testing.T) {
	e := logoutEngine(t)
	e.runDone = make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	e.runCancel = cancel
	go func() {
		<-ctx.Done()
		_ = os.WriteFile(e.session, []byte("last-session-write"), 0600)
		close(e.runDone)
	}()
	calls := 0
	err := e.logoutWith(func(context.Context) error { calls++; return nil }, func(path string) error {
		select {
		case <-e.runDone:
		default:
			t.Error("deleted session before writer exited")
		}
		return os.Remove(path)
	})
	if err != nil {
		t.Fatal(err)
	}
	if e.IsReady() {
		t.Fatal("retired engine is ready")
	}
	if _, err := os.Stat(e.session); !os.IsNotExist(err) {
		t.Fatalf("session still exists: %v", err)
	}
	if err := e.Start(); err == nil {
		t.Fatal("retired engine restarted")
	}
	if err := e.logoutWith(func(context.Context) error { calls++; return nil }, os.Remove); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("duplicate remote logout calls = %d", calls)
	}
}

func TestLogoutRemoteFailurePreservesSessionAndReadiness(t *testing.T) {
	e := logoutEngine(t)
	err := e.logoutWith(func(context.Context) error { return errors.New("offline") }, func(string) error { t.Error("removed session on remote failure"); return nil })
	if err == nil || !e.IsReady() || e.IsLogoutPending() {
		t.Fatalf("failure state: %v ready=%v pending=%v", err, e.IsReady(), e.IsLogoutPending())
	}
	if _, err := os.Stat(e.session); err != nil {
		t.Fatal("lost session on remote failure")
	}
}

func TestLogoutCleanupFailureIsRetryableWithoutRemoteCall(t *testing.T) {
	e := logoutEngine(t)
	err := e.logoutWith(func(context.Context) error { return nil }, func(string) error { return errors.New("disk error") })
	if err == nil || !e.IsLogoutPending() || e.IsReady() {
		t.Fatalf("cleanup state: %v pending=%v", err, e.IsLogoutPending())
	}
	err = e.logoutWith(func(context.Context) error { t.Error("retried remote logout after success"); return nil }, os.Remove)
	if err != nil || e.IsLogoutPending() {
		t.Fatalf("cleanup retry: %v", err)
	}
}

func TestLogoutAlreadyRevokedSessionCleansLocalStorage(t *testing.T) {
	e := logoutEngine(t)
	err := e.logoutWith(func(context.Context) error { return tgerr.New(401, "AUTH_KEY_UNREGISTERED") }, os.Remove)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(e.session); !os.IsNotExist(err) {
		t.Fatal("revoked session was retained")
	}
}

func TestStopDuringPhonePromptDoesNotWaitForInput(t *testing.T) {
	e := NewEngine(1, "fixture", filepath.Join(t.TempDir(), "tg.session"))
	ctx, cancel := context.WithCancel(context.Background())
	e.runCancel = cancel
	e.runDone = make(chan struct{})
	states := make(chan string, 10)
	e.login = newLoginCoordinator(func(s, _ string) { states <- s })
	go func() {
		defer close(e.runDone)
		_ = e.login.run(ctx, func(context.Context) (bool, error) { return false, nil }, func(ctx context.Context) error { _, err := e.login.ask(ctx, StateNeedPhone, ""); return err }, nil)
	}()
	receiveLoginState(t, states, StateNeedLoginMethod)
	if err := e.SelectLoginMethod("phone"); err != nil {
		t.Fatal(err)
	}
	receiveLoginState(t, states, StateNeedPhone)
	e.Stop()
	select {
	case <-e.runDone:
	default:
		t.Fatal("Stop returned while login was still running")
	}
	if err := e.SubmitPhone("+390000"); err == nil {
		t.Fatal("stopped login accepted input")
	}
}

func TestLogoutSerializesConcurrentProxyStartup(t *testing.T) {
	e := logoutEngine(t)
	var workers sync.WaitGroup
	for i := 0; i < 16; i++ {
		workers.Add(1)
		go func() { defer workers.Done(); _, _ = e.StartProxy() }()
	}
	if err := e.logoutWith(func(context.Context) error { return nil }, os.Remove); err != nil {
		t.Fatal(err)
	}
	workers.Wait()
	if e.IsReady() {
		t.Fatal("proxy startup revived readiness after logout")
	}
	if _, err := e.StartProxy(); err == nil {
		t.Fatal("retired engine started a new proxy")
	}
}
