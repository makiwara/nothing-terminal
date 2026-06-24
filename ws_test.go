package main

import (
	"bytes"
	"io"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// writerFunc adapts a func to io.Writer (used as the hub's input sink in tests).
type writerFunc func([]byte) (int, error)

func (f writerFunc) Write(p []byte) (int, error) { return f(p) }

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// TestWSAttach drives the full WebSocket <-> Hub wiring end to end WITHOUT a real
// PTY: an io.Pipe stands in for the PTY master. It proves the three protocol
// behaviours the Android app depends on — output fan-out (binary down), input
// (binary up), and the resize control plane (JSON both ways).
func TestWSAttach(t *testing.T) {
	pr, pw := io.Pipe()

	var mu sync.Mutex
	var sink bytes.Buffer
	sinkW := writerFunc(func(p []byte) (int, error) {
		mu.Lock()
		defer mu.Unlock()
		return sink.Write(p)
	})
	var lastCols, lastRows int
	setSz := func(c, r int) {
		mu.Lock()
		lastCols, lastRows = c, r
		mu.Unlock()
	}

	h := newHub(pr, sinkW, setSz)
	go h.pumpOutput()

	srv := httptest.NewServer(h.serveWS(""))
	defer srv.Close()
	url := "ws" + strings.TrimPrefix(srv.URL, "http") + "/attach"

	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()

	// Client reports its viewport -> server should resize the PTY to it.
	if err := conn.WriteJSON(ctrl{T: "resize", Cols: 100, Rows: 30}); err != nil {
		t.Fatalf("write resize: %v", err)
	}
	waitFor(t, "PTY resize to 100x30", func() bool {
		mu.Lock()
		defer mu.Unlock()
		return lastCols == 100 && lastRows == 30
	})

	// Server output must reach the client as a binary frame.
	go io.WriteString(pw, "rich-tui-bytes")
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	var gotOutput bool
	var gotSize bool
	for !gotOutput {
		typ, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		switch typ {
		case websocket.BinaryMessage:
			if string(data) == "rich-tui-bytes" {
				gotOutput = true
			}
		case websocket.TextMessage:
			if strings.Contains(string(data), `"size"`) {
				gotSize = true
			}
		}
	}
	if !gotSize {
		t.Error("expected at least one size control frame before output")
	}

	// Client input (binary) must reach the PTY sink.
	if err := conn.WriteMessage(websocket.BinaryMessage, []byte("keys!")); err != nil {
		t.Fatalf("write input: %v", err)
	}
	waitFor(t, "input to reach sink", func() bool {
		mu.Lock()
		defer mu.Unlock()
		return strings.Contains(sink.String(), "keys!")
	})
}
