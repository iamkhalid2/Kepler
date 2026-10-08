# BetterBooks: Experience, Design System & Motion

> Part of the master plan, [`../PLAN.md`](../PLAN.md). **Live prototype:** https://claude.ai/artifact/MwAQh5KMfsi1W8DkPURbnK (source in [`../design/prototype/`](../design/prototype/)). The design bar is **Revolut**: fluid, tactile, morph-heavy, obsessive about detail (#42). It is built on Material 3 Expressive, dark mode comes first, and per-book screens take their color from the cover art. The clickable prototype that shows this spec lives in [`../design/prototype/`](../design/prototype/).

---

## 1. Design principles
1. **The book is the hero.** Cover color sets the mood of every per-book surface. Navigation chrome steps back while you read or listen.
2. **One continuous space.** Read, listen and learn are *states of the same screen*, not separate apps. Every transition is a morph (shared element), never a hard cut.
3. **Feel every action.** Every meaningful action has a deliberate haptic and a spring. Nothing is linear, and nothing is silent.
4. **Calm by default, depth on demand.** Show the next useful action and put power features one gesture away: a long-press, a sheet, or the spark button.
5. **Effort that builds memory.** Learning UI asks before it tells: try the answer first, then reveal it. Progress means things you recalled, not pages you turned.
6. **Honest numbers.** Costs, time left, requests left and "generated ahead" are always visible where they matter. They never nag.
7. **Accessible craft.** Every animation respects reduced motion. Every haptic can be turned down or off. Every control has a TalkBack label.

---

## 2. Information architecture (#95)
```
Bottom bar: [ Home ]  [ Library ]  [ Notebook ]          + floating now-bar (mini player) above it
Home      → Resume card (mode-aware) · Previously on… · Review due · Generating · Recent highlights
Library   → All / Reading / Listening / Has audio / Generating / Finished · Collections · Series · Search · Import (+)
Notebook  → Review (FSRS session) · Highlights · Cards · Vocabulary · Maps · Export
Book hub  → hero cover · progress · [Read | Listen | Learn] · narration status · Setup
Reader    → one screen, segmented: Read | Read + Listen | Listen  (spark ✦ button → Ask / Recap / People-Concepts / Map)
Player    → full player (morphed from the now-bar): chapters, speed, sleep, bookmarks, A/B audition
Settings  → (avatar/gear on Home) providers & models · budgets · appearance · haptics · reading defaults · backup · about
```

---

## 3. Design tokens

### 3.1 Color (dark first)
| Token | Dark | Light | Use |
|---|---|---|---|
| `bg` | `#0B0B0E` | `#F7F6F3` | app background (light is warm paper) |
| `surface-1` / `-2` / `-3` | `#141418` / `#1C1C22` / `#26262E` | `#FFFFFF` / `#F1F0EC` / `#E8E6E1` | cards, sheets, raised controls |
| `outline` | `#2E2E38` | `#DEDBD4` | hairlines (1 px) |
| `text-1` / `-2` / `-3` | `#F5F5F7` / `#A1A1AA` / `#85858F` | `#111114` / `#4A4A55` / `#8A8A94` | primary, secondary and tertiary text |
| `brand` | `#8B7CFF` (Lumen violet) | `#5B47E8` | primary actions, focus, brand moments |
| `ember` | `#FF8F5C` | `#E8642C` | secondary accent: listening, generation, streak warmth |
| `positive` / `warning` / `negative` | `#3DDC97` / `#FFC857` / `#FF5D73` | darker tones of each | review ratings, states |
| **Cover scheme** | MaterialKolor scheme from the cover's Palette seed (style `Expressive`, contrast ≥ 4.5:1 guaranteed) | same, light variant | book hub, reader chrome, player, now-bar tint |

