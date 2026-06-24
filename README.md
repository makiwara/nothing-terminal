# nothing-terminal

**Research question:** can a local terminal session running a *rich TUI* be exposed
over SSH to another terminal client?

**Answer: yes.** A rich TUI works over SSH because nothing re-renders it — the app
writes ANSI/VT escape sequences to a PTY, those raw bytes are forwarded over the
SSH channel, and the *client's own terminal* interprets them. You only need to get
three things right: allocate a real PTY, propagate terminal size (SIGWINCH), and
forward bytes raw (no line buffering).

This repo is `termshare`, a ~300-line Go proof-of-concept that goes one step
further than the basic question: it shares **one live session with multiple
participants at once** — the local user plus any number of remote clients using a
**stock `ssh` client** (no custom client needed). It's a miniature of what
tmux/tmate do.

## Two ways to attach

The same `Hub` multiplexes a single PTY to any mix of participants. It exposes
**two** transports:

- **WebSocket** (`:8080/attach`) — the contract for the **Android app**. See
  [PROTOCOL.md](PROTOCOL.md). This is the path that matters going forward; the
  future `nothing-serious` Python service implements the same protocol.
- **SSH** (`:2222`) — kept for quick manual testing with a stock terminal.

Requires Go 1.21+ (`brew install go`). **Must run in a real terminal** (PTY
allocation needs a genuine TTY; sandboxes return `ENXIO`).

```bash
go build -o termshare .

# Host a session (defaults to $SHELL; any TUI works):
./termshare -- htop                  # serves WS :8080 + SSH :2222
./termshare -ws :8080 -token secret  # require ?token=secret on WS attach

# Attach over SSH (manual test):
ssh -p 2222 -tt -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null localhost

# Attach over WebSocket (what the app does) — e.g. with websocat:
websocat 'ws://localhost:8080/attach?cols=80&rows=24'
```

Every attached client sees and drives the **same** live session; the PTY is sized
to the smallest attached client. Logs go to stderr and will garble a local
raw-mode session — redirect with `2>/tmp/termshare.log` if you also drive it
locally, or run it headless (no TTY on stdin = server-only, no local attach).

> This repo is the research spike + a **stand-in server** so the Android app can
> be built before any `nothing-serious` work. See PROTOCOL.md for the wire format
> the app targets.

## How it works

```
        ┌─────────── one child process (shell / htop / vim) ───────────┐
        │                       one real PTY                            │
        └───────────────▲───────────────────────────┬──────────────────┘
            keystrokes   │ (merged)        output    │ (fanned out)
                         │                           ▼
                  ┌──────┴──────────────────────── Hub ───────────────────┐
                  │  participants = local terminal + N ssh.Session(s)      │
                  │  • output  → every participant                         │
                  │  • input   ← any participant → the one PTY             │
                  │  • PTY sized to the SMALLEST attached terminal         │
                  └────────────────────────────────────────────────────────┘
                         ▲                                  ▲
                 local raw-mode stdin              gliderlabs/ssh server
                                                   (stock `ssh` clients)
```

- **`hub.go`** — the transport-agnostic core: fan-out, input merge, and the
  "size to the smallest terminal" policy (tmux's default, so no client is
  truncated). Decoupled from the PTY so it's unit-testable.
- **`main.go`** — wiring: spawns the child on a PTY (`creack/pty`), attaches the
  local terminal as participant #0 (raw-mode stdin), and runs the SSH server
  (`gliderlabs/ssh`), attaching each connection as another participant.

### Late-joiner repaint
When a client joins mid-session, it has missed the bytes already drawn. `termshare`
briefly nudges the PTY size to trigger SIGWINCH, so well-behaved full-screen apps
repaint themselves at full fidelity. This is a cheap stand-in for what tmux does
properly: maintain an in-memory model of the screen grid and re-emit it. That
(via a VT emulator such as `hinshun/vt10x` or `charmbracelet/x/vt`) is the main
thing to build next for robustness.

## Dependencies

| Library | Role |
|---|---|
| [`github.com/gliderlabs/ssh`](https://github.com/gliderlabs/ssh) | Embeddable SSH server; PTY + window-change handling |
| [`github.com/creack/pty`](https://github.com/creack/pty) | Allocate & resize the child's PTY |
| [`golang.org/x/term`](https://pkg.go.dev/golang.org/x/term) | Raw mode + size for the local terminal |

## Status, limitations & next steps

Verified: the multiplexing core (`go test -race`) — fan-out, input merge, and the
smallest-size resize policy. The full PTY + SSH round trip is exercised by the
manual two-terminal test above. (It can't be run inside a sandboxed CI/agent
environment, which blocks PTY device allocation — `pty.Start` returns ENXIO.)

Not done (it's a prototype):

- **No authentication** — accepts any connection. Do **not** expose to an
  untrusted network. Real use needs `PublicKeyHandler` / authorized-keys.
- **No true screen replay** — late joiners rely on the SIGWINCH nudge; add a VT
  emulator for a faithful snapshot.
- **No read-only / view-only participants**, no per-client cursor, no scrollback.
- **Smallest-size policy** means one small window shrinks everyone (same tradeoff
  tmux has without per-client windows).

### Alternatives considered
- **[Charm Wish](https://github.com/charmbracelet/wish)** — best path if the goal
  were serving *your own* TUI app (each connection = a fresh session). We chose the
  live-*sharing* model instead, which needs the multiplexer above.
- **tmux / [tmate](https://tmate.io/)** — the production-grade version of exactly
  this. tmate adds a relay so sharing works through NAT with no inbound port.
