// Command termshare exposes a single local terminal session (running any rich
// TUI) to multiple participants at once: the local user plus any number of
// remote clients connecting with a stock `ssh` client. It is a deliberately
// small proof-of-concept — a few hundred lines standing in for what tmux/tmate
// do at scale — built to answer one question: can a rich TUI be shared live
// over SSH to an unmodified terminal client? (It can.)
//
// Architecture:
//   - One child process runs on one real PTY (creack/pty).
//   - A Hub (see hub.go) fans the PTY's output out to every participant and
//     feeds every participant's keystrokes back into the single PTY.
//   - The PTY is sized to the SMALLEST attached terminal (tmux's default) so no
//     client sees truncated output.
//   - On join, the PTY size is briefly nudged to provoke SIGWINCH, prompting
//     well-behaved full-screen apps to repaint themselves at full fidelity.
//
// Security: there is NO authentication. This is a prototype — do not expose it
// to an untrusted network.
package main

import (
	"flag"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"syscall"

	"github.com/creack/pty"
	"github.com/gliderlabs/ssh"
	"golang.org/x/term"
)

func main() {
	wsAddr := flag.String("ws", ":8080", "WebSocket listen address (the Android-app contract)")
	sshAddr := flag.String("ssh", ":2222", "SSH listen address (empty to disable)")
	token := flag.String("token", "", "if set, require ?token=<v> on WS attach")
	demo := flag.Bool("demo", false, "serve a generated ANSI demo stream instead of a PTY (no real terminal needed)")
	flag.Parse()

	if *demo {
		runDemo(*wsAddr, *sshAddr, *token)
		return
	}

	command := flag.Args()
	if len(command) == 0 {
		shell := os.Getenv("SHELL")
		if shell == "" {
			shell = "/bin/bash"
		}
		command = []string{shell}
	}

	cmd := exec.Command(command[0], command[1:]...)
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")
	ptmx, err := pty.Start(cmd)
	if err != nil {
		log.Fatalf("failed to start %v: %v", command, err)
	}
	defer ptmx.Close()

	hub := newHub(ptmx, ptmx, func(cols, rows int) {
		pty.Setsize(ptmx, &pty.Winsize{Rows: uint16(rows), Cols: uint16(cols)})
	})

	// Pump PTY output to all participants until the child exits.
	go func() {
		hub.pumpOutput()
		log.Println("session ended (child process exited)")
		os.Exit(0)
	}()

	// Attach the local terminal as a participant only when we have a real TTY;
	// otherwise run headless as a pure server. (Logs go to stderr and will
	// garble a local raw-mode session — redirect with `2>/tmp/termshare.log`.)
	if term.IsTerminal(int(os.Stdin.Fd())) {
		local := attachLocal(hub, ptmx)
		defer local.detach()
	} else {
		log.Println("stdin is not a TTY: running headless (no local attach)")
	}

	// SSH server (handy for manual testing with a stock terminal).
	if *sshAddr != "" {
		go func() {
			log.Printf("SSH on %s (test: ssh -p %s -tt <host>)", *sshAddr, portOf(*sshAddr))
			if err := (&ssh.Server{Addr: *sshAddr, Handler: hub.handleSSH}).ListenAndServe(); err != nil {
				log.Printf("ssh server stopped: %v", err)
			}
		}()
	}

	// WebSocket server — what the Android app connects to. See PROTOCOL.md.
	mux := http.NewServeMux()
	mux.HandleFunc("/attach", hub.serveWS(*token))
	log.Printf("WebSocket on %s/attach", *wsAddr)
	log.Fatal(http.ListenAndServe(*wsAddr, mux))
}

// handleSSH attaches one remote SSH session as a participant.
func (h *Hub) handleSSH(s ssh.Session) {
	ptyReq, winCh, isPty := s.Pty()
	if !isPty {
		io.WriteString(s, "termshare: this session needs a PTY (use: ssh -t)\n")
		return
	}
	log.Printf("client attached: %s (%dx%d)", s.RemoteAddr(), ptyReq.Window.Width, ptyReq.Window.Height)

	id := h.add(s, ptyReq.Window.Width, ptyReq.Window.Height)
	defer func() {
		h.remove(id)
		log.Printf("client detached: %s", s.RemoteAddr())
	}()

	// Track this client's window-size changes.
	go func() {
		for w := range winCh {
			h.setSize(id, w.Width, w.Height)
		}
	}()

	// This client's keystrokes drive the shared PTY.
	io.Copy(h.sink, s)
}

// localParticipant is the local terminal's attachment to the hub.
type localParticipant struct {
	hub      *Hub
	id       int
	oldState *term.State
	sigCh    chan os.Signal
}

// attachLocal wires the local terminal in as a participant: raw-mode stdin is
// forwarded to the PTY, and local window resizes feed the size policy. When
// stdin is not a TTY (e.g. running headless), it degrades gracefully.
func attachLocal(h *Hub, sink io.Writer) *localParticipant {
	fd := int(os.Stdin.Fd())
	cols, rows, err := term.GetSize(fd)
	if err != nil {
		cols, rows = 80, 24
	}
	oldState, _ := term.MakeRaw(fd)

	id := h.add(os.Stdout, cols, rows)
	lp := &localParticipant{hub: h, id: id, oldState: oldState, sigCh: make(chan os.Signal, 1)}

	go io.Copy(sink, os.Stdin) // local keystrokes -> shared PTY

	signal.Notify(lp.sigCh, syscall.SIGWINCH)
	go func() {
		for range lp.sigCh {
			if c, r, err := term.GetSize(fd); err == nil {
				h.setSize(id, c, r)
			}
		}
	}()

	return lp
}

func (lp *localParticipant) detach() {
	signal.Stop(lp.sigCh)
	if lp.oldState != nil {
		term.Restore(int(os.Stdin.Fd()), lp.oldState)
	}
	lp.hub.remove(lp.id)
}

func portOf(addr string) string {
	for i := len(addr) - 1; i >= 0; i-- {
		if addr[i] == ':' {
			return addr[i+1:]
		}
	}
	return addr
}
