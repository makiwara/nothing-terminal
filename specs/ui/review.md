# After-send review

This is the cockpit's deliberate divergence from the chat app. In nothing-to-say the recorder dismisses on send and the transcript becomes an outgoing message. Here the stakes are higher — a misheard command is not a typo — so nothing reaches the PTY until the operator confirms what will run. On send the surface does not dismiss; it transcribes, then becomes a review panel.

Mockup: voice_review cards 5 (transcribing), 6 (review), 7 (precise edit), 8 (confirmed), and the animated send→review card.

## Transcribing

Mockup card 5.

While `POST /sessions/{id}/voice` (propose) is in flight, the surface stays and shows progress. It is intentionally quiet: a non-inverted block — dark `accent-soft` surface, `accent` dot and timer, `muted` "transcribing…", a faint static waveform — with only the block content gently breathing (a low-amplitude opacity pulse, not a whole-surface flash). Nothing is being captured, so it carries far less weight than the live capture block.

Propose is side-effect-free and returns `{transcript, action}`. On error or empty result the panel shows it; nothing is sent.

## Review

Mockup card 6.

The returned proposal fills the panel:

- Transcript. The proposed command as a filled `accent-soft` block (no border; the fill is the affordance). The panel background is otherwise plain (provisional — see index Open decisions).
- Buttons. Three, on the dark panel: Cancel (`danger` outline), Adjust (`accent` outline), Confirm (filled `accent`).

Actions:

- Cancel. Discard; return to TERMINAL. The PTY is untouched (propose was side-effect-free).
- Adjust. Re-record — slides the recorder back up (RECORDING), passing the prior transcript as refine context.
- Confirm. Inject. Only Confirm reaches the PTY.

## Precise edit

Mockup card 7.

Tapping the transcript focuses the field and raises the system keyboard (the OS IME — the app draws no keys). While editing, the Cancel/Adjust/Confirm buttons are hidden; they return when the keyboard is dismissed. The edited text is what Confirm injects.

## Confirm — inject

Mockup card 8.

Confirm calls `POST /sessions/{id}/send {action}`, which injects into the PTY:

- A text action injects the (possibly edited) command followed by Enter — `text + \n`.
- A signal action injects a control byte instead — e.g. a "stop"/"cancel" utterance proposes Ctrl-C (`0x03`). The action carries which kind it is; the phone does not decide.

The injected bytes echo back over the WS and render on the page; the surface dismisses and returns to TERMINAL.

## Protocol binding

- Propose (on Send): `POST /sessions/{id}/voice` (audio) → `{transcript, action}`. Side-effect-free.
- Confirm: `POST /sessions/{id}/send {action}` → inject. The only call that touches the PTY.

The phone never runs STT or the action LLM and never parses the terminal stream; it proposes and confirms, the brain transcribes, cleans up, and injects.
