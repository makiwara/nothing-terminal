package main

import (
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// The WebSocket endpoint is the contract the Android app codes against, and the
// one the future nothing-serious `terminals/` service must implement
// identically. See specs/protocol.md.
//
//   Server -> client:  binary = raw terminal output; text = {"t":"size",...}
//   Client -> server:  binary = input bytes;         text = {"t":"resize",...}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     func(r *http.Request) bool { return true }, // prototype: any origin
}

// ctrl is a JSON control frame (text message), used on both directions.
type ctrl struct {
	T    string `json:"t"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

type outMsg struct {
	typ  int
	data []byte
}

// wsClient adapts one WebSocket connection to a hub participant. gorilla forbids
// concurrent writes, so every frame (binary data + JSON control) funnels through
// a single writePump goroutine.
type wsClient struct {
	conn      *websocket.Conn
	send      chan outMsg
	done      chan struct{}
	closeOnce sync.Once
}

func newWSClient(conn *websocket.Conn) *wsClient {
	c := &wsClient{conn: conn, send: make(chan outMsg, 1024), done: make(chan struct{})}
	go c.writePump()
	return c
}

// Write implements io.Writer: a chunk of terminal output becomes one binary
// frame. pumpOutput reuses its buffer, so we copy.
func (c *wsClient) Write(p []byte) (int, error) {
	b := make([]byte, len(p))
	copy(b, p)
	if !c.enqueue(outMsg{websocket.BinaryMessage, b}) {
		return 0, io.ErrClosedPipe
	}
	return len(p), nil
}

// sendSize tells the client the effective grid size (control plane).
func (c *wsClient) sendSize(cols, rows int) {
	b, _ := json.Marshal(ctrl{T: "size", Cols: cols, Rows: rows})
	c.enqueue(outMsg{websocket.TextMessage, b})
}

// enqueue is non-blocking. A client too slow to keep up (buffer full) is
// disconnected rather than served a corrupted stream — dropping bytes mid
// escape-sequence would garble the terminal. It can reconnect for a fresh paint.
func (c *wsClient) enqueue(m outMsg) bool {
	select {
	case <-c.done:
		return false
	default:
	}
	select {
	case c.send <- m:
		return true
	default:
		c.close()
		return false
	}
}

func (c *wsClient) writePump() {
	for {
		select {
		case <-c.done:
			return
		case m := <-c.send:
			c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.conn.WriteMessage(m.typ, m.data); err != nil {
				c.close()
				return
			}
		}
	}
}

func (c *wsClient) close() {
	c.closeOnce.Do(func() {
		close(c.done)
		c.conn.Close()
	})
}

// serveAttach returns the handler for GET /sessions/{id}/attach: it looks up the
// session and wires the WebSocket to its hub as a participant. If token is
// non-empty it is required as ?token= or Bearer.
func (reg *Registry) serveAttach(token string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if token != "" && reqToken(r) != token {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		sess := reg.Get(r.PathValue("id"))
		if sess == nil {
			http.Error(w, "no such session", http.StatusNotFound)
			return
		}
		h := sess.hub
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		client := newWSClient(conn)

		// Initial viewport may be passed as ?cols=&rows= (else 0 = unknown until
		// the client sends its first resize frame).
		cols := atoiDefault(r.URL.Query().Get("cols"), 0)
		rows := atoiDefault(r.URL.Query().Get("rows"), 0)
		id := h.add(client, cols, rows)
		h.setOnResize(id, client.sendSize)
		ec, er := h.size()
		client.sendSize(ec, er) // tell the client the current grid size up front
		log.Printf("ws attached: session=%s %s (%dx%d)", sess.ID, r.RemoteAddr, cols, rows)

		defer func() {
			h.remove(id)
			client.close()
			log.Printf("ws detached: session=%s %s", sess.ID, r.RemoteAddr)
		}()

		// Read loop: binary = input bytes (the emulator's back-channel responses);
		// text = JSON control (resize). User commands arrive via POST .../send.
		for {
			typ, data, err := conn.ReadMessage()
			if err != nil {
				return
			}
			switch typ {
			case websocket.BinaryMessage:
				h.sink.Write(data)
			case websocket.TextMessage:
				var m ctrl
				if json.Unmarshal(data, &m) == nil && m.T == "resize" {
					h.setSize(id, m.Cols, m.Rows)
				}
			}
		}
	}
}

func atoiDefault(s string, def int) int {
	if n, err := strconv.Atoi(s); err == nil {
		return n
	}
	return def
}
