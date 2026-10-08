# Unifying reading, listening and AI in BetterBooks

Search access hit a session limit partway through, so some Readium API details below are marked UNVERIFIED. I did not repeat material from `docs/research/`, such as Fish timestamps, FGS budgets or the provider abstraction.

## 0. Core recommendation
- **Use one coordinate system for everything: a "Text Spine" of stable sentence IDs.** Build it once at import from the same segmenter the TTS pipeline uses: block → sentence, each with a global ordinal.
  - Reading positions, audio timestamps, annotations, AI fences, cards and graph evidence all point at sentence IDs.
  - Readium Locators and audio milliseconds are *derived views* of a sentence ID. They are never the source of truth.
  - This is the only way positions survive regeneration, voice swaps, re-pagination and imported-audio realignment.

## 1. Read↔listen continuity
**What the market does**
- **Audible "Read & Listen" (US launch 18 Feb 2026).** A one-tap Listen / Read & Listen toggle sits above the cover. Words highlight in real time, progress is kept across devices, and a library filter shows eligible titles. https://futurefive.com.au/story/audible-unites-ebooks-audiobooks-with-read-listen , https://help.audible.com/s/article/listen-with-whispersync-for-voice
- **Kindle's "furthest page read" broke in practice.** Rereads leave it stuck at the end of the book, and endnotes count as furthest. Newer Kindles resume at the *most recent* page and keep furthest as a manual option. https://www.40tech.com/2011/01/04/how-to-reset-the-furthest-read-location-on-your-kindle/ , https://drivers.softpedia.com/get/Tablets/Amazon-Kindle-Paperwhite-2nd-Generation-Firmware-545.shtml (secondary source)
- **Storytel** uses "format mapping" to search text and audio editions together. https://www.storytel.com/cl/c/app-features
- **Spotify Page Match** takes a camera scan and offers "Play from here", or tells you to flip forward or back. https://newsroom.spotify.com/2026-02-05/bookshop-partnership-page-match-announcement/
- **Storyteller** (community-reported):
  - Double-tap a sentence to play from there; the current sentence is highlighted.
  - There is a separate speed for audio-only and for readaloud, and users run readaloud at 2–3x.
  - Position sync works only when both devices can reach the server. https://brianlovin.com/hn/38747710 , https://storyteller-platform.dev/docs/reading/storyteller-apps
- **Readwise Reader TTS:**
  - Auto-scroll follows the narration, and a manual scroll detaches it.
  - The transport bar then swaps in **Return** (back to the spoken sentence) and **Jump** (play from the centre of the screen). https://docs.readwise.io/reader/docs/faqs/text-to-speech

**Mechanics for BetterBooks**
- **Readium Kotlin has no Media Overlays.** The 3.1.2 feature matrix marks it "planned", and the changelog through 3.4.0 (2026-09-11) adds none. So you build your own sync layer. https://readium.org/kotlin-toolkit/3.1.2 , https://github.com/readium/kotlin-toolkit/blob/develop/CHANGELOG.md
- **Reading → audio:** visible sentence → SyncMap → (file, ms).
  - Get the visible element from `firstVisibleElementLocator()`.
  - Resolve it to the first fully visible sentence, then look up the SyncMap row.
- **Audio → reading:** playhead → binary search over the SyncMap → sentence → Locator → `navigator.go()`.
  - Readium warns that `go(utteranceLocator)` turns pages only at utterance boundaries.
  - Use a word-level locator throttled to about 1 s to turn pages mid-sentence. https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/tts.md
- **Highlighting:**
  - Use the Decorator API with a `readalong` group. Since 3.2.0, decorations can optionally render behind the text.
  - Add word highlights from Fish segments mapped to character ranges within the sentence.
  - The cheapest exact approach is to wrap sentences in `<span id>` when serving the XHTML (what Media Overlays needs), so the CSS selector is simply `#s123`. Whether Readium 3.x exposes a resource-transform hook is UNVERIFIED; spike it. The fallback is Decorator with text-context Locators and no DOM changes.
- **Background playback:**
  - One Media3 `MediaSessionService` owns one Player. The reader is only an observer.
  - Leaving the reader keeps playback running. Returning re-attaches in "follow" state.
  - The 6 h/24 h FGS timeout covers `dataSync` and `mediaProcessing`, not `mediaPlayback`. Prefetching the next few paragraphs while playing can therefore run inside the playback service. This is my inference; check it against Play policy. https://developer.android.com/develop/background-work/services/fg-service-timeout
