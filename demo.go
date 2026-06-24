package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/gliderlabs/ssh"
)

// runDemo serves a generated ANSI stream over the same protocol/hub as a real
// session, with NO PTY required. It exists so the client (and the rendering path
// end to end) can be exercised in environments where a PTY can't be allocated.
func runDemo(wsAddr, sshAddr, token string) {
	pr, pw := io.Pipe()
	hub := newHub(pr, inputLogger{}, func(cols, rows int) {})
	go hub.pumpOutput()
	go generateDemo(pw)

	if sshAddr != "" {
		go func() {
			_ = (&ssh.Server{Addr: sshAddr, Handler: hub.handleSSH}).ListenAndServe()
		}()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/attach", hub.serveWS(token))
	log.Printf("DEMO mode: WebSocket on %s/attach (generated ANSI, no PTY)", wsAddr)
	log.Fatal(http.ListenAndServe(wsAddr, mux))
}

// inputLogger stands in for the PTY's input side in demo mode: it logs whatever
// the client sends (keystrokes + emulator back-channel responses) so the input
// path can be verified without a real shell.
type inputLogger struct{}

func (inputLogger) Write(p []byte) (int, error) {
	log.Printf("input from client: %q", p)
	return len(p), nil
}

// generateDemo paints a full screen every tick (so any late joiner is repainted
// within one tick) exercising colors, bold, and absolute cursor positioning.
func generateDemo(w io.Writer) {
	i := 0
	for {
		var b strings.Builder
		b.WriteString("\x1b[H") // cursor home; we overwrite rather than clear (no flicker)
		line(&b, "\x1b[1;36m== nothing-terminal demo ==\x1b[0m")
		line(&b, "\x1b[2m phone  <->  WebSocket  <->  Go stand-in\x1b[0m")
		line(&b, "")
		line(&b, fmt.Sprintf(" tick  \x1b[1;33m%d\x1b[0m", i))
		line(&b, "")
		var bars strings.Builder
		bars.WriteString(" colors: ")
		for c := 0; c < 8; c++ {
			fmt.Fprintf(&bars, "\x1b[4%dm  \x1b[0m", c)
		}
		line(&b, bars.String())
		line(&b, "")
		pos := i % 24
		line(&b, fmt.Sprintf(" %s\x1b[1;32m*\x1b[0m", strings.Repeat(" ", pos)))
		line(&b, "")
		line(&b, "\x1b[2m type — your keys echo to the server log\x1b[0m")
		_, _ = w.Write([]byte(b.String()))
		i++
		time.Sleep(500 * time.Millisecond)
	}
}

// line writes one row, clears to end-of-line (so shrinking content leaves no
// stale chars), and moves to the next row.
func line(b *strings.Builder, s string) {
	b.WriteString(s)
	b.WriteString("\x1b[K\r\n")
}
