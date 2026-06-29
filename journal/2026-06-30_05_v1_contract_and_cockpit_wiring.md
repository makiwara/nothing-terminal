# Control-plane v1: pin the contract, wire the cockpit to it

refs: `specs/control_plane.md` (the contract moved here), `specs/protocol.md` (unchanged data plane), `specs/ui/ring.md` + `specs/ui/review.md` (the UI surfaces wired), `../nothing-serious/journal/v20/2026-06-30_28_remote_coding.md` (the backend plan that depends on this contract — its Phase 0 confirms these deltas), `android/.../net/{Models,ControlApi}.kt`, `android/.../net/CockpitModel.kt`, `android/.../shell/CockpitScreen.kt`, `control.go`, `session.go`.

Closed the gap between the frozen contract and what the client and stand-in actually implemented. The `remote_coding` backend plan in nothing-serious is built around our client leg being the contract verbatim, and its Phase 0 asks to "confirm the v1 `control_plane.md` additions are present." They were present in the doc but not in the code — the client parsed only v0 fields and the Go stand-in emitted only v0. This entry brings both legs to v1 and wires the three UI surfaces that consume the new fields, so the cockpit rework is testable end to end against `termshare`.

## Three contract clarifications (the doc is ours to move)

Per the CLAUDE.md rule, contract changes land in `specs/control_plane.md` first and the backend follows. Three additive pins, agreed with the nothing-serious side (mirrored in their `specs/remote_coding/overview.md` under "Contract dependencies"):

- Voice upload is `multipart/form-data`, not a raw body: an `audio` part (`audio/ogg; codecs=opus`) plus an optional `context` part (`application/json`, `{"transcript": "<prior>"}`). The mime matches what the vendored recorder emits and what the backend STT (ElevenLabs Scribe) consumes natively, so neither side transcodes. This also resolves the prior hole — the `context` refine field existed in the doc but had no carrier; a part is the carrier (a transcript is too long and encoding-fragile for a query param or header). Dropped `application/octet-stream`. The STT language is a backend-config concern with an operator override (the operator mixes Russian and English); it is not inferred from the audio and is not a wire field.
- Session display metadata: `state`, `started_at`, `exit_code`, and `exit_reason` (`host_restarted | halted | child_exited`). `exit_reason` was added beyond the original v1 list so the ring manager's "host restarted" affordance has a real field; the backend's Mongo row already carried it, so surfacing it on the wire is additive.
- Script `command` hint for the preset selector's subtitle line.

## Both legs to v1

Client `net/`: `Script` gains `command`; `Session` gains `state`/`startedAt`/`exitCode`/`exitReason`, all defaulted so a v0 response still parses (a row with no `state` reads as running). `ControlApi.voice` posts multipart with the audio part and, when given a prior transcript, the JSON context part. Parsing of the new fields is tolerant (`opt*`).

Go stand-in: `/scripts` emits a `command` hint (the real argv joined, or `demo: <title>` for demo scripts). Sessions carry the metadata through a lock-guarded `MarshalJSON` — necessary because `state`/`exit_*` are now mutated after the session is published into the ring, so the encoder reading the ring would otherwise race the child-exit goroutine. The biggest behavioural change is exited-retention: a child exiting no longer drops the session from the ring (the old `r.Close(id)` on EOF is gone); instead the goroutine `cmd.Wait()`s for the real exit code and flips the row to `state: "exited", exit_reason: "child_exited"`, retained until an explicit `DELETE`. The stand-in only ever produces `child_exited` — `halted` and `host_restarted` are backend reconcile semantics it has no notion of; documented as such in the doc and a code comment. `go test -race` covers the v1 shape (running + exited) and the retain-until-DELETE rule via a PTY-free unit test.

## Wiring the three UI consumers

The fields were landing in the model but no screen read them. All three are native cockpit code, not vendored.

- Ring manager (`ui/ring.md`): each row now shows a running/exited dot (`accent` vs `faint`), and a `muted` subtitle — uptime from `started_at` for running rows, the exit reason for exited ones (`host restarted`, `halted`, or `exited · code N`). A 1s tick in the manager advances the uptime without waiting for a ring refresh.
- Preset selector (`ui/ring.md`): the script's `command` renders `muted` under the `accent` label.
- Adjust / refine (`ui/review.md`): the Adjust button was a stub aliased to Cancel. It now carries the reviewed transcript into the next propose as `context`, dismisses the review, and brings the recorder back up in RECORDING. The re-entry is driven through the vendored `RecordingController`'s public gesture API (`arm()` → `updateDrag(0f)` to mount → `updateDrag(0f)` to lock), landing in hands-free Locked with Send/Cancel. This deliberately does not touch the vendored file — the controller stays a faithful, re-syncable copy; the refine behaviour lives in our `CockpitModel.adjust` and the native surface. Context is consumed once by the next capture and cleared on confirm/cancel; a cancelled re-record can leave it set for one extra capture, a benign edge (the stand-in ignores context; the backend treats it as a hint).

## State

Contract-ready for the backend: the deltas its Phase 0 checks for are present in the doc and proven in the stand-in. Cockpit-ready against `termshare`: connect, render, propose → review → confirm/send, the ring manager, the selector, and the Adjust refine loop all function. Verified by `compileDebugKotlin`, `lintDebug`, and `go test -race ./...`; the live UI (gesture feel, the auto-lock re-entry on a real device, exited rows behind a real PTY) still wants device verification — the sandbox cannot allocate a PTY, so exited rows can only be exercised behind a real shell, not `-demo`. The remaining track is the real `terminals/`/`remote_coding` backend in nothing-serious, which is their work.
