// Spike: does gotd (current-layer, maintained pure-Go MTProto) authenticate
// against the CURRENT Telegram servers, including 2FA, and read account state?
//
// This is the make-or-break test for using gotd as the raw-MTProto foundation.
// It performs a REAL login on YOUR account (the code is sent to your Telegram).
//
// Run:
//   go mod tidy
//   TG_API_ID=<id> TG_API_HASH=<hash> TG_PHONE=+39xxxxxxxxxx go run .
// then type the login code (and 2FA password if asked) when prompted.
package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/gotd/td/session"
	"github.com/gotd/td/telegram"
	"github.com/gotd/td/telegram/auth"
	"github.com/gotd/td/tg"
)

type termAuth struct {
	phone string
	in    *bufio.Reader
}

func (a termAuth) Phone(_ context.Context) (string, error) { return a.phone, nil }

func (a termAuth) Code(_ context.Context, _ *tg.AuthSentCode) (string, error) {
	fmt.Print("Enter the login code you received: ")
	code, err := a.in.ReadString('\n')
	return strings.TrimSpace(code), err
}

func (a termAuth) Password(_ context.Context) (string, error) {
	fmt.Print("2FA enabled — enter your password: ")
	pwd, err := a.in.ReadString('\n')
	return strings.TrimSpace(pwd), err
}

func (a termAuth) AcceptTermsOfService(_ context.Context, _ tg.HelpTermsOfService) error {
	return nil
}

func (a termAuth) SignUp(_ context.Context) (auth.UserInfo, error) {
	return auth.UserInfo{}, errors.New("signup not supported in spike")
}

func env(name string) string {
	v := os.Getenv(name)
	if v == "" {
		fmt.Printf("Missing env var %s\n", name)
		os.Exit(2)
	}
	return v
}

func main() {
	apiID, err := strconv.Atoi(env("TG_API_ID"))
	if err != nil {
		fmt.Println("TG_API_ID must be an integer")
		os.Exit(2)
	}
	apiHash := env("TG_API_HASH")
	phone := env("TG_PHONE")

	client := telegram.NewClient(apiID, apiHash, telegram.Options{
		SessionStorage: &session.FileStorage{Path: "spike-session.json"},
	})

	ctx := context.Background()
	runErr := client.Run(ctx, func(ctx context.Context) error {
		flow := auth.NewFlow(
			termAuth{phone: phone, in: bufio.NewReader(os.Stdin)},
			auth.SendCodeOptions{},
		)
		if err := client.Auth().IfNecessary(ctx, flow); err != nil {
			return fmt.Errorf("auth: %w", err)
		}

		self, err := client.Self(ctx)
		if err != nil {
			return fmt.Errorf("self: %w", err)
		}
		fmt.Printf("RESULT: AUTH OK ✅  id=%d firstName=%q username=%q premium=%v\n",
			self.ID, self.FirstName, self.Username, self.Premium)
		fmt.Println("gotd authenticates and makes authorized calls against current servers.")

		// Optional read-path probe: set TG_CHANNEL and TG_MSG to test getFile.
		maybeDownloadProbe(ctx, client.API())
		return nil
	})
	if runErr != nil {
		fmt.Printf("RESULT: FAILED ❌  %v\n", runErr)
		os.Exit(1)
	}
}
