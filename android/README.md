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

Landscape, full-screen terminal, voice-only — no keyboard.

- Swipe horizontally to move through the ring of open sessions.
- Drag vertically to scroll scrollback.
- Top-right ☰: close the active terminal, or open a new one from the script list.
- Hold the bottom handle to speak; release to propose; the transcript appears with Cancel / Adjust / Send, and only Send injects.

The recorder is a stub here: it captures no real audio. The stand-in's mock STT ignores the upload and returns a canned proposal. On merge into nothing-to-say it is replaced by that app's `RecordingController`.

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
4. In the app: open a session from the ☰ menu; it appears in the ring and renders the demo stream. Hold to speak → Send injects `git status`, which echoes on the session's `sent:` line.

## Notes

- Termux coordinates: `com.github.termux.termux-app:terminal-view:v0.118.0` via JitPack (the `v` prefix is required; the wiki's `com.termux:…` 401s). The API is v0.118.0, older than master — verify method names against it.
- Config: `TERMINALS_BASE_URL` is the http base; the app derives REST (http) and per-session WS (ws) from it. Use https/wss in any networked deployment.
- When the real `nothing-serious` backend ships (same specs/protocol.md plus the REST shapes), only `TERMINALS_BASE_URL` + token change — no client code.
- Verify UI behaviour on a device or emulator; a clean build does not confirm the cockpit works.
