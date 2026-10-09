Design lead sign-off, round 2 (Opus). Not committed.

Checked on the emulator:
- Library with 2, 12 and 26 books. More books came from a temporary seed in the view model, now removed.
- The flights from shelf to hub, hub to reader, reader to hub, and hub to player, using screen recordings checked frame by frame.
- Narration setup in four paused states: paid OK, book cap, monthly cap and offline. A temporary hook forced each state, and it is now removed.
- The Settings notice, both "switching soon" and "ended, not yet confirmed".
- Reader selection colour and the player cover.

APPROVED: Library bookcase, the shared book flights, Hub, the Settings notice, the player cover load, and the paid confirmation flow.

CHANGES (presentation only):
1. Shelf lean: the last spine never leaned on a full plank, because the rule also needed room for the ghost. It now leans whenever it fits, unless leaning is what pushes the ghost off the plank. New unit test added.
2. Paused panel: the reason now hangs beside a warning dot, in the player's status-line style, with 16 dp before the action keys.
3. Gutter alignment:
   - Hub "Set up" and "Remove from library" now sit on the 24 dp gutter.
   - "Open Settings" and "Change the limit" were 4 dp in; they now line up with the text.
4. The Settings "Limit for one book" description was cut off mid-sentence. It is now "Paid narration pauses at this amount".

Tests: `:feature:library` 56/56 and `:core:designsystem` 11/11 pass. `:app:installDebug` builds and installs (it includes assembleDebug).

REMAINING DESIGN DEBT (ranked):
1. Reader selection is still Chrome blue, because `RsProperties` is not set in `ReadiumReaderEngine` (engine/epub, not my area).
2. The player shows a missing-key error with "Try again"; that should open Settings.
3. `KpTextLink` has a 4 dp inner pad that every leading link has to compensate for.
4. After the reader opens, the page stays blank for about 0.5 s before text draws (emulator).
5. Not checked: light mode, the now-bar to player transition, and predictive back.
