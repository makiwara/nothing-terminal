package main

import (
	"bytes"
	"io"
	"sync"
	"testing"
)

// syncBuffer is a goroutine-safe io.Writer for capturing a participant's screen.
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.String()
}

// TestFanOut verifies that session output is delivered to every attached
// participant — the core of "share one session with many clients".
func TestFanOut(t *testing.T) {
	pr, pw := io.Pipe()
	h := newHub(pr, io.Discard, nil)

	a, b, c := &syncBuffer{}, &syncBuffer{}, &syncBuffer{}
	h.add(a, 80, 24)
	h.add(b, 80, 24)
	h.add(c, 80, 24)

	done := make(chan struct{})
	go func() { h.pumpOutput(); close(done) }()

	// io.Pipe writes block until the reader (pumpOutput) consumes them, so once
	// WriteString returns the fan-out has happened.
	io.WriteString(pw, "rich-tui-bytes")
	pw.Close()
	<-done

	for name, p := range map[string]*syncBuffer{"a": a, "b": b, "c": c} {
		if got := p.String(); got != "rich-tui-bytes" {
			t.Errorf("participant %s got %q, want %q", name, got, "rich-tui-bytes")
		}
	}
}

// TestSmallestSize verifies the tmux-style "size to the smallest terminal"
// policy, including that unsized (0) participants are ignored.
func TestSmallestSize(t *testing.T) {
	parts := map[int]*participant{
		0: {cols: 120, rows: 40},
		1: {cols: 80, rows: 50},
		2: {cols: 100, rows: 24},
		3: {cols: 0, rows: 0}, // not yet reported -> ignored
	}
	cols, rows := smallestSize(parts)
	if cols != 80 || rows != 24 {
		t.Fatalf("smallestSize = %dx%d, want 80x24", cols, rows)
	}
}

// TestApplySizeResizesToSmallest verifies the hub drives the underlying session
// resize callback to the smallest attached size as participants join.
func TestApplySizeResizesToSmallest(t *testing.T) {
	var mu sync.Mutex
	var lastCols, lastRows int
	setSz := func(cols, rows int) {
		mu.Lock()
		lastCols, lastRows = cols, rows
		mu.Unlock()
	}

	h := newHub(nil, io.Discard, setSz)
	h.add(io.Discard, 120, 40) // only client -> 120x40
	mu.Lock()
	gotC, gotR := lastCols, lastRows
	mu.Unlock()
	if gotC != 120 || gotR != 40 {
		t.Fatalf("after first join, size = %dx%d, want 120x40", gotC, gotR)
	}

	h.add(io.Discard, 80, 24) // smaller client joins -> shrink to 80x24
	mu.Lock()
	gotC, gotR = lastCols, lastRows
	mu.Unlock()
	if gotC != 80 || gotR != 24 {
		t.Fatalf("after smaller join, size = %dx%d, want 80x24", gotC, gotR)
	}
}
