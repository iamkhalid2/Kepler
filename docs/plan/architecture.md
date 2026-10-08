# BetterBooks: Architecture & Tech Stack

> Part of the master plan, [`../PLAN.md`](../PLAN.md). Binding inputs: [`../decisions.md`](../decisions.md) (#1–#95) and the reports in [`../research/`](../research/). Library versions were checked against Google Maven and Maven Central on **2026-10-08**. Re-check them at Phase 0, and pin them exactly in `gradle/libs.versions.toml`.

---

## 1. Guiding constraints

| Constraint | Consequence |
|---|---|
| The AI writes 100% of the code, with no Android Studio (#8, #8b) | Prefer compile-time safety: Kotlin with strict types, generated DI and Room. Every UI state gets a screenshot test (Roborazzi), so the agent can *look* at its work. Every subsystem must be testable on the JVM with no device. Build and deploy run entirely from the terminal. |
| Platform-independent core, so iOS could follow later (#5) | Domain, Text Spine, AI, SRS, TTS and LLM provider APIs live in **pure-Kotlin modules** (`kotlin("jvm")` today, convertible to KMP later) with no `android.*` imports. Android-only code stays in `engine:*` and `feature:*` modules. |
| One FOSS build (#66) | No Google Play Services, Firebase, ML Kit, Crashlytics or proprietary SDKs. Every dependency must be GPL-3.0-compatible: Apache-2.0, BSD, MIT, LGPL and GPL are fine. **No AGPL**, so no MuPDF. |
| Positions must survive anything (#67) | The **Text Spine** (sentence IDs) is the single coordinate system. Everything else is a derived view. |
| A single audio pipeline (#68) | One Media3 `MediaSession` and one custom `ReadAlongPlayer`. Fish, system TTS and imported audio are all just *sources*. |
| Tight LLM budgets (#25, #78, #94) | All LLM traffic goes through one `RequestBudgeter`. Results are cached forever and keyed by content plus prompt version. |

---

## 2. Toolchain & verified versions

| Tool / library | Version | Notes |
|---|---|---|
| JDK | 21 (Homebrew `openjdk@21`) | `JAVA_HOME=/opt/homebrew/opt/openjdk@21`. Gradle toolchain is set to 21. |
| Gradle | **9.8.1** | Wrapper committed. Configuration cache and build cache **on**. |
| Android Gradle Plugin | **9.4.1** | androidx.pdf with Compose needs AGP ≥ 9.2. |
| Kotlin | **2.4.20** (K2) | Readium 3.4.0 is built against 2.4.20. |
| KSP | **2.3.12** | Used for Room and Hilt. |
| Android CLI | 1.0 (`android`) | SDK installs, `android run`, `android screen`, `android layout`, `android docs`. |
| compileSdk / targetSdk / minSdk | **37 / 37 / 28** | |
| Compose BOM | **2026.09.00** | |
| Material 3 | **1.5.0-beta01** (pinned outside the BOM) | Needed for the Expressive APIs (MaterialShapes, motion scheme, new components). If a beta API breaks, the fallback is stable 1.4.0 plus our own shape and motion tokens (risk R7). |
| androidx.graphics:graphics-shapes | **1.1.0** | `RoundedPolygon` and `Morph` for shape morphs. |
| Navigation 3 | **1.2.0** | Back stack held as state, which suits shared-element transitions and predictive back. |
| Activity / Lifecycle / Core | 1.13.0 / 2.11.0 / 1.19.1 | Plus core-splashscreen 1.2.0. |
| Hilt | **2.60.1** (+ androidx.hilt 1.4.0 for WorkManager) | Used only in `:app` and Android modules. Pure-Kotlin modules use plain constructor injection with `javax.inject.Inject`. The KMP migration path is Metro (1.4.5). |
| Media3 | **1.11.1** | exoplayer, session, transformer and common. `SilenceSkippingAudioProcessor` was fixed in 1.11.1. Session getters must be called on the app looper. |
| Readium Kotlin Toolkit | **3.4.0** | shared, streamer and navigator (the Fragment EPUB navigator); opds is added in v1.x. Licensed BSD-3. |
| androidx.fragment:fragment-compose | **1.9.1** | `AndroidFragment {}` hosts `EpubNavigatorFragment`. |
| androidx.pdf | **1.0.0-beta01** | pdf-viewer-fragment and pdf-compose (experimental), plus `PdfDocument` for text. Apache-2.0. |
| Room | **3.0.3** (`androidx.room3`) | Kotlin Multiplatform, built on the androidx.sqlite driver. |
| androidx.sqlite:sqlite-bundled | **2.7.1** (align with Room 3.0.3) | Bundled SQLite, which compiles in **FTS5**. Room annotations stop at FTS4, so FTS5 tables are created with raw SQL in a migration callback. |
| DataStore | **1.2.1** | Settings. |
| WorkManager | **2.12.0** | Generation, indexing and prefetch. |
| Glance | **1.2.0** | Home-screen widgets. |
| Ktor client | **3.6.0** (OkHttp engine) | Used by both the Fish and OpenRouter clients. SSE plugin. `MockEngine` in tests. |
| kotlinx.serialization / coroutines | **1.11.0 / 1.11.0** | |
| Coil | **3.6.3** | Covers. |
| MaterialKolor | **5.0.1** | Builds a Material color scheme from a seed color, for theming from cover art. |
| androidx.palette | 1.0.0 | Extracts the seed color from a cover. |
| LiteRT-LM | **0.18.0** | Runs the optional EmbeddingGemma 2 (270M). |
| FsrsKt | **1.0.1** (`one.felsen.fsrskt:fsrskt`) | FSRS-6, GPL-3.0, Kotlin Multiplatform. |
| commonmark-java | **0.30.0** | Converts Markdown to EPUB at import. |
| jsoup | **1.23.2** | XHTML clean-up, HTML import and block extraction helpers. |
| jump3r (LAME port, LGPL) | 1.0.5 | Candidate for v1.x MP3 export. Pure Java, so no NDK. Check its licence file at the time. |
| Testing | JUnit 4.13.2 + kotlin.test, Robolectric **4.17**, Roborazzi **1.76.0**, Turbine **1.2.1**, kotlinx-coroutines-test | |
| Formatting / lint | Spotless **8.10.3** + ktlint, Android Lint | **detekt is deferred:** 1.23.8 predates Kotlin 2.4 and 2.0 is still alpha. Revisit when 2.0 is stable. |

Gradle hygiene: put convention plugins in `build-logic/` (`bb.android.application`, `bb.android.library`, `bb.android.compose`, `bb.jvm.library`, `bb.room`, `bb.hilt`, `bb.test`). Use a version catalog only, with no hard-coded versions. `org.gradle.configuration-cache=true`, `org.gradle.caching=true`, `org.gradle.parallel=true`, and Kotlin daemon JVM args set for 24 GB of RAM.

---

## 3. Module graph

```
:app                         Activity, Nav3 graph, Hilt root, app-level theming, deep links
│
├─ feature/                   (Android, Compose; each depends on core + engines through interfaces)
│   ├─ :feature:onboarding    welcome, keys, privacy note, ColorOS battery guide
│   ├─ :feature:home          resume card, Previously on…, review due, generation status
│   ├─ :feature:library       grid/list, filters, collections, series, import
│   ├─ :feature:bookhub       book page: Read · Listen · Learn, setup entry
│   ├─ :feature:setup         content type, narration style, voices/cast, reading options, cost/ETA
│   ├─ :feature:reader        reader screen (Read / Read+Listen / Listen), typography sheet, selection menu, search, TOC, Book Map
│   ├─ :feature:player        now-bar, full player, speed, sleep timer, chapters, audition A/B
│   ├─ :feature:learn         Ask, Recap, People/Concepts cards, Map, Takeaways, check-ins, recall
│   ├─ :feature:notebook      highlights, notes, cards, vocabulary, daily review, exports
│   └─ :feature:settings      providers/models, budgets, appearance, haptics, backup, about
│
├─ engine/                    (domain engines; the JVM ones are KMP-ready)
│   ├─ :engine:epub           Android: Readium open/iterate, EpubReaderEngine, decorations, search
│   ├─ :engine:pdf            Android: androidx.pdf PdfReaderEngine + text reconstruction
│   ├─ :engine:convert        JVM: TXT/MD/HTML → EPUB3 builder (MOBI/CBZ in v1.x)
│   ├─ :engine:ingest         Android: import orchestration → structure → Text Spine → FTS
│   ├─ :engine:tts-api        JVM: TtsProvider, capabilities, ScriptIR, renderers
│   ├─ :engine:tts-fish       JVM: Fish client (SSE timestamps, voices, voice design)
│   ├─ :engine:tts-system     Android: TextToSpeech provider (synthesizeToFile)
│   ├─ :engine:llm-api        JVM: LlmProvider, structured output, RequestBudgeter
│   ├─ :engine:llm-openrouter JVM: OpenRouter client, model discovery, key/limits probe
│   ├─ :engine:ai             JVM: prompts, chapter packs, fence, recap/ask/explain/cards/graph services
│   ├─ :engine:retrieval      Android: FTS5 queries, LiteRT-LM embeddings, RRF fusion
│   ├─ :engine:srs            JVM: Scheduler interface + FSRS (FsrsKt) implementation
│   ├─ :engine:audio          Android: decode/trim/gain/encode AAC, silence, M4B writer, export
│   ├─ :engine:generation     Android: orchestrator, segment cache, workers, cost/ETA
│   ├─ :engine:playback       Android: PlaybackService (MediaLibraryService), ReadAlongPlayer, SyncMap, effects, sleep timer
│   └─ :engine:sync           JVM + Android: Markdown/W3C JSON/Readwise/Anki export, backup/restore
│
├─ core/
│   ├─ :core:model            JVM: domain types (no Android)
│   ├─ :core:common           JVM: Result, dispatchers, Clock, hashing, logging facade, IDs
│   ├─ :core:spine            JVM: segmentation, ordinals, offset maps, fuzzy re-anchoring, alignment
│   ├─ :core:database         Android: Room 3 schema, DAOs, FTS5 migration callback
│   ├─ :core:datastore        Android: settings and encrypted secrets (Keystore AES-GCM)
│   ├─ :core:data             Android: repositories (DB + files + engines), the single source of truth for features
│   ├─ :core:designsystem     Android: tokens, theme, components, motion, haptics, shapes
│   ├─ :core:ui               Android: shared composables (cover, progress ring, sheets)
│   └─ :core:testing          fixtures (public-domain EPUBs), fakes, recorded LLM/Fish responses
│
└─ :widget                    Glance widgets (Now Playing, Review Due, Continue Reading)
```

**Dependency rules**, enforced by a small Gradle check task in `build-logic`:
- `feature → core, engine` **interfaces only**. Implementations are bound in `:app` through Hilt.
- `engine:*-api` modules depend only on `:core:model` and `:core:common`.
- JVM modules must not depend on Android modules.
- No feature depends on another feature. Navigation between features uses route keys defined in `:core:model`.

---

## 4. Domain model & persistence (Room 3)

### 4.1 The Text Spine (#67)
At import, every book is flattened into **blocks**: paragraphs, headings, list items, quotes, tables, figures, code, math, notes and verse lines. Each block is split into **sentences** with `android.icu.text.BreakIterator` plus an abbreviation guard list ("Mr.", "Dr.", "e.g.", "St.", initials and so on).
- Every sentence gets a global, dense **ordinal** (0..N) and a stable ID of `bookId:ordinal`.
- The segmenter version is stored. If it ever changes, annotations are re-anchored using quote, prefix and suffix plus fuzzy search, following the Hypothesis method. Annotations that can't be re-anchored become orphans and are never deleted.

### 4.2 Tables (abridged; full DDL is written in Phase 2)
| Table | Key columns |
|---|---|
| `book` | id, title, authors, language, `contentType` (FICTION/NONFICTION/MIXED), format, filePath, originalPath, sha256, kosyncMd5, coverPath, seedColor, seriesId, seriesIndex, studyMode (OFF/LIGHT/STUDY), fenceMode, profileJson, spineVersion, addedAt, lastOpenedAt |
| `section` | id, bookId, parentId, ordinal, level, title, href, `kind` (FRONT/BODY/BACK/NOTES/INDEX…), startOrdinal, endOrdinal, included |
| `block` | id, bookId, sectionId, ordinal, `kind`, href, cssSelector, rawText, metaJson (table cells, figure alt and src, list index, quote attribution) |
| `sentence` | bookId, ordinal (PK pair), blockId, text, startInBlock, endInBlock |
| `sentence_fts` | FTS5 virtual table (external content = sentence) |
| `entity` / `fact` / `edge` | Knowledge graph: kind (PERSON/PLACE/CONCEPT/TERM/ORG), aliases, description, `firstMentionOrdinal`; facts and edges carry an `evidenceOrdinal` |
| `voice`, `cast_assignment` | provider voice catalog cache; speakerKey (NARRATOR / QUOTE / entityId) → voice + params |
| `script_line` | bookId, startOrdinal, endOrdinal, speakerKey, styleTags, intensity, confidence, editedByUser |
| `lexicon_entry` | scope (GLOBAL/SERIES/BOOK), scopeId, term, replacement, phoneme, caseSensitive |
| `audio_rendition` | id, bookId, kind (GENERATED/IMPORTED/SYSTEM), provider, model, castHash |
| `audio_segment` | id, renditionId, startOrdinal, endOrdinal, filePath, durationMs, cacheKey, take, chosen, status |
| `sync_row` | renditionId, ordinal, segmentId, startMs, endMs, wordTimings (BLOB), source (PROVIDER/SYSTEM/STT/SMIL), confidence |
| `generation_job` | id, bookId, renditionId, state, priority, done/total, costEstimate, costActual, lastError |
| `position` | bookId, kind (READING/LISTENING), ordinal, charOffset, locatorJson, updatedAt |
| `consumed_interval` | bookId, startOrdinal, endOrdinal, via (READ/LISTEN), at. Merged on write. |
| `annotation` | id, bookId, start/end ordinal and char, quote/prefix/suffix, locatorJson, color, style, noteMd, createdVia (READ/LISTEN/HEADSET/AI), orphaned |
| `bookmark` | id, bookId, ordinal, label, note |
| `card` | id, bookId, srcStart, srcEnd, fenceOrdinal, type (QA/CLOZE/VOCAB), front, back, userAnswer, FSRS state (stability, difficulty, due, lastReview, reps, lapses, state) |
| `review_log` | cardId, rating, reviewedAt, elapsedDays, scheduledDays |
| `vocab` | id, bookId, term, contextOrdinal, definition, source (WORDNET/LLM/WIKI), cardId |
| `ai_artifact` | id, bookId, kind, keyHash, fenceBucket, model, promptVersion, json, createdAt |
| `llm_usage` | day, provider, requests, tokensIn, tokensOut |
| `embedding` | bookId, chunkId, startOrdinal, endOrdinal, model, dims, vector (BLOB float16) |
| `read_session` | id, bookId, mode (READ/LISTEN/REVIEW), start, end, ordinalsConsumed, words |
| `series`, `collection`, `collection_book`, `tag`, `book_tag` | |

### 4.3 Files (app-specific storage)
```
files/books/<bookId>/original.<ext>      untouched import
files/books/<bookId>/book.epub           canonical EPUB (converted when needed)
files/books/<bookId>/cover.jpg
files/audio/<renditionId>/<segmentId>-t<take>.m4a   AAC-LC 64 kbps mono (#22)
files/models/embeddinggemma-2-270m.litertlm         optional, downloaded
cache/http/…  cache/stream/…
```
Exports always go out through SAF (`ACTION_CREATE_DOCUMENT` / tree URIs) or MediaStore (`IS_AUDIOBOOK`). The app never asks for broad storage permissions.

---

## 5. Subsystems

### 5.1 Reader engine (#51–#58, #70–#71)
```kotlin
interface ReaderEngine {
    val position: StateFlow<SpinePosition>            // first fully visible sentence
    val selection: SharedFlow<TextSelection>          // ordinal range + quote, for the selection menu
    suspend fun open(book: BookHandle, at: SpinePosition?)
    suspend fun goTo(target: SpinePosition, animated: Boolean = true)
    fun applyDecorations(group: String, decorations: List<SpineDecoration>) // highlights, read-along, search hits
    fun applyPreferences(prefs: ReaderPreferences)
    suspend fun search(query: SearchQuery): Flow<SearchHit>
}
```
- **EpubReaderEngine** wraps Readium `EpubNavigatorFragment` inside `AndroidFragment {}`.
  - **Spike S1:** inject `data-bb="<ordinal>"` spans per sentence through a streamer-level resource transform when the publication is opened. If it works, `firstVisibleElementLocator()` resolves directly to an ordinal, and read-along decorations become a CSS selector.
  - **Fallback:** Decorator with text-context Locators built from the `sentence` table.
- **PdfReaderEngine** wraps androidx.pdf. Its spine is built from `PdfDocument.getPageContent()`, then the header/footer stripper, de-hyphenation and paragraph merge. Each sentence maps to (page, char range). Highlights are a custom overlay drawn from selection bounds.
- **Footnote popups:** intercept internal link taps. If the target block is a note (by `epub:type` or a heuristic), show a bottom sheet rendered from the `block` table. Inner links still work.
- **Book Map:** the section hierarchy sized by sentence count, with consumed intervals drawn over it and highlights and cards as pins.

### 5.2 Playback platform (#33, #35, #68–#70, #74)
- **`PlaybackService : MediaLibraryService`**, `foregroundServiceType="mediaPlayback"`.
  - It owns one `MediaLibrarySession` around **`ReadAlongPlayer`**, a `SimpleBasePlayer` that delegates to an internal ExoPlayer.
  - The browse tree serves Android Auto: Continue → Recent → Library → Book → Chapters.
- **What the outside world sees:** Media3 items are **chapters**, so notifications, Auto and Bluetooth show familiar chapter metadata.
- **What ExoPlayer plays internally:** a playlist of **segments** (one per paragraph, or one per sentence for system TTS). Silence gaps between them are `SilenceMediaSource` items of deterministic length: sentence 0 ms (already in the audio), paragraph 450 ms, section 1,100 ms, chapter 2,000 ms. All of these can be tuned.
- **Sources** plug into the playlist builder: `GeneratedSource` (Fish segments and streaming), `SystemTtsSource` (per-sentence `synthesizeToFile`), `ImportedSource` (M4B/MP3 with chapter mapping or SMIL).
- **Effects:**
  - `PlaybackParameters(speed 0.5–3.5, pitch 1.0)`.
  - Skip silence.
  - Volume boost through `LoudnessEnhancer` on the audio session.
  - EQ through `android.media.audiofx.Equalizer` presets. Device support varies, so the UI hides it when unavailable.
- **SyncMap:** the playhead goes through a binary search over `sync_row` to an ordinal, published as `StateFlow<NowSpeaking(ordinal, wordIndex)>`.
  - The reader observes it to draw decorations and turn pages, with word-level turns throttled to 1 s.
  - A manual scroll detaches following. The bar then offers **Return** and **Play from here**, in the style of Readwise.
- **Sleep timer:** duration or end of chapter, with fade-out and shake-to-extend (accelerometer). Bedtime mode (#41i) also ramps speed and volume down slowly.
- **Smart rewind** rewinds to the start of the sentence, extending further after long pauses. **Undo seek** is a stack of the last 5 jumps.
- **Headset gestures:** in `MediaSession.Callback.onMediaButtonEvent`, opt-in remapping of double/triple tap to *highlight current sentence* (#74). The earcon is a `ToneGenerator` blip and the confirmation is a haptic.

### 5.3 TTS providers (#13–#16, #20, #28)
```kotlin
interface TtsProvider {
    val id: ProviderId
    val capabilities: TtsCapabilities   // streaming, timestamps(granularity), maxChars, markup dialect,
                                        // multiSpeaker(max), voiceCloning, voiceDesign, speedRange,
                                        // formats, languages, billingUnit, concurrency
    suspend fun voices(query: VoiceQuery): List<Voice>
    fun synthesize(request: SynthesisRequest): Flow<SynthesisEvent> // AudioChunk | Alignment | Done | Error
}
interface ScriptRenderer { fun render(ir: ScriptIR, caps: TtsCapabilities): RenderedText }
```
- **ScriptIR** is a neutral markup made of `Text`, `Pause(kind)`, `Style(tags, intensity)`, `Speaker(key)`, `Emphasis`, `Phoneme(term, arpabet)` and `Skip`. Each provider's renderer **removes** whatever that provider doesn't support. Renderers:
  - `FishS2Renderer`: `[cues]`, `<|speaker:N|>` and phoneme tags.
  - `FishS1Renderer`: parentheses.
  - `PlainRenderer`: for system TTS.
- **FishProvider** (Ktor):
  - Endpoint: `POST /v1/tts/stream/with-timestamp` (SSE). Audio is Opus at 48 kHz, decoded on the device and re-encoded to AAC 64k.
  - **The `model` header is always explicit.** `ModelPolicy` picks `s2.1-pro-free` until 2026-11-30 23:59 UTC, then `s2.1-pro`. It shows an in-app alert and a new cost estimate before the first paid request. A unit test asserts that no request can leave without a model header.
  - Concurrency: a semaphore of 5 by default, which the user can raise to 15 or 50 to match their Fish tier. It backs off exponentially with jitter on 429 and 503, and Fish sends no `Retry-After`.
  - `pronunciation_dictionary` inline from the lexicon (5,000 entries max). Low temperature (0.5) for voice consistency between requests.
  - Voices: `GET /model?licensed=true` for the library, plus voice design (`POST /v1/voice-design`).
- **SystemTtsProvider** uses Android `TextToSpeech`. Each sentence goes to its own WAV file through `synthesizeToFile`, so highlighting is sentence-accurate.
- **Ready for later adapters:** ElevenLabs, Gemini TTS, Azure, Cartesia, MiniMax and Kokoro (sherpa-onnx). Each is a new module implementing `TtsProvider` plus a renderer.

### 5.4 Generation orchestrator (#17–#23)
- **Units of work:**
  - A **segment** is one paragraph-sized rendered script, or one speaker run for multi-speaker text.
  - **Cache key** = `sha256(renderedText ‖ providerId ‖ model ‖ voiceIds ‖ paramsJson ‖ lexiconVersion ‖ rendererVersion)`. A cache hit means no request.
- **Two drivers:**
  1. **Just-in-time, inside PlaybackService:** keeps a window of about 10 minutes of audio generated ahead of the playhead. This is legitimate playback work, so it runs under the `mediaPlayback` foreground service (check against Play policy before a Play release; risk R4).
  2. **Background whole-book generation:** a WorkManager `CoroutineWorker` using `setForeground(ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC))`.
     - It checkpoints after every segment and handles `onStopped`, including the Android 15+ 6 h/24 h timeout. It reschedules with backoff and resumes when the app returns to the foreground.
     - Constraints: network connected; battery not low (the user can change this).
- **Pipeline per segment:**
  1. Render IR to provider text and stream the SSE.
  2. Assemble the Opus chunks and decode them to PCM (MediaCodec).
  3. Trim leading and trailing silence (−50 dBFS).
  4. Match gain toward −18 LUFS (a BS.1770 K-weighted estimate written in Kotlin).
  5. Encode AAC-LC at 64 kbps mono (MediaCodec + MediaMuxer) into an `.m4a`.
  6. Align the timestamp segments to sentence ordinals (§5.5) and write `sync_row`s.
- **Cost and time estimator:**
  - Cost = UTF-8 bytes of the rendered text × the provider rate. Fish is $0 on free and $15 per 1M bytes on paid.
  - Time is estimated from a measured throughput EWMA per provider and concurrency level.
  - Caps are per book and per month (#17). Generation **pauses** at a cap and asks the user; it never overspends silently.
- **Takes:** regenerating a paragraph adds a take. The user picks one, and the SyncMap rows for that range are swapped atomically.

### 5.5 Alignment
- Fish returns timestamp segments on *normalized* text: punctuation is stripped and apostrophes are dropped.
- **Aligner** (in `:core:spine`):
  1. Tokenize both sides with a case- and punctuation-insensitive, digit-aware tokenizer.
  2. Run Needleman–Wunsch on the tokens, with the normalization map as a hint, so "1984" matches "nineteen eighty-four".
  3. Project word times onto the source character ranges.
  4. Sentence boundaries are the first and last word of each sentence.
  5. Confidence is the fraction of tokens matched. Below 0.8, fall back to the segment start and end and spread time across characters in proportion.
- The same aligner is reused later to align imported audio after cloud speech-to-text (v1.x).

### 5.6 LLM layer (#25, #78, #94)
```kotlin
interface LlmProvider {
    suspend fun models(): List<LlmModel>   // contextLength, free, supportsJsonSchema, supportsImages
    suspend fun complete(req: LlmRequest): LlmResult   // messages, jsonSchema?, maxTokens, temperature, images
}
class RequestBudgeter(/* perMinute = 15 (safety under 20), perDay = 50 | 1000, priorities */)
```
- **OpenRouter client:**
  - `GET /api/v1/key` tells us the account tier (`is_free_tier` gives the 50/day or 1,000/day limit).
  - `GET /api/v1/models` is filtered to models free on both prompt and completion, with a context window of 64k or more. They are ranked by: supports `response_format`/structured outputs → context length → a bundled `model-hints.json` quality tier (updated each release) → recency.
  - A vision-capable free model is chosen separately, for figures.
  - Last resort is `openrouter/free`. The user can override with any model.
- **Budgeter priorities:** INTERACTIVE (ask, explain) > PREFETCH (next chapter pack) > BACKGROUND (table and figure descriptions, pronunciation). It shows "requests left today", paces background work to 1 request per 4 s or slower, and never spends the last 10% of the daily budget on background work.
- **Reliability:**
  - Every prompt is versioned (`prompt-id@v`) and every response is validated against a kotlinx.serialization schema. One automatic repair retry. If that fails too, the chapter is marked "AI unavailable" and the UI degrades gracefully.
  - **Prompts return IDs and labels, never rewritten book text.**
- **Caching:** `ai_artifact` is keyed by (kind, content hash, fence bucket, model, prompt version). Answers to Ask are cached by question hash plus fence.

### 5.7 Retrieval & fence (#73, #89, #90)
- **Fence service:** frontier = the highest ordinal in a contiguous run of `consumed_interval`, excluding jumps and back matter. The mode is set per book.
  - `fenceOrdinal()` feeds every AI query **before** any text reaches a prompt.
  - FTS: `WHERE ordinal <= :fence`.
  - Vectors: filtered before scoring.
  - Graph facts: `evidenceOrdinal <= :fence`.
- **Hybrid search:** FTS5 BM25 top 50 plus embedding cosine top 50, fused with Reciprocal Rank Fusion (k=60), then the top 8 chunks of about 300 tokens each, with sentence citations.
- **Embeddings:** chunks are ~256 tokens with a 1-sentence overlap. EmbeddingGemma 2 at 256 dimensions, stored as float16 BLOBs, and compared by brute-force cosine in Kotlin, which takes about 10 ms per book. Indexing runs as a WorkManager job while the phone is idle or charging.

### 5.8 SRS (#79–#82, #85)
`interface Scheduler { fun schedule(card: CardState, rating: Rating, now: Instant): CardState; fun preview(card): Map<Rating, Duration> }`. The implementation is FsrsKt with default FSRS-6 parameters and a desired retention of 0.9.
- **Test vectors** are ported from the py-fsrs/fsrs-rs reference tests.
- **Review queue:** due cards first (most overdue first), then new cards within the daily new-card cap. The queue mixes books.

### 5.9 Sync & export (#39, #40, #59, #62, #81)
- **Markdown/Obsidian:** one file per book, with YAML front matter and highlights in TOC order. Each highlight carries a note, a quote, and a deep link (`betterbooks://book/<id>/s/<ordinal>`). The file is written to a SAF tree URI.
- **W3C Web Annotation JSON:** `TextQuoteSelector` + `TextPositionSelector`, plus an EPUB CFI computed at export.
- **Readwise:** `POST /api/v2/highlights/` with `highlight_url` set to the deep link, so repeat sends don't duplicate.
- **AnkiDroid** instant-add API via a JitPack artifact, behind an optional `<queries>` entry; check the artifact's licence (LGPL). Also CSV/TSV export with Anki headers.
- **Backup:** a zip of a DB snapshot (via `VACUUM INTO`) plus settings, without audio by default (the user can opt in) and without secrets. Android Auto Backup covers the DB and settings.

### 5.10 Secrets & privacy (#45, #46, #48)
- User API keys are encrypted with an Android Keystore AES-256-GCM key, and the ciphertext lives in DataStore. Keys are never logged, and a redacting interceptor scrubs `Authorization` headers.
- **Development:** `local.properties` (`FISH_API_KEY`, `OPENROUTER_API_KEY`) is read by Gradle into `BuildConfig` fields **in the debug build type only**. The debug app pre-fills onboarding with them. Release builds never contain them, and a CI check fails if they appear in a release.
- `.gitignore` covers `local.properties`, `*.keystore` and `/captures`.
- No analytics. Crash reports are written to a local file the user can choose to share.

### 5.11 Background work & OEM realities (#10)
| Work | Mechanism | Limits |
|---|---|---|
| Playback plus just-in-time generation | `mediaPlayback` FGS (PlaybackService) | Android 17 background-audio hardening means MediaSessionService is required. |
| Whole-book generation | WorkManager plus `dataSync` FGS | Android 15+: 6 h per 24 h in the background, then `onTimeout` → checkpoint and stop. Android 16: job quota also applies while the app runs an FGS. |
| Embedding indexing, chapter-pack prefetch | WorkManager (expedited for packs) | Battery and idle constraints |
| User-requested exports (M4B) | WorkManager plus `dataSync` FGS, with a progress notification | |

**ColorOS (the Find X9):** detect OPPO, OnePlus and realme. Onboarding and the generation screen offer a guided "Keep BetterBooks running" sheet, with deep links to the app's battery page and auto-launch setting, and illustrations of each step. The FOSS build may request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. A Play build would use the settings-list intent instead.

---

## 6. Build, deploy & verify without Android Studio

```bash
# one-time env (in ~/.zshrc)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

./gradlew spotlessApply                     # format
./gradlew :app:assembleDebug                # build
./gradlew testDebugUnitTest                 # JVM + Robolectric tests
./gradlew recordRoborazziDebug              # (re)record screenshots → */build/outputs/roborazzi/*.png
./gradlew verifyRoborazziDebug              # screenshot diff gate
./gradlew lintDebug

adb devices -l                              # Find X9 over USB, or: adb pair <ip:port> && adb connect <ip:port>
./gradlew :app:installDebug && adb shell am start -n app.betterbooks.debug/app.betterbooks.MainActivity
android run                                 # alternative: build + deploy + launch
adb exec-out screencap -p > /tmp/bb.png     # (scratchpad path in practice) the agent views the PNG
android layout                              # UI tree for assertions
adb logcat --pid=$(adb shell pidof -s app.betterbooks.debug) -v color
adb shell screenrecord --time-limit 15 /sdcard/bb.mp4 && adb pull /sdcard/bb.mp4
```

**The agent's loop for each UI task:**
1. Write the composable together with its `@Preview`-style Roborazzi test covering every state.
2. Record the screenshots and *view* the PNGs.
3. Iterate until it looks right.
4. Install on the Find X9 and screenshot it.
5. For motion: record the screen and inspect key frames, using `adb shell input` to script taps and swipes.

**CI (GitHub Actions):**
- `ci.yml` on every push and PR: `setup-java@v4 (21)`, `gradle/actions/setup-gradle`, SDK packages through `android sdk install`, then `spotlessCheck lintDebug testDebugUnitTest verifyRoborazziDebug assembleDebug`. The debug APK is uploaded as an artifact.
- `release.yml` on a `v*` tag: build a signed APK using keystore secrets, then create a GitHub release with the changelog.

---

## 7. Testing strategy (#12)
| Layer | What | How |
|---|---|---|
| Spine/ingest | segmentation goldens, TOC↔spine mapping, front/back matter, notes, encrypted vs font-obfuscated | JVM tests over fixture EPUBs in `:core:testing`. Public domain: *Meditations*, *On the Origin of Species*, *Pride and Prejudice*, *Walden*, *The Federalist Papers*, plus one fixed-layout sample, one malformed sample and one with obfuscated fonts. |
| Normalization/renderers | numbers, Roman numerals, abbreviations; Fish S2 cue rendering; stripping unsupported markup | Table-driven golden tests |
| Providers | Fish SSE parsing, model-header guarantee, 429 backoff; OpenRouter model ranking, budgeter | Ktor `MockEngine` with **recorded** responses. No network in CI. |
| AI | prompt schema validation, repair path, **fence never leaks** (a planted marker sentence past the fence must never appear in any prompt) | Recorded responses plus property tests |
| SRS | FSRS-6 reference vectors | JVM |
| Audio | M4B writer round trip (parse our own boxes back), silence and gain math | JVM + Robolectric |
| UI | every screen state in light and dark, at font scales 1.0 and 1.5 | Roborazzi |
| E2E | import → read → highlight → restart → restored; listen with system TTS 2 min; review session | A few Robolectric/Compose UI tests, plus scripted adb runs on the Find X9 |
| Live smoke (local only) | one Fish paragraph and one OpenRouter call, using `local.properties` keys | `./gradlew :app:liveSmokeTest -Plive=true`, never in CI |

---

## 8. Performance budgets (on the Find X9)
| Metric | Budget |
|---|---|
| Cold start to interactive Home | ≤ 700 ms (with baseline profiles from Phase 8) |
| Open an EPUB to the first page | ≤ 800 ms for a book up to 2 MB |
| Import plus Text Spine for a 100k-word book | ≤ 8 s (embeddings excluded) |
| First audio after tapping Listen | System TTS ≤ 500 ms; Fish ≤ 3 s on good network |
| Read-along highlight drift | ≤ 250 ms (sentence level) |
| Jank | < 1% of frames janky at 120 Hz in the library, reader and player |
| APK size | ≤ 35 MB (no models bundled) |
| Memory while reading and playing | ≤ 350 MB, plus about 330 MB while embeddings are being indexed |
| 16 KB page alignment | Every `.so` (sqlite-bundled, LiteRT-LM, any later NDK library) checked in CI with `zipalign -c -P 16 -v 4` |
