# Design brief (binding)

Design lead approves all UI. Overrides `docs/plan/experience.md` §4–5 (no cover glow, blurred covers, filter chips, card grids, cover-art colour outside the book). Units dp/sp.

## 1. Components

Spacing scale 4/8/12/16/24/32/48; gutter 24; section gap 32; targets ≥48×48. **No elevation anywhere**: depth comes only from the key light (bevels, lips, contact ellipses). Radii: keys, inputs, toasts 4; sheet top 8; slips and cards 2; covers 1.5. Circles only for the play key, rings and 6 dp dots. No pills.

- **Primary key**: `key` fill, `onKey` Albert 15/600, height 52, full width or ≥120. Bevel: inset top 1 white .7, inset bottom 1 black .2, drop 0/2 black .9 (light .25). Pressed inverts bevel; disabled `surface2`, flat.
- **Secondary key**: `surface1`, 1 `edge` border, inset top 1 white .07 (light .9), Albert 14/600, height 48, min width 84.
- **Text link**: Albert 14/600 `accentText`, padded to 48.
- **Hairline**: 1 `hairline`, inset to gutter. Accent rule (1 `accent`) at most once per screen.
- **Segmented**: `surface1` track, 1 hairline, height 48; active segment is a small primary key (r3, flex 1.5).
- **Sheet**: `surface1`, top plank lip (white .10→0), handle 32×3 at 12, padding 24, detents 50%/full, scrim black .5 (light .25).
- **List row**: 56 one-line, 72 two-line; 40 leading slot (28×42 cover or icon); title Albert 16/500, meta 13 `text2`; separator starts at the text.
- **Input**: 52, r4, `surface1`, 1 hairline; focus 1 `accent` + accent caret; caps label above; error 13 `negative`.
- **Switch**: track 44×24 r4 `surface2`; thumb 20×20 r3 `key` with bevel; on = `accent` track.
- **Toast**: `surface2`, r4, min 48, padding 12/16, Albert 14/500, optional link; 16 above now-bar; 4 s.
- **Badge**: counts are Fraunces numerals inline; unread = 18 ring, 1.5 dashed `accentText`, numeral 9.5/700; status = 6 dot + caps.
- **Tab bar**: Home, Library, Notebook; 64 + inset; top hairline; active `text` with 12×2 accent rule above icon; inactive `text2`.

## 2. Type

- Display: `BbFonts.display` 360, 34/38, −0.02em.
- Headline: `display` 360, 26/30.
- Title: Fraunces 450, opsz 24, SOFT 50, 19/24 (add `BbFonts.title`).
- Spine/cover text: `displaySmall`, 9–14.
- Author: Literata italic 400, 14/20, `text2` (add italic).
- UI body: `BbFonts.ui` 400, 15/22. Key labels 600 (never 700). Caption 13/18 `text2`. Caps 600, 11/14, +0.14em, `text2`.
- Reading: `BbFonts.reading` 400, 19/30 default, user 15–26; small-caps opener, hung numeral.
- Book-derived prose (AI answers, recaps, slips): `reading` 16/26.
- Numerals: Fraunces 500, opsz 18, `tnum`, 13–16. Big numerals: `display` 360 `tnum` 40/44.

Fraunces opsz tracks size.

## 3. Colour

`Bb.palette` tokens only; no literal colour in feature code. Designsystem must add from `shelf-language.md` §2: `key`, `onKey`, `body`, `page`, `readText`, `pencil`, `edge` (hairline +8% L) and the materials (cloth, silk, bone ribbon, plank).

- `bg` screens; `surface1` sheets, inputs; `surface2` toasts; `page` + `readText` reader.
- `accent` fills only: listened ribbon, progress/frontier rule, active tab rule, switch track, focus border, generation ring, read-along marks, logo dot. Never a filled button.
- `accentText`: links, citation numerals, live states.
- `secondary`: second entity kind in Map, FSRS "learning" only.
- `positive`/`warning`/`negative`: text, 6 dots, 2 dp key edges; never large fills.

Light mode: linen neutrals, `key` turns ink with bone label, bone ribbon turns graphite, contact α .30, lip over a 0.5 seam; object lighting unchanged. Every seed derives by the same rules; nothing assumes blue.

## 4. Motion

Springs only (ζ/stiffness): `snap` 1.0/1600 chrome, segmented; `settle` .90/400 sheets, toasts, pitch; `move` .82/320 position, size; `turn` .72/260 yaw; `lift` 1.0/800 shadows; `open` .85/220 board; `reveal` .90/380 page clip; `flip` .86/380. Stagger 60 ms, max 5.

