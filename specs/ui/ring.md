# Ring and structural surfaces

The ring is a `HorizontalPager` over the brain's open sessions plus one dedicated manager page. It is server-owned and persistent (tmux-like): the phone reflects `GET /sessions`, it does not keep its own list. Swiping horizontally moves between pages; the manager is the first page, the terminals follow.

## Terminal page

Mockup: terminal_cockpit cards 1 (idle) and 2 (alt-screen TUI).

A terminal page is the cell grid and nothing else but a small title. One renderer — a real VT emulator — owns a fixed grid and handles plain output and alt-screen TUIs identically, fed raw bytes over the page's WebSocket.

- Title. Top-left, `muted`, 12sp, with a leading `accent` dot when the session is running. No menu, no toolbar, no page indicator.
- Vertical scrollback. A vertical drag moves the emulator's `topRow` into its own transcript — not a list. Pin-to-bottom is `topRow` at 0; a scroll up holds position until released back to bottom.
- Record. A swipe up from the bottom enters RECORDING (see [voice_input.md](voice_input.md)). The faint speak-handle marks it.
- Render + input. WS `GET /sessions/{id}/attach`, raw bytes both ways, per `../protocol.md`. The visible page streams actively; off-screen pages may stay attached for instant swipe or lazily attach on settle (a tuning choice, deferred to implementation).

## Ring manager

Mockup: terminal_cockpit card 3.

A dedicated ring page that lists the open terminals and is the only place to close or open them.

- Header. Inverted bar, "OPEN TERMINALS".
- Rows. One per open session: a live dot (`accent` running, `faint` exited), the session name, its age, and a Halt control (`muted` outline).
- Halt. `DELETE /sessions/{id}`. The WS closes, the page drops from the ring; if it was active, the swipe lands on a neighbour, or the empty-ring state if it was the last.
- Add new terminal. Opens the preset selector.
- Ring dots. A position indicator at the bottom (the manager page's own affordance, not shown on terminal pages).

## Preset selector

Mockup: terminal_cockpit card 4.

A separate full screen reached from the manager's Add. It lists the brain's preset scripts; picking one adds a terminal panel to the ring. It does not author presets — the script catalog is server-owned (admin CRUD on the brain).

- Header. Inverted bar, "Add terminal", with a Cancel (dismiss) on the right.
- Rows. One per script from `GET /scripts`: the script name (`accent`) over its command (`muted`).
- Pick. `POST /sessions {script_id}` → a new page appears in the ring and renders the session. Cancel returns to the manager.

## Empty ring

Mockup: terminal_cockpit card 5.

When no sessions are open, the only ring page is the manager, showing "No open sessions" and Add new terminal. Open one to begin.

## Protocol binding

- Ring contents: `GET /sessions`.
- Page stream: WS `GET /sessions/{id}/attach` (`../protocol.md`).
- Halt: `DELETE /sessions/{id}`.
- Catalog: `GET /scripts`.
- Open: `POST /sessions {script_id}`.

All control-plane calls are Bearer-token'd over WSS to the home host; the exact REST shapes — including the `started_at`/`state` session metadata and exited-session retention this manager needs — are specified in `../control_plane.md` (v1 additions, pending in the stand-in and the backend).
