Design lead device pass (Opus), emulator-5554, dark Cobalt, plus light mode and Vermilion spot checks. Nothing committed. Build green: :app:assembleDebug passes, and unit tests pass in designsystem, library, study, player and reader.

Changed
- BookScene: per-channel springs from the brief (move, turn, settle, lift) replace the softer 170/130 springs, and retargets keep velocity. New enterFrom poses: lift first, yaw at +60 ms, position at +90 ms.
- Hub: the book enters edge-on (yaw 90) and turns to 24, landing with a small overshoot. Player: the book turns from the hub pose to face you (yaw 0, x1.14). Both wait for the jacket to decode, so the typographic cover no longer flashes and swaps.
- Shelf: spines were 9 dp and unreadable. They are now 16/20/25% of cover width with larger spine type, and a jacketed book's spine and boards take the jacket's colour.
- Italics were rendering upright everywhere: the synthesised Literata italic was registered as a real italic. Fixed, so author lines and quotes are italic now.
- Reader: the bookmark ribbon now hangs in the margin, not over the text. Selection handles and cursors use pencil instead of the default teal.
- Learn: the request budget moved under the caps title, so the book title is no longer cut off.
- Review: the card is a 3:4 index card, the front prompt is 21 sp, and the rules sit under the text lines. "0 freezes left" is hidden when there are none left.
- Settings: field labels are sentence case under the group caps. In light mode the key edge is darker, so secondary keys are visible.

Still below the bar, worst first
1. No cover-to-reader page-open or shared BookStage. Navigation between screens is a crossfade.
2. WebView selection fill is still Chrome blue. It needs RsProperties in engine/epub.
3. Player shows an empty hero for about 0.5 s while playback info loads.
4. Notebook spines do not match the shelf colours.
5. Brief section 6 still says 6/9/12%.

Not verified: onboarding (needs a data reset), the now-bar, Read + Listen, haptics.