Signature transformations, one `BookStage` above nav, all scrubbed by predictive back:
1. **Spine → hub cover**: `lift` immediately, yaw 90→24 at +60 ms, position at +90 ms; hub text rises once yaw <40°.
2. **Cover → reader**: board hinges 0→−165°, paper liner shows; past .55 the page clip grows from the projected page quad to full screen.
3. **Cover/now-bar → player**: book turns to face you: yaw 0, pitch −6, height ×1.14; chrome fades in below 8° yaw; drag-down follows the finger.

Card flip per `shelf-language.md` §3, `flip` spring, `tick` at 90°.

Ribbons swing only on events (Home appearance per resume, progress change, sentence change at stiffness 40, book acceleration). Peak ≤4°, second ribbon .85·v at +120 ms. Nothing loops.

Reduced motion (scale 0 or app setting): poses snap, transformations and flip become 120 ms crossfades, no ribbons or stagger.

## 5. Haptics

`Haptics.play(token)`: composition primitive if supported, else `HapticFeedbackConstants`. Setting Off / Subtle (×.5) / Full, default Full; silent when system touch feedback is off.

- `tick` LOW_TICK .4 / CLOCK_TICK: chapter detents, sleep dial per 5 min, flip at 90°, rubric checks.
- `tap` TICK .7 / KEYBOARD_TAP: tabs, segmented, switches, secondary keys.
- `confirm` CLICK 1.0 / CONFIRM: primary keys, play/pause, highlight or card saved.
- `settle` THUD .6 / CONTEXT_CLICK: sheet detent, book landing, map drop.
- `success` QUICK_RISE .6 → CLICK +40 ms / CONFIRM: key verified, narration done, Good/Easy, review complete.
- `threshold` GESTURE_THRESHOLD_ACTIVATE: grade swipe crossing, back commit.
- `reject` REJECT: errors, budget exhausted, unreadable file.
- `heartbeat` TICK, TICK +120 ms .5: narration buffer ran dry.

## 6. Screens

States: content, empty, loading (static `surface2` blocks), error, offline. Copy: plain, warm, sentence case, no exclamation marks, no nagging; say what happened and what still works. An unreadable file says only "This file can't be opened. It may be damaged or encrypted."

**Onboarding.** One book on a plank at y 34%, width 100, changing pose per step; four hairline step segments on top, current in accent. Steps: wordmark + display line; Read, Listen, Remember as three caps lines while the book turns 90→24→0; optional Fish and OpenRouter keys (link "Skip for now"); privacy in three sentences; ColorOS battery steps on OPPO/OnePlus/realme only.

**Home.** Caps date; display greeting left; current book (width 100) on a plank right, bone and accent ribbons with numerals ("41% listened"). Resume block: caps "LISTENING · AVA · 1.4×", title 22, author, caption, primary "Resume listening". Hairline rows: Previously on (only after ≥3 days away); Daily review ("12 due", "about 6 min", secondary "Start"); Generating (24 ring, "Binding narration 62%"); highlight slip on `page`. Empty: bare plank, "Add your first book", primary "Add a book".

**Library.** Caps "LIBRARY · 34"; icon keys Search, Shelf/List, Add. Sort link opens a sheet that also holds filters. Spines on planks every 196, height 150, thickness 6/9/12% of cover width. List rows 72; the toggle morphs spines into rows. Empty: plank + primary "Add books".

**Book hub.** Book 128×192 left, yaw 24, ribbons below; right: headline, author, caps "NON-FICTION · 312 PAGES". Primary for the last mode ("Continue reading"/"Resume listening"), secondary for the other. Narration row: ring + "62% bound · Ava" or "No narration yet" + link "Set up". Learn is a contents page: Ask, Recap, Concepts, People, Map, Notes with dotted leaders and right captions ("up to Ch 7").

**Narration setup.** Caps sections: Type (link to change), Voice (rows with 48 preview key reading this book), Style (radio rows), Reading (switches: footnotes, tables, citations, front/back matter), Lexicon (sheet). Cost slip on `page`, numerals right: characters, cost ("$0.00 until 30 Nov"), time, storage. Primary "Bind narration" morphs into the chapter-arc ring. Fish limit hit: "Today's Fish limit is used up. The system voice is ready now, or Fish continues tomorrow." + secondary "Use system voice".

