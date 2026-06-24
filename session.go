package main

import (
	"fmt"
	"io"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/creack/pty"
)

// Script is a catalog entry the "open new" menu lists. Exactly one of command or
// demo drives a session: command runs a real shell on a PTY (needs a genuine
// terminal); demo generates an ANSI stream (no PTY) so the harness runs anywhere.
type Script struct {
	ID      string
	Label   string
	command []string
	demo    *demoSpec
}

type demoSpec struct {
	title string
	color int // ANSI foreground digit 0-7
}

// Session is one open terminal: a hub over either a PTY or a generated stream.
type Session struct {
	ID       string `json:"id"`
	ScriptID string `json:"script_id"`
	Label    string `json:"label"`
	Cols     int    `json:"cols"`
	Rows     int    `json:"rows"`

	hub     *Hub
	closeFn func()

	mu       sync.Mutex
	lastSent string // demo only: most recent injected input, echoed on screen
}

// Inject writes input bytes into the session (the voice "send" path).
func (s *Session) Inject(data []byte) {
	if t := strings.TrimRight(string(data), "\r\n"); t != "" {
		s.mu.Lock()
		s.lastSent = t
		s.mu.Unlock()
	}
	s.hub.sink.Write(data)
}

func (s *Session) lastSentText() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.lastSent
}

// Registry holds the open sessions (the ring) and the script catalog.
type Registry struct {
	mu       sync.Mutex
	sessions map[string]*Session
	order    []string
	scripts  []Script
	nextID   int
}

func newRegistry(scripts []Script) *Registry {
	return &Registry{sessions: map[string]*Session{}, scripts: scripts}
}

func (r *Registry) Scripts() []Script {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]Script(nil), r.scripts...)
}

func (r *Registry) script(id string) *Script {
	for i := range r.scripts {
		if r.scripts[i].ID == id {
			return &r.scripts[i]
		}
	}
	return nil
}

func (r *Registry) List() []*Session {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]*Session, 0, len(r.order))
	for _, id := range r.order {
		out = append(out, r.sessions[id])
	}
	return out
}

func (r *Registry) Get(id string) *Session {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.sessions[id]
}

// Open spawns a session from a script and adds it to the ring.
func (r *Registry) Open(scriptID string) (*Session, error) {
	sc := r.script(scriptID)
	if sc == nil {
		return nil, fmt.Errorf("no such script: %q", scriptID)
	}
	r.mu.Lock()
	r.nextID++
	id := fmt.Sprintf("s%d", r.nextID)
	r.mu.Unlock()

	sess := &Session{ID: id, ScriptID: sc.ID, Label: sc.Label}

	if sc.command != nil {
		cmd := exec.Command(sc.command[0], sc.command[1:]...)
		cmd.Env = append(cmd.Environ(), "TERM=xterm-256color")
		ptmx, err := pty.Start(cmd)
		if err != nil {
			return nil, err
		}
		sess.hub = newHub(ptmx, ptmx, func(cols, rows int) {
			pty.Setsize(ptmx, &pty.Winsize{Rows: uint16(rows), Cols: uint16(cols)})
		})
		sess.closeFn = func() { _ = cmd.Process.Kill(); ptmx.Close() }
		go func() {
			sess.hub.pumpOutput()
			r.Close(id) // child exited
		}()
	} else {
		pr, pw := io.Pipe()
		sess.hub = newHub(pr, demoSink{}, func(cols, rows int) {})
		stop := make(chan struct{})
		sess.closeFn = func() { close(stop); pw.Close() }
		go sess.hub.pumpOutput()
		go generateDemoStream(pw, *sc.demo, sess.lastSentText, stop)
	}

	r.mu.Lock()
	r.sessions[id] = sess
	r.order = append(r.order, id)
	r.mu.Unlock()
	return sess, nil
}

// Close kills a session and removes it from the ring. Idempotent.
func (r *Registry) Close(id string) bool {
	r.mu.Lock()
	sess := r.sessions[id]
	if sess != nil {
		delete(r.sessions, id)
		for i, sid := range r.order {
			if sid == id {
				r.order = append(r.order[:i], r.order[i+1:]...)
				break
			}
		}
	}
	r.mu.Unlock()
	if sess == nil {
		return false
	}
	sess.closeFn()
	return true
}

// demoSink discards the back-channel for demo sessions (they run no real shell).
type demoSink struct{}

func (demoSink) Write(p []byte) (int, error) { return len(p), nil }

// generateDemoStream paints a full screen every tick (so any late joiner repaints
// within one tick) and echoes the last injected command, so the voice send path
// is visible without a real shell.
func generateDemoStream(w io.Writer, spec demoSpec, lastSent func() string, stop <-chan struct{}) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	i := 0
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
		}
		var b strings.Builder
		b.WriteString("\x1b[H")
		demoLine(&b, fmt.Sprintf("\x1b[1;3%dm== %s ==\x1b[0m", spec.color, spec.title))
		demoLine(&b, "\x1b[2m nothing-terminal stand-in (demo session)\x1b[0m")
		demoLine(&b, "")
		demoLine(&b, fmt.Sprintf(" tick \x1b[1;33m%d\x1b[0m", i))
		demoLine(&b, "")
		var bars strings.Builder
		bars.WriteString(" colors: ")
		for c := 0; c < 8; c++ {
			fmt.Fprintf(&bars, "\x1b[4%dm  \x1b[0m", c)
		}
		demoLine(&b, bars.String())
		demoLine(&b, "")
		if s := lastSent(); s != "" {
			demoLine(&b, fmt.Sprintf(" sent: \x1b[1;32m%s\x1b[0m", s))
		} else {
			demoLine(&b, "\x1b[2m (speak to send a command)\x1b[0m")
		}
		if _, err := w.Write([]byte(b.String())); err != nil {
			return
		}
		i++
	}
}

func demoLine(b *strings.Builder, s string) {
	b.WriteString(s)
	b.WriteString("\x1b[K\r\n")
}
