// Package tgengine is the Go side of tgstream, exposed to Android via gomobile.
//
// Contract: control calls (login, start/stop) cross the JNI boundary, while the
// multi-GB byte stream is served by an in-process HTTP server on 127.0.0.1 that
// ExoPlayer connects to directly — so no large buffers ever cross JNI.
//
// Only methods with gomobile-compatible signatures (string/int/bool/[]byte/error
// and the LoginListener interface) are exported. All gotd/tg types stay internal.
package tgengine

import (
	"context"
	"errors"
	"strings"
	"sync"
	"time"

	"github.com/gotd/log/logzap"
	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/telegram/peers"
	"github.com/gotd/td/tg"
)

// Login states reported to LoginListener.OnState.
const (
	StateConnecting      = "CONNECTING"
	StateNeedLoginMethod = "NEED_LOGIN_METHOD"
	StateWaitQR          = "WAIT_QR"
	StateNeedPhone       = "NEED_PHONE"
	StateNeedCode        = "NEED_CODE"
	StateNeedPassword    = "NEED_PASSWORD"
	StateAuthorized      = "AUTHORIZED"
	StateError           = "ERROR"
	StateLoggedOut       = "LOGGED_OUT"
	StateLoggingOut      = "LOGGING_OUT"
)

// LoginListener receives auth state changes. Implemented in Kotlin.
type LoginListener interface {
	OnState(state string, message string)
}

// Engine is the single entry point bound to Android.
type Engine struct {
	apiID   int
	apiHash string
	session string // path to the gotd session file

	lifecycleMu  sync.Mutex
	mu           sync.Mutex
	client       *telegram.Client
	api          *tg.Client
	peers        *peers.Manager
	listener     LoginListener
	qrListener   QRLoginListener
	loginUpdates chan struct{}

	// Authentication inputs belong to one cancelable attempt.
	login *loginCoordinator

	started       bool
	ready         bool
	stopped       bool
	loggingOut    bool
	logoutPending bool
	loggedOut     bool
	runCancel     context.CancelFunc
	runDone       chan struct{}

	// Long-lived context for media downloads. Canceled only on Stop — never
	// per-request, so finishing/aborting an HTTP request never cancels an
	// in-flight getFile and thus never tears down a pooled connection.
	dlCtx    context.Context
	dlCancel context.CancelFunc

	proxy *proxyServer

	// per-channel document cache (msgID -> *tg.Document), guarded by docMu.
	// docInflight de-duplicates concurrent getMessages for the same key so a
	// burst of player requests (HEAD+GET, seeks, parallel conns) can't fan out
	// into N simultaneous channels.getMessages calls and trip a FLOOD_WAIT.
	docMu       sync.Mutex
	docs        map[string]*tg.Document // key: "<channelID>:<msgID>"
	docInflight map[string]*docCall     // key: "<channelID>:<msgID>"

	// resolved InputChannel cache (channelID -> InputChannelClass). Avoids a
	// per-request peer lookup that can queue on the main connection and add ~1s
	// to every seek.
	chanMu    sync.Mutex
	chanCache map[int64]tg.InputChannelClass

	// per-DC media clients (pool of N connections each), guarded by mediaMu
	mediaMu  sync.Mutex
	mediaAPI map[int]*tg.Client
	mediaInv map[int]telegram.CloseInvoker
	mediaMax map[int]int64 // pool size each DC client was created with
}

// NewEngine creates an engine. sessionFile is an absolute path in app storage.
//
// The caller (Kotlin) passes the user's own API credentials, or empty values when
// the user hasn't entered any. In the empty case we fall back to the app's
// built-in defaults, which live obfuscated in this binary (see credentials.go) and
// never cross JNI — so a user value, when present, always wins.
func NewEngine(apiID int, apiHash string, sessionFile string) *Engine {
	if apiID <= 0 {
		apiID = DefaultApiID()
	}
	if apiHash == "" {
		apiHash = DefaultApiHash()
	}
	dlCtx, dlCancel := context.WithCancel(context.Background())
	e := &Engine{
		apiID:       apiID,
		apiHash:     apiHash,
		session:     sessionFile,
		docs:        make(map[string]*tg.Document),
		docInflight: make(map[string]*docCall),
		chanCache:   make(map[int64]tg.InputChannelClass),
		dlCtx:       dlCtx,
		dlCancel:    dlCancel,
	}
	e.login = newLoginCoordinator(e.setState)
	e.loginUpdates = make(chan struct{}, 1)
	return e
}

// SetLoginListener registers the state callback.
func (e *Engine) SetLoginListener(l LoginListener) {
	e.mu.Lock()
	e.listener = l
	e.mu.Unlock()
}

func (e *Engine) setState(state, msg string) {
	e.mu.Lock()
	l := e.listener
	if (e.stopped || e.loggingOut) && state != StateLoggingOut && state != StateLoggedOut {
		l = nil
	}
	e.mu.Unlock()
	if l != nil {
		l.OnState(state, msg)
	}
}

