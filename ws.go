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
// identically. See PROTOCOL.md.
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

// serveWS returns an HTTP handler that attaches each WebSocket connection to the
// hub as a participant. If token is non-empty it is required as ?token=.
func (h *Hub) serveWS(token string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if token != "" && r.URL.Query().Get("token") != token {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
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
		log.Printf("ws client attached: %s (%dx%d)", r.RemoteAddr, cols, rows)

		defer func() {
			h.remove(id)
			client.close()
			log.Printf("ws client detached: %s", r.RemoteAddr)
		}()

		// Read loop: binary = input bytes (keystrokes + emulator responses);
		// text = JSON control (resize).
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
