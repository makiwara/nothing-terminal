# nothing-terminal · Android cockpit

The Android client: a landscape, voice-only terminal cockpit that attaches to termshare-protocol sessions and renders rich TUIs with Termux's VT engine. Conventions from nothing-to-say (Kotlin 2.3, Compose + Material3, single-activity, singleton-model state, OkHttp).

Standalone app now; the terminal logic is isolated in the portable `net/` and `term/` packages so it can be lifted into nothing-to-say later as a panel. The `shell/` and `ui/theme/` are the disposable host, dropped on merge.

```
com.humanemagica.nothing.terminal
├── MainActivity, shell/, ui/theme/      ← disposable host (dropped on merge)
│   └── shell/CockpitScreen.kt           ring pager, menu, hold-to-speak, review overlay
├── net/                                 ← PORTABLE (control + data plane)
│   ├── ControlApi.kt        REST: scripts, session ring, voice propose/send
│   ├── CockpitModel.kt      singleton: ring + scripts + review state
│   ├── SessionConnection.kt per-session WebSocket (specs/protocol.md)
│   ├── Protocol.kt          JSON size/resize frames
│   └── Models.kt            Script, Session, Action, Proposal
└── term/                                ← PORTABLE (rendering)
    ├── RemoteTerminalView.kt   Termux emulator + renderer in a View; scrollback
    ├── WsTerminalOutput.kt     emulator back-channel → WebSocket
    └── MinimalSessionClient.kt no-op TerminalSessionClient the emulator needs
```

## The cockpit

Landscape, full-screen terminal, voice-only — no keyboard. Specced in [`../specs/ui/`](../specs/ui/README.md) with mockups in `../specs/mockups/`.

- Swipe horizontally through the ring. The first page is the manager (lists open terminals, Halt each, Add new); the rest are terminals, each just a small title over the cell grid.
- Drag vertically on a terminal to scroll scrollback.
- Add new terminal → the preset selector (a separate screen); pick a preset to open a panel in the ring.
- Swipe up from the bottom handle to record; release sends, or pull to the top to lock (Cancel / Send). The audio uploads to propose; the transcript appears (editable via the system keyboard) with Cancel / Adjust / Confirm, and only Confirm injects.

The recorder is the real capture engine vendored from nothing-to-say (`voice/`, Opus over Concentus). Capture, audio focus, and the gesture are not yet device-verified. The ring-manager metadata (uptime, exited state) waits on the v1 control-plane fields (`../specs/control_plane.md`); until then a row shows the label and a static running dot. Adjust currently just dismisses (re-record by pulling the handle again).

## Why not Termux's TerminalView?

Termux's `TerminalSession` is `final` and hard-wired to a local subprocess, and the emulator's response back-channel (DA replies, cursor reports) flows through the session to that subprocess — not somewhere we can redirect to the network. So we drive Termux's `TerminalEmulator` (VT/ANSI engine) and `TerminalRenderer` (cell drawing) directly inside our own `View`, with a custom `TerminalOutput` that sends to the WebSocket. That keeps input and emulator responses flowing to the remote session (see specs/protocol.md, "full-duplex conduit").

## Run it against the stand-in

1. Start the stand-in on the Mac (demo sessions need no PTY):
   ```
   cd ..  &&  go build -o termshare .  &&  ./termshare
   ```
2. Point the app at it in `local.properties` (gitignored):
   - Physical phone on the same wifi: `TERMINALS_BASE_URL=http://<mac-lan-ip>:8080`
   - Emulator: `TERMINALS_BASE_URL=http://10.0.2.2:8080`
   - Leave `TERMINALS_DEVICE_TOKEN` blank unless the stand-in was started with `-token`.
3. Build and install: `./gradlew :app:installDebug`.
4. In the app: on the manager page, Add new terminal → pick a preset; it appears in the ring and renders the demo stream. Swipe up to speak → Confirm injects `git status` (the stand-in's canned proposal), which echoes on the session's `sent:` line.

## Notes

- Termux coordinates: `com.github.termux.termux-app:terminal-view:v0.118.0` via JitPack (the `v` prefix is required; the wiki's `com.termux:…` 401s). The API is v0.118.0, older than master — verify method names against it.
- Config: `TERMINALS_BASE_URL` is the http base; the app derives REST (http) and per-session WS (ws) from it. Use https/wss in any networked deployment.
- When the real `nothing-serious` backend ships (same specs/protocol.md plus the REST shapes), only `TERMINALS_BASE_URL` + token change — no client code.
- Verify UI behaviour on a device or emulator; a clean build does not confirm the cockpit works.