**Settings.** Caps groups of rows. Providers: "Connected" + positive dot, or "Not set". Budgets as numerals ("18 requests left today"). Accent: 24×40 spines on a mini plank, chosen one lifted.

**Reader.** `page`, margins 26; running head caps 10.5 +0.16em and folio numeral 12 at top 24. Chrome hidden; centre tap toggles. Top bar 56: back, title 16, Aa, Search, link "Learn". Bottom: track with 1×6 chapter ticks, accent fill, 2×10 frontier mark; caption "41% through · 18 min left in Book II"; segmented Read / Read + Listen / Listen. Selection strip: `key` r4 h48, words Highlight, Explain, Ask, Card, Define split by 1 rules, no icons. A 14×56 silk bookmark hangs top-right.
Read + Listen: sentence band `readAlong` α .12; transport 72: narrator caption, −15, 56 play key (`key` circle, r8 square while playing), +30, speed key 64×48. Manual scroll detaches: link "Return" and secondary "Play from here" rise.

**Now-bar.** 64 tall, 8 above tab bar, 12 inset, `surface1` r4 with lip; 32×48 book stands on it, overhanging 8; title 15, caption, 48 play key, 1 accent progress along the bottom. Up opens the player, down dismisses.
**Player.** Caps "LISTENING · AVA"; book 220 wide, yaw 0, on a plank at y 30%; headline, author. Scrubber: 48 touch, 2 track, accent fill, ticks, 4×20 `key` thumb, numerals both ends. Transport −15, 72 play, +30. Secondary keys: speed, Sleep, Bookmark, Chapters.

**Ask.** Caps "ASK · MEDITATIONS"; fence row "Up to Book II · §1" + "Change" ("Whole book" for non-fiction). No bubbles: questions `ui` 15 `text2` right-aligned; answers as book prose with accent superscript numerals jumping into the book. Suggestions: italic rows. Composer: input 52 + 48 send `key`. Working: "Reading Book II…" over a growing accent rule. No key: "Add an OpenRouter key in Settings to ask questions." Budget out: "No requests left today. Notes and cards still work."
**Recap.** Per chapter: caps "BOOK I · 6 DAYS AGO", title, prose collapsed to 3 lines + "More", citations; secondary "Read aloud".
**Concepts / People.** Back-of-book index: letter heads display 28, 56 rows ("first in Ch 2").
**Map.** Concepts are paper slips (r2, ui 13/500), people cloth tabs, third kind `secondary`; edges hairline, selected path accent.

**Sheets.** Explain: quoted sentence italic with `pencil` sideline, prose answer, secondary "Make a card", link "Ask more". Define: word display 28, phonetics caption, numbered senses. Card: front/back inputs, live index-card preview, primary "Save card". Check-in: paper slip rising over the page, prompt Literata 17, answer input, primary "Reveal", link "Skip" always visible; rubric lines check 60 ms apart.

**Review.** Caps "REVIEW · 12 LEFT" + hairline progress. Index card on `page`, r2, accent rule 24 from top, ruled hairlines, two paper edges offset 4 and 8. Tap flips. Four secondary grade keys with 2 dp top edges (Again `negative`, Hard `warning`, Good `positive`, Easy `accent`) showing intervals. Swipe left Again, right Good, up Easy. Done: seven 10 dp week squares, today fills, "That's today's review. More cards come due tomorrow." Empty: "Nothing is due today."

**Notebook.** Segmented Highlights / Cards / Words; groups by book (12×40 spine, title, count). Highlights are slips: prose 16/24, 2 dp `pencil` sideline, location caption, 16 apart. Link "Export" opens a sheet. Empty: "Highlights you make while reading land here."

## 7. Never

Glow blobs, background gradients, glass/blur, pill-chip rows, uniform rounded cards, shadows, FABs, filled accent buttons, emoji, exclamation marks, confetti, looping animation, shimmer, unstyled Material defaults, copyright/DRM messaging, streaks or guilt.

## 8. Review checklist

1. No `Color(0x` in feature code; no elevation.
2. Targets ≥48; spacing on scale.
3. Type per §2; tabular numerals.
4. Accent per §3; one primary key per screen.
5. Transformations interruptible, back-scrubbed, P95 frame <8.3 ms at 120 Hz.
6. Idle screens still; haptics per §5; reduced motion per §4.
7. Screenshots: dark and light, every preset, 1.0 and 1.5 font scale; contrast passes; no clipped titles.
8. Every state present with §6 tone.
9. Beside DirC, it reads as the same object world, not a dark-card app.
