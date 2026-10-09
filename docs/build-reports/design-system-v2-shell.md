DESIGN SYSTEM V2 + APP SHELL (design lead)

Inspiration: re-read the v1 boards, the production player, the saved Dribbble shots, plus new Dribbble searches (audiobook mini player, reading app highlights, audiobook library). Adopted: MidnightReads (3D book breaking out of a dashboard card's top edge, highlight cards) and Libby/Symfonium's chapter-scoped mini player progress.

BRIEF: docs/design/DESIGN-BRIEF.md v2 (about 2190 words, binding). Must know: Albert Sans for chrome, Literata for book text, Fraunces only for hero titles. Elevation is by tone: bg #0A0A0C, surface1 card #16161A, surface2 raised, surface3 sheet. Card r20, pill buttons, no accent-filled buttons (key = primary/play). 3D objects only on the Home continue card, Hub hero, Listen face, bookcase view, review index card. Tab switch is a 180 ms fade-through. Shared cover key cover/<bookId>.

API (core/designsystem): KpSpace, KpRadius, KpSize; KpType.largeTitle/title1/title2/displayTitle/subhead/label/button/tabLabel/stat/bigStat/time (v1 names kept); palette.surface3; KpIcons (own vectors) + KpIconView, KpSkipIcon(seconds, forward); KpButton(style, small, icon), KpIconButton(Plain/Tonal/Filled), KpCard, KpStatTile, KpRow, KpSectionHeader, KpChevron, KpLargeTopBar, KpTopBar, KpSegmented (sliding thumb), KpChip, KpSlider, KpScrubber (chapter), KpInput(inCard), KpChatInput, KpSkeleton, KpEmptyState, KpErrorCard, KpStreamingCaret, KpSheet, KpToastHost, KpMiniPlayer, KpProgressBar, KpCoverTile. KpPrimaryKey/KpSecondaryKey/KpSwitch now render in the v2 style. BookStage is deprecated and no longer provided. BookScene has a new keys param; pose state follows stageKeys/keys, and the entrance is saved so it never replays.

COVERS: CoverRequest(bookId, title, author, coverPath); rememberCoverStyle(); KpCovers.peek(bookId), peek(req, style, px), load, preload(reqs, style, px), invalidate(bookId), trim. Composables: rememberCoverImage, CoverImage(req, sharedElement), BookObject(req, yaw, turnIn, ribbon, sharedElement). Unit tests: CoverKeysTest and ByteLruTest pass (19 designsystem tests, 19 app nav tests).

NAV: SharedTransitionLayout plus a seekable entry transition with predictive back. Each tab has its own ViewModelStore and saved state. Route.Player is replaced by Route.Reader(face = LISTEN|READ); the face switches in place in the same entry. Route.Player(id) is kept as a factory.

CALLERS MUST CHANGE: draw every cover through CoverImage/BookObject/KpCoverTile; call KpCovers.preload when a list arrives. Delete BookLook.rememberCover/decodeCover and the player's rememberCoverLoad. Set sharedElement = true on one cover per book: the Library tile, the Home continue card, the Hub hero, the Listen face. Pass BookScene keys. Drop NowBar (the shell renders KpMiniPlayer).

VERIFIED: assembleDebug, emulator (phone was locked). Tab switch shows no replay and keeps state, mini player, Listen face. NOT verified: predictive back gesture, shared transition visuals (no caller uses them yet), light mode.

FOR THE LEAD: KpIcons are my own vectors, not copied Material Symbols, so NOTICE needs no change.