// Start connects and authorizes if a valid session exists. If not authorized,
// it drives the login flow via the SubmitPhone/Code/Password calls and reports
// progress through the LoginListener. Safe to call once.
func (e *Engine) Start() error {
	e.lifecycleMu.Lock()
	defer e.lifecycleMu.Unlock()
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return errors.New("engine has stopped; create a new engine")
	}
	if e.started {
		e.mu.Unlock()
		return nil
	}
	e.started = true
	runCtx, runCancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	e.runCancel, e.runDone = runCancel, done
	e.mu.Unlock()

	dispatcher := tg.NewUpdateDispatcher()
	dispatcher.OnLoginToken(func(ctx context.Context, _ tg.Entities, _ *tg.UpdateLoginToken) error {
		select {
		case e.loginUpdates <- struct{}{}:
		default:
		}
		return nil
	})
	client := telegram.NewClient(e.apiID, e.apiHash, telegram.Options{
		SessionStorage: &session.FileStorage{Path: e.session},
		UpdateHandler:  dispatcher,
		Logger:         logzap.New(zapLogger()),
		// Weak 32-bit TV boxes (e.g. Leelbox L1) take much longer for the
		// MTProto DH key exchange; the 15s default migration timeout is too
		// short and login fails with "migrate to dc: context deadline exceeded".
		MigrationTimeout: 60 * time.Second,
		DialTimeout:      30 * time.Second,
		ExchangeTimeout:  30 * time.Second,
		// MediaOnly() on the HOME DC tries to export auth to itself, which
		// Telegram refuses with DC_ID_INVALID. But auth keys are per-DC and shared
		// across a DC's regular/media endpoints, so the media pool already reuses
		// the home key — the export is redundant. Swallow that specific error so
		// MediaOnly returns the (already-authorized) media pool; real transfer
		// errors for foreign DCs still propagate.
		OnTransfer: func(ctx context.Context, _ *telegram.Client, fn func(context.Context) error) error {
			if err := fn(ctx); err != nil {
				if strings.Contains(err.Error(), "DC_ID_INVALID") {
					return nil
				}
				return err
			}
			return nil
		},
	})
	e.mu.Lock()
	e.client = client
	e.mu.Unlock()

	go func() {
		defer close(done)
		defer runCancel()
		e.setState(StateConnecting, "")
		runErr := client.Run(runCtx, func(ctx context.Context) error {
			flow := auth.NewFlow(bridgeAuth{e: e}, auth.SendCodeOptions{})
			if err := e.login.run(ctx, func(ctx context.Context) (bool, error) {
				s, err := client.Auth().Status(ctx)
				if err != nil {
					return false, err
				}
				return s.Authorized, nil
			}, func(ctx context.Context) error { return flow.Run(ctx, phoneAuthClient{Client: client.Auth(), e: e}) },
				func(ctx context.Context) error { return e.runQRLogin(ctx, client) }); err != nil {
				e.setState(StateError, loginErrorMessage(err))
				return err
			}
			e.mu.Lock()
			e.api = client.API()
			e.peers = peers.Options{}.Build(e.api)
			e.mu.Unlock()

			// Populate channel access hashes so streams can resolve by id.
			if err := e.syncDialogs(ctx); err != nil {
				// non-fatal: some channels may still resolve lazily
				logW("dialog sync warning: %v", err)
			}

			e.mu.Lock()
			if e.stopped || ctx.Err() != nil {
				e.mu.Unlock()
				return ctx.Err()
			}
			e.ready = true
			e.mu.Unlock()
			e.setState(StateAuthorized, "")
			<-ctx.Done() // keep the connection alive until stop or transport failure
			return ctx.Err()
		})
		e.mu.Lock()
		e.ready, e.started = false, false
		e.mu.Unlock()
		if runErr != nil && runCtx.Err() == nil {
			e.setState(StateError, loginErrorMessage(runErr))
		}
	}()
	return nil
}

// IsReady reports whether the client is authorized and ready to stream.
func (e *Engine) IsReady() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.ready && !e.stopped && !e.loggingOut
}

// Stop cancels login/download work and waits until session writers have exited.
// A stopped engine is terminal; create another engine for a subsequent login.
func (e *Engine) Stop() {
	e.lifecycleMu.Lock()
	defer e.lifecycleMu.Unlock()
	e.stopLocked()
}

func (e *Engine) stopLocked() {
	e.mu.Lock()
	e.stopped, e.ready = true, false
	cancel, done, proxy := e.runCancel, e.runDone, e.proxy
	e.mu.Unlock()
	if e.dlCancel != nil {
		e.dlCancel()
	}
	if cancel != nil {
		cancel()
	}
	if proxy != nil {
		proxy.stop()
	}
	e.closeMedia()
	if done != nil {
		<-done
	}
}

// Ping returns a constant; used to verify the AAR loads and JNI works.
func Ping() string { return "pong" }

// Version reports the engine build version.
func Version() string { return "tgengine 0.1.0" }
