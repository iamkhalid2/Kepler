# Kepler — Master Plan

*Read. Listen. Remember.* · Plan v1, written 2026-10-08.

**Sources of truth:**
- [`decisions.md`](decisions.md): decisions #1–#95, binding.
- Detailed sections:
  - [`plan/architecture.md`](plan/architecture.md): modules, stack, data, subsystems, build loop.
  - [`plan/pipelines.md`](plan/pipelines.md): ingest, normalization, audio generation, AI and internalization.
  - [`plan/experience.md`](plan/experience.md): IA, design tokens, motion, haptics, widgets, brand.
- Research: [`research/`](research/), 8 reports.
- Clickable prototype: **https://claude.ai/artifact/MwAQh5KMfsi1W8DkPURbnK** (a private Artifact; source in [`design/prototype/`](design/prototype/)). Logo SVGs are in [`design/brand/`](design/brand/).

---

## 1. Executive summary

**What:** Kepler is an open-source (GPL-3.0) Android app that helps people **internalize** books. It does three jobs that no current app does together:
1. **Read.** A first-class ebook reader: EPUB and PDF, beautiful typography, highlights, lookups, search, and a Book Map for non-fiction structure.
2. **Listen.** Any EPUB becomes a detailed, customizable audiobook through Fish Audio (S2.1 Pro). The listener controls narration style, voices, full cast for fiction, an optional quote voice for non-fiction, and how notes, tables and figures are handled. Playback is pro-level. Imported audiobooks play too.
3. **Remember.** A study loop built on the evidence:
   - recall prompts before, during and after chapters;
   - flashcards where the AI suggests the question and *you* answer, scheduled with FSRS-6;
   - a fenced Ask, Recap, People/Concepts and Map that never spoils past your position;
   - all of it working while you listen, too.

**How:**
- Native Kotlin with Jetpack Compose (Material 3 Expressive, Revolut-level motion and haptics).
- Media3 for playback; Readium 3.4 and androidx.pdf for reading.
- Room 3 with FTS5; WorkManager; Ktor clients for Fish and OpenRouter; FsrsKt; and on-device EmbeddingGemma for retrieval.
- Everything runs on the device. The user brings their own keys. The single build is FOSS with no proprietary SDKs.
- Pure-Kotlin core modules keep a later iOS port possible through Kotlin Multiplatform.

**The key bets:**
1. **The Text Spine.** Sentence IDs are the one coordinate system. That makes read/listen sync, annotations, the AI fence and cards robust to regeneration and re-pagination.
2. **Our own ReadAlongPlayer** instead of Readium's TTS navigator. One MediaSession plays Fish, system TTS and imported audio identically, with real speed, skip-silence, EQ and Auto support.
3. **One cached chapter pack per chapter.** Recap, takeaways, cards, X-Ray, the map, check-ins and pronunciation all come from a single LLM call per chapter, so the AI fits within free-tier budgets.
4. **Learning integrity over AI flash.** The AI asks before it tells. Progress counts recall, not pages.

**Hard date:** Fish's free model (`s2.1-pro-free`) ends **2026-11-30**. After that, generation costs $15 per 1M bytes, about $8–9 per novel. The roadmap makes sure real generation is built and tested on the device well before then.

---

## 2. Release scope

