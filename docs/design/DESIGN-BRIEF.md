# Design brief v2 (binding)

Owner: design lead (Opus). Supersedes v1. Decisions #107-#112. Anything not covered: follow the nearest rule and list it under DESIGN ASSUMPTIONS.

**Direction.** Production structure first, real objects as accents. Every screen must read as finished with the 3D book removed: dense cards, clear hierarchy, standard controls. Objects appear only where section 7 lists them. References: v1 prototype boards (structure), the ElevenReader-style player (finish), the Dribbble board (postmortem section 6) plus MidnightReads (3D book breaking out of a dashboard card's top edge).

## 1. Tokens (code: `KpSpace`, `KpRadius`, `KpSize`, `Kp.palette`, `KpType`)

**Spacing (dp):** 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64. Screen gutter 20. Card padding 16 (list cards) or 20 (hero cards). Gap between stacked cards 12. Section header to its content 8; between sections 28. Bottom content padding = mini player + tab bar + 16.

**Radii:** card 20; tile/stat 14; button and chip: full pill; icon button: circle; input 16 (chat input: pill); sheet top 28; mini player 18; cover image 6 (thumbnails under 56 dp: 4); segmented track 14, thumb 11; toast 14.

**Surfaces (elevation by tone, no drop shadows on UI):**

| Token | Dark | Light | Use |
|---|---|---|---|
| bg | #0A0A0C | #F4F2EE | screen |
| surface1 | #16161A | #FFFFFF | cards, tiles, inputs on bg |
| surface2 | #1F1F24 | #EFEDE8 | pressed state, inputs and buttons inside a card |
| surface3 | #26262C | #FFFFFF | sheets, menus, mini player, toast |
| hairline | #2A2A30 | #DEDBD4 | row separators, input focus-less border in light |

Light cards carry a 1 dp hairline border; dark cards none. Shadows only on 3D objects (their own) and the mini player (black 35%, blur 12, y 4).

**Colour roles.** `text`/`text2` for text. `accent` fills progress, the scrubber track, ribbon. `accentText`: links and one caps label per card. `key` (warm off-white dark, ink light) fills primary and play buttons, label `onKey`. No accent-filled buttons. `readAlong` = paragraph band, `readAlongLine` = spoken word. Never hardcode a hue.

## 2. Type

Albert Sans for chrome and controls. Literata for book text and book-derived prose. Fraunces only for display: hero book titles, onboarding and empty-state headlines. No mono; numbers use tabular figures.

| Role (`KpType.`) | Face | Size/line | Weight | Use |
|---|---|---|---|---|
| largeTitle | Albert | 30/36, -0.01em | 650 | tab root titles |
| title1 | Albert | 22/28 | 600 | detail screen titles |
| title2 | Albert | 18/24 | 600 | card titles, sheet titles |
| displayTitle | Fraunces | 26/30 | 400 | hero book title |
| rowTitle | Albert | 16/22 | 600 | row titles, mini player title |
| body | Albert | 15/22 | 400 | body copy |
| subhead | Albert | 14/20 | 500 | meta, secondary labels |
| caption | Albert | 13/18 | 400 | captions |
| label | Albert | 11/14, +0.08em, caps | 650 | overlines ("LISTENING · CHAPTER 4") |
| button | Albert | 15/20 | 600 | buttons, segments |
| tabLabel | Albert | 12/16 | 600 | tab bar |
| stat | Albert tnum | 22/26 | 650 | stat tiles |
| bigStat | Albert tnum | 40/44 | 650 | review count |
| time | Albert tnum | 12/16 | 500 | scrubber times |
| prose | Literata | 16/26 | 400 | recaps, answers |

Font scale 1.0-1.5: no fixed-height text containers except tab bar and mini player.

## 3. Components (code names in brackets)

- **Card** [`KpCard`]: surface1, r20, padding 16/20; press tone surface2, no scale.
- **List row** [`KpRow`]: min 56 (one line) / 72 (two lines), gutter 20 (16 inside a card), leading slot 40 (book thumbnail 32x48 r4), headline + caption `text2`, trailing chevron 20 or 48 dp icon button. Separators 1 dp hairline from the text start [`KpRowSeparator`].
- **Section header** [`KpSectionHeader`]: label caps `text2`, optional trailing link (subhead 600, accentText), 40 high.
- **Buttons** [`KpButton`]: Primary key fill, Secondary surface2 (surface1 on bg), Ghost accentText; h52 or 40 (small); pill; min width 96; icon 20 + 8 gap. One Primary per region. `KpPrimaryKey`/`KpSecondaryKey` now render as these.
- **Icon button** [`KpIconButton`]: 48 touch, 24 glyph; Plain or Tonal (40 circle surface1). Filled (key circle) only for play/pause and send.
- **Icons** [`KpIcons`, `KpIconView`]: own vectors, 24 grid, 1.8 stroke, round caps; solid play, pause, bookmark-on. Skip [`KpSkipIcon`]: 300 degree arrow circle with the seconds numeral centred (9 sp 700); -15 / +30.
- **Segmented control** [`KpSegmented`]: track surface1 r14 h40 padding 3; thumb surface3 (dark) / white with hairline (light), r11, slides on the `snap` spring; labels button style, `text` selected, `text2` otherwise. 2-4 segments.
- **Chips** [`KpChip`]: h32 pill, surface1; selected key fill. Only: Library filters, Ask citations, Hub genre tag.
- **Slider** [`KpSlider`]: track 4 r2 surface2, active accent, thumb 18 key circle (24 while dragging). Tick haptic at detents.
- **Scrubber** [`KpScrubber`]: chapter-scoped (#109). Track 4 (6 while dragging), no thumb at rest, 14 thumb while pressed. Times under it: elapsed left, "3h 10m left in book" centre (`text2`), "-12:40" right, `time` style. Estimated durations prefix "~".
- **Sheet** [`KpSheet`]: surface3, top r28, handle 36x4 r2 `text2` 40%, padding 20 / bottom 24, title2 heading, scrim black 50% (light 25%).
- **Input** [`KpInput`]: h52 r16 surface1 (surface2 inside cards), no border in dark, hairline in light, focus 1.5 dp accent border; label caps above (8 gap); error caption `negative` below. Multiline min 112. Chat input [`KpChatInput`]: pill h52, trailing 40 send button (key circle).
- **Mini player** [`KpMiniPlayer`]: h64, margin 8, 8 above the tab bar, surface3, r18. Cover 32x48; headline + caption ("Chapter 4 · 18 min left"); 44 play/pause key circle; 2 dp accent chapter progress on the bottom edge. Tap or swipe up opens the Listen face; swipe down pauses and hides.
- **Tab bar** [`KpTabBar`, app/nav]: h64 + inset, top hairline; icon 24 over tabLabel; selected `text` on a 56x32 surface2 pill (fades 120 ms, never slides), else `text2`.
- **Top bars:** [`KpLargeTopBar`] caption over largeTitle, up to two tonal actions right. [`KpTopBar`] h56, tonal back, optional centred title + caption, actions.
- **Toast** [`KpToastHost`]: surface3 r14, min 48, 4 s, 16 above bottom chrome.
- **Stat tile** [`KpStatTile`]: surface1 r14 h72, stat numeral + caption.
- **Covers** [`CoverImage`, `KpCoverTile`, `BookObject`]: see section 6.

## 4. Motion (code: `KpMotion`)

- Tab switch: fade-through, 60 ms out (linear) then 120 ms in (FastOutSlowIn). No slide, no scale, no entrance replay; each tab keeps its scroll, state and ViewModels.
- Push: incoming fades in 220 ms from +24 dp; outgoing fades 120 ms. Pop reverses. Predictive back scrubs the pop (outgoing scales to 0.92 and fades).
- Shared cover: the cover is one shared element `cover/<bookId>` from Library/Home to Hub hero to Listen face, bounds on spring(0.9, 380), corner radius morphs. It always draws the cached image, never a placeholder.
- In-screen state: `snap` spring (1.0, 1600) for toggles and segments, `settle` (0.9, 400) for values, `move` (0.82, 320) for layout.
- The 3D turn (flat to yaw 16) plays once per Hub or Listen entry. Lists never stagger; nothing animates on a tab root.
- Streaming text: tokens append without animation; a 2x16 accent caret blinks at 1 Hz until done.
- Reduced motion (app or system): every transition is a 120 ms crossfade, no 3D turn, shared elements snap.

## 5. Haptics

Tap: buttons, toggles, segments, chips. Tick: scrubber chapter boundaries, slider detents, knob steps. Confirm: committing actions (save card, grade, start narration). Reject: errors. Nothing on tab switch, scroll or page turn.

## 6. Covers (pipeline is binding)

Every cover goes through `KpCovers` (process memory cache, IO decode, generated cover rendered once per book). Screens call `KpCovers.preload` when a list arrives and draw with `CoverImage` / `BookObject`; a cached cover shows on the first frame. Never decode covers yourself; never show a generic cover while a real one loads.

## 7. Real-world accents (placement is exhaustive)

| Object | Where | Spec |
|---|---|---|
| BookObject (3D book) | Home continue card; Hub hero; Listen face | Continue: 88x132, breaks 24 above the card top, yaw 18. Hub: 200 tall. Listen: 260 tall (220 on screens < 700 dp tall). Ambient tint behind Hub and Listen hero: cover colour radial at 24% over bg. |
| Bookcase | Library, optional view only | the existing shelf (`BookScene`), toggled from the top bar; choice remembered |
| Silk ribbon | below a BookObject (`ribbon` arg) | length 24 + 80 x position share |
| Index card | Review card face | paper, 1 red rule, faint blue lines, r6 |
| Page turn | reader page change | reader agent's curl |
| Knob / dial | speed and sleep sheets, optional | detent Tick per step |

One 3D object per screen at most (bookcase excepted); below 64 dp covers are flat `CoverImage`.

## 8. Screens

**Home.** Caption "Wednesday evening" + "Welcome back", settings button. Continue card: BookObject left breaking the top edge; label (LISTENING · chapter), title2, author, 4 dp position bar + "34% · 2h 10m left"; Listen (Primary small) and Read (Secondary small) resume in one tap. Then, when present: "Previously on" card (after 2+ days away, prose 3 lines, "Full recap"); Daily review card (bigStat, "about 6 min", week dots, Start 56 key circle); narration status row card (ring, "Narrating · 62% · Ava"); "Continue" carousel of 104-wide tiles; Recent highlights (3 quote cards, "See all").

**Library.** "Library"; actions: grid/bookcase toggle, Add. Search (h44 pill). Chips: All, Reading, Listening, Finished, Non-fiction. Label "24 BOOKS". Grid (#110): 3 columns (600 dp+: 5), gaps 12 / 20; `KpCoverTile`: cover 2:3, 3 dp position bar, title 14/18 600, author caption. Headphones badge top-right when narrated, ring while generating. Long-press: actions sheet.

**Hub.** Top bar, genre chip. Hero: tint, BookObject 200, displayTitle, author. Stat tiles: "34%" where you are (#111), "4h 10m" left to listen, "38" cards. Three 88-high r18 action tiles (icon over label): Read, Listen (key), Learn. Narration row, "Learn from this book" cards, chapter rows with position.

**Listen / Read (#112, one destination, `face` argument).** Top bar: chevron-down, centred segmented Listen | Read (200 wide), more. Face switch crossfades 180 ms; playback continues. Listen face: tint, BookObject, displayTitle + "Chapter 4 · narrated by Ava", chapter chip (h36 pill, list icon + title; opens chapters), scrubber, transport: sleep, -15, play/pause (72 key circle), +30, speed ("1.25x"); bottom row: voice pill (h40, avatar + name), bookmark. Read face: Literata, paragraph band `readAlong` r8, spoken word `readAlongLine` r4, active paragraph kept at 35% height; dock over a 32 dp fade: chapter chip, scrubber, transport (play 56); idle dock = chip + play. Double-tap a paragraph plays from there.

**Learn.** Top bar "Learn" / book, scope chip ("Up to here" / "Whole book"). Segmented Ask, Recap, Map, Concepts. Ask: question bubble right (surface2 r20), answer card (surface1, prose), citation chips, "Make a review card"; chat input docked.

**Notebook.** "Notebook", search. Review banner card when due. Segmented Highlights, Notes, Cards. Grouped by book (header row: thumbnail, title, count); item cards: 3 dp highlight bar, prose 15/24 max 4 lines, "Chapter 3 · 2 days ago", overflow.

**Review.** Close, "Daily review" + "card 1 of 12", segment progress (4 high). Index card holds the question (title2) and source. Answer input. Hint (Secondary) + Show answer (Primary). Grades: four 56-high r14 buttons, label + interval.

**Settings.** Detail top bar. Grouped cards (r20) with section labels: Reading, Listening, AI, Appearance, Data, About. Rows 56 with switch, value + chevron or chevron.

**Onboarding.** Three pages: object in the top 45%, Fraunces 30/36 headline, body 16/24 `text2`, dots (active 18 pill), Primary "Continue", Ghost "Skip". Last: "Add your first book" + optional keys.

**Narration setup.** "Narration". Segmented Fish Audio | On this phone. Voice cards (avatar 40, name, descriptor, preview; selected 1.5 accent border). Estimate stat tiles. Docked Primary "Start narration".

## 9. States

- **Loading:** surface1 skeleton blocks at final sizes, alpha 0.5-1.0 over 1.2 s [`KpSkeleton`]; no full-screen spinners.
- **Empty** [`KpEmptyState`]: column at 30% height, optional object (Home, Library), Fraunces 24 headline, body `text2` max 2 lines, one Primary.
- **Error** [`KpErrorCard`]: headline, one plain sentence, small Secondary "Try again". No codes.
- **Streaming (all AI):** first token target 1 s. Before it: label "Reading the book…" with caret. Then tokens append; citations become chips as they resolve; Stop as a Ghost button while streaming.

## 10. Copy

Sentence case, warm, specific ("12 cards, about 6 minutes"). No exclamation marks, no hype, no warnings about the person's files, copyright or DRM. Times: "2h 10m", "18 min".

## 11. Review checklist

1. Remove the 3D object: does the screen still look finished and informative?
2. Every cover drawn through `KpCovers`; no placeholder frame on revisit.
3. Tab switch shows no slide, no replay, same scroll position.
4. Touch targets 48; contrast passes; font scale 1.5 clips nothing.
5. Dark and light screenshots; every accent preset.
