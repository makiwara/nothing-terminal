# nothing-terminal

Voice-driven terminal cockpit for the home server: operate the home machine's shell (rich TUIs) from an Android phone, hands-free. The deliverable is the Android client under `android/`. See [`android/README.md`](android/README.md) to build and run it.

## What's here

- `android/` — the Android cockpit (the deliverable). Landscape, voice-only, renders rich TUIs with Termux's VT engine.
- `specs/` — the frozen wire contract, co-owned with the backend: `protocol.md` (per-session WebSocket data plane), `control_plane.md` (REST: scripts, ring, voice propose/send), and `ui/` (cockpit UI specs).
- `journal/` — design history for this repo's half of the system.

## The backend

The client talks to the HOME-only Python `remote_coding` service in the sibling `nothing-serious` repo, which implements `specs/protocol.md` + `specs/control_plane.md` verbatim. The phone reaches it through the hosted relay; point `TERMINALS_BASE_URL` at that endpoint (see `android/README.md`). A Go stand-in server (`termshare`) used to live here to exercise the client before that backend existed; it has been retired now that the real service ships. Its history remains in git.
