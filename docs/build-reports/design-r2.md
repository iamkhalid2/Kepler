Design lead round 2 (Opus): library composition and the shared book layer. Emulator-5554, dark Cobalt. Nothing committed.

BUILT
- Library: the shelf is now a bookcase. Lit planks every 196 dp fill the viewport at any book count. Spines stand at stable natural heights (88 to 100 percent). The first book in sort order stands face-out. The last spine leans 9 degrees on its neighbour, and a dashed ghost spine ("Add a book", 48 dp target) stands where the next book goes. Books lift toward you while pressed. Layout rules are in ShelfLayout.kt, which is pure and tested. uiautomator dump shows one node per book plus the ghost.
- Shared book layer, core/designsystem BookStage.kt and StagePlan.kt:
  - AppRoot provides LocalBookStage and gives each back-stack entry a LocalStageOwner. A SideEffect calls stage.setTop(entryKey), and BookStageOverlay draws above the nav host.
  - BookScene(stageKeys, stageTag) publishes each book's resting pose in root px. The reader publishes Modifier.bookPageSlot(bookId, pageColor, ready).
  - When the top entry changes and a book has a slot on both screens, one Flight springs it from source to target. Each channel has its own Animatable. Both screens hide their copy, and the target scene is claimed so it skips its own entrance and takes over at the same pose.
  - A page target opens the board (0 to -165 degrees, with a liner), grows the first page to the page rect, and fades when the reader reports a location. The fade waits at most 150 ms past ready, with a 1.4 s ceiling. The WebView is never transformed.
  - A retarget keeps velocity, so back mid-flight turns the same book round. Reduced motion means no flights.
- Player: the hero shows at once using the stage's last-known look. The jacket fades in over the typographic cover.
- Reader: selection colour comes from the palette, via ReaderStyle.selectionColor/selectionInk and compositeOver. Loading blocks use paper colours. System bar icons follow the page.
- The brief is updated: library composition, spine widths, and transition rules.

VERIFIED by screenrecord frame tiles: shelf to hub, hub back to shelf, back mid-flight, hub to reader (open and reveal), reader back to hub, back mid-open, and hub to player.
Tests: designsystem, library, reader, player, study, app and epub all pass. :app:assembleDebug is green.

STILL BELOW MY BAR
1. Engine wiring is missing (not my file). ReadiumReaderEngine.navigatorFragmentFactory must set readiumCssRsProperties = RsProperties(selectionBackgroundColor = style.selectionColor?.let(::Color), selectionTextColor = style.selectionInk?.let(::Color)). Until then, web selection stays Chrome blue.
2. Native selection handles use @color/kepler_pencil from XML. They are a palette value, but static and dark-mode only.
3. Predictive-back scrubbing and "hub text rises below 40 degrees" are not done.
4. The reader's first frame is the NIGHT default before settings load. The page colour follows, but the dark-to-paper switch is visible.
5. Not verified: now-bar to player (no playback was active), light mode, and 12 or 80 books on device. gfxinfo on the emulator is not representative.

DESIGN ASSUMPTIONS
- Face-out first book at yaw 20, pitch -3.
- Ghost spine: dashed text2 at alpha .6, with a plus 18 dp above its foot.
- Lean of 9 degrees, one per bookcase.
- The board swings off the left screen edge while opening.
