# nothing-terminal · Android client

A minimal Android terminal **client** that attaches to a termshare-protocol server
over WebSocket and renders a rich TUI. Conventions copied from `nothing-to-say`
(Kotlin 2.3, Compose + Material3, single-activity, singleton-model state, OkHttp);
the terminal itself reuses Termux's VT engine.

Standalone app now, but the terminal code is isolated in two portable packages so
it can be lifted into nothing-to-say later as a "terminal panel":

```
com.humanemagica.nothing.terminal
├── MainActivity / shell/ / ui/theme/   ← standalone shell (dropped on merge)
├── net/                                ← PORTABLE
│   ├── TerminalClient.kt   WebSocket + connection state (PROTOCOL.md)
│   └── Protocol.kt         JSON control frames
└── term/                               ← PORTABLE
    ├── RemoteTerminalView.kt   custom View: Termux emulator + renderer + input
    ├── WsTerminalOutput.kt     emulator output → WebSocket (the back-channel)
    └── MinimalSessionClient.kt no-op TerminalSessionClient the emulator requires
```

## Why not just use Termux's TerminalView?

Termux's `TerminalSession` is `final` and hard-wired to a local subprocess, and the
emulator's **response back-channel** (DA replies, cursor reports) flows through the
session to that subprocess — somewhere we can't redirect to the network. So we use
the pieces that *are* reusable — `TerminalEmulator` (the VT/ANSI engine) and
`TerminalRenderer` (its real cell drawing) — inside our own small `View`, with a
custom `TerminalOutput` that sends to the WebSocket. That keeps input **and**
emulator responses flowing to the remote PTY (see PROTOCOL.md "full-duplex conduit").

## Run it against the Go stand-in

1. On your Mac, start the stand-in server **in a real terminal** (it needs a PTY):
   ```bash
   cd ..            # repo root
   go build -o termshare . && ./termshare -- htop
   ```
2. Point the app at it in `local.properties` (gitignored):
   - **Emulator:** `TERMINALS_WS_URL=ws://10.0.2.2:8080/attach` (10.0.2.2 = host loopback)
   - **Physical phone on same wifi:** `ws://<your-mac-LAN-ip>:8080/attach`
   - Leave `TERMINALS_DEVICE_TOKEN` blank unless you started the server with `-token`.
3. Build & install:
   ```bash
   ./gradlew :app:installDebug      # or open android/ in Android Studio and Run
   ```

The app auto-connects on launch and renders the shared session; the key bar adds
esc/tab/arrows that soft keyboards omit.

## Status — prototype scaffold

Known areas to iterate (deliberately minimal):
- **Input**: TYPE_NULL key events + a small key bar. No IME composition, no Ctrl/Alt
  bar (hardware Ctrl works), no mouse, no scrollback gestures, no text selection.
- **Resize**: re-creates the emulator on size change (the server repaints after every
  SIGWINCH, so nothing is lost) rather than `emulator.resize()` in place.
- **Termux coordinates**: `com.github.termux.termux-app:terminal-view:v0.118.0` via
  JitPack. (The wiki's `com.termux:terminal-view` 401s; the canonical multimodule
  path with the `v` prefix is what resolves.)
- **Security**: token is sent as `?token=` (stand-in) and `Authorization: Bearer`
  (nothing-serious). Use WSS in any networked deployment.

When `nothing-serious`' `terminals/` service ships (same PROTOCOL.md), only
`TERMINALS_WS_URL` + token change — no client code changes.
