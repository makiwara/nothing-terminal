# Terminal-over-WebSocket protocol (v0)

This is the wire contract between a terminal **client** (the Android app) and a
**server** that owns a PTY-backed session. The `nothing-serious` `remote_coding`
service (Python) implements it, so the Android app works against it.

The guiding principle is **transparent, bidirectional, raw-byte passthrough**:
the PTY master byte stream is the ground truth and is forwarded verbatim into a
real terminal emulator. Bytes are the *data plane*; JSON is a thin *control
plane* for things bytes can't carry (size).

This document covers only the per-session stream. The REST control plane — the script catalog, the session ring, and the voice propose/send flow — is specified in `control_plane.md`.

## Connect

```
GET /attach?token=<t>&cols=<c>&rows=<r>     Upgrade: websocket
```

- `token` — required only if the server is configured with one. (In production:
  a Bearer token; for the prototype, a `?token=` query param.)
- `cols`, `rows` — optional initial viewport; may be omitted and sent later as a
  `resize` control frame.

Use **WSS** in any networked deployment (the tunnel terminates TLS).

## Frames

WebSocket message **type** selects the plane — no in-band framing/escaping.

### Server → Client
| Type | Payload | Meaning |
|---|---|---|
| **Binary** | raw bytes | Terminal output (VT/ANSI). Append verbatim to the emulator. |
| **Text** | `{"t":"size","cols":C,"rows":R}` | Effective grid size. Render at this size. Sent on attach and whenever it changes. |

### Client → Server
| Type | Payload | Meaning |
|---|---|---|
| **Binary** | raw bytes | Input: keystrokes **and** emulator-generated responses (DA replies, cursor-position reports, mouse, bracketed-paste). Send verbatim. |
| **Text** | `{"t":"resize","cols":C,"rows":R}` | Client viewport size. Send on connect and on every change (rotation, font size, keyboard show/hide). |

## Semantics

- **Effective size = the smallest of all attached clients** (tmux's policy), so
  no client is truncated. A client may therefore receive a `size` smaller than
  its own viewport; render at the given size (letterbox the remainder).
- A `resize` from any client recomputes the effective size; if it changed, the
  server resizes the PTY (→ `SIGWINCH` → the app repaints) and broadcasts the new
  `size` to all clients.
- **Late joiners:** on attach the client should expect a burst of output bytes — the server repaints the current screen. The repaint mechanism is server-side and not part of this contract.
- **Backpressure:** a client that can't keep up is disconnected rather than sent
  a partial stream (dropped bytes corrupt escape sequences). Reconnect for a
  fresh paint.
- **TERM:** the server runs the child with `TERM=xterm-256color`; the client
  emulator must be xterm-256color compatible (Termux's is).

## Non-goals (v0)

No auth handshake beyond the token, no multiplexed channels, no scrollback
replay, no view-only role, no compression. These are additive later without
breaking the byte/JSON split above.
