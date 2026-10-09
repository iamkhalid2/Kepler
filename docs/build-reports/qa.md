QA RUN ON DEVICE (emulator-5554, Android 16). Nothing committed.

WORKS END TO END (from screenshots and logs)
- Cold start 0.7-1.1 s. Onboarding with the debug keys: both verified.
- Import through the system picker (Meditations) and through Open with (Pride and Prejudice). Both land on the hub. A broken file shows only the neutral "can't be opened" toast. Deep links work.
- Shelf, hub, Home and Settings work.
- Reader: page turns, contents, search (jumps to the hit), selection strip, highlight, sepia theme and size.
- System TTS: plays (MediaSession PLAYING), read-along band follows, speed 1.5x applies, the 5-minute sleep timer paused it on time, notification pause and play work.
- Fish (s2.1-pro-free, about 5 requests): wrote 3 AAC segments with sentence sync rows (19.78 s file, 3 spans), and the playhead and band tracked them.
- Learn with the real OpenRouter key:
  - Ask returned an answer with 3 citations, and the budget is shown.
  - Recap, Concepts and Map all worked.
  - Card from selection: suggestions, Check my answer and Save all worked.
  - Review: grade Good, then the Done screen.
  - Explain and Define worked.
- Offline: Ask says "Can't reach OpenRouter"; audio already made still plays.
- No copyright or DRM wording anywhere.

FIXES OUTSIDE :app
1. Reader lost its place on rotation or font size change (it jumped to the start of the chapter); it now holds and restores the position (engine/epub).
2. Process death crashed the app on restore, in Readium's dummy navigator; stale fragment state is now dropped (engine/epub, MainActivity).
3. Two modules defined `learn_caps`/`requests_left`, so the hub showed "Learn · %1$s"; the library's copies are renamed.
4. Narration covered excluded front and back matter, and coverage counted it. Both now use included sections only (engine/tts, core/database).
5. Fish voices were not filtered by the book's language (Japanese voices were listed first).
6. Pause reasons showed raw codes ("system_stop"), and errors read like "Network error: UnknownHostException". Both now use plain copy.
7. In Read + Listen the transport covered the bottom of the page.
8. Ask showed raw ordinals ("[272] [3358]"); markers are now numbered 1..n to match the citation list.
9. Explain and Define:
   - They failed when the selection was past the frontier; the selected sentence is now allowed.
   - Passage tags are stripped from their answers.
10. The hub said "whole book" for Concepts and Map, which are fenced; they now show the fence.
11. Map labels overlapped and ran off the edges.
12. Concepts had no gutter, the Review "Close" link was clipped, and the spine mark said "BB".

REMAINING BUGS (ranked)
1. Frontier advances slowly: dwell probation after a jump means Recap and Concepts stay "Read a little first" until a few pages have been read slowly.
2. Fish via WorkManager is stopped when the network drops and is not resumed. It stays "Paused" until Bind is pressed again.
3. Library spines are not drawn on a 3D shelf; they are flat and tiny at the left.
4. "Daily review" and the hub card count lag one refresh.
5. I removed the Fish key in Settings during testing, so re-enter it on the emulator.

DESIGN ASSUMPTIONS
- "Not ready" caption in the read-along transport on a playback error.
- Plain pause copy for the narration setup (cap, offline, system).