- **Multiple positions:**
  - Store `ReadingPosition` and `ListeningPosition` separately, plus `lastActiveMode` and timestamps.
  - Resume the most recent one.
  - If the other is ahead and newer, show a **non-modal** chip ("You listened to §3.2. Jump?") instead of Kindle's blocking dialog.
  - In Read & Listen mode the two positions are locked together.

## 2. "Listen" on a book with no audio yet
- **Readium `TtsEngine` contract:**
  - `speak(requestId, text, language)`, `stop()`, `voices`.
  - Listener callbacks: `onStart`, `onRange(IntRange)`, `onDone`, `onError`. https://github.com/readium/kotlin-toolkit/blob/develop/readium/navigators/media/tts/src/main/java/org/readium/navigator/media/tts/TtsEngine.kt
- **Gotchas in `TtsPlayer` (from its source):**
  - It speaks **one utterance at a time and never passes lookahead text to the engine**.
  - **Pause cancels and replays the utterance from its start.**
  - Any preference change restarts the utterance.
  - Media buttons rely on a "fake playing audio" workaround. https://github.com/readium/kotlin-toolkit/blob/develop/readium/navigators/media/tts/src/main/java/org/readium/navigator/media/tts/TtsPlayer.kt
- **What a Fish-backed engine would have to do:**
  - Own its own audio output.
  - Prefetch by walking the content iterator itself.
  - Map Fish's normalized segments to `IntRange`.
  - Give up ExoPlayer's skip-silence, Sonic speed up to 3.5x, EQ and gapless playback, while Fish's own speed range is only 0.5–2.0.
- **Verdict: don't put Fish behind TtsNavigator.** Build a **ReadAlongPlayer** instead:
  - An ExoPlayer playlist of per-paragraph segments, from cache files or a progressive stream, plus the SyncMap.
  - Exposed to the MediaSession through `ForwardingSimpleBasePlayer` or `SimpleBasePlayer`, so the system-TTS and Fish backends can swap under **one** session. https://developer.android.com/media/media3/session/player
  - Keep Readium for content iteration, tokenization, Decorator and navigation.
- **System-TTS fallback:**
  - `onRangeStart` fires only if the engine reports timing, and it is tied to the playback head. https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener
  - Either ship TtsNavigator with `AndroidTtsEngine` in v1 (fastest), or `synthesizeToFile` per sentence into the same ExoPlayer pipeline. That gives uniform controls with sentence-level highlighting; whether ranges fire in file mode is UNVERIFIED.
- **Recommended behaviour when the user taps Listen:**
  - With a Fish key and network: stream from the playhead. Show a "generated ahead: 4 min" buffer ring, and warn if listening speed outruns generation (the actual generation lead is UNVERIFIED; measure it).
  - Otherwise: start system TTS instantly.
  - Later: hot-swap from system TTS to Fish at the next paragraph boundary once at least 30 s is buffered.
- **Regenerating a paragraph** replaces only that paragraph's SyncMap rows. Positions stored as sentence IDs are unaffected.

## 3. Unified annotations
- **Always anchor to text, even when the note is created by ear.** The anchor holds:
  - `(startSentenceId, startChar, endSentenceId, endChar)`
  - The Readium Locator JSON: href, progression, selector and `text.before/highlight/after`. https://readium.org/architecture/models/locators/
  - An EPUB CFI, computed at export time only.
- **Headset capture:**
  - Readwise lets users remap double or triple tap to "highlight" (in Account → Headphone gestures). Snipd's triple tap saves a "smart" span around the tap. https://www.mickmel.com/from-google-podcasts-to-snipd/
  - Media3 converts a Bluetooth double tap into `seekToNext`, and HEADSETHOOK handling had bugs. Many earbuds send NEXT/PREVIOUS directly. So intercept in `MediaSession.Callback` and remap when the setting is on. Test on the OPPO with real earbuds. https://developer.android.com/reference/androidx/media3/session/MediaSession , https://github.com/androidx/media/issues/1493
  - Capture the current sentence. If the tap lands within about 1.5 s of a sentence start, take the previous sentence too, to cover reaction lag.
  - Confirm with an earcon and a haptic.
  - Optionally have AI expand the capture to the whole idea, batched at session end to save requests.