| | **0.1 "Reader + Voice"** (preview) | **0.2 "Remember"** (preview) | **1.0** (public) | **1.x** | **Later** |
|---|---|---|---|---|---|
| Reader | EPUB reader, library, typography/themes, TOC, Book Map, footnote popups, search, highlights/notes/bookmarks, lookups (WordNet, Wikipedia) | Selection AI (Explain, Ask, Card) | PDF (androidx.pdf), TXT/MD/HTML import, library-wide FTS, print page numbers | MOBI/AZW3, CBZ, OPDS catalogs, e-ink mode, page curl | FB2, DOCX, CBR, DjVu, LCP |
| Listen | system TTS + **Fish generation** (narrator), ReadAlongPlayer, Read+Listen and Listen modes, pro player, sleep timer, headset buttons, Android Auto, cost/ETA, background generation | quote voice, lexicon editor | **full cast + emotion intensity**, narrator A/B, bedtime mode, series memory, M4B/M4A export, imported audiobooks + Media Overlays import, widgets polish | MP3 export, other TTS providers, Kokoro on-device, cloud STT alignment for imports, hot-swap from system TTS to Fish | Media Overlays export, ambience/SFX, voice cloning |
| Remember | — | **chapter packs, fence, Recap, Ask (FTS), Takeaways, People/Concepts, Map, pre-questions, check-ins, chapter recall, cards + FSRS review, vocabulary, Notebook, exports (Markdown/Obsidian, JSON, Readwise, Anki), Review widget, weekly goal** | embeddings (EmbeddingGemma) for hybrid Ask, table/figure descriptions, predict-your-score stats | teach-it-back, audio review playlist, apply-it, book synthesis, cross-book graph | photo page jump, dialogue navigation, shareable presets |
| Platform | onboarding, keys, ColorOS guide, backup file | stats | accessibility and performance passes, baseline profiles, F-Droid metadata, signed releases | WebDAV, kosync, Hardcover | Wear OS, Chromecast, iOS (KMP) |

