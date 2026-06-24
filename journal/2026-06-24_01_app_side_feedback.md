# App-side feedback on the backend contract

refs: `specs/protocol.md` (the contract this repo owns), `../nothing-serious/journal/v20/2026-06-24_05_terminals.md` (the backend brief these notes respond to), nothing-serious git history before commit `b16b132` (the removed v1 backend: `terminals/socket.py`, `runner.py`, `voice.py`, `keys.py`)

Findings from the client + stand-in side (this repo) after checking the `nothing-serious` backend brief against the live codebase. They live here because this repo owns the contract (`specs/protocol.md`) and the stand-in that exercises it; the sibling brief should reference this entry rather than carry app-side reasoning.

## WS transport: Starlette mount is necessary, not a preference

The removed v1 backend's `terminals/socket.py` mounted its `/tty` WebSocket as a Starlette sub-app wrapping `app.asgi_app`, and its docstring gives the reason verbatim: "Quart's url map is closed by the time the home runner initialises." The terminals service inits late (deferred `SERVICE_ORDER` startup), after the URL map is finalized, so a Quart `@websocket` route cannot be registered at that point. Quart `@websocket` is therefore not viable here; the ASGI-mount pattern is the path. `_mount_starlette` (`mcp/__init__.py`) composes cleanly — its middleware chains over the previous asgi app, so a second mount needs no extension. The prior `socket.py` already did `hmac.compare_digest` on `TERMINALS_DEVICE_TOKEN` plus dual auth (device bearer or admin session cookie) — directly reusable.

Resolution: WS via Starlette `WebSocketRoute` mounted on `app.asgi_app`.

## pyte 0.8 does not model the alternate screen

Empirical probe — feed pyte `1049h` (enter alt screen) → write → `1049l` (leave):

```
primary line : 'PRIMARY'
alt line     : 'ALTSCR'
after-leave  : 'ALTSCR'   <- pyte did not restore the primary buffer
1049 in modes: False      <- pyte does not even track the mode
```

vim, htop, and top all run in the alternate screen — the cockpit's reason to exist. A pyte snapshot loses the primary/alt distinction and will not tell the client to enter or leave the alt screen, desyncing the client emulator's buffer state. A pyte-based repaint would have to track DECSET `1049`/`47`/`1047` itself, which is materially more than "snapshot pyte's grid."

## The "Go stand-in repaint" parity baseline does not exist

The Go `termshare` stand-in has no pyte/snapshot path. Its late-joiner mechanism is the SIGWINCH nudge: resize the PTY so the app redraws itself. `specs/protocol.md` states the repaint mechanism is server-side and out of contract scope. So there is nothing to byte-compare a pyte emitter against; any brief text implying such a baseline is unfounded.

Resolution: late-joiner repaint via the SIGWINCH nudge. The nudge is alt-screen-correct (the app re-emits its own redraw) and is already proven on device. A pyte snapshot is deferred/optional, and only for plain line-mode scrollback where no redraw is coming.

## Reuse the removed v1 backend

Backend Phase 1 is not a clean rewrite. The removed v1 backend (in `nothing-serious` git history before commit `b16b132`) carries reusable scaffolding: `socket.py` (mount + auth), `runner.py` (PTY runner), `voice.py`/`keys.py` (voice-to-key dispatch). The protocol changes to raw-byte `specs/protocol.md` (not the old row-frames) and the repaint changes to the nudge, but the mount, auth, and runner shape transfer directly.
