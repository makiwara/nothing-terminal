# nothing-terminal

Voice-driven terminal cockpit for the home server: operate the home machine's shell (rich TUIs) from an Android phone, hands-free. The deliverable is the Android client under `android/`. The `termshare` at the root is the development stand-in server — a harness implementing `specs/protocol.md` (the per-session WebSocket data plane) plus the control plane the app needs, so the client can be built and exercised before the real backend (the HOME-only Python `terminals/` service in `nothing-serious`) exists.

## The stand-in server

Sessions are demo-backed by default — each runs a generated ANSI stream, no PTY — so the harness runs anywhere, including sandboxes that can't allocate a PTY. Pass a command to add a real PTY-backed script (needs a genuine terminal).

Requires Go 1.22+ (`brew install go`).

```
go build -o termshare .
./termshare                       # serve :8080 with demo scripts (monitor, logs, build)
./termshare -- htop               # also adds a real "htop" script (run in a real terminal)
./termshare -token SECRET         # require ?token= or Authorization: Bearer on every endpoint
```

Endpoints (all optionally gated by `-token`):

```
GET    /scripts                      list the catalog
GET    /sessions                     list the open ring
POST   /sessions {script_id}         open a session
DELETE /sessions/{id}                close a session
GET    /sessions/{id}/attach         WebSocket stream (specs/protocol.md)
POST   /sessions/{id}/voice          mock propose -> {transcript, action}
POST   /sessions/{id}/send {action}  inject the confirmed action
```

The voice endpoints are a mock — there is no STT here. `/voice` returns a canned proposal (`?intent=stop` returns a Ctrl-C signal action); `/send` injects the action into the session, and a demo session echoes it on a `sent:` line so the path is visible.

## Architecture

```
  Android cockpit (android/)             termshare stand-in (this repo)
  ┌───────────────────────────┐         ┌──────────────────────────────────┐
  │ ring = HorizontalPager     │── REST ─│ Registry: scripts + session ring  │
  │ page → RemoteTerminalView  │── WS ───│ Session → Hub → demo gen / PTY    │
  │ voice → review → send      │── REST ─│ mock STT propose / send → inject  │
  └───────────────────────────┘         └──────────────────────────────────┘
```

- `hub.go` — the per-session multiplexer: fan-out to attached clients, input merge, smallest-size resize. PTY-agnostic, unit-tested.
- `session.go` — the registry, the script catalog, and the demo generator.
- `control.go` — the REST control plane.
- `ws.go` — the WebSocket data plane (`specs/protocol.md`).

## Run the client against it

See `android/README.md`. In short: run `./termshare`, install the app, point `TERMINALS_BASE_URL` at the Mac's LAN IP (`http://<mac-lan-ip>:8080`), and open a session from the menu.

## Notes

- Not production; no real auth beyond the optional shared `-token`. The real backend in `nothing-serious` owns the security model.
- The sandbox can't allocate PTYs (`pty.Start` → ENXIO); demo sessions need none. Run a real-shell script only in a genuine terminal.
- Tests: `go test -race ./...`.
