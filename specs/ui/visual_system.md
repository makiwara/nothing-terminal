# Visual system

The cockpit borrows nothing-to-say's design system unchanged — one theme (Pitch Cyan), one font (PT Mono) — so it reads as the same app. The only deliberate departure is orientation. Token values are mirrored from nothing-to-say's `ui/theme/Color.kt`; the mockups encode them in `specs/mockups/styles.css`.

## Orientation and frame

Landscape-locked. The app never raises a soft keyboard except for transcript editing (see [review.md](review.md)), so the rotation/resize churn that plagues terminal apps does not occur; the only size source is the page's own viewport. The mockup reference frame is 427×240 dp — the target device's portrait dp grid rotated. The real device is the size of record.

## Color tokens

Pitch Cyan. The first eight are nothing-to-say's canonical tokens; the last two are derived for the recorder's hands-free areas.

- `bg` #000000 — surface background, and the foreground on inverted surfaces.
- `ink` #D0E8EB — primary text (terminal cells, transcript body).
- `muted` #5A7376 — secondary text (titles, metadata, labels).
- `faint` #2D3D40 — tertiary (timestamps, inactive dots).
- `edge` #1A2A2C — device/frame border.
- `rule` #0E1A1C — hairline dividers.
- `accent` #5DD5E0 — the cyan: prompts, primary actions, inverted fills.
- `accent-soft` #0A2426 — filled-block background (transcript, calm surfaces).
- `danger` #B68CFF — destructive/Cancel (provisional; see index Open decisions).
- `accent-panel` #2F6A70 — recorder hands-free area when held (= `accent` at ~0.5 over `bg`).
- `accent-lock` #194044 — recorder hands-free area when locked (= `accent-soft` at ~0.6 over the panel).

## Typography

PT Mono, line-height 1.55. Sizes: body 14, header/hint 13, metadata/timestamp 12, inline button/menu 15. PT Mono ships one weight; bold/italic are synthesized — do not rely on weight to carry meaning.

## The inversion rule

The inverted treatment is `accent` fill with `bg` (black) content — dot, text, waveform, button borders. It is a deliberate signal, not decoration: it marks a surface as live or primary. Apply it only to

- the live voice-capture surface (held / locked / options), and
- the ring-management headers ("Open terminals", "Add terminal").

Do not invert passive surfaces. The after-send transcribing block is intentionally non-inverted — dark surface, accent content — because nothing is being captured; the phone is waiting on the brain (see [review.md](review.md)).

## Shared components

- Terminal cell grid. Termux `TerminalEmulator` + `TerminalRenderer` in a custom `View`; `ink` on `bg`, `accent` for the prompt glyph. Owns the whole page below the title.
- Small title. `muted`, 12sp, top-left; a leading `accent` dot marks a running session. The only chrome on a terminal page.
- Inverted header. Full-bleed `accent` bar, `bg` uppercase text, 12sp, flush to the panel's top edge. Used by the manager and selector.
- Button families:
  - Capture buttons (recorder Cancel/Send): `bg` text and border on the `accent` strip.
  - Review buttons: outlined, on the dark review panel — Adjust `accent`, Cancel `danger`, Confirm filled `accent` (`bg` text).
- Transcript block. Filled `accent-soft`, `ink` text, no border; the fill is the "tap to edit" affordance. Raises the system keyboard.
- System keyboard. The Android system IME — the app focuses the transcript field; the OS raises and dismisses it. The app never draws keys.
- Speak-handle. A short `accent` bar (50% opacity) at the bottom center of a terminal page — the shared swipe-up-to-record affordance, identical to the chat app's recording handle.