**0.1 is daily-usable** (#7): you can read any DRM-free EPUB and listen to it in Fish's voices with synced highlighting.

---

## 3. Roadmap

Each phase ends with a **user checkpoint**, a short demo with screenshots or recordings from the Find X9. Phases are ordered by dependency and by the Fish deadline. The AI executes them, so there are no calendar estimates apart from the Fish date.

### Phase 0: Foundation
- **Goal:** a repository and build loop that are pleasant for an agent, with the app running on the Find X9.
- **Scope:**
  - `git init`, GPL-3.0 `LICENSE`, `README`, `.gitignore` (with **`local.properties`**).
  - `build-logic` convention plugins and a version catalog (versions from architecture.md §2).
  - Module skeletons, `:core:designsystem` tokens (colors, type, shape, motion, haptics), and the bundled fonts (Geist, Literata).
  - `:app` with Nav3, three empty tabs, and the brand icon (adaptive and themed).
  - Roborazzi set up, CI workflows, and debug `BuildConfig` key injection.
- **Acceptance:**
  - `./gradlew spotlessCheck lintDebug testDebugUnitTest verifyRoborazziDebug assembleDebug` passes locally and in CI.
  - `installDebug` on the Find X9 works; a screenshot shows the splash, the icon, and Home with tokens applied.
  - Release-build check: no keys appear in the APK.
- **Checkpoint:** you see Kepler on your phone.

### Phase 1: Design prototype & brand ✅ (delivered with this plan)
- Clickable prototype of the key flows and motion, plus 3 logo concepts (A recommended): https://claude.ai/artifact/MwAQh5KMfsi1W8DkPURbnK. Source and SVGs are in [`design/`](design/).
- **Checkpoint:** you approve or redirect the visual direction and the logo **before** Phase 2's UI work. Your feedback updates `plan/experience.md`.

### Phase 2: Reader core
- **Spikes first:**
  - **S1:** inject sentence spans through Readium's streamer transform.
  - **S2:** map a Locator to a sentence ordinal and back; check accuracy across 5 fixture books.
- **Scope:**
  - Import through SAF, and the encrypted-file check (neutral error only).
  - Ingest: structure, blocks, Text Spine, FTS5. Profile heuristics.
  - Library: grid/list, filters, collections, series, covers, metadata editing.
  - Book hub skeleton.
  - Reader:
    - Readium Fragment inside `AndroidFragment`, behind `ReaderEngine`.
    - Themes and typography (per-theme settings, publisher-style override, font import).
    - Tap zones, volume keys, brightness swipe, slide or no page turn, scroll mode.
    - TOC, progress with ticks, time left, back/forward, footnote popups, Book Map, in-book search.
    - Highlights, notes and bookmarks anchored to sentences.
    - Lookups: `PROCESS_TEXT` both ways, WordNet, Wikipedia.
  - Positions, consumed intervals and read sessions. Backup file.
- **Acceptance:**
  - 5 fixture EPUBs open in ≤ 800 ms.
  - After app restart, font change and theme change, the position restores to the exact sentence and highlights redraw in place.
  - An encrypted fixture shows only the neutral error. A font-obfuscated fixture opens normally.
  - Roborazzi covers every reader state.
- **Checkpoint:** you read a real book on the phone for a day.

### Phase 3: Listen platform (system TTS)
- **Scope:**
  - `PlaybackService` (MediaLibraryService) and `ReadAlongPlayer` (SimpleBasePlayer over ExoPlayer), with sources and silence gaps.
  - System TTS rendered per sentence.
  - SyncMap. Read + Listen (follow highlight, auto page turn, Return and Play-from-here) and Listen (karaoke transcript).
  - Now-bar with the morph into the full player.
  - Speed 0.5–3.5×, skip silence, boost, EQ, sleep timer (fade, shake, end of chapter), smart rewind, undo seek, bookmarks.
  - Headset buttons with opt-in highlight remap. Android Auto browse tree. Notification and lock-screen commands.
- **Acceptance:**
  - 30 minutes of screen-off listening with no stalls.
  - Highlight drift ≤ 250 ms.
  - Auto browse tree checked with the Desktop Head Unit (`android sdk install extras/google/auto` or similar) or in a car.
  - A recording of the morph is reviewed.
- **Checkpoint:** you listen to a book with system TTS.

### Phase 4: Fish generation — must ship before **2026-11-15** (buffer before 11-30)
- **Scope:**
  - Fish client: SSE with timestamps, model policy with a guaranteed explicit header, backoff, licensed voices, voice design.
  - Normalization rules, citation/notes/tables/figures/code options (without the LLM: mentions and alt text), lexicon (manual).
  - ScriptIR and the Fish S2 renderer. Micro-segment first-audio, and the just-in-time window in PlaybackService.
  - Background `GenerationWorker` (dataSync FGS, checkpoints, onTimeout handling) and the ColorOS keep-alive guide.
  - Decode → trim → gain → AAC 64k pipeline. The aligner, which builds the SyncMap from timestamps.
  - Takes and regeneration. Setup screen with voice picker, styles, reading options, and the **cost/ETA card and caps**.
- **Acceptance:**
  - On the Find X9, a full chapter of a fixture non-fiction book generates with first audio in ≤ 3 s.
  - Sentence highlight drift ≤ 250 ms.
  - Killing the app mid-book resumes with no duplicate requests (verified from logs).
  - A unit test proves every Fish request carries a model header. An after-deadline test switches to `s2.1-pro` and shows the alert.
- **→ Release 0.1** as a GitHub pre-release APK.
- **Checkpoint:** you listen to your own non-fiction book in a Fish voice.

### Phase 5: AI foundation & Learn
- **Scope:**
  - OpenRouter client: key and tier probe, free-model discovery and ranking, vision model selection.
  - `RequestBudgeter` with a visible counter. Prompt registry, schema validation and repair. `ai_artifact` cache.
  - Book profile. **Chapter packs** (fiction and non-fiction schemas) with prefetch one chapter ahead.
  - Fence service (intervals, jump suppression, modes, strict mode).
  - Explain/Define (WordNet → LLM), Ask with FTS-only retrieval and citations, Recap, Takeaways, People/Concepts (X-Ray), single-book Map.
  - LLM table summaries and figure descriptions. Pronunciation candidates fed into the lexicon editor.
- **Acceptance:**
  - Recorded-response tests pass.
  - The **planted spoiler marker never reaches a prompt**, across every service.
  - A whole-book run on the 50/day profile stays within budget with no failures.
  - Ask answers carry valid citations, and tapping one jumps to the passage.
- **Checkpoint:** you ask your book questions and get a recap after a break.

### Phase 6: Remember (study loop) & Notebook
- **Scope:**
  - Pre-questions and section check-ins, spoken in Listen mode.
  - Chapter recall with predict-your-score and a rubric checklist.
  - Card suggestion → user answer → LLM comparison → accept, with a daily new-card cap.
  - FSRS-6 (FsrsKt) behind `Scheduler`, with test vectors. Mixed daily review UI. Vocabulary builder.
  - Notebook tab (highlights, cards, vocabulary, maps). Exports: Markdown/Obsidian (SAF folder), W3C JSON, Readwise, AnkiDroid + CSV.
  - Review Due and Now Playing widgets. Daily notification (opt-in). Weekly goal with freezes. Unified stats.
- **Acceptance:**
  - FSRS vectors pass.
  - An end-to-end test of a review session runs through all 4 grades.
  - Export files validate: Obsidian opens them, Readwise accepts the API calls in a live smoke test, AnkiDroid receives notes.
  - Widget screenshots cover every state.
- **→ Release 0.2.**
- **Checkpoint:** you run the loop for a week.

### Phase 7: Performance & depth
- **Scope:**
  - **Full cast:** characters and aliases, quote detection, attribution, low-confidence review queue, auto-casting, Cast Review UI, multi-speaker rendering.
  - Emotion tags with the intensity slider. Quote voice for non-fiction. Series memory.
  - Narrator A/B audition and hot-swap. Bedtime mode. Lexicon editor with in-context preview.
- **Acceptance:**
  - Attribution is ≥ 90% correct on a hand-labelled chapter of *Pride and Prejudice*.
  - Re-casting one character regenerates only that character's lines.
  - A/B playback switches with no gap.
- **Checkpoint:** a full-cast fiction chapter, and a non-fiction chapter with a quote voice.

### Phase 8: Formats, export, imports & 1.0
- **Scope:**
  - PDF reader with text reconstruction. TXT/MD/HTML → EPUB conversion.
  - M4B export with Nero chapters, cover and tags (`Mp4ChapterWriter`), plus M4A per chapter.
  - Imported M4B/MP3 playback, chapter mapping and Media Overlays EPUB import.
  - EmbeddingGemma 2 download and hybrid retrieval (RRF).
  - Accessibility pass. Baseline profiles and performance budgets. Library-wide search. Print page numbers.
  - F-Droid metadata. Signed release pipeline. Licenses screen.
- **Acceptance:**
  - Every performance budget in architecture.md §8 is met on the Find X9.
  - TalkBack walk-through of the main flows.
  - An exported M4B shows its chapters in VLC and in Voice (an Android player).
  - `zipalign -P 16` passes.
- **→ Release 1.0** on GitHub, with F-Droid submission.
- **Checkpoint:** a public launch decision, including a final name and trademark check before the Play Store (#49).

### After 1.0
The 1.x backlog, in order: MOBI/AZW3 + CBZ → OPDS → WebDAV + kosync → teach-it-back → audio review → cross-book graph → MP3 export → e-ink mode → other TTS adapters (ElevenLabs, Gemini) → Kokoro on-device → cloud-STT alignment for imports → apply-it and synthesis.

---

## 4. Decision coverage
| Decisions | Where |
|---|---|
| #1–#7 product, #47 process | §1–§3 here |
| #8–#12 engineering, #45–#48 privacy and keys | architecture §1–§2, §5.10, §6, §7 · Phase 0 |
| #13–#17 providers and cost, #25 LLM | architecture §5.3, §5.6 · pipelines §3, §4 · Phases 4–5 |
| #18–#23 generation | pipelines §3 · architecture §5.4–§5.5 · Phase 4 |
| #24, #26–#30 voices, casting, lexicon | pipelines §3.4, §4.5, §2.8 · Phases 4, 7 |
| #31 + non-fiction rules | pipelines §1, §2 · Phases 2, 4, 5 |
| #32 ambience | Later |
| #33–#36 player, read-along, integrations, stats | architecture §5.2 · experience §5–§6 · Phases 3, 6 |
| #37–#40 import, user content, export, accounts | pipelines §1, §5 · Phases 2, 8 |
| #41a–#41j "and then some" | pipelines §4.4, §3.4 · Phases 5–7 · Later |
| #42–#44 design and brand | experience · Phase 1 |
| #49–#50 name and app ID | §3 Phase 8 checkpoint · Phase 0 |
| #51–#66 reader core | architecture §5.1, §5.9 · pipelines §1 · Phases 2, 8, 1.x |
| #67–#76 unified read and listen | architecture §4.1, §5.2, §5.5, §5.7 · pipelines §4.7, §5.1 · Phases 2–3, 8 |
| #77–#94 internalization | pipelines §4 · architecture §5.6–§5.8 · Phases 5–6, 1.x |
| #95 IA | experience §2, §4 · Phases 0, 2 |

---

## 5. Risk register
| # | Risk | L | I | Mitigation |
|---|---|---|---|---|
| R1 | **Fish free tier ends on 2026-11-30**, or changes earlier. Its terms are unclear for distributed apps. | High | Med | Phase 4 ships by 11-15. `ModelPolicy` with a guaranteed header. Cost/ETA plus caps. The provider interface is ready for other vendors. Users bring their own keys, so there is no shared key and no ToS problem from key sharing. |
| R2 | **OpenRouter free roster churn and quality.** Models disappear and JSON output is unreliable. | High | Med | Live discovery and ranking, a bundled hints list, schema validation with repair, graceful "AI unavailable" states. The user can pick a paid model. Cached results survive model churn. |
| R3 | **ColorOS kills background work** on the Find X9. | High | Med | Per-segment checkpoints, the guided keep-alive sheet, and just-in-time generation inside the playback foreground service. Tested on the device early (Phase 4). |
| R4 | **Android 15/16/17 limits:** the 6 h dataSync budget, the job quota while an FGS runs, background-audio hardening; also whether Play accepts generation under `mediaPlayback`. | Med | Med | WorkManager plus `onTimeout` checkpoints, and resume on app foreground. Re-check Play policy before any Play release; v1 ships on GitHub/F-Droid. |
| R5 | **Readium Fragment navigator in Compose:** interop rough edges, and whether span injection (S1) is feasible. | Med | Med | Spikes S1/S2 come first in Phase 2. `ReaderEngine` abstraction. Decorator fallback. |
| R6 | **Fish timestamp alignment is fuzzy** (normalized text) or missing for some models. | Med | Med | Token-level Needleman–Wunsch with the normalization map, confidence scoring, a proportional fallback, and sentence-level sync as the floor. |
| R7 | **Material 3 1.5.0-beta API churn.** | Med | Low | Pin versions. Design-system wrappers isolate M3E usage. Fallback to 1.4.0 plus our own tokens. |
| R8 | **M4B chapter writing** (no Android muxer support). | Med | Low | A pure-Kotlin `Mp4ChapterWriter` with round-trip tests. Players validated in Phase 8. FFmpeg is not needed. |
| R9 | **Attribution accuracy** with free LLMs. | Med | Med | Deterministic quote detection first. A confidence threshold sends lines to the review queue. User fixes are kept. Full cast is opt-in, and narrator mode is the default. |
| R10 | **Spoiler leakage** through the model's prior knowledge of famous books. | Med | Low | Fence at retrieval time, planted-marker tests, and the opt-in strict mode that strips title and author. |
| R11 | **Name collision / weak trademark** (BetterReads, bookkeeping "Better Books"). | Med | Med | Working name for GitHub. Trademark check plus a decision before the Play Store (#49). The app ID can change until the first Play upload. |
| R12 | **Scope size.** | High | Med | Strict phase gates. 0.1 is daily-usable early. The 1.x backlog is explicit, and nothing moves into a phase without updating `decisions.md`. |
| R13 | **Build speed for the agent loop** (about 30 modules). | Med | Low | Configuration cache, build cache, parallel builds, module-scoped tasks (`:feature:reader:testDebugUnitTest`), Roborazzi per module. |
| R14 | **LiteRT-LM / EmbeddingGemma** fails on the Dimensity 9500 or is slow. | Low | Low | FTS-only fallback. The model download is optional. A device test in Phase 8. |

---

## 6. Open questions for you (each has a working default; nothing is blocked)
1. **Logo:** A (Seed b), B (Folio wave) or C (Ribbon play)? *Default: A.* Reply with a letter or a change.
2. **Domain and GitHub org:** register `kepler.dev` and create an org such as `kepler-app`? Only you can do this. *Default: the repo stays local until you say where to push.*
3. **After 2026-11-30:** should I keep exercising generation on your paid Fish key during development, within a monthly dev cap of, say, $10? *Default: yes, $10 cap.*
4. **Prototype feedback:** anything in the motion or visual direction to change before Phase 2's UI work?
