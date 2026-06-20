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
	"sync"

	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/telegram/peers"
	"github.com/gotd/td/tg"
)

// Login states reported to LoginListener.OnState.
const (
	StateConnecting   = "CONNECTING"
	StateNeedPhone    = "NEED_PHONE"
	StateNeedCode     = "NEED_CODE"
	StateNeedPassword = "NEED_PASSWORD"
	StateAuthorized   = "AUTHORIZED"
	StateError        = "ERROR"
	StateLoggedOut    = "LOGGED_OUT"
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

	mu       sync.Mutex
	client   *telegram.Client
	api      *tg.Client
	peers    *peers.Manager
	listener LoginListener

	// login bridge channels (buffered, size 1)
	phoneCh chan string
	codeCh  chan string
	pwdCh   chan string

	readyCh    chan struct{} // closed when api is ready
	shutdownCh chan struct{} // closed to stop the client run loop
	started    bool

	// Long-lived context for media downloads. Canceled only on Stop — never
	// per-request, so finishing/aborting an HTTP request never cancels an
	// in-flight getFile and thus never tears down a pooled connection.
	dlCtx    context.Context
	dlCancel context.CancelFunc

	proxy *proxyServer

	// per-channel document cache (msgID -> *tg.Document), guarded by docMu
	docMu sync.Mutex
	docs  map[string]*tg.Document // key: "<channelID>:<msgID>"

	// resolved InputChannel cache (channelID -> InputChannelClass). Avoids a
	// per-request peer lookup that can queue on the main connection and add ~1s
	// to every seek.
	chanMu    sync.Mutex
	chanCache map[int64]tg.InputChannelClass

	// per-DC media clients (pool of N connections each), guarded by mediaMu
	mediaMu  sync.Mutex
	mediaAPI map[int]*tg.Client
	mediaInv map[int]telegram.CloseInvoker
}

// NewEngine creates an engine. sessionFile is an absolute path in app storage.
func NewEngine(apiID int, apiHash string, sessionFile string) *Engine {
	dlCtx, dlCancel := context.WithCancel(context.Background())
	return &Engine{
		apiID:      apiID,
		apiHash:    apiHash,
		session:    sessionFile,
		phoneCh:    make(chan string, 1),
		codeCh:     make(chan string, 1),
		pwdCh:      make(chan string, 1),
		readyCh:    make(chan struct{}),
		shutdownCh: make(chan struct{}),
		docs:       make(map[string]*tg.Document),
		chanCache:  make(map[int64]tg.InputChannelClass),
		dlCtx:      dlCtx,
		dlCancel:   dlCancel,
	}
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
	e.mu.Unlock()
	if l != nil {
		l.OnState(state, msg)
	}
}

// Start connects and authorizes if a valid session exists. If not authorized,
// it drives the login flow via the SubmitPhone/Code/Password calls and reports
// progress through the LoginListener. Safe to call once.
func (e *Engine) Start() error {
	e.mu.Lock()
	if e.started {
		e.mu.Unlock()
		return nil
	}
	e.started = true
	e.mu.Unlock()

	client := telegram.NewClient(e.apiID, e.apiHash, telegram.Options{
		SessionStorage: &session.FileStorage{Path: e.session},
		Logger:         zapLogger(),
	})
	e.client = client

	go func() {
		e.setState(StateConnecting, "")
		runErr := client.Run(context.Background(), func(ctx context.Context) error {
			flow := auth.NewFlow(bridgeAuth{e: e}, auth.SendCodeOptions{})
			if err := client.Auth().IfNecessary(ctx, flow); err != nil {
				e.setState(StateError, err.Error())
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

			e.setState(StateAuthorized, "")
			close(e.readyCh)

			<-e.shutdownCh // keep the connection alive for streaming
			return nil
		})
		if runErr != nil {
			e.setState(StateError, runErr.Error())
		}
	}()
	return nil
}

// IsReady reports whether the client is authorized and ready to stream.
func (e *Engine) IsReady() bool {
	select {
	case <-e.readyCh:
		return true
	default:
		return false
	}
}

// SubmitPhone provides the phone number (E.164, e.g. +39...) for login.
func (e *Engine) SubmitPhone(phone string) { e.phoneCh <- phone }

// SubmitCode provides the login code received via Telegram.
func (e *Engine) SubmitCode(code string) { e.codeCh <- code }

// SubmitPassword provides the 2FA password.
func (e *Engine) SubmitPassword(pwd string) { e.pwdCh <- pwd }

// Stop tears down the client run loop and proxy.
func (e *Engine) Stop() {
	if e.dlCancel != nil {
		e.dlCancel()
	}
	if e.proxy != nil {
		e.proxy.stop()
	}
	e.closeMedia()
	select {
	case <-e.shutdownCh:
	default:
		close(e.shutdownCh)
	}
}

func (e *Engine) recv(ch chan string, ctx context.Context) (string, error) {
	select {
	case v := <-ch:
		return v, nil
	case <-ctx.Done():
		return "", ctx.Err()
	}
}

// bridgeAuth feeds the gotd auth flow from the Submit* channels.
type bridgeAuth struct{ e *Engine }

func (b bridgeAuth) Phone(ctx context.Context) (string, error) {
	b.e.setState(StateNeedPhone, "")
	return b.e.recv(b.e.phoneCh, ctx)
}

func (b bridgeAuth) Code(ctx context.Context, _ *tg.AuthSentCode) (string, error) {
	b.e.setState(StateNeedCode, "")
	return b.e.recv(b.e.codeCh, ctx)
}

func (b bridgeAuth) Password(ctx context.Context) (string, error) {
	b.e.setState(StateNeedPassword, "")
	return b.e.recv(b.e.pwdCh, ctx)
}

func (b bridgeAuth) AcceptTermsOfService(_ context.Context, _ tg.HelpTermsOfService) error {
	return nil
}

func (b bridgeAuth) SignUp(_ context.Context) (auth.UserInfo, error) {
	return auth.UserInfo{}, errors.New("signup not supported")
}

// Ping returns a constant; used to verify the AAR loads and JNI works.
func Ping() string { return "pong" }

// Version reports the engine build version.
func Version() string { return "tgengine 0.1.0" }
