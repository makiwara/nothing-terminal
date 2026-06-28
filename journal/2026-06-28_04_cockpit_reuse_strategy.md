# Cockpit UX reuse strategy — copy tokens, vendor the recorder, build the ring native

refs: `specs/ui/README.md` (the UI specs this builds), `specs/control_plane.md` (the REST contract), `journal/2026-06-28_03_cockpit_ui_specs.md` (the design + specs), `../nothing-to-say/app/src/main/java/com/humanemagica/nothing/voice/` and `.../chat/RecordingSurface.kt` (the recorder being vendored, read-only, pinned at `nothing-to-say@8488823`), `android/` (where the vendored code lands).

Decided how to build the new cockpit UX by reusing nothing-to-say: a hybrid keyed to each component's reuse profile, not one mechanism for all. This is the cheap, reversible move; it deliberately defers the expensive, committing ones (a shared library, or merging into nothing-to-say) until the cockpit is built and proven and the still-open app-vs-merge question is decided. Reuse mechanism is downstream of that architecture decision, so we take on a little drift debt now rather than build infra (or churn the live sibling) for an app that does not exist yet.

## Decision, by component

- Design tokens (Pitch Cyan, PT Mono, dims). Copy into the standalone app's disposable `ui/theme/` (the mockups already encode the values). Too small to justify touching the sibling. Consolidate into a shared design module later, if and when the shared-library step happens.
- Recorder. Copy-vendor the capture engine (state machine + Opus encoder) into a new portable `voice/` package — a faithful, re-syncable copy, pinned to `nothing-to-say@8488823`, marked do-not-diverge. Build the slide-up surface native against that engine's API (per `specs/ui/`), not by vendoring nothing-to-say's Compose surface — see the vendor-surface section for why. All cockpit-specific behaviour — the surface, the propose/send sink, the after-send review — stays in native code, outside the vendored files.
- Ring, review, terminal. Build native here. The ring is a thin HorizontalPager pattern (pattern reuse, no shared code); the after-send review is cockpit-specific (our divergence from chat); the terminal is already Termux, not from nothing-to-say.
- Shared library (C) and merge (D). Deferred. Revisit once the cockpit is proven and the architecture decision is made; extraction is far cheaper once the real shared surface is known.

## Recorder vendor surface

Vendor the capture core only, repackaged to `com.humanemagica.nothing.terminal.voice` under `android/app/src/main/java/com/humanemagica/nothing/terminal/voice/`, parallel to the portable `net/` and `term/`. On merge or a future shared-library step this package is the unit that gets swapped or extracted.

Vendor (the engine — done):
- `RecordingController.kt` — the Idle/Arming/Held/Locked/Options state machine; its sink is already routing-agnostic (`onSend: suspend (path, durationSec) -> Unit`).
- `OpusRecorder.kt` + `OggOpusWriter.kt` — audio capture and Opus/Ogg encoding (Concentus; API 28, no NDK).
- `RecordingState.kt` — the shared active flag.

Shim, do not vendor:
- `core.NtsLog`, `core.Storage` — small cockpit adapters (logcat-only log; recording dir under `cacheDir`), rather than dragging in nothing-to-say's `core` package.

Build native, do not vendor:
- The Compose surface (`chat/RecordingSurface.kt`) and its pull gesture. Vendoring it looked right on paper, but on inspection it is heavily coupled to nothing-to-say's full `NtsDims`, `MaterialTheme` typography, and chat-layout assumptions (the inset is the chat-name header; `RecordPullArea` needs `atBottom`/`onFastScrollToBottom`; `PhotoButton`/`CameraGlyph`). A "faithful" copy would drag the whole theme and still need a heavy fork for landscape and the cockpit review flow — which defeats the re-syncable point of vendoring. So the vendor boundary stays at the engine (re-syncable), and the surface is built native against the controller's API (`state`/`top`/`elapsedSec`/`exit` flows; `arm`/`updateDrag`/`release`/`tapSend`/`tapCancel`; the `setHandleTop`/`setScreenHeight`/`mountAt` plumbing), to our `specs/ui/` design and token values.

Leave behind (chat-side, not needed):
- `VoiceRouter`, `VoiceSequencer`, `VoicePlayback`, `IncomingTranscriber`, `TranscriptStore`, `OutgoingStore` — routing, playback, on-device STT, and persistence. The cockpit uploads audio to the brain's propose call; it plays back nothing, transcribes nothing locally, and stores nothing.

Divergence boundary: the cockpit's differences live entirely in code we own — the native surface, the sink (which uploads to `POST …/voice` instead of a chat send), and the after-send transcribe/review flow (`specs/ui/review.md`). The vendored engine stays close to upstream so recorder fixes re-sync rather than fork.

## Why copy-vendor, not reimplement or share now

- Versus reimplementing to the same patterns (pattern reuse): the recorder is a proven, subtle gesture + capture state machine with edge cases already solved; re-deriving it is the most regression-prone path. Copying is both safer and cheaper.
- Versus a shared library (C) now: extracting a clean `voice` module out of nothing-to-say means refactoring and decoupling it from chat concerns inside a live, uncommitted sibling — invasive, needs build infra, couples release cycles, and is premature before the cockpit exists.
- Versus merging (D): reverses the earlier separate-app lean (separate sittings, landscape vs portrait, ring incompatibility) and churns the sibling most of all.
- Accepted cost: duplication drift — upstream recorder fixes do not propagate automatically. Mitigated by pinning the provenance commit and keeping the copy faithful (divergence confined to our sink and review), so a re-sync is mechanical. This debt is only worth carrying because it keeps the door open to consolidate (C) or merge (D) later without having pre-committed to either.

## Status and next

The engine is vendored: `voice/` (the four files above) repackaged to `…terminal.voice` with provenance headers, plus the `core/` shims, the `RECORD_AUDIO` permission, and the Concentus dependency (`io.github.jaredmdobson:concentus:1.0.2`, Maven Central). `:app:compileDebugKotlin` and `:app:lintDebug` are green. Capture itself (mic, audio focus, OGG/Opus output) is unverified — it needs a real device.

The voice flow is now wired on top of the engine: tokens copied (`ui/theme/Palette.kt`), `CockpitModel` drives the controller (`onSend` → propose, then a transcribe flag and `confirm` → send), a native landscape surface (`shell/RecorderLayer.kt`) renders the inverted two-block capture, and `CockpitScreen` hosts it plus the transcribe/review overlay (Cancel/Adjust/Confirm, editable transcript). compile + lint green; the surface choreography is a first cut and gesture feel + capture remain device-unverified. No sibling was touched; the recorder is read-only context.

Next: the ring rework — replace the ☰ menu with the manager page, add the preset selector and the inverted headers, per `specs/ui/ring.md`.