Reader themes, saved per theme as in Apple Books (#55):

| Theme | Page | Ink |
|---|---|---|
| Day | `#FFFFFF` | `#1A1A1A` |
| Paper | `#F7F3EA` | `#2A2622` |
| Sepia | `#F4ECD8` | `#3B2F22` |
| Night | `#1B1B1F` | `#D6D3CC` |
| AMOLED | `#000000` | `#C9C9C9` |
| Custom | user-picked | user-picked |

### 3.2 Typography
All fonts are **OFL and bundled**. The FOSS build can't use Play downloadable fonts.

| Role | Font | Sizes and weights |
|---|---|---|
| UI | **Geist** | Display 40/44 · 600 · −2%. Headline 28/32 · 600 · −1.5%. Title 20/26 · 600. Body 16/24 · 400. Label 13/16 · 500 · +1%. Numerals are always tabular (`tnum`). |
| Data and numbers | **Geist Mono** | timers, costs, request counters |
| Reading (default) | **Literata** | user scale 14–32, default 19/1.55 |
| Reading options | Source Serif 4, Atkinson Hyperlegible Next, iA Writer Duospace, OpenDyslexic, Geist, system serif, plus imported fonts | |

### 3.3 Shape, space, elevation
- **Radius:** 6 (chips) · 12 (inputs) · 20 (cards) · 28 (sheets, hero cards) · full (pills, FAB).
- **Morphing shapes** come from M3E `MaterialShapes` plus `graphics-shapes` `Morph`:
  - play is a rounded square that morphs to a circle when paused;
  - the generate button morphs from a cookie shape into a progress ring;
  - the active tab is a soft-burst blob behind the icon.
- **Spacing:** 4-pt grid (4, 8, 12, 16, 20, 24, 32, 40, 56). Side gutter 20.
- **Elevation:** almost none. Depth comes from surface steps, a 1 px top highlight (`white @ 6%`) and blurred cover glows behind heroes. There are no drop shadows on dark.

### 3.4 Motion tokens (Compose)
Use `MaterialTheme(motionScheme = MotionScheme.expressive())` as the base, and override with named springs:

| Token | Spring (dampingRatio, stiffness) | Use |
|---|---|---|
| `snap` | 0.90, 1400 | toggles, chips, segmented indicator |
| `glide` | 0.82, 700 | sheets, cards, list reorder |
| `hero` | 0.86, 320 | shared-element morphs: cover → hub → player, now-bar → player |
| `bounce` | 0.55, 500 | celebrations, play/pause morph overshoot, card grade |
| `fade` | tween 180 ms, emphasized decelerate | color and alpha changes |

When reduced motion is on, springs become 120 ms fades with no translation or scale, and shared elements cross-fade.

### 3.5 Haptic tokens
`Haptics.play(token)` maps each token to `VibrationEffect.Composition` primitives when they are supported (`areAllPrimitivesSupported`), and falls back to `HapticFeedbackConstants` otherwise. The setting is **Off / Subtle (×0.5) / Full**.

| Token | Primitive (scale) | Fallback | Used for |
|---|---|---|---|
| `tick` | LOW_TICK (0.4) | CLOCK_TICK | scrubber chapter detents, list snap, sleep dial every 5 min |
| `tap` | TICK (0.7) | KEYBOARD_TAP | chips, toggles, tab switch |
| `confirm` | CLICK (1.0) | CONFIRM | play/pause, save highlight, accept card |
| `settle` | THUD (0.6) | CONTEXT_CLICK | sheet settling at its detent, drag drop |
| `success` | QUICK_RISE (0.6) → CLICK (1.0) after 40 ms | CONFIRM | grading Good or Easy, generation complete, review done |
| `threshold` | — | GESTURE_THRESHOLD_ACTIVATE | swipe-to-grade crossing, pull-to-generate |
| `reject` | — | REJECT | an error, a budget cap reached, an encrypted file |
| `heartbeat` | TICK, then TICK after 120 ms (0.5) | — | buffer ran dry (waiting for generation) |

---

## 4. Screen inventory
Every screen has these states, covered by Roborazzi in light and dark at 1.0× and 1.5× font scale: **content · empty · loading · error · offline**.

| Screen | Purpose | Key components & behavior |
|---|---|---|
| **Onboarding** | welcome, then 3 pillar cards (Read · Listen · Remember), then keys, then a privacy note, then the ColorOS guide | parallax pillar cards; key fields with a live "Verify" (a cheap call) and a check-mark morph; privacy note in plain words (#46) |
| **Home** | the daily start point | Resume card (cover glow, mode chip "Listening · Ch 7 · 18 min left", one-tap resume); *Previously on…* (only after a gap); Review card ("12 due · ~6 min", week dots); Generating card (live ring); Recent highlights carousel |
| **Library** | browse and manage | 3-column grid that switches to list with a morph; filter chips; collections and series rows; search with FTS across titles **and** text; (+) import sheet (files, folder watch, later OPDS) |
| **Book hub** | everything about one book | hero cover (shared element from the grid); progress (read and listen); [Read · Listen · Learn] segmented; narration status ("62% generated · Ava (Fish)"); Setup; Learn grid (Ask, Recap, Concepts/People, Map, Takeaways, Notes) |
| **Setup** | make the audiobook *yours* | detected type pill (editable); style presets; narrator voice carousel with previews from this book; full-cast toggle (fiction) or quote-voice toggle (non-fiction); reading options (footnotes, tables, figures, citations, front and back matter); lexicon; **cost & ETA card** ("640k chars · $0.00 until Nov 30 · ≈ 38 min · 290 MB"); Generate (cookie → ring morph) |
| **Cast review** (fiction) | fix who says what | character list with voice chips; low-confidence queue ("14 lines to check"); a line editor showing text, speaker, style and intensity, with a 1-request preview |
| **Reader** | read | page (Readium) with fading chrome; top bar (back, title, Aa, search, ✦); bottom bar (chapter progress with ticks, time left, segmented **Read / Read+Listen / Listen**); selection menu (Highlight · Explain · Ask · Card · Define); footnote sheet; Book Map; search |
| **Read + Listen** | follow along | follow-highlight on the current sentence (cover-tinted background behind the text); auto page turn; transport bar; scrolling manually detaches and shows **Return** and **Play from here** |
| **Listen** | eyes-free with context | karaoke transcript (current sentence large, neighbors dimmed) over a blurred cover; big transport; chapter scrubber |
| **Now-bar** | persistent mini player | floats above the tab bar, tinted by the cover; play/pause morph; progress hairline; swipe up to the full player (hero morph), swipe down to dismiss |
| **Full player** | control | big cover; scrubber with chapter ticks and detent haptics; ±15/30 s; speed (0.5–3.5×, presets and fine slider); sleep timer dial; bookmark (+ note); headset-highlight hint; chapters; narrator A/B |
| **Learn: Ask** | questions grounded in the book | chat with citation chips that jump into the book; fence indicator ("Up to Ch 7 · change"); suggested questions from the pack |
| **Learn: Recap** | come back fast | "Previously on…" as cards per chapter, collapsible, with citations; play it aloud (system TTS) |
| **Learn: People / Concepts** | an X-Ray that respects the fence | grid of entity cards, then a detail with facts up to the fence and first mention |
| **Learn: Map** | see the structure | force-directed graph; drag, pin, add links, notes on edges; filter by kind |
| **Check-in** (inline) | retrieval in the flow | card sliding up at the end of a section: prompt, then answer field (type or voice), then reveal gist and rubric; skip is always one tap |
| **Chapter recall** | end-of-chapter retrieval | predict score (slider), then free recall, then rubric hits and misses with links, then suggested cards |
| **Notebook → Review** | the daily habit | card stack; tap to flip (3D); answer field for QA cards; grade with Again/Hard/Good/Easy buttons showing the next interval, or swipe; completion moment (`success` haptic, a bloom of week dots) |
| **Notebook → Highlights / Cards / Vocabulary** | your knowledge | grouped by book and chapter; filters; edit; Export sheet (Obsidian · Markdown · Readwise · Anki · JSON) |
| **Settings** | control | providers (Fish key, tier, model policy; OpenRouter key, tier probe, model picker with "auto: best free"); budgets (monthly cost cap, requests left today); appearance; haptics; reading defaults; backup/restore; about and licenses |

---

## 5. Signature motion moments
| Moment | Spec | Compose APIs |
|---|---|---|
| **Cover → Book hub** | The tapped grid cover grows into the hub hero (`hero` spring). The cover glow fades in behind it. The title cross-fades from tile caption to headline. Back reverses it, driven by predictive back progress. | `SharedTransitionLayout`, `Modifier.sharedElement` / `sharedBounds`, Nav3 scene transitions, `PredictiveBackHandler` |
| **Now-bar → Full player** | The bar's bounds expand to full screen. The cover thumbnail travels to the large cover position, the play button travels and morphs, and the tab bar slides down. A drag-down follows the finger (velocity-aware) and snaps with `settle`. | `sharedBounds` + `AnchoredDraggable` with spring snap |
| **Play ↔ Pause** | Rounded-square ↔ circle morph with `bounce` overshoot. The icon cross-fades at the midpoint, with a `confirm` haptic. | `Morph(RoundedPolygon, RoundedPolygon)` animated by `Animatable` |
| **Read / Read+Listen / Listen** | The segmented pill indicator slides (`snap`). The page view transforms: in Listen the page shrinks into the transcript while the cover blurs in behind (`hero`). | `AnimatedContent` with a custom `SizeTransform`, `graphicsLayer` blur (API 31+) |
| **Chapter scrubber** | Drag shows a floating chapter label. Each chapter tick gives a `tick` haptic. Release snaps to the sentence start. | custom `Slider` track + `Haptics` |
| **Generation ring** | A circle made of N arcs (one per chapter) that fill as segments finish. The current arc shimmers, and the ring turns into a check-mark (`success`) when done. | `Canvas` + `animateFloatAsState` + `Morph` |
| **Sleep dial** | Circular dial: drag sets minutes, with a `tick` every 5 minutes. The center shows big tabular digits that roll. "End of chapter" is a notch at the top. | `Canvas` + pointer input + `AnimatedContent` digits |
| **Review card** | Flip on Y with perspective (`glide`). Swiping past 35% triggers `threshold`, and the card tints toward the grade color. On release the card flies out with `bounce`, and the next card rises from below with a slight scale. | `graphicsLayer(rotationY, cameraDistance)`, `AnchoredDraggable` |
| **Highlight save** | A ripple of the highlight color sweeps across the selected text, then a small `confirm` haptic, and a chip pops "Saved · Add note". | Readium decoration plus an overlay `Canvas` |
| **Check-in reveal** | The answer card lifts, and the gist slides out from behind it. Rubric items check in one by one, 60 ms apart, each with a `tick`. | `AnimatedVisibility`, staggered `LaunchedEffect` |

---

## 6. Widgets & system surfaces (#35)
Glance widgets use the cover scheme where relevant, and system dynamic colors otherwise:

| Widget | Size | Contents | States |
|---|---|---|---|
| **Now Playing** | 4×2 (resizes to 2×2) | cover with glow, title, chapter, progress arc, play/pause, −15/+30 s | playing · paused · generating ("ahead 6:20") · nothing playing (shows a "Continue <book>" card instead) |
| **Review Due** | 2×2 | big count, "≈ 6 min", week dots, one card's front (the answer is never shown) | due · all caught up ("Next review tomorrow 9:00") |
| **Continue Reading** | 4×1 | cover strip, title, "12 min left in chapter", progress bar | reading · finished |

**Android Auto** (`MediaLibraryService` browse tree):
- Tree: **Continue** (one playable item) → **Recently played** → **Library** (books with audio or system-TTS ready) → **Collections**. Each book lists its chapters.
- Per Auto guidelines: large artwork, short titles, no reading UI. The sleep timer turns itself off while connected to Auto.

**Notification and lock screen:** Media3 default media notification with custom command buttons: −15 s, +30 s, and Bookmark (replacing skip-next when the user chooses).

---

## 7. Accessibility
- **TalkBack:** labels for every icon. Reader text is in a WebView, which TalkBack reads natively. Custom actions on cards ("Grade Good"). Live regions for generation progress and the buffer.
- **Text and touch:** font scale up to 2.0 without clipping (tested at 1.5). Touch targets of 48 dp or more. Contrast ≥ 4.5:1 for text, also with cover-derived schemes thanks to the contrast guard.
- **Motion and haptics:** reduced motion follows `Settings.Global.ANIMATOR_DURATION_SCALE == 0` plus an in-app toggle. Haptics can be turned off.
- **Reading support:** the OpenDyslexic and Atkinson Hyperlegible fonts, and a line-focus ruler (later).

---

## 8. Brand
- **Name:** BetterBooks. **Tagline:** *Read. Listen. Remember.*
- **Logo concepts.** Concept **A** is recommended. All three are drawn in the prototype and saved as SVG in [`../design/brand/`](../design/brand/).
  - **A · Seed b (recommended).** A lowercase *b* made of a rounded stem and a ring-shaped bowl, with a solid **seed dot** at the centre of the bowl. The bowl holds the idea you keep: you internalize the book.
    - Geometry on the adaptive-icon grid (108 units):
      - stem: rect x=34, y=26, w=11, h=56, r=5.5;
      - bowl: ring centered at (58, 63), r=16.5, stroke 11;
      - seed: dot r=4.6 at (58, 63).
    - Colors: flat two-tone, with no gradient. The stem and bowl are Lumen violet `#8B7CFF` and the seed is ember `#FF8F5C`, on ink `#17151F`. The light variant uses `#5B47E8` and `#E8642C` on paper `#F7F3EA`. The monochrome/themed version is a single color.
  - **B · Folio wave.** Two page arcs meeting at a spine, drawn as one continuous stroke that reads both as an open book and as a sound wave, with a seed dot above it.
  - **C · Ribbon play.** A bookmark ribbon whose notched tail is a play triangle cut-out. Read and listen in one glyph.
- **Wordmark:** "BetterBooks" in Geist SemiBold at −2% tracking. "Books" is set at 70% opacity in UI lockups.
- **App icon:** an adaptive icon with foreground mark A and an ink background. The themed (monochrome) icon is mark A at 100% in the system tint.

---

## 9. How polish is verified
1. **Roborazzi screenshots** of every state, viewed by the agent. Diffs gate CI.
2. **On-device screen recordings** (`adb shell screenrecord`) of each signature moment, checked frame by frame for jumps, linear easing, or missing overshoot.
3. **A haptic checklist** run on the Find X9: each token felt once, and nothing fires twice on one action.
4. **Jank checks** with `adb shell dumpsys gfxinfo app.betterbooks.debug framestats` on the library, reader and player. Target under 1% janky frames.