- **Re-anchoring when the EPUB or segmenter changes** (the Hypothesis approach):
  - Try offset plus quote first.
  - Then fuzzy-search the prefix and suffix near the expected spot.
  - Otherwise mark the annotation as an **orphan**; never delete it. https://web.hypothes.is/blog/fuzzy-anchoring/
- **Export:**
  - Markdown, as already decided.
  - W3C Web Annotation JSON with a `TextQuoteSelector` plus an EPUB CFI. This is the EDRLab/Thorium profile, now in W3C PMWG EPUB annotation work that Readwise said it could adopt. https://www.w3.org/2023/09/pmwg-slides/annotations.pdf , https://lists.w3.org/Archives/Public/public-pm-wg/2025Dec/0020.html

## 4. Positional AI fencing
- **Prior art:**
  - Kindle "Ask this Book" answers from the current reading location and can't be switched off. https://gigazine.net/gsc_news/en/20251216-ask-this-book
  - Amazon says "Story So Far" recaps stop at your position, but Engadget says the recaps are not spoiler-free. A fence enforced only in the prompt is not enough. https://www.aboutamazon.com/md/news/books-and-authors/kindle-recaps-feature-ebook-series-refreshers , https://engadget.com/2202142/kindle-app-features-ios-has-ereader-still-doesnt-have
- **Frontier** = the highest sentence ordinal consumed in linear flow, from either mode.
  - Store consumed **intervals**, not just a maximum.
  - Reading counts after a dwell scaled to word count, or once the user pages past.
  - Listening counts sentences actually played.
  - TOC jumps, search hits, footnote links and scrubbing don't advance the frontier until the user reads about 2 pages contiguously there, or confirms "Count as read?".
  - Back matter and endnotes are excluded.
- **Enforce the fence before the LLM sees anything:**
  - Retrieval over SQLite FTS5 is filtered with `ordinal <= fence`.
  - Recaps are stitched from cached per-chapter summaries up to the fence, plus the partial current chapter.
  - Graph and card facts carry an `evidenceOrdinal` and are filtered when displayed.
- **Strict mode** drops the title and author from prompts, so the model can't fall back on what it already knows about famous books. How well this works is UNVERIFIED.
- **Request budget:** one call when a chapter is finished, returning JSON with the summary, takeaways, entities, facts and edges. That covers recap, cards, map and takeaways for about 1 request per chapter, so a 30-chapter book fits the 50/day limit.
- **Fence modes** (set per book):
  - Frontier: fiction default.
  - Current position.
  - Whole book: non-fiction default for ask and search, per decision 41b.
  - Custom: drag a marker on the progress bar.
  - A "Rereading" toggle sets the fence to the whole book.
- **Quizzes and cards** are always fenced to what has been consumed.

## 5. Information architecture
- **What I could check:**
  - Kindle iOS makes the current book the *first* nav item, ahead of Home. https://ixd.prattsi.org/2025/09/design-critiques-amazon-kindle-ios-app/
  - Readwise splits reading (Reader: Home/Library/Feed) from review (Daily Review in the separate Readwise app). https://docs.readwise.io/reader/docs
  - NotebookLM gives each notebook Sources / Chat / Studio tabs; Studio makes flashcards, quizzes and audio overviews. https://9to5google.com/2025/05/01/notebooklm-android-iphone/ , https://9to5google.com/2025/11/06/notebooklm-app-flashcards-quizzes/
  - The 2026 tab layouts of Storytel, Apple Books, Speechify, ElevenReader and Everand are UNVERIFIED.

| | Option A: 3 tabs + morphing now-bar (recommended) | Option B: book as workspace | Option C: mode tabs |
|---|---|---|---|
| Top level | **Home** (resume card that knows the mode, "Previously on…", review due today) · **Library** (filters for has-audio, generating, imported audio) · **Notebook** (highlights, notes, cards, review, maps across books) | Library · Review | Read · Listen · Learn · Library |
| Inside a book | Reader with a persistent mini "now-bar" that morphs (shared element) into the player | Hub with Read · Listen · Learn (Ask, Recap, Cards, Map, Takeaways, Notes) | Mode-specific screens |
| Pros | Familiar; review becomes a daily habit, which is how books get internalized | Best for deep non-fiction study | Easy to explain |
| Cons | A book's learning is split between its hub and Notebook | One extra hop to resume | Same book in two tabs; repeats the Kindle/Audible split Amazon is now collapsing |

