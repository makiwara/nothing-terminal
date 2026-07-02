# Terminal cockpit — control plane (REST)

The REST control plane the cockpit uses to list scripts, manage the session ring, and run the voice propose/send flow. It complements `protocol.md`, which is the per-session WebSocket data plane; together they are the contract between the Android client and any server. The `nothing-serious` `remote_coding` service implements it. As with `protocol.md`: if a change is needed, change this doc first and flag that the backend must follow.

## Conventions

- Transport: HTTPS; the per-session stream upgrades to WSS. Use TLS in any networked deployment (the tunnel terminates it).
- Auth: a device bearer token (`TERMINALS_DEVICE_TOKEN`). Production sends `Authorization: Bearer <t>`; the prototype also accepts `?token=<t>`. When the server is configured with a token every endpoint is gated; missing or wrong → 401.
- Request bodies are JSON, except `POST …/voice`, which is `multipart/form-data` (see [Voice upload encoding](#voice-upload-encoding)). Errors return `{"error": "<message>"}` with a 4xx status (404 for an unknown session or script).

## v0 — the baseline

The original endpoints the contract shipped with; the cockpit rework's additions are in v1 below.

- `GET /scripts` — the catalog the selector lists.
- `GET /sessions` — the open ring, in order.
- `POST /sessions` — open a session from a script; body `{script_id}`. 201 with the session object, or 404 if no such script.
- `DELETE /sessions/{id}` — close (halt) a session. 204, or 404. Idempotent.
- `GET /sessions/{id}/attach` — the WebSocket stream; see `protocol.md`.
- `POST /sessions/{id}/voice` — propose. `multipart/form-data`: an `audio` part, plus (v1) an optional `context` part. Side-effect-free. Returns `{transcript, action}`. See [Voice upload encoding](#voice-upload-encoding).
- `POST /sessions/{id}/send` — inject the confirmed action; body `{action}`. 204.

Shapes:

```yaml
script:   { id: string, label: string }
session:  { id: string, script_id: string, label: string, cols: int, rows: int }
proposal: { transcript: string, action: Action }
Action:
  # exactly one kind
  - { kind: "text",   text: string }      # inject text + CR
  - { kind: "signal", signal: "INT" }     # inject Ctrl-C (0x03)
```

Load-bearing semantics — keep these:

- propose never injects; only send touches the PTY. The phone shows the proposal and waits for Confirm.
- send is verbatim and stateless. It injects exactly the action in its body — no reference to a server-held proposal, no re-run of STT or the action LLM. This is what lets the client edit the transcript freely before Confirm.
- action is tagged by `kind`. `text` injects the (possibly edited) command plus a carriage return; `signal: "INT"` injects `0x03`. Unknown kinds are ignored.

## Voice upload encoding

`POST /sessions/{id}/voice` is `multipart/form-data`, not a raw body — so the audio and the optional refine context ride together:

- `audio` part — the recording, `audio/ogg; codecs=opus` (Opus in Ogg). This is what the cockpit recorder emits and what the backend STT (ElevenLabs Scribe) consumes natively, so neither side transcodes. Servers must not assume `application/octet-stream`.
- `context` part (v1, optional) — `application/json` carrying the prior transcript for the Adjust/refine re-record: `{"transcript": "<prior transcript>"}`. A part rather than a query param or header, because a transcript is too long and encoding-fragile for those.

The transcription language is a backend concern, resolved from the backend's own config with an operator override (the operator mixes languages). It is not inferred from the audio and is not a wire field.

## v1 — required by the cockpit rework

The new UI (`ui/`) needs the following; the `nothing-serious` backend implements them. `exit_reason` distinguishes a child exiting on its own (`child_exited`) from backend reconcile semantics (`halted`, `host_restarted`). Each ties to a surface.

1. Session display metadata — for the ring manager (`ui/ring.md`). Add to each `session`:

```yaml
started_at:  string   # RFC3339; the manager renders uptime from this
state:       string   # "running" | "exited"
exit_code:   int       # present when state == "exited"
exit_reason: string    # present when state == "exited": "host_restarted" | "halted" | "child_exited"; the manager shows why
```

2. Exited-session retention — for the ring manager. A session whose script exits stays in `GET /sessions` with `state: "exited"` until an explicit `DELETE`. This changes the v0 baseline, where a child exit auto-dropped the session from the ring. Tradeoff: retention lets the operator see and dismiss a finished session, at the cost of holding dead session metadata; the alternative keeps auto-drop and the manager simply never shows an exited row. Decision: retain — the design shows exited rows. The backend must confirm it can.

3. Script display hint — for the preset selector. Add `command` (or `hint`) to each `script`, so the selector can show the command under the name. The v0 baseline `script` shape carries only `{id, label}`.

4. Voice refine context — for review → Adjust. `POST /sessions/{id}/voice` carries the prior transcript in an optional `context` part, so a re-record refines rather than starts cold. Encoding in [Voice upload encoding](#voice-upload-encoding).

## Out of scope — deliberately not required

- No preset authoring from the phone. The selector adds a panel from an existing preset; it does not create presets. The catalog stays admin-managed on the brain — no device-facing write or CRUD endpoint.
- No scrollback replay. The client emulator keeps its own transcript; the server repaints the current screen on attach only (`protocol.md`).
- The action LLM is not a safety gate. send injects whatever the authorized device sends — proposed or hand-edited; the device token is the only control.
