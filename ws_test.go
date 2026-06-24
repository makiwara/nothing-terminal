package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// writerFunc adapts a func to io.Writer (used as a hub sink in tests).
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

// TestWSAttach drives the per-session WebSocket attach end to end without a PTY:
// an io.Pipe stands in for the session's byte source. It proves the three
// protocol behaviours the app depends on — output fan-out (binary down), input
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
	sess := &Session{ID: "s1", hub: h}
	reg := &Registry{sessions: map[string]*Session{"s1": sess}, order: []string{"s1"}}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /sessions/{id}/attach", reg.serveAttach(""))
	srv := httptest.NewServer(mux)
	defer srv.Close()
	url := "ws" + strings.TrimPrefix(srv.URL, "http") + "/sessions/s1/attach"

	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()

	if err := conn.WriteJSON(ctrl{T: "resize", Cols: 100, Rows: 30}); err != nil {
		t.Fatalf("write resize: %v", err)
	}
	waitFor(t, "PTY resize to 100x30", func() bool {
		mu.Lock()
		defer mu.Unlock()
		return lastCols == 100 && lastRows == 30
	})

	go io.WriteString(pw, "rich-tui-bytes")
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	var gotOutput, gotSize bool
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

	if err := conn.WriteMessage(websocket.BinaryMessage, []byte("keys!")); err != nil {
		t.Fatalf("write input: %v", err)
	}
	waitFor(t, "input to reach sink", func() bool {
		mu.Lock()
		defer mu.Unlock()
		return strings.Contains(sink.String(), "keys!")
	})
}

// TestControlPlane exercises the REST surface the cockpit uses — scripts, the
// session ring, and the mock voice propose/send — against demo-backed sessions
// (no PTY), so it runs anywhere.
func TestControlPlane(t *testing.T) {
	reg := newRegistry([]Script{{ID: "demo", Label: "Demo", demo: &demoSpec{title: "demo", color: 6}}})
	mux := http.NewServeMux()
	mux.HandleFunc("GET /scripts", reg.handleScripts)
	mux.HandleFunc("GET /sessions", reg.handleListSessions)
	mux.HandleFunc("POST /sessions", reg.handleOpenSession)
	mux.HandleFunc("DELETE /sessions/{id}", reg.handleCloseSession)
	mux.HandleFunc("POST /sessions/{id}/voice", reg.handleVoice)
	mux.HandleFunc("POST /sessions/{id}/send", reg.handleSend)
	srv := httptest.NewServer(mux)
	defer srv.Close()

	get := func(path string) (int, string) {
		resp, err := http.Get(srv.URL + path)
		if err != nil {
			t.Fatalf("GET %s: %v", path, err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b)
	}
	post := func(path, body string) (int, string) {
		resp, err := http.Post(srv.URL+path, "application/json", strings.NewReader(body))
		if err != nil {
			t.Fatalf("POST %s: %v", path, err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b)
	}

	if code, body := get("/scripts"); code != 200 || !strings.Contains(body, `"demo"`) {
		t.Fatalf("GET /scripts = %d %s", code, body)
	}

	code, body := post("/sessions", `{"script_id":"demo"}`)
	if code != 201 {
		t.Fatalf("POST /sessions = %d %s", code, body)
	}
	var sess struct{ ID string }
	if err := json.Unmarshal([]byte(body), &sess); err != nil || sess.ID == "" {
		t.Fatalf("open session body: %s", body)
	}

	if code, body := get("/sessions"); code != 200 || !strings.Contains(body, sess.ID) {
		t.Fatalf("GET /sessions = %d %s", code, body)
	}

	if code, body := post("/sessions/"+sess.ID+"/voice", ""); code != 200 ||
		!strings.Contains(body, `"git status"`) || !strings.Contains(body, `"text"`) {
		t.Fatalf("voice propose = %d %s", code, body)
	}

	if code, _ := post("/sessions/"+sess.ID+"/send", `{"action":{"kind":"text","text":"hello world"}}`); code != 204 {
		t.Fatalf("send = %d", code)
	}
	if got := reg.Get(sess.ID).lastSentText(); got != "hello world" {
		t.Fatalf("after send, lastSent = %q, want %q", got, "hello world")
	}

	req, _ := http.NewRequest(http.MethodDelete, srv.URL+"/sessions/"+sess.ID, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 204 {
		t.Fatalf("DELETE session = %v %v", err, resp)
	}
	if code, body := get("/sessions"); code != 200 || strings.Contains(body, sess.ID) {
		t.Fatalf("ring not empty after close: %s", body)
	}
}
