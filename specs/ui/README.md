# Terminal cockpit — phone UI spec

The phone-side UI for the voice-driven terminal cockpit: a landscape, voice-only surface that attaches to `specs/protocol.md` sessions, renders rich TUIs, and drives input by voice with a confirm step. This is the design contract for building the Compose UI; the rendered mockups in `specs/mockups/` are the visual source of truth, and each surface below cites the card it comes from.

Status: designed (mockups + this spec). Authoritative for the phone UI. Where it conflicts with the sibling design note `../../../nothing-to-say/journal/002/2026-06-24_36_terminal_cockpit.md` (read-only context), this spec wins — see Deltas below. The in-tree `android/` app now implements this design — the ring with a manager page and preset selector (no ☰), the inverted treatment, and the voice flow (vendored recorder → propose → editable transcribe/review → confirm). Still pending: device verification of capture and gesture feel, the full position-interpolated surface choreography (the current one is state-snapped), and the v1 control-plane metadata the manager will show (uptime, exited state).

## The parts

- [visual_system.md](visual_system.md) — orientation, color tokens, typography, the inversion rule, shared components.
- [ring.md](ring.md) — the ring and its structural surfaces: terminal page, ring manager, preset selector, empty ring.
- [voice_input.md](voice_input.md) — the slide-up recorder: two-block geometry, gesture thresholds, capture states, animations.
- [review.md](review.md) — after send: transcribe, the Cancel/Adjust/Confirm review, keyboard editing, and inject semantics.

## Screen inventory

Each surface maps to a mockup card (`specs/mockups/<file>` card N):

- Terminal page (idle) — terminal_cockpit card 1.
- Terminal page (alt-screen TUI) — terminal_cockpit card 2.
- Ring manager — terminal_cockpit card 3.
- Preset selector — terminal_cockpit card 4.
- Empty ring — terminal_cockpit card 5.
- Default rest (speak-handle) — voice_review card 1.
- Recording held / locked / options — voice_review cards 2 / 3 / 4.
- Transcribing — voice_review card 5.
- Review (Cancel/Adjust/Confirm) — voice_review card 6.
- Precise edit (system keyboard) — voice_review card 7.
- Confirmed (injected) — voice_review card 8.

## Gesture and state model

The cockpit has one default surface (a ring of pages) and three transient flows reached from it (recording, review, the selector). There is no keyboard except the system IME raised for precise edits; there is no per-terminal menu — management lives on the ring's manager page.

```
                 ┌──────────────────────────── RING ────────────────────────────┐
                 │  HorizontalPager:  [ manager ] [ term A ] [ term B ] …          │
                 └───────────────────────────────────────────────────────────────┘
   on a TERMINAL page:                     on the MANAGER page:
     swipe ◀▶ = move ring                    tap Halt   → DELETE /sessions/{id}
     drag  ▲▼ = scrollback                    tap Add    → SELECTOR
     swipe ▲  = RECORDING

   RECORDING:  arm ─▶ held ─▶ locked / options
                            │  Send │ Cancel
                            ▼       ▼
                         REVIEW   (TERMINAL)
   REVIEW:   transcribing ─▶ review { Cancel | Adjust | Confirm }
               tap transcript ─▶ system-keyboard edit ─▶ review
               Adjust  ─▶ RECORDING (prior transcript as refine context)
               Confirm ─▶ inject (text+Enter / signal) ─▶ TERMINAL
               Cancel  ─▶ TERMINAL

   SELECTOR:  pick preset → POST /sessions → new TERMINAL page;  Cancel → MANAGER
```

States in code terms (registered in the standalone app's own gesture doc until merge, then folded into nothing-to-say's `gestures.md` beside `CHAT_LINE`): `TERMINAL`, `RECORDING` (arm/held/locked/options), `REVIEW` (transcribing/review/edit), `SELECTOR`. The manager is a ring page, not a modal state.

## Protocol binding (summary)

Each surface is backed by the wire contract — the per-session stream in `../protocol.md`, the control-plane REST in `../control_plane.md`. Per-surface detail lives in the area docs.

- Terminal page render + input: WS `GET /sessions/{id}/attach` (`../protocol.md`).
- Ring contents: `GET /sessions`.
- Manager: Halt = `DELETE /sessions/{id}`; Add = `GET /scripts` then SELECTOR.
- Selector: pick = `POST /sessions {script_id}`.
- Voice: Send = `POST /sessions/{id}/voice` (propose, side-effect-free) → `{transcript, action}`; Confirm = `POST /sessions/{id}/send {action}` (inject).

## Deltas from the sibling design note

This spec keeps that note's core (real VT emulator, landscape, server-owned ring, voice review loopback) and changes four things, all reflected in the mockups:

- No per-terminal menu. The top-right ☰ is gone; terminals carry only a small title. Close/open moves to the ring manager page (Halt each, Add new).
- Two-stage voice. The recorder's own Send submits audio; the returned proposal opens a separate review with Cancel/Adjust/Confirm. (The note framed Send and review as one step.)
- Editable transcript. Tapping the proposed command raises the system keyboard for precise edits before Confirm.
- Inverted treatment. The live capture surface and the ring-management headers use the inverted palette (accent fill, black content); passive surfaces do not.

## Open decisions

Recorded here so the spec stays honest; resolve and update the area docs.

- Cancel color. The mockups use nothing-to-say's `danger` token `#B68CFF` (a violet). Provisional — confirm the violet or substitute a literal magenta.
- Review-panel background. Currently pure black (the recorder's `accent-soft` tint was dropped). Provisional — keep black or give the review panel its own tint.
- App vs module. Whether the cockpit ships as its own app consuming a shared voice module, or merges into nothing-to-say, is an open architectural question (see the journal). It does not affect this UI spec.
