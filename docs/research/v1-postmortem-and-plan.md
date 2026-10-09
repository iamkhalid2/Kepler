# Kepler V1: post-mortem and rebuild plan

Date: 2026-10-09. Written after product-owner review of V1 on the Find X9.
Inputs: owner feedback, the v1 prototype boards (rendered headless), the running app, code traces, live OpenRouter latency measurements, published UX patterns of production readers and audiobook players.

## 1. Verdict

V1 works end to end, but it reads as an experiment, not a product. The cause is a direction error, not polish: the "Shelf" direction was applied to the whole app, so every screen became a tactile object floating on an empty black void, with sparse information and novel controls. The v1 prototype the owner likes does the opposite: standard, dense, scannable information architecture (cards with clear hierarchy, a persistent mini player, labelled control rows, rich home), which is what production apps share.

New direction (binding, replaces "Shelf everywhere"): **production-grade structure first, real-world 3D objects as accents.** The structure, density and controls of a top-tier app (v1 prototype, ElevenReader, Audible, Apple Books); the 3D book, silk ribbons, paper grain and tactile keys appear where a real object earns its place: the cover on Home, Hub and Player, the bookcase as one optional Library view, the review index card, the page-turn. Never as a substitute for information.

## 2. Root causes found (with evidence)

| Problem the owner saw | Root cause | Evidence |
|---|---|---|
| Ask / Recap / Concepts "take days" | (a) Model ranking sorts free models by context size and recency, not speed, so it lands on `nemotron-3.5-lightning` (13-15 s to first token) or `nemotron-3-ultra` (overloaded); (b) no streaming, so nothing appears until the whole reply is done; (c) 180 s request timeout before falling back to the next model; (d) Recap/Concepts first generate a ~2,000-token JSON "chapter pack" on that slow model. | Live benchmark, same Ask-sized prompt, streaming: `nemotron-3-super:free` 0.7 s first token / 1.2 s total; `gemini-2.5-flash-lite` 0.3 / 0.45 s; `qwen3.7-flash` 0.7 / 0.9 s; `nemotron-3.5-lightning:free` 15 s; `dots-3-note:free` 12 s; `nemotron-3-ultra:free` overloaded; Gemma free models 429. |
| Covers flicker between generic and real, sometimes another book's | (a) `BookScene` keeps per-book animation state by list index, so a re-sort (e.g. "Recent" after opening a book) hands one book another's state; (b) every screen decodes covers from disk itself, starting from the generic cover; no shared memory cache. | `BookScene.kt:205` `books.indices.map { animatePose(...) }`; `BookLook.kt:52-81` `produceState(null)` + `BitmapFactory` per screen. |
| Progress logic is wrong | "x% read" is the share of sentences *consumed with enough dwell time* (the spoiler-fence measure), not where you are. Jumping or skimming leaves it far behind. | `PositionRepositoryImpl` recomputes `readFraction` from consumed intervals. |
| Seekbar is flawed | Scrubber spans the whole book (hours), so fine seeking is impossible. | `Scrubber.kt` `BookScrubber(fraction)`. |
| Tab switching animation is horrible | One `AnimatedContent` fades whole tab trees; tabs are recreated on each switch and the shelf replays its entrance animation. | `AppRoot.kt:239-244`. |
| Read vs listen is not seamless | Reader and player are separate routes; listening opens a different screen. | Nav structure. |
| Player controls look wrong | Custom-drawn -15/+30 glyphs and an unusual layout instead of the familiar pattern. | Device screenshots vs v1 board. |

## 3. What production apps do (patterns we adopt)

- **Read and listen are one screen.** Audible's player has a Listen / Read & Listen toggle above the cover; the text view highlights the spoken passage and auto-scrolls (Amazon "Immersion Reading"). ElevenReader (owner's reference) shows text with paragraph + word highlight, and the transport docked at the bottom of the same screen; double-tap a paragraph to play from there.
- **Chapter-scoped scrubber, labelled.** Apple Books and Libby scrub within the chapter; Audible makes the scope a setting. Pattern: chapter scrubber by default with elapsed / remaining *in chapter*, the chapter name as a tappable chip opening the chapter list, and "Xh left in book" as secondary text.
- **Standard transport.** Sleep, -15, play/pause (large), +30, speed in one row; secondary row: voice/narrator, bookmark, more. Stock skip glyphs, 48 dp targets.
- **Home is a dashboard.** Continue card (cover, progress, one-tap resume), recap "previously on" after a gap, review due, generation status, recent highlights; persistent mini player above the tab bar.
- **Library is a grid of covers by default** (real cover art, progress bar under each, listening badge), with the 3D bookcase as an alternative view.
- **AI answers stream.** First words in about 1 s, citations resolve as they arrive; heavy summaries are prepared in the background, never on tap.
- **Shared element transitions** (Compose `SharedTransitionLayout` + `sharedElement`) for cover → detail → player, with an image cache whose placeholder key equals the shared key so no blank or wrong frame ever shows (Coil 3 `memoryCacheKey` = `placeholderMemoryCacheKey`).
- **Tab bar**: tabs keep their state (no re-creation), switch with a short fade-through, no entrance replays.

