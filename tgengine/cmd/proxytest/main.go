// Desktop harness to exercise the tgengine proxy without Android.
//
// Reuses an existing gotd session file (e.g. the one created by spike-gotd).
//
// Run:
//   TG_API_ID=<id> TG_API_HASH=<hash> TG_SESSION=../../spike-gotd/spike-session.json \
//     go run ./cmd/proxytest
// Then, from another shell:
//   curl -s -D - -H "Range: bytes=0-1048575" \
//     "http://127.0.0.1:<port>/stream?channel=<id>&messages=<m1-m2>&conns=3" -o /tmp/out.bin
package main

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"time"

	"tgstream/tgengine"
)

type listener struct{ in *bufio.Reader; e *tgengine.Engine }

func (l *listener) OnState(state, msg string) {
	fmt.Printf("[state] %s %s\n", state, msg)
	switch state {
	case tgengine.StateNeedPhone:
		fmt.Print("phone (+39...): ")
		s, _ := l.in.ReadString('\n')
		l.e.SubmitPhone(trim(s))
	case tgengine.StateNeedCode:
		fmt.Print("code: ")
		s, _ := l.in.ReadString('\n')
		l.e.SubmitCode(trim(s))
	case tgengine.StateNeedPassword:
		fmt.Print("2FA password: ")
		s, _ := l.in.ReadString('\n')
		l.e.SubmitPassword(trim(s))
	}
}

func trim(s string) string {
	for len(s) > 0 && (s[len(s)-1] == '\n' || s[len(s)-1] == '\r' || s[len(s)-1] == ' ') {
		s = s[:len(s)-1]
	}
	return s
}

func env(k string) string {
	v := os.Getenv(k)
	if v == "" {
		fmt.Printf("missing env %s\n", k)
		os.Exit(2)
	}
	return v
}

func main() {
	apiID, err := strconv.Atoi(env("TG_API_ID"))
	if err != nil {
		fmt.Println("TG_API_ID must be int")
		os.Exit(2)
	}
	session := os.Getenv("TG_SESSION")
	if session == "" {
		session = "proxytest-session.json"
	}

	e := tgengine.NewEngine(apiID, env("TG_API_HASH"), session)
	e.SetLoginListener(&listener{in: bufio.NewReader(os.Stdin), e: e})
	if err := e.Start(); err != nil {
		fmt.Println("start:", err)
		os.Exit(1)
	}

	// Wait until ready.
	for i := 0; i < 120 && !e.IsReady(); i++ {
		time.Sleep(500 * time.Millisecond)
	}
	if !e.IsReady() {
		fmt.Println("not ready (login incomplete)")
		os.Exit(1)
	}

	port, err := e.StartProxy()
	if err != nil {
		fmt.Println("proxy:", err)
		os.Exit(1)
	}
	fmt.Printf("PROXY READY on http://127.0.0.1:%d\n", port)
	fmt.Printf("Try: curl -s -D - -H 'Range: bytes=0-1048575' 'http://127.0.0.1:%d/stream?channel=<id>&messages=<m1-m2>&conns=3' -o /tmp/out.bin\n", port)
	select {} // block forever
}
