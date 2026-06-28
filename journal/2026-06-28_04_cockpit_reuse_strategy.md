# Cockpit UX reuse strategy — copy tokens, vendor the recorder, build the ring native

refs: `specs/ui/README.md` (the UI specs this builds), `specs/control_plane.md` (the REST contract), `journal/2026-06-28_03_cockpit_ui_specs.md` (the design + specs), `../nothing-to-say/app/src/main/java/com/humanemagica/nothing/voice/` and `.../chat/RecordingSurface.kt` (the recorder being vendored, read-only, pinned at `nothing-to-say@8488823`), `android/` (where the vendored code lands).

Decided how to build the new cockpit UX by reusing nothing-to-say: a hybrid keyed to each component's reuse profile, not one mechanism for all. This is the cheap, reversible move; it deliberately defers the expensive, committing ones (a shared library, or merging into nothing-to-say) until the cockpit is built and proven and the still-open app-vs-merge question is decided. Reuse mechanism is downstream of that architecture decision, so we take on a little drift debt now rather than build infra (or churn the live sibling) for an app that does not exist yet.

## Decision, by component

- Design tokens (Pitch Cyan, PT Mono, dims). Copy into the standalone app's disposable `ui/theme/` (the mockups already encode the values). Too small to justify touching the sibling. Consolidate into a shared design module later, if and when the shared-library step happens.
- Recorder (capture state machine + Opus encoder + slide-up surface). Copy-vendor the capture core into a new portable `voice/` package — a faithful, re-syncable copy, pinned to `nothing-to-say@8488823`, marked do-not-diverge. All cockpit-specific behaviour stays in native cockpit code, outside the vendored files.
- Ring, review, terminal. Build native here. The ring is a thin HorizontalPager pattern (pattern reuse, no shared code); the after-send review is cockpit-specific (our divergence from chat); the terminal is already Termux, not from nothing-to-say.
- Shared library (C) and merge (D). Deferred. Revisit once the cockpit is proven and the architecture decision is made; extraction is far cheaper once the real shared surface is known.

## Recorder vendor surface

Vendor the capture core only, repackaged to `com.humanemagica.nothing.terminal.voice` under `android/app/src/main/java/com/humanemagica/nothing/terminal/voice/`, parallel to the portable `net/` and `term/`. On merge or a future shared-library step this package is the unit that gets swapped or extracted.

Copy:
- `RecordingController.kt` — the Idle/Arming/Held/Locked/Options state machine; its sink is already routing-agnostic (`onSend: suspend (path, durationSec) -> Unit`).
- `OpusRecorder.kt` + `OggOpusWriter.kt` — audio capture and Opus/Ogg encoding.
- `RecordingState.kt` — the shared active flag.
- The pull gesture and the Compose surface (`chat/RecordingSurface.kt`), stripped of the chat-only `PhotoButton`/`CameraGlyph`.

Shim, do not vendor:
- `core.NtsLog`, `core.Storage` — adapt to the cockpit's own logging and recording-dir helpers (small adapters), rather than dragging in nothing-to-say's `core` package.

Leave behind (chat-side, not needed):
- `VoiceRouter`, `VoiceSequencer`, `VoicePlayback`, `IncomingTranscriber`, `TranscriptStore`, `OutgoingStore` — routing, playback, on-device STT, and persistence. The cockpit uploads audio to the brain's propose call; it plays back nothing, transcribes nothing locally, and stores nothing.

Divergence boundary: the cockpit's differences live entirely in code we own — the sink (which uploads to `POST …/voice` instead of a chat send) and the after-send transcribe/review flow (`specs/ui/review.md`). The vendored files stay close to upstream so recorder fixes re-sync rather than fork.

## Why copy-vendor, not reimplement or share now

- Versus reimplementing to the same patterns (pattern reuse): the recorder is a proven, subtle gesture + capture state machine with edge cases already solved; re-deriving it is the most regression-prone path. Copying is both safer and cheaper.
- Versus a shared library (C) now: extracting a clean `voice` module out of nothing-to-say means refactoring and decoupling it from chat concerns inside a live, uncommitted sibling — invasive, needs build infra, couples release cycles, and is premature before the cockpit exists.
- Versus merging (D): reverses the earlier separate-app lean (separate sittings, landscape vs portrait, ring incompatibility) and churns the sibling most of all.
- Accepted cost: duplication drift — upstream recorder fixes do not propagate automatically. Mitigated by pinning the provenance commit and keeping the copy faithful (divergence confined to our sink and review), so a re-sync is mechanical. This debt is only worth carrying because it keeps the door open to consolidate (C) or merge (D) later without having pre-committed to either.

## Status and next

Decision recorded. The actual vendoring (copying the files, repackaging, stripping the chat bits, wiring the shims, and pointing the sink at propose/send) is the next step, not done here. No sibling is touched by this entry; the recorder is read-only context.