## 4. Rebuild plan (V1.1)

Order matters: fix the foundations, then rebuild screens on them, then polish on device.

1. **Design system v2** (Opus design lead): production component set (cards, list rows, chips, segmented control, sliders, sheets, mini player, tab bar), type scale (Geist-like UI sans for chrome, Literata for reading, Fraunces sparingly for display), spacing, elevation by tone; tactile accents defined as specific components (BookObject, Ribbon, PaperCard, IndexCard). Update the design brief; record the decision.
2. **Foundations**:
   - Image pipeline: one in-memory cover cache keyed by book id (Coil 3), placeholder = cached cover, typographic cover generated once and cached too.
   - `BookScene` state keyed by book id, not index.
   - Navigation: Navigation Compose with saved state per tab; `SharedTransitionLayout` for cover transitions.
   - Progress model: `position` (where you are, shown everywhere) separate from `consumed` (spoiler fence only).
   - AI: streaming chat completions; model choice by measured latency (probe the top candidates once a day, keep p50 first-token, prefer under 2 s), 20 s timeout with fast fallback; chapter packs built in the background (WorkManager, prefetch the next section while reading); Ask answers directly from retrieval without waiting for packs. Free models only (#108).
3. **Screens**, in this order: Player + read-along as one screen (Listen / Read toggle, paragraph+word highlight, double-tap to play from here, chapter scrubber, standard transport); Home dashboard; Library (cover grid default, bookcase view optional); Hub; Reader chrome; Learn (streaming Ask); Notebook; Review; Settings; Onboarding.
4. **Device pass on the Find X9** with frame timing (gfxinfo), then adversarial review, fixes.

## 5. Owner decisions (2026-10-09)

1. AI: free models only, chosen by measured latency (#108).
2. Scrubber: current chapter by default (#109).
3. Library: cover grid by default, bookcase optional (#110).

## 6. Inspiration board (Dribbble, 2026-10-09)

Shots studied (internal reference only; screenshots kept outside the repo):
- Abuk audiobook player by tubik — https://dribbble.com/shots/26534699-Abuk-Mobile-App-Audiobook-Player-Screen
- Digital Reading App concept by Victoria Grinevich — https://dribbble.com/shots/27438081-Digital-Reading-Mobile-App-Concept
- Audio book app by Hyperactive — https://dribbble.com/shots/27309461--Mobile-design-for-audio-book-app-Hyperactive
- Smart-lighting rotary control by Imran Jakir (search "tactile ui mobile"); plus broad scans of "audiobook player app", "ebook reader app", "audiobook app dark", "reading app home".

Patterns to adopt, by screen:
- **Player / Listen face (Abuk + the owner's reference):** large real cover with a soft contact shadow (our 3D BookScene cover, turned slightly) on an ambient background tinted from the cover art; a quiet icon row (bookmark, speed, sleep, more) between cover and title; title + author; a slim chapter scrubber with elapsed / -remaining; transport = previous chapter, replay 15, large accent play/pause, forward 30, next chapter; a chapter chip ("Part 1 · Intro") at the bottom that opens the chapter list; voice pill. Read-along face: text-forward with paragraph band + word highlight, transport docked.
- **Library (Grinevich):** editorial dark theme; small mono uppercase labels with counts ("BOOKS · 128 ITEMS"); filter chips at the top (All · Reading · Listening · Finished · Non-fiction); covers in a grid with thin position bars; list rows with thumbnail, serif title, mono meta and an overflow button. Realistic 3D object renders as accents for collections/empty states (a book lying open, a stack), drawn by our BookScene renderer rather than bitmaps.
- **Home (Hyperactive + v1 board):** hero "continue" block with the cover as a 3D object and ambient tint, a format toggle pill (Read / Listen) on the hero itself so resuming in either mode is one tap; "Continue" carousel with progress bars on covers; review and recap cards below.
- **Tactile accents (Imran Jakir):** physical controls only where they add delight without hurting speed: the sleep timer as a rotary kitchen-timer dial and the speed control as a detented knob (in their sheets), the review index card, ribbons as bookmarks/progress. Everything else is clean, standard UI.
- **Typography:** serif display titles (Fraunces) for book titles and section heads, a clean sans for UI, mono for small metadata and numerals (counts, times), as in the editorial references.
