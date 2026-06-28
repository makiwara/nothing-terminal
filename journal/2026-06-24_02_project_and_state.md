# nothing-terminal — project overview and state

refs: `specs/protocol.md` (the wire contract), `journal/2026-06-24_01_app_side_feedback.md` (backend-contract findings), `../nothing-serious/journal/v20/2026-06-24_05_terminals.md` (backend brief, read-only sibling), `../nothing-to-say/journal/002/2026-06-24_36_terminal_cockpit.md` (phone-side UI design, read-only sibling)

## What this is

A voice-driven terminal cockpit for the home server: operate the home machine's shell — including rich full-screen TUIs (vim, htop, top) — from an Android phone, hands-free. The phone shows one full-screen terminal at a time, swipes through a ring of open sessions, scrolls scrollback vertically, and takes input only by voice: hold to speak, review the transcribed command, and Send. There is no keyboard.

The work is split across three repos. This one owns the Android client (the deliverable), the Go `termshare` stand-in (a throwaway harness), and `specs/protocol.md` (the contract). The real backend is the HOME-only Python `terminals/` service in the sibling `nothing-serious`; the phone-side UI design and the backend design live in the siblings' journals. This repo never writes to the siblings — it references them.

## How it fits together

```
  Android cockpit (this repo, android/)        same contract        server
  ┌────────────────────────────────┐                          ┌──────────────────────────────┐
  │ landscape ring (HorizontalPager)│── REST: scripts, ring ──▶│ termshare stand-in (this repo)│  now
  │ RemoteTerminalView (Termux VT)  │── WS: specs/protocol.md ─│   demo sessions, no PTY        │
  │ hold-to-speak → review → Send   │── REST: voice/send ─────▶│   mock STT propose / inject    │
  └────────────────────────────────┘                          └──────────────────────────────┘
                  │                                            ┌──────────────────────────────┐
                  └─ same calls, URL swap ────────────────────▶│ nothing-serious terminals/    │  later
                                                               │   HOME-only, real PTYs + STT   │
                                                               └──────────────────────────────┘
```

## What lives in this repo

- `specs/protocol.md` — the per-session WebSocket data plane: binary frames carry raw terminal bytes both directions; JSON text frames carry size/resize. The frozen contract the stand-in, the app, and the future backend all implement. The repaint mechanism is server-side and explicitly out of the contract.
- `termshare` (Go stand-in) — a session registry plus a control plane: `GET /scripts`, the `/sessions` ring CRUD, `GET /sessions/{id}/attach` (the data plane), and a mock voice flow (`POST .../voice` proposes a canned action, `POST .../send` injects it). Sessions are demo-backed (a generated ANSI stream, no PTY) so the harness runs anywhere; a command on the CLI adds a real PTY-backed script for use in a genuine terminal. Not production.
- `android/` — the cockpit. Termux's `TerminalEmulator` + `TerminalRenderer` drive rendering inside a custom `View` (Termux's `TerminalSession`/`TerminalView` are final and subprocess-bound, so they are not used); a `SessionConnection` per ring page carries the byte stream; a `CockpitModel` singleton holds the ring, the script catalog, and the voice review state. The portable `net/` and `term/` packages are kept isolated from the disposable `shell/` so the cockpit can be lifted into nothing-to-say as a panel.

## State

Done and verified:
- The contract and its promotion from the old root `PROTOCOL.md` to `specs/protocol.md`.
- The Go stand-in: `go vet` and `go test -race` green (per-session WS attach plus a full control-plane test); the REST surface smoke-tested over HTTP.
- The Android cockpit compiles clean against the Termux v0.118.0 API.
- Committed and pushed to `origin/main` (commit `28eb9e5`).

Done but NOT device-verified (the open risk):
- The cockpit UI has not run on hardware. The target phone (a Xiaomi that drops USB constantly) stayed disconnected through the rework, so the install never landed. Unverified: the horizontal-swipe-vs-vertical-scrollback gesture split, the per-session WebSocket lifecycle as pages attach and detach on swipe, the hold-to-speak → review → Send flow, and the landscape layout. A clean build does not confirm the UI works.

Intentional stubs and limitations:
- The voice recorder is a stub — it captures no real audio. The stand-in's mock STT ignores the upload and returns a canned proposal (`git status`, or a Ctrl-C signal for `?intent=stop`). Real capture arrives via nothing-to-say's `RecordingController` on merge.
- The stand-in is a harness, not production; no real auth beyond an optional shared `-token`.
- Control keys beyond stop/cancel are deferred; the backend's LLM action schema is built to grow into them.

Key decisions (detail in entry `01` and the backend brief):
- The real backend serves its WebSocket via a Starlette `WebSocketRoute` on an ASGI mount, not Quart `@websocket` — Quart's url map is closed by the time the HOME service initialises, as the removed v1 `socket.py` already proved.
- Late-joiner repaint uses the SIGWINCH nudge, not a `pyte` snapshot: pyte 0.8 does not model the alternate screen, so it cannot faithfully repaint the headline TUIs.
- Backend Phase 1 reuses the removed v1 backend's scaffolding from nothing-serious git history (before commit `b16b132`): mount, auth, and PTY runner transfer; the protocol and repaint change.

## Cross-repo split

- This repo: client, contract, stand-in.
- `nothing-serious` (sibling, read-only): the HOME-only Python `terminals/` backend. Brief at `journal/v20/2026-06-24_05_terminals.md`. Owns the security model and the real STT/LLM voice pipeline.
- `nothing-to-say` (sibling, read-only): the Android conventions this client follows and the `RecordingController` reused for real voice capture on merge. Phone-UI design at `journal/002/2026-06-24_36_terminal_cockpit.md`.

Each repo journals its own half and references the others; nothing is written across the read-only boundary. One outstanding cross-repo cleanup, for the siblings' owner to apply: trim the app-side feedback addendum out of the nothing-serious brief and point it at `journal/2026-06-24_01_app_side_feedback.md` here.

## Next

- Device-verify the cockpit — open a session, confirm the ring renders, test scrollback and the voice review→send flow. This is the only step between "compiles" and "works", and it needs the phone connected.
- Then backend Phase 1 in `nothing-serious`: the HOME-gated `terminals/` service reaching byte-parity with this stand-in, after which the app needs only a URL and token change. That is sibling work, not this repo's.
