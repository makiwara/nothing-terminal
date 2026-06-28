# Voice input — the recorder

Input is voice-only. The recorder is nothing-to-say's `RecordingController` and its slide-up surface, reused unchanged; the cockpit only redirects the captured audio to the brain's propose call and diverges in what happens after send (see [review.md](review.md)). This doc specifies the capture surface as the source renders it (`../../../nothing-to-say/app/src/main/java/com/humanemagica/nothing/chat/RecordingSurface.kt`), so the cockpit matches the chat app rather than a simplified mock.

Mockup: voice_review cards 1–4 (rest, held, locked, options) and the animated arm→mount and lock cards.

## Geometry — two blocks meet

The surface is not one panel sweeping the full height. It is a top-pinned block over a hands-free area, with the controls entering from opposite ends so the active gesture windows are short.

- Top block. Status row (dot + elapsed timer) over the waveform, pinned to the top, height = `mountAt` of the canvas (0.25 compact / 0.33 large; the mockup uses ~84px in the 240dp frame). It fades in as the pull approaches; it does not slide.
- Hands-free area. Everything below the block. `accent-panel` when held; darkens to `accent-lock` when locked.
- Inverted palette. The block and button strips are `accent` fill with `bg` (black) content — the live-capture signal (see visual_system.md).

## Gesture thresholds

The drag fraction `top` runs 0 (canvas top) to 1 (bottom). From `RecordingController`:

- `REVEAL_START` 0.50 — the block starts fading in as the pull passes here.
- `mountAt` 0.25 / 0.33 — the block is fully shown, flashes, and capture starts.
- `OPTIONS_AT` 0.75 — pulled back down this far → hands-free Options.
- The panel leads the finger by `recordingLead` ≈ 96–120px, shortening travel.

So the meaningful windows are each about a quarter of the canvas (reveal 0.50→0.25, lock 0.25→0, options 0.50→0.75), not a full-screen drag.

## States

- Rest. Mockup card 1. A terminal page with the speak-handle lip; not recording.
- Arm. Pull up past slop; the block begins to reveal. Capture has not started.
- Held (mount). At `mountAt` the block fades fully in, the panel has risen to meet it, an ink flash fires, and capture starts. Releasing here sends.
- Locked. Mockup card 3. Pulled to the top; the hands-free area darkens to `accent-lock` and Cancel/Send rise from below the block. Hands-free.
- Options. Mockup card 4. Pulled back down (0.50→0.75); Cancel/Send drop in from the top over the block. Same two actions, opposite origin.

Cancel discards and returns to TERMINAL. Send (release-in-held, or the button in locked/options) uploads the audio to propose and enters REVIEW.

## Animation

- Waveform. Live wiggle while capturing — a continuous bar animation (~900ms cycle, per-bar phase offset). Static during the arming preview.
- Dot. Static. The status dot does not pulse (the source's recording.html mock pulses it; the source does not — match the source).
- Mount / lock flash. A brief `ink` wash at ~0.6 alpha (~260ms) when capture starts and when locking.

## After send

Send is side-effect-free: it uploads audio to `POST /sessions/{id}/voice` (propose) and the surface transitions to REVIEW. Nothing reaches the PTY here. The recorder's own Cancel/Send are the capture controls; the inject decision belongs to REVIEW's Confirm.