- **Recommendation: A at the top level, with B's hub as the book detail page.**
- **The reader is one screen with three states:** Read, Read + Listen (transport bar plus follow-highlight) and Listen (karaoke transcript over the cover). A single segmented control switches between them, Audible-style.
- **AI entry points:**
  - The selection menu offers Explain, Ask, Card and Highlight.
  - A spark button opens a fenced sheet with Ask, Recap, People/Concepts and Map.

## 6. Data model
- **Book:** file hash, edition fingerprint, content type.
- **Block / Sentence:** id, ordinal, href, CSS selector, text, segmenter version.
- **AudioRendition:** `GENERATED | IMPORTED | SYSTEM_CACHE`, cast, provider and model.
- **AudioSegment:** file, take and cache key.
- **SyncMapRow:** rendition, sentence, file, start and end ms, word-timings blob, source (`provider | stt | ctc | smil`), confidence.
- **Imported M4B:**
  - Storyteller's pipeline is Whisper transcription (most of the 1–4 h per book on server hardware), Levenshtein chapter location and per-sentence fuzzy match. v3 adds CTC with MMS and Viterbi for word timings. https://storyteller-platform.dev/docs/managing/aligning , https://runtimewire.com/article/storyteller-v3-ctc-aligner-immersive-reading
  - On a phone, on-device transcription is impractical for v1.
  - Instead: chapter-level mapping first; import Storyteller or other Media Overlays EPUBs by parsing SMIL into the SyncMap; then cloud STT with word timestamps (Fish ASR about $0.36/h, per earlier research) plus on-device fuzzy matching. Record unmatched audio spans such as intros.
- **Positions:** ReadingPosition, ListeningPosition, ConsumedInterval and Frontier (with manual override).
- **Annotation:** as described in section 3, plus `createdVia` (`READ | LISTEN | HEADSET | AI`).
- **Card:** an FSRS state via FsrsKt (GPL-3.0, so it is compatible with BetterBooks), a source sentence range and a fence ordinal. https://central.sonatype.com/artifact/one.felsen.fsrskt/fsrskt
- **KG:** Node (kind, aliases, first-mention ordinal), Fact (node, text, evidence sentence), Edge (relation, evidence ordinal).
- **AiArtifact cache:** keyed by (kind, fence bucket, model, prompt version).
- **Export:** generated audio as an EPUB3 Media Overlays readaloud. That is interoperable with Storyteller-compatible readers. The Calliope paper backs capturing timing during synthesis rather than aligning afterwards. https://arxiv.org/abs/2602.10735

## Decision points for the developer
1. **Canonical coordinate.** Options: sentence ID, Readium Locator, or CFI. **Default: sentence ID, with the Locator stored as a derived field.**
2. **Playback engine.** Options: Readium TtsNavigator, or your own ExoPlayer ReadAlongPlayer behind `ForwardingSimpleBasePlayer`. **Default: your own player. Use TtsNavigator only for the v1 system-TTS fallback, if at all.**
3. **"Listen" on a book with no audio.** Options: system TTS, Fish streaming, or a hybrid hot-swap. **Default: Fish streaming when a key and network are available, otherwise system TTS. Add the hot-swap later.**
4. **Sentence highlighting.** Options: inject DOM spans, or Decorator with text context only. **Default: inject spans, after a spike that confirms Readium's resource-transform API.**
5. **Position reconciliation.** Options: Kindle's furthest-read, most-recent only, or separate positions with a nudge. **Default: separate positions, resume the most recent one, show a non-modal chip.**
6. **Frontier rules.** Options: a raw maximum, or consumed intervals with jump suppression. **Default: intervals, with jumps and back matter excluded.**
7. **Spoiler defaults.** **Default: fiction uses Frontier; non-fiction uses Whole book for ask and search; cards are always fenced; strict mode is opt-in.**
8. **Imported M4B alignment.** Options: on-device Whisper or CTC, cloud STT plus fuzzy match, or importing Media Overlays EPUBs. **Default: v1 does Media Overlays import and chapter-level mapping; v1.x adds cloud STT; on-device CTC comes later.**
9. **Headset highlight.** Options: off, or opt-in remapping of double or triple tap. **Default: opt-in, offered during the first listening session.**
10. **Annotation export.** **Default: Markdown plus W3C Web Annotation JSON. Add a Readwise sync later.**
11. **IA.** Options: A, B or C. **Default: A, with B's per-book hub.**
12. **Spaced repetition.** Options: SM-2 or FSRS. **Default: FSRS via FsrsKt.**
13. **Media Overlays export of generated audio.** **Default: yes, in a later phase.**