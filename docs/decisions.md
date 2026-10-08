# BetterBooks — Product & Engineering Decisions

Decided 2026-10-08 during the requirements round. This file is the source of truth: later plans must follow it, and any change to a decision is recorded here. "(Claude's pick)" marks an item the user left to Claude.

> **Pivot (2026-10-08, round 2):** the project was renamed from *OpenAudiobook* to **BetterBooks**. It is now a full ebook reader for everyone, not only an audiobook generator. Every audiobook decision below still holds. Reader, read-and-listen and internalization decisions were added in round 2 (#49–#95, at the end of this file).

## Vision
**BetterBooks is an app that helps you internalize books, and it happens to be open source.** It has three pillars:
1. **Read.** A best-in-class ebook reader for all readers, not just audiobook listeners.
2. **Listen.** EPUBs become detailed, customizable AI audiobooks, and existing audiobooks play too. The aim is to match the best audiobook players and go further.
3. **Internalize.** AI features help you understand, remember and connect what you read or hear.

Fiction and non-fiction:
- **Non-fiction is as important as fiction.** For the primary user it matters more.
- The app is not a fiction or story app. Every feature must work well for non-fiction: textbooks, essays, history, science, business, and books with citations, tables and figures.

## Product
| # | Topic | Decision |
|---|---|---|
| 1 | Audience | Public. Play Store eventually. |
| 2 | Source | Open source, GPL-3.0. |
| 3 | Monetization | Free. Users bring their own API keys for TTS and the LLM. |
| 4 | Distribution | GitHub releases first, then the Play Store. |
| 5 | iOS | Maybe later. Build native Android now and keep the core platform-independent so Kotlin Multiplatform is possible. |
| 6 | Languages | English books first. All UI strings go in resources so the UI can be translated. |
| 7 | Release shape | Phased releases. The first release must be good enough to use daily. |

## Engineering
| # | Topic | Decision |
|---|---|---|
| 8 | Stack | Native Kotlin + Jetpack Compose (Material 3 Expressive). AI writes 100% of the code. |
| 8b | Tooling | **No Android Studio.** Use JDK 21 (Homebrew `openjdk@21`), Google's Android CLI (`android`, Homebrew cask `android-cli`), SDK components installed through the CLI, and the Gradle wrapper. Verify UI with JVM screenshot tests (e.g. Roborazzi), plus on-device runs via adb. Keep the toolchain lightweight. |
| 9 | SDK levels | minSdk 28. compileSdk and targetSdk 37 (Android 17; the SDK has platform `android-37.0` and build-tools 37.0.0 installed). |
| 10 | Test device | OPPO Find X9, Android 16 (ColorOS 16). No emulator by default. ColorOS kills background work aggressively, so the app must handle battery-optimization exemptions. |
| 11 | Repo | App ID **pending** (proposed `app.betterbooks`; waiting on the name-collision check). Git plus GitHub, with GitHub Actions CI. |
| 12 | Testing | Unit tests for the pipeline and parsing, screenshot tests for UI, and a few end-to-end UI tests. |

## TTS & LLM providers
| # | Topic | Decision |
|---|---|---|
| 13 | Key model | Users bring their own Fish key in v1. The architecture must allow a backend proxy later. |
| 14 | Fish model | Default `s2.1-pro-free` until it ends (2026-11-30 per changelog), then switch automatically to `s2.1-pro` and alert the user. Always send the `model` header explicitly, because a missing or misspelled header is billed at the paid rate. |
| 15 | Providers in v1 | Fish plus Android system TTS. The provider interface must be ready for ElevenLabs, Gemini, Azure, Cartesia, MiniMax and Kokoro. |
| 16 | On-device neural TTS (Kokoro) | Later. |
| 17 | Cost controls | Show a cost and time estimate before generating. Per-book and monthly caps. |
| 25 | LLM | **OpenRouter, user's own key.** Default to the best *currently available* free model (`:free`). Discover it from OpenRouter's live model list, because the free roster changes often; never hardcode a model ID. The user can pick any OpenRouter model. Respect free limits: 20 requests/min, and 50/day, or 1,000/day once the account has bought $10 or more of credits. Favor long-context models and large windows so few requests are needed. **Update (round 2):** the developer account has unlocked 1,000 free requests/day. The app still has to work for users on 50/day. |

## Generation pipeline
| # | Topic | Decision |
|---|---|---|
| 18 | Mode | Hybrid. The first chapter streams within seconds; the rest generates in the background. |
| 19 | Where it runs | Entirely on the device in v1. No server. |
| 20 | Request size | One paragraph per request, using Fish's timestamp endpoint. |
| 21 | ASR quality check | Optional, off by default. |
| 22 | Storage codec | AAC 64 kbps mono. |
| 23 | Regeneration | Regenerate one sentence or paragraph at a time, with multiple takes to choose from. |

## Voices, casting, performance
| # | Topic | Decision |
|---|---|---|
| 24 | Cast | Single narrator by default. Fiction gets full cast with one tap. Non-fiction has an optional separate "quote voice". |
| 26 | Emotion | The LLM adds Fish emotion tags. An intensity slider runs from flat narration to radio drama. Every line can be edited. |
| 27 | Cast review | Full control: merge aliases, reassign lines, set voice, speed and pitch per character, and work through a queue of low-confidence lines. |
| 28 | Voices | Fish library restricted to `licensed=true` voices, plus Fish voice design. Cloning the user's own voice comes later. |
| 29 | Series memory | Yes. A character keeps the same voice and pronunciations across a series. |
| 30 | Pronunciation | The LLM flags unusual terms automatically. Lexicons per book and per series, with a preview in context. |
| 31 | Non-prose | Skip front and back matter automatically, with toggles. Read footnotes at the end of the chapter by default (configurable). Announce chapter titles. See the non-fiction section below. |
| 32 | Ambience, music, sound effects | Later. |

## Non-fiction is first-class
- **Content type.** Detect fiction, non-fiction or mixed automatically. The type sets defaults for cast, notes, tables and style, and the user can override it.
- **Navigation.** Build a hierarchy from multi-level headings (part, chapter, section, subsection). Support next and previous section, and show progress per section.
- **Citations.** Remove superscript note markers and bracketed references. Parenthetical citations such as "(Smith, 2019)" can optionally be skipped.
- **Footnotes and endnotes.** Read inline after the sentence or paragraph, at the end of the section or chapter, or skip them.
- **Tables.** Read rows, give an LLM-written spoken summary, or skip.
- **Figures, charts and images.** Use alt text or a vision-LLM description, or skip.
- **Code and math.** Speak them or skip them with a short mention. Math can come as MathML or LaTeX.
- **Structure cues.** Read lists with natural enumeration. Block quotes and epigraphs can use the quote voice. Announce sidebars and callouts.
- **Back matter.** Skip references, bibliography, index, glossary and acknowledgments by default.
- **Highlights and notes.** Capture them while listening, by tapping or with the headset. Export to Markdown in an Obsidian-friendly format.
- **Key takeaways.** Generate chapter takeaways as text on demand.
- **Narration style.** Presets per content type: lecture/clear, conversational, dramatic.

## Player
| # | Topic | Decision |
|---|---|---|
| 33 | Controls | Speed 0.5–3.5x without changing pitch. Skip silence, volume boost, EQ. Sleep timer with fade, shake to extend, and end-of-chapter option. Smart rewind to the start of the sentence. Bookmarks with notes. Undo a seek. Per-book overrides. (Approved.) |
| 34 | Read-along | Sentence highlighting first, inside a full Readium reader. Word-level highlighting next. |
| 35 | Integrations | v1: Android Auto, a home-screen widget (Glance, must be **beautiful**), headset buttons. Later: Wear OS, Chromecast. |
| 36 | Stats | Simple listening stats in v1. |

## Library, import, export
| # | Topic | Decision |
|---|---|---|
| 37 | Import | EPUB for generation. The app also plays existing M4B and MP3 audiobooks. **Expanded in round 2:** see #52 for the reader formats. |
| 38 | User content | **No warnings, disclaimers or nags of any kind about the user's EPUBs** on import, conversion or export. Font obfuscation is not DRM: treat it as normal and convert. The app does not implement DRM circumvention. If a file is encrypted and can't be read, it gets a neutral, technical parse error only. Official Readium LCP support may come later. |
| 39 | Export | M4B with chapters and cover, and MP3 per chapter. No warnings. |
| 40 | Accounts and sync | No accounts. A local backup file in v1. |

## "And then some"
| # | Feature | Decision |
|---|---|---|
| 41a | Recap | **v1.** A comprehensive summary of everything up to the current position. Fiction gets a story recap; non-fiction gets the key ideas. |
| 41b | Ask the book | **v1.** Fiction is limited to what has been heard, to avoid spoilers. Non-fiction can also search the whole book. |
| 41c | Character cards and relationship map | **v1.** Non-fiction gets key concept and people cards plus a concept map instead. |
| 41d | Chat with a character | No. |
| 41e | Photo of a printed page to jump to that spot | Later (Claude's pick). |
| 41f | Language-learning mode | No. |
| 41g | Dialogue navigation | Later (Claude's pick). |
| 41h | Narrator hot-swap and side-by-side audition | v1 (Claude's pick). |
| 41i | Bedtime mode | v1 (Claude's pick). |
| 41j | Shareable casting presets | Later (Claude's pick). |

## Design
| # | Topic | Decision |
|---|---|---|
| 42 | Design language | Inspired by **Revolut**: fluid motion, morphing shapes, shared-element transitions, rich deliberate haptics, obsessive detail. Built on Material 3 Expressive. Dynamic color from cover art. Dark mode first. |
| 43 | Prototype | Build a clickable design prototype before coding. |
| 44 | Logo | A minimal logo, designed by Claude. |

## Privacy & process
| # | Topic | Decision |
|---|---|---|
| 45 | Telemetry | No analytics. Crash reporting is opt-in at most. |
| 46 | First-run notice | A short privacy note at setup: book text is sent to the chosen TTS and LLM providers, and their free tiers may train on it. This is about privacy, not copyright. |
| 47 | Process | Work in phases with checkpoints and small commits. |
| 48 | API keys | Done. `FISH_API_KEY` and `OPENROUTER_API_KEY` are in `local.properties`. That file must be gitignored and never committed or printed. |

---

# Round 2: Reader, unified read and listen, internalization

These questions come from the round-2 research: `docs/research/reader-landscape.md`, `internalization-science.md`, `reader-tech.md` and `unified-read-listen-ai.md`. On 2026-10-08 the user told Claude to *"go with your suggested answers"*. Every answer below is Claude's suggested default, accepted as-is, and the user can override any of them.

## Name & identity
| # | Question | Decision |
|---|---|---|
| 49 | Keep the name? | **BetterBooks**, with the tagline **"Read. Listen. Remember."** It is the working name for GitHub releases. Before the Play Store listing: re-check trademarks (USPTO, EUIPO) and weigh the overlap with *BetterReads* (an iOS app with a similar pitch). betterbooks.dev was available on 2026-10-08, and only the user can register it. |
| 50 | App ID | `app.betterbooks`. It can still change until the first Play upload. |

## Reader core
| # | Question | Decision |
|---|---|---|
| 51 | EPUB engine | Readium Kotlin Toolkit **3.4.0**, using its stable Fragment-based navigator (`EpubNavigatorFragment`) hosted in Compose through `AndroidFragment`. It sits behind our own `ReaderEngine` interface so we can switch to Readium's experimental Compose navigator once it catches up (it lacks `firstVisibleElementLocator`, JS hooks and animated turns). |
| 52 | Formats in v1 | EPUB 2/3, both reflowable and fixed-layout, plus PDF. TXT, Markdown and HTML are **converted to EPUB on import**, and the original file is kept. v1.x adds MOBI/AZW3 (libmobi, LGPL) and CBZ. Later: FB2, DOCX, CBR, DjVu. |
| 53 | PDF engine | **androidx.pdf 1.0.0-beta01** (Apache-2.0, minSdk 28). It gives search, selection and page text. We build our own highlight overlay, and our own text clean-up (headers and footers, hyphenation, paragraph merging) so PDFs can feed AI and TTS. **No MuPDF**, because its AGPL terms forbid proprietary components. OCR for scanned PDFs comes later. |
| 54 | Page turns | Readium's animated slide, or no animation. A continuous scroll mode is also available. Page curl (an AGSL shader over page snapshots) is a post-v1 prototype. |
| 55 | Typography & themes | Font, size, weight, line spacing, paragraph spacing, margins, justification and hyphenation, with an explicit "Override publisher styles" toggle. Users can import their own fonts. Settings are saved per theme, as in Apple Books. Themes: day, sepia, night, AMOLED black, and custom. Bundled OFL reading fonts include Literata, Source Serif 4 and Atkinson Hyperlegible, plus OpenDyslexic. |
| 56 | Navigation | Hierarchical table of contents. Progress bar with chapter ticks. Time left in the chapter and book, from the reader's own speed. Back and forward after link jumps. Footnote popups (custom-built, because Readium has none). A **Book Map** structure overview in the style of KOReader, which matters most for non-fiction. Print page numbers when the EPUB provides a page list. |
| 57 | Input | Configurable tap zones, page turning with the volume keys, and brightness by swiping along the left edge. |
| 58 | Search | In-book search with contains, whole-word and regex modes. Library-wide full-text search through SQLite FTS5 (bundled SQLite). |
| 59 | Annotations | Highlight colors and styles (highlight, underline, squiggly), Markdown notes and bookmarks, all anchored to sentence IDs (#67). Export to Markdown in an Obsidian vault folder through SAF, W3C Web Annotation JSON, and **Readwise API push** in v1. Importing from KOReader, Moon+ and ReadEra comes later. |
| 60 | Lookups | `ACTION_PROCESS_TEXT` handoff to other apps, and BetterBooks registers its own "Explain in BetterBooks" action. A bundled offline Open English WordNet dictionary. Wikipedia through the Action API, not RESTBase, which is being retired. LLM "explain in context". Later: StarDict import and a Wiktionary pack. **No ML Kit.** |
| 61 | Library | Shelves and collections, series, tags, and smart filters (has audio, generating, in review, finished). Metadata and cover editing. Import single files or watch a folder through SAF. **v1.x:** OPDS catalogs (Project Gutenberg, Standard Ebooks new releases, a Calibre content server) through Readium's OPDS module. |
| 62 | Sync | v1: a local backup file plus Android Auto Backup. v1.x: WebDAV folder sync, and the kosync progress protocol (percentage only at first, so it works with KOReader and Readest). Later: Google Drive appdata, and opt-in Hardcover tracking. |
| 63 | E-ink | v1.x: an e-ink preset with no animation, pure black and white, paginated layout and volume keys. No vendor SDK. |
| 64 | Accessibility | TalkBack support, font scaling, reduced motion that respects the system animator scale, and OpenDyslexic. Bionic reading only as a low-priority experiment. |
| 65 | Stats & goals | Unified stats for reading and listening, including speed estimates. An opt-in **weekly goal of review days, with 2 freezes**. No guilt-trip copy, and no nagging notifications. |
| 66 | Build flavors | **One FOSS build with no proprietary SDKs**, ready for F-Droid. A Play flavor is added only if a proprietary need comes up. Optional AI models, such as the embedding model, are downloaded at runtime and never bundled. |

## Unified read ↔ listen
| # | Question | Decision |
|---|---|---|
| 67 | Canonical position | **The Text Spine: stable sentence IDs.** Each sentence has a global ordinal, assigned at import by the same segmenter the TTS pipeline uses. Readium Locators, CFIs and audio milliseconds are all *derived* from it, so positions survive regeneration, voice swaps, re-pagination and re-alignment. |
| 68 | Playback engine | Our own **ReadAlongPlayer**: an ExoPlayer playlist of per-paragraph segments plus a SyncMap, exposed to one Media3 `MediaSession` through `SimpleBasePlayer`/`ForwardingSimpleBasePlayer`. Readium's TtsNavigator is **not** used for Fish, because it handles one utterance at a time, pause restarts the utterance, and it bypasses ExoPlayer's speed, skip-silence and EQ. System TTS renders sentence by sentence (`synthesizeToFile`) into the same pipeline. |
| 69 | "Listen" on a book with no audio yet | With a Fish key and a network connection, stream Fish from the playhead and show a "generated ahead" buffer ring. Otherwise, start system TTS instantly. Hot-swapping from system TTS to Fish at a paragraph boundary comes later. |
| 70 | Reader modes | One reader screen with a segmented control for **Read / Read + Listen / Listen**. Read + Listen is the follow-along highlight with a transport bar. Listen is a karaoke-style transcript over the cover. |
| 71 | Sentence highlight | Inject `<span id>` per sentence when serving the XHTML, if a spike confirms Readium's resource-transform hook works. Otherwise fall back to Readium Decorations with text-context Locators. |
| 72 | Reading vs listening position | Store them separately. Resume whichever is most recent. If the other one is ahead and newer, show a non-modal chip, e.g. "You listened to §3.2 · Jump". In Read + Listen mode the two stay locked together. |
| 73 | Spoiler fence ("frontier") | Track **consumed intervals**, not just the furthest point. Jumps from the TOC, search, links or scrubbing don't count until the user reads about 2 pages straight there. Back matter is excluded. Fence modes are set per book: Frontier (fiction default); Whole book (non-fiction default for ask and search); Current position; or a custom marker. A "Rereading" toggle sets the fence to the whole book. Cards and quizzes are always fenced. An opt-in strict mode removes the title and author from prompts. **The fence is enforced at retrieval time, before the LLM sees any text**, not only in the prompt. |
| 74 | Headset highlight | Opt-in remap of double or triple tap, offered during the first listening session. It captures the current sentence, and also the previous one if the tap lands within 1.5 s of a sentence start. Confirmed with an earcon and a haptic. |
| 75 | Imported audiobook ↔ EPUB alignment | v1: chapter-level mapping, plus importing Media Overlays EPUBs (e.g. from Storyteller) by parsing their SMIL into the SyncMap. v1.x: cloud speech-to-text alignment (Fish ASR, about $0.36 per hour). On-device CTC alignment comes later. |
| 76 | Media Overlays export of generated audio | Later phase: an EPUB3 read-aloud that works with Storyteller-compatible readers. |

## Internalization ("help you internalize books")
Evidence base: retrieval practice and spacing have the strongest evidence. Self-explanation, generation, teaching and concrete examples have moderate evidence. Highlighting, rereading and summaries are weak on their own, and AI summaries can create an "illusion of fluency". See `docs/research/internalization-science.md`.

| # | Question | Decision |
|---|---|---|
| 77 | How much study mode by default | Set per book from the detected content type. **Fiction gets Light:** recap, explain, takeaways, and an X-Ray of people, places and terms. **Non-fiction gets Study:** the full loop. Every prompt can be skipped with one tap, and there is a global off switch. |
| 78 | When LLM work happens | **One cached "chapter pack" per chapter**, made in a single long-context call and prefetched one chapter ahead. Several chapters share a call when the model's context allows. A pack holds: the gist, 3–5 pre-questions, 5–10 key ideas as rubric points, entities and facts with evidence ordinals, relationship edges, candidate cards, "apply it" prompts, and a summary for recaps. Results are cached forever, keyed by (kind, chapter, fence bucket, model, prompt version). |
| 79 | Who writes flashcards | **The AI suggests the question, the user answers in their own words, and the AI compares that answer with the source.** Accepting the AI's own answer is a secondary option. New cards are capped per day (default 10). Every card cites and links its source passage. |
| 80 | Scheduler | **FSRS-6 through FsrsKt 1.0.1** (GPL-3.0, Kotlin Multiplatform), behind a `Scheduler` interface and checked against FSRS test vectors. Default parameters, with a per-user optimizer (fsrs-rs) later. |
| 81 | Anki | AnkiDroid instant-add API, plus CSV export for desktop Anki. |
| 82 | Grading free recall | The LLM produces a **rubric checklist** and the user confirms it. The user always sets the FSRS rating. A "predict your score" step before quizzes shows predicted vs actual, to counter overconfidence. |
| 83 | Pre-questions | On for non-fiction. Off for fiction, with opt-in "predict what happens next" prompts built only from text before the current position. |
| 84 | End-of-section check-ins | Non-fiction, on by default: "In one sentence, what was the claim?" The user answers, then sees the gist. In Listen mode the check-in is spoken, to counter mind-wandering. Fiction: off. |
| 85 | Daily review | **One mixed queue:** cards, vocabulary, and highlights brought back as cards when the user opts in. A Glance widget shows "N due" with one card face. One daily notification at a time the user picks (opt-in). |
| 86 | Vocabulary builder | **v1.** Lookups are saved with their context sentence into the review queue. |
| 87 | Teach it back (Feynman) | v1.x. The user explains by voice through on-device `SpeechRecognizer`, with typing as a fallback. The LLM plays a confused student and asks one follow-up. |
| 88 | Audio review ("commute mode") | v1.x. A playlist of prompt, pause, reveal, using system TTS so it costs no Fish credits. |
| 89 | Ask the book | Retrieval combines FTS5 and on-device embeddings with Reciprocal Rank Fusion and is **filtered by the fence first**. Answers cite passages, and tapping a citation jumps there. |
| 90 | Embeddings | **EmbeddingGemma 2 (270M) on LiteRT-LM 0.18.0**, an optional 165 MB download. Vectors are stored as BLOBs and compared in Kotlin. Until the model is downloaded, search falls back to FTS5 only. sqlite-vec comes later, for search across the whole library. |
| 91 | Concept graph | v1: a single-book concept and relationship map built from chapter packs, which the user can rearrange and annotate (maps the learner builds beat maps they are handed). v1.x: links across books from embeddings, with the LLM explaining a link only when tapped. |
| 92 | "Apply it" and book synthesis | v1.x. Non-fiction "apply it": the user writes an implementation intention and gets a 7-day follow-up. Book synthesis: the user writes first, then the AI compares with the key ideas. |
| 93 | Policy against shallow use | No summary-instead-of-reading defaults: recaps are offered after a gap or on request. Progress counts retrievals, not pages or highlights. Quizzes give a hint before the answer. No bulk auto-generated decks. Every AI-written quiz item cites its source. |
| 94 | AI budget | A request budgeter shows "requests left today" and keeps background prefetch to 1 request per 4 s or slower (the limit is 20/min). It adapts to a 50/day or 1,000/day account. |

## Information architecture
| # | Question | Decision |
|---|---|---|
| 95 | Top-level structure | **Three tabs: Home · Library · Notebook**, plus a persistent "now-bar" mini player that morphs into the full player.<br>• **Home:** resume card that knows the last mode, "Previously on…", reviews due today, generation status.<br>• **Library:** books, with filters.<br>• **Notebook:** highlights, notes, cards, daily review and maps, across all books.<br>Each book has a **hub page with Read · Listen · Learn**. Learn contains Ask, Recap, People/Concepts, Map, Takeaways and Notes. |

## Round 3: Name & visual identity (2026-10-08)
| # | Question | Decision |
|---|---|---|
| 96 | Name | **BetterBooks** is confirmed. The user considered OpenBooks (rejected: an IRC ebook-download tool, a nonprofit and an active trademark already use the name), Ingrain, Recto, Carrel and Mull. |
| 97 | Palette | **Cobalt**: black and white with one electric cobalt blue. Tokens: bg `#000000`, surface-1 `#101012`, surface-2 `#1A1A1E`, hairline `#2A2A30`, text `#FFFFFF`, text-2 `#9B9BA3`, accent `#4D8BFF` (shown as `#7AA8FF` when used as text on black), secondary `#2DD4BF`, positive `#34D399`, warning `#FBBF24`, negative `#F87171`. Lumen is retired. |
| 98 | Design language | The v1 prototype language was **rejected** as too generic. The user wants something unique and artistically beautiful that stays practical. Four art directions are being explored on the canvas: Folio (editorial), Grid (Swiss), Shelf (tactile book-object) and Signal (generative instrument). The user picks one or a mix, then the full prototype and `plan/experience.md` are rebuilt in it. |
| 99 | Design direction | **C · Shelf (tactile book-object)** is chosen. The user wants 3D-feeling objects and smooth, transformative animations (objects that morph between states, e.g. spine → cover → player). Folio, Grid and Signal are shelved, though specific ideas may be borrowed if the user asks. Source files: `docs/design/prototype/project/DirC-*.dc.html`. |
