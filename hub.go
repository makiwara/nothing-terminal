package main

import (
	"io"
	"sync"
)

// participant is anything that can display the session and be resized: the
// local terminal or a remote SSH session.
type participant struct {
	id   int
	out  io.Writer // where this participant's screen is drawn
	cols int
	rows int
	// onResize, if set, is called with the effective PTY size whenever it
	// changes. Transports that have a control channel (WebSocket) use this to
	// tell the client what grid size to render; raw transports (SSH, local)
	// leave it nil.
	onResize func(cols, rows int)
}

// Hub owns the single shared session (one PTY in production) and the set of
// attached participants. It is intentionally decoupled from *os.File / the pty
// package so the multiplexing core can be tested without allocating a real PTY:
//   - src   is the session's output stream (PTY master read side)
//   - sink  is the session's input stream  (PTY master write side)
//   - setSz resizes the underlying session
type Hub struct {
	src   io.Reader
	sink  io.Writer
	setSz func(cols, rows int)

	mu      sync.Mutex
	parts   map[int]*participant
	nextID  int
	curCols int
	curRows int
}

func newHub(src io.Reader, sink io.Writer, setSz func(cols, rows int)) *Hub {
	return &Hub{src: src, sink: sink, setSz: setSz, parts: make(map[int]*participant)}
}

// size returns the current effective (smallest-attached) size.
func (h *Hub) size() (cols, rows int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.curCols, h.curRows
}

// setOnResize attaches a size-change callback to a participant.
func (h *Hub) setOnResize(id int, fn func(cols, rows int)) {
	h.mu.Lock()
	if p, ok := h.parts[id]; ok {
		p.onResize = fn
	}
	h.mu.Unlock()
}

// pumpOutput copies session output to every attached participant. Returns when
// the session's output stream closes (child process exits).
func (h *Hub) pumpOutput() {
	buf := make([]byte, 32*1024)
	for {
		n, err := h.src.Read(buf)
		if n > 0 {
			h.mu.Lock()
			for _, p := range h.parts {
				p.out.Write(buf[:n]) // best-effort; a slow client must not stall others
			}
			h.mu.Unlock()
		}
		if err != nil {
			return
		}
	}
}

// add registers a participant with its initial terminal size and returns its id.
func (h *Hub) add(out io.Writer, cols, rows int) int {
	h.mu.Lock()
	id := h.nextID
	h.nextID++
	h.parts[id] = &participant{id: id, out: out, cols: cols, rows: rows}
	h.mu.Unlock()

	h.applySize()
	h.nudge() // provoke a redraw so the new joiner sees a full screen
	return id
}

func (h *Hub) remove(id int) {
	h.mu.Lock()
	delete(h.parts, id)
	h.mu.Unlock()
	h.applySize()
}

func (h *Hub) setSize(id, cols, rows int) {
	h.mu.Lock()
	if p, ok := h.parts[id]; ok {
		p.cols, p.rows = cols, rows
	}
	h.mu.Unlock()
	h.applySize()
}

// smallestSize returns the smallest width and height across participants (tmux's
// default policy), so no participant sees truncated output. Zero-sized
// participants (size not yet reported) are ignored.
func smallestSize(parts map[int]*participant) (cols, rows int) {
	for _, p := range parts {
		if p.cols > 0 && (cols == 0 || p.cols < cols) {
			cols = p.cols
		}
		if p.rows > 0 && (rows == 0 || p.rows < rows) {
			rows = p.rows
		}
	}
	return cols, rows
}

// applySize recomputes the smallest attached size and resizes the session if it
// changed.
func (h *Hub) applySize() {
	h.mu.Lock()
	cols, rows := smallestSize(h.parts)
	changed := cols > 0 && rows > 0 && (cols != h.curCols || rows != h.curRows)
	var cbs []func(int, int)
	if changed {
		h.curCols, h.curRows = cols, rows
		for _, p := range h.parts {
			if p.onResize != nil {
				cbs = append(cbs, p.onResize)
			}
		}
	}
	h.mu.Unlock()

	if changed && h.setSz != nil {
		h.setSz(cols, rows)
	}
	for _, cb := range cbs {
		cb(cols, rows) // notify control-channel clients of the new grid size
	}
}

// nudge briefly shrinks then restores the session to force a SIGWINCH,
// prompting full-screen apps to repaint. A cheap stand-in for true
// screen-state replay (what tmux does with an in-memory grid model).
func (h *Hub) nudge() {
	h.mu.Lock()
	cols, rows := h.curCols, h.curRows
	h.mu.Unlock()
	if cols < 2 || rows < 2 || h.setSz == nil {
		return
	}
	h.setSz(cols, rows-1)
	h.setSz(cols, rows)
}
