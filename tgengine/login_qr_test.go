package tgengine

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image/png"
	"testing"
	"time"

	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/telegram/auth/qrlogin"
	"github.com/gotd/td/tg"
	"github.com/gotd/td/tgerr"
)

type qrFixture struct {
	export      func(context.Context) (qrlogin.Token, error)
	importToken func(context.Context) (*tg.AuthAuthorization, error)
}

func (q qrFixture) Export(ctx context.Context, _ ...int64) (qrlogin.Token, error) {
	return q.export(ctx)
}
func (q qrFixture) Import(ctx context.Context) (*tg.AuthAuthorization, error) {
	return q.importToken(ctx)
}

func TestQRRenewsAndCompletesAtExpiryWithoutAnotherImage(t *testing.T) {
	clock := make(chan time.Time, 2)
	updates := make(chan struct{}, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	exports, images := 0, 0
	q := qrFixture{export: func(context.Context) (qrlogin.Token, error) {
		exports++
		if exports == 3 {
			return qrlogin.Token{}, nil
		}
		return qrlogin.NewToken([]byte{byte(exports)}, int(time.Now().Add(time.Minute).Unix())), nil
	}}
	err := runQRFlow(ctx, q, updates, func(_ context.Context, token qrlogin.Token) error {
		images++
		if token.Empty() {
			t.Error("displayed an empty token")
		}
		clock <- time.Now()
		return nil
	}, func(time.Duration) (<-chan time.Time, func()) { return clock, func() {} })
	if err != nil {
		t.Fatal(err)
	}
	if images != 2 || exports != 3 {
		t.Fatalf("images=%d exports=%d", images, exports)
	}
}

func TestQRScanTriggersConfirmationAndMigration(t *testing.T) {
	for _, initial := range []bool{false, true} {
		t.Run(fmt.Sprintf("initial_%v", initial), func(t *testing.T) {
			updates := make(chan struct{}, 1)
			exports, imports, images := 0, 0, 0
			q := qrFixture{export: func(context.Context) (qrlogin.Token, error) {
				exports++
				if initial || exports > 1 {
					return qrlogin.Token{}, &qrlogin.MigrationNeededError{MigrateTo: &tg.AuthLoginTokenMigrateTo{DCID: 4}}
				}
				return qrlogin.NewToken([]byte("fixture"), int(time.Now().Add(time.Minute).Unix())), nil
			}, importToken: func(context.Context) (*tg.AuthAuthorization, error) {
				imports++
				return &tg.AuthAuthorization{}, nil
			}}
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			err := runQRFlow(ctx, q, updates, func(context.Context, qrlogin.Token) error { images++; updates <- struct{}{}; return nil }, loginTimer)
			if err != nil || imports != 1 {
				t.Fatalf("confirmation err=%v imports=%d", err, imports)
			}
			if initial && images != 0 {
				t.Fatal("displayed QR after migration already authorized")
			}
		})
	}
}

func TestQRCancelNeverPublishesAnImage(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	q := qrFixture{export: func(context.Context) (qrlogin.Token, error) {
		cancel()
		return qrlogin.NewToken([]byte("fixture"), int(time.Now().Add(time.Minute).Unix())), nil
	}}
	err := runQRFlow(ctx, q, nil, func(context.Context, qrlogin.Token) error { t.Error("published canceled QR"); return nil }, loginTimer)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel = %v", err)
	}
}

func TestQRReturnsPasswordNeededAndFloodWaitWithoutRetryLoop(t *testing.T) {
	for _, rpcType := range []string{"SESSION_PASSWORD_NEEDED", "FLOOD_WAIT_30"} {
		t.Run(rpcType, func(t *testing.T) {
			calls := 0
			rpcErr := tgerr.New(400, rpcType)
			q := qrFixture{export: func(context.Context) (qrlogin.Token, error) {
				calls++
				return qrlogin.Token{}, fmt.Errorf("export: %w", rpcErr)
			}}
			err := runQRFlow(context.Background(), q, nil, func(context.Context, qrlogin.Token) error { t.Error("unexpected QR"); return nil }, loginTimer)
			if !errors.Is(err, rpcErr) || calls != 1 {
				t.Fatalf("err=%v calls=%d", err, calls)
			}
		})
	}
}

func TestQRImageHasWhiteQuietZone(t *testing.T) {
	data, err := qrPNG(qrlogin.NewToken([]byte("non-secret-test-fixture"), 123))
	if err != nil {
		t.Fatal(err)
	}
	im, err := png.Decode(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	r, g, b, _ := im.At(0, 0).RGBA()
	if r != 65535 || g != 65535 || b != 65535 {
		t.Fatal("QR quiet zone is not white")
	}
	for _, point := range [][3]int{{31, 32, 65535}, {32, 32, 0}, {40, 40, 65535}, {48, 48, 0}} {
		r, _, _, _ := im.At(point[0], point[1]).RGBA()
		if int(r) != point[2] {
			t.Fatalf("QR finder/quiet zone pixel at %d,%d = %d", point[0], point[1], r)
		}
	}
	if len(data) > 64*1024 {
		t.Fatal("QR image exceeds control payload budget")
	}
}

func TestPasswordRetryPreservesWhitespace(t *testing.T) {
	passwords := []string{" wrong ", " correct "}
	index := 0
	err := passwordLogin(context.Background(), func(context.Context, string) (string, error) {
		v := passwords[index]
		index++
		return v, nil
	}, func(_ context.Context, v string) error {
		if v == " wrong " {
			return auth.ErrPasswordInvalid
		}
		if v != " correct " {
			t.Fatalf("password was changed: %q", v)
		}
		return nil
	})
	if err != nil || index != 2 {
		t.Fatalf("password retry: %v prompts=%d", err, index)
	}
}

func TestPasswordCancelDoesNotCallTelegram(t *testing.T) {
	err := passwordLogin(context.Background(), func(context.Context, string) (string, error) { return "", context.Canceled }, func(context.Context, string) error { t.Error("submitted canceled password"); return nil })
	if !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
