# EPUB to AI audiobook pipeline: research brief for OpenAudiobook (as of 2026-10-08)

## Critical facts that shape the requirement questions
- **Fish `s2.1-pro-free` is time-limited and carries conditions.**
  - Price is $0. It runs under a "Fair Use Policy" with no SLA, and "requests may be used to improve model quality."
  - Apps with more than $1M ARR must contact Fish.
  - Fish's own page gives two end dates: "through August 31, 2026" and, in bold, "through November 30, 2026". Assume it ends about 7 weeks from now.
  - The paid models (`s2.1-pro`, `s2-pro`, `s1`) cost **$15 per million UTF-8 bytes**. CJK text and emoji cost 3–4x per character. https://fish.audio/blog/s2-1-pro-free-api/ , https://docs.fish.audio/developer-guide/models-pricing/pricing-and-rate-limits
- **Concurrency depends on prepaid total:** 5 (under $100), 15 ($100+), 50 ($1,000+). A 429 response has no `Retry-After` header, so back off exponentially. The free tier's own concurrency is UNVERIFIED.
- **Shipping a developer key inside the APK** makes it extractable, and every user would share one fair-use quota. The choice is between users bringing their own key (BYOK) and a proxy backend.

## 1. EPUB parsing
- **Readium Kotlin Toolkit 3.x** is the strongest native base.
  - It provides a content iterator, a text tokenizer that splits text into utterances with Locators, and `TtsNavigator` (Media3, background playback, notification controls).
  - It has a `TtsEngine` interface for third-party engines and an LCP module.
  - Apps targeting Android 11+ must declare `INTENT_ACTION_TTS_SERVICE` in `<queries>`. https://readium.org/kotlin-toolkit/3.1.2/guides/tts , https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/tts.md
  - `flutter_readium` exists but is at 0.x. I found no React Native equivalent, so either framework would need native modules.
- **Spine vs table of contents (general knowledge):**
  - TOC entries (nav/NCX) can point to `#fragment`s inside one spine file, or one chapter can span several files.
  - Spine items marked `linear="no"` (notes, popups) should not be narrated in order.
  - Build chapters by mapping TOC hrefs and fragments onto spine positions. Fall back to headings when the TOC is missing.
- **Front and back matter:**
  - Use EPUB3 `epub:type` values (frontmatter, bodymatter, backmatter, copyright-page, toc, index, footnote, endnote, noteref, epigraph, pagebreak), nav landmarks, and the EPUB2 `<guide>`. https://www.w3.org/TR/epub-ssv-11/
  - Many books lack these semantics, so heuristics plus user toggles are required.
- **Elements that need explicit handling:**
  - Footnotes: skip, read inline, or read at chapter end.
  - Page-break markers: strip them.
  - `display:none` text: don't narrate it.
  - Ruby: decide whether to drop `<rt>` or use it as the reading.
  - Tables: linearize or skip.
  - Verse: line breaks become short pauses.
  - Drop caps in a separate span: avoid splitting the word ("T he").
  - Images: use alt text, as an option.
- **EPUB TTS hints:** `ssml:ph` / `ssml:alphabet` attributes and PLS lexicons (`rel="pronunciation"`) exist but are rarely used. Honor them when present. https://www.w3.org/TR/epub-tts/
- **DRM detection order:**
  1. `license.lcpl` present → LCP.
  2. `META-INF/rights.xml` with an Adobe `encryptedKey` → ADEPT.
  3. `encryption.xml` listing only fonts with the IDPF/Adobe obfuscation algorithm → **not DRM**. MuPDF and epubcheck wrongly flag this case.
  - https://readium.org/lcp-specs/lcp-license-processing-model , https://idpf.org/epub/20/spec/FontManglingSpec.html
  - Reading LCP content requires EDRLab certification and an annual fee. https://www.edrlab.org/readium-lcp/how-to-lcp-reading-system-provider/
  - ADEPT cannot be processed. Show a clear "can't convert" message.

## 2. Text normalization
- **Fish built-ins:**
  - `normalize` defaults to true but only covers **English and Chinese**.
  - `pronunciation_dictionary`: up to 3 dictionaries of 5,000 entries each (key ≤256 chars, value ≤1,024, `case_sensitive` flag), inline or by id/version. https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech
  - S2 emotion tags use `[free-form natural language]`. A tag applies until the next tag or the end of the sentence.
  - S1 uses `(parenthesis)` tags. A third-party guide warns S1 may read tags aloud, so adapters must strip or translate them. https://docs.fish.audio/api-reference/emotion-reference
- **LLM normalization works:** PolyNorm (Apple, EMNLP 2025) used few-shot LLM normalization and got lower WER than a production rule system across 8 languages. https://arxiv.org/html/2511.03080v1 . I found no evidence on heteronym accuracy (UNVERIFIED).
- **Recommended pattern:**
  - Deterministic rules first, since they are cheap and cacheable.
  - Then an LLM pass only for ambiguous spans (Roman numerals like "Chapter IV" vs "George IV", "St.", "Dr.", heteronyms). It should return replacements by span ID.
  - A per-book lexicon the user can edit.
  - Keep a normalized→original character offset map, because highlighting and sync need it.

## 3. Chunking and continuity
- **Fish chunks text internally:**
  - `chunk_length` is 100–300 (default 300), `min_chunk_length` 50.
  - `condition_on_previous_chunks=true` uses earlier audio *within the same request* as context. Sending a paragraph or scene per request should therefore sound more continuous than one request per sentence.
  - No `seed` parameter is documented, so regenerations are not reproducible.
  - Defaults: temperature 0.7, top_p 0.7, repetition_penalty 1.2. `latency="normal"` is the most stable setting.
  - Fish's best-practices page says to reuse a saved `reference_id` rather than send raw reference audio. https://docs.fish.audio/resources/best-practices/text-to-speech
  - Fish documents no maximum text length per request. A third-party wrapper caps it at 3,000 (UNVERIFIED).
- **ElevenLabs equivalent:** "request stitching" via `previous_request_ids` (max 3) and `previous_text`/`next_text`. It is not supported on v3. https://elevenlabs.io/docs/eleven-api/guides/how-to/text-to-speech/request-stitching
- **Sentence segmentation:** `android.icu.text.BreakIterator` (API 24+) or Readium's tokenizer. Whether Android supports ICU's abbreviation-suppression keyword (e.g. for "Mr.") is UNVERIFIED.
- **Seams:**
  - Trim each chunk's leading and trailing silence, then insert deterministic gaps (sentence, paragraph, scene, chapter) with 5–20 ms fades. These values are heuristics (UNVERIFIED).
  - S2 also supports a `[pause]` tag.
- **QA:**
  - LLM-based TTS can skip, repeat, or invent words.
  - Gate each chunk with round-trip ASR, keyed on deletion rate and length ratio. Aggregate WER hides dropped phrases.
  - Fish `transcribe-1` costs $0.36 per audio hour.
  - Fish also has `features:["quality-guard"]`, whose behavior is undocumented (UNVERIFIED).

## 4. Multi-voice casting
- **Benchmark:** PDNC, 28 novels and 37,131 quotes.
- **Michel et al. (NAACL 2025):**
  - Llama-3-8B: **90.6%** overall, 89.1% on non-explicit quotes.
  - BookNLP+: 78.5% overall, 68.9% non-explicit. ChatGPT: about 71%.
  - Llama-3-70B made 3 errors in 1,442 quotes on one unseen novel.
  - **The gold character list was in the prompt**, so these are upper bounds. Explicit "said X" quotes reach 93–99% for every system; the hard part is anaphoric and implicit quotes. https://arxiv.org/html/2406.11380v3
- **ModernBookNLP "joint scoring":** an encoder model at **94.5%** that runs more than 1000x faster than LLM approaches on an A100. https://arxiv.org/abs/2608.02359
- **Existing projects:**
  - prakharsr/audiobook-creator: GLiNER plus an LLM that estimates gender and age, then Kokoro. https://gittrend.io/repo/prakharsr/audiobook-creator
  - Alexandria: LLM speaker annotation plus a second correction pass, then Qwen3 TTS.
  - kenkui: Ollama or cloud LLMs.
  - ebook2audiobook: SML tags and `--voice_map`. Its multi-voice behavior is UNVERIFIED. https://github.com/DrewThomasson/ebook2audiobook
  - epub2tts: switches voice per chapter only. https://github.com/aedocw/epub2tts
- **Fish multi-speaker:**
  - `<|speaker:N|>` tags with a `reference_id` array, S2 family only.
  - Maximum speakers is 2 per Runware and 5 per ComfyUI (UNVERIFIED).
- **Pipeline design:**
  - Steps: character and alias extraction → deterministic quote detection (curly, straight and «» quotes, em-dash dialogue, multi-paragraph quotes) → windowed LLM attribution with the character list → style and emotion tags.
  - The LLM should **return IDs and labels only, never rewritten text**.
  - Add a user "cast review" screen.
- **LLM cost estimate (my estimate):**
  - A 100k-word novel is about 130–135k tokens.
  - 4k windows with a 1k stride, plus prompts, comes to roughly 250–400k input tokens and 30–100k output tokens.
  - Anthropic list prices: Haiku 5.5 $0.10/$0.50 per million tokens (prompts ≤100k), Sonnet 5.5 $2/$10, Opus 5.5 $4/$20. The Batch API is 50% off.
  - That gives roughly **$0.05–0.10 per book (Haiku), $1–2 (Sonnet), $2–4 (Opus)**, before thinking tokens.
- **On-device LLM is not viable for whole books:** the Gemini Nano Prompt API (beta) allows under 4,000 input tokens, runs on few devices (Pixel 10 on nano-v3, some nano-v2 models), and has a per-app quota. https://developers.google.com/ml-kit/genai/prompt/android/get-started

## 5. Generation strategy and cost
- **Cost per book:**
  - 400k–1M characters → about **$6–15 per book** on paid `s2.1-pro`.
  - Fish's own ratio ("1M bytes ≈ 180k words ≈ 12 h") implies 250 words per minute. Typical narration is about 150–160 wpm (roughly 9,300 words per finished hour, a rule of thumb).
  - So a 100k-word novel is about 10–11 hours of audio and about $8–9.
- **Just-in-time vs pre-generation:**
  - Fish claims 70–90 ms to first audio, which makes streaming ahead of the playhead feasible.
  - Offline listening, export, QA and cast review all require pre-generation.
- **Cache key:** hash of normalized text, speaker/voice ID and voice version, provider, model, every parameter, tags, and lexicon version. With no seed, store the audio rather than relying on regeneration.
- **Android background limits:**
  - On Android 15+, `dataSync` and `mediaProcessing` foreground services get **6 hours per 24 hours**. When it runs out, `onTimeout()` fires, and new starts throw `ForegroundServiceStartNotAllowedException`. https://developer.android.com/develop/background-work/services/fg-service-timeout
  - Generation therefore needs resumable, per-chunk checkpointed jobs (WorkManager).
- **Storage per hour (mono):** Opus 24 kbps 10.8 MB; Opus 32 kbps 14.4 MB; AAC 64 kbps 28.8 MB; MP3 128 kbps 57.6 MB; MP3 192 kbps 86.4 MB; WAV 44.1 kHz 16-bit 318 MB. A 10.5-hour book at 64 kbps is about 300 MB.
- **Fish output options:** MP3 at 64/128/192 kbps (32 or 44.1 kHz); Opus at 48 kHz (24–64 kbps); WAV/PCM up to 44.1 kHz.

## 6. Read-along sync
- **Fish `/v1/tts/stream/with-timestamp`:**
  - Streaming only (server-sent events).
  - Word segments in seconds, relative to each chunk; add `chunk_audio_offset_sec` for the full timeline.
  - Snapshots are cumulative per `chunk_seq`, and alignment can be null.
  - Punctuation is missing from segments, so you must map them back to the source text.
  - Fish recommends Opus output for this endpoint. https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech-stream-with-timestamps
- **ElevenLabs:** character-level alignment plus a `normalized_alignment`, non-streaming.
- **Fallbacks:**
  - Sentence-per-request gives sentence-level sync for free, at the cost of prosody continuity.
  - CTC forced alignment with Meta's MMS model, as Storyteller does. https://runtimewire.com/article/storyteller-v3-ctc-aligner-immersive-reading
  - The Calliope paper argues forced alignment "introduces drift" and that timestamps should be captured during synthesis. https://arxiv.org/abs/2602.10735
- **Android system TTS:** `onRangeStart` (API 26) reports word ranges during playback. It may not fire for `synthesizeToFile` (UNVERIFIED).
- **Export option:** EPUB3 Media Overlays (SMIL). Few reading apps support it well.

## 7. Output packaging
- **M4B chapters:**
  - Media3 `Mp4Muxer` has no documented chapter support. https://developer.android.com/reference/androidx/media3/muxer/Mp4Muxer
  - Chapters must be written as a Nero `moov/udta/chpl` atom (max 255 chapters per TagLib) or a QuickTime chapter track. Writing both is safest.
  - **FFmpegKit is retired** and its binaries were removed in 2025. A community fork (LGPL-3.0, supports 16 KB pages) exists.
  - Play requires 16 KB page support for native libraries; the fork's README says from Nov 2025 (I didn't confirm on Google's site). This affects ffmpeg, sherpa-onnx and whisper.cpp builds.
- **Loudness targets:**
  - ACX: −23 to −18 dB RMS, peaks ≤ −3 dB, noise floor ≤ −60 dB, 44.1 kHz MP3 at ≥192 kbps CBR, consistent channel layout. https://help.acx.com/s/article/acx-audio-submission-requirements
  - General spoken word: about −16 to −19 LUFS. Apple podcasts: −16 LKFS ±1, true peak ≤ −1 dBFS. Spotify normalizes playback to −14 LUFS.
  - Fish's `normalize_loudness` works per request. You still need a per-file integrated-loudness pass after assembly, and gain matching across voices and providers.

## 8. On-device TTS fallback
- **Android system TTS:** quality depends on the OEM engine. `getMaxSpeechInputLength` is about 4,000 characters (general knowledge; verify).
- **sherpa-onnx models:**
  - Kokoro (v1.0, Kotlin API, Android demo), Matcha (en/zh), KittenTTS (mini v0.1, nano v0.2), ZipVoice (voice cloning), Piper/VITS.
  - Pocket TTS and Supertonic (66M) appear only in third-party wrappers or apps.
  - Flutter has an official `sherpa_onnx` package; the React Native wrapper is third-party.
- **Kokoro speed (VoxSherpa, self-reported):** a Snapdragon 8 Gen 3 flagship synthesizes 1 minute of audio in 20–40 s; mid-range phones take 60–90 s; budget phones 2–3 minutes. Mid and low-end devices can't stream it in real time. https://github.com/CodeBySonu95/VoxSherpa-TTS
- **Kitten:** 25–80 MB with lower RAM use, but its quality was an early checkpoint at launch.

## 9. Provider abstraction
- **Capability flags the interface needs:**
  - Streaming type (HTTP chunked, SSE, WebSocket) and time to first audio.
  - Maximum input per request, and billing unit (bytes, characters, tokens or seconds).
  - Timestamp granularity (none, sentence, word, character) and whether it is streaming-only.
  - Style/emotion markup dialect (Fish S2 `[free-form]`, S1 parentheses, ElevenLabs v3 audio tags, SSML, OpenAI-style instructions).
  - Multi-speaker per request: syntax and maximum speakers.
  - Voice cloning (reference audio plus transcript, 10–30 s), voice design, voice library IDs.
  - Context continuity mechanism (conditioning on previous chunks, stitching).
  - Pronunciation support (lexicon API, IPA, SSML phoneme).
  - Seed and determinism.
  - Output formats and sample rates; supported languages; built-in normalization.
  - Speed range (Fish: 0.5–2.0) and volume.
  - Concurrency and retry semantics.
  - Data-use terms and voice licensing.
- **Architecture:** a neutral internal markup (style, pause, emphasis, speaker) with a renderer per provider that strips anything unsupported.
- **Prior art:**
  - Readium's `TtsEngine` interface.
  - ebook2audiobook's engine list (XTTSv2, Bark, VITS, Piper and others).
  - epub_to_audiobook (Azure, OpenAI, Edge, Piper, Kokoro).
  - audiblez: writes per-chapter WAVs, then uses ffmpeg to build the M4B.
  - Readest offers Native, Web and Edge TTS. Edge TTS speaks an unofficial protocol that fails with 403 when its version constant goes stale. https://readest.com/docs/listen

## 10. Legal and Play policy
- **US:**
  - There is no statutory format-shifting exception, and fair use for it is unsettled.
  - **DMCA §1201 bans DRM circumvention even when the underlying use is lawful.** The accessibility exemption covers only the act of circumvention, not tools.
- **UK:** the private-copying exception (s28B) was quashed in July 2015, and I found no sign it was reintroduced. https://www.gov.uk/government/news/quashing-of-private-copying-exception
- **EU:** private copying is handled with levies that vary by member state (UNVERIFIED).
- **Data flow:** sending book text to a cloud TTS or LLM means a third party processes copyrighted text. Fish's free tier may train on requests, so this needs disclosure.
- **Sharing:** shared generated audio would be a distributed derivative work. Avoid sharing or public-library features.
- **Play IP policy:** bans apps that infringe or encourage infringement, and Google may ask for proof of rights. https://support.google.com/googleplay/android-developer/answer/9888072
- **Play AI-Generated Content policy:** covers "voice … recordings of real-life individuals using AI", so voice cloning is in scope. A 2024 report says apps must provide in-app flagging of offensive output; Google's current help page doesn't mention it (UNVERIFIED). https://support.google.com/googleplay/android-developer/answer/14094294

## Key architectural decisions
1. **Where processing runs.**
   - Fully on-device with BYOK: private, no server, but API keys live on the phone.
   - Backend proxy: hides keys, allows metering, caching and billing; costs ops effort and privacy.
   - Hybrid.
2. **TTS key model.**
   - Embedded developer key: extractable, shared quota, and the free tier is expiring.
   - BYOK: friction for users.
   - Paid accounts: billing, about $8–9 per novel on paid Fish.
3. **Generation mode.**
   - Just-in-time: instant start, needs network.
   - Full pre-generation: offline, export, QA; slow, bounded by the 6-hour FGS budget.
   - Hybrid: stream chapter 1 and pre-generate the rest.
4. **Request granularity.**
   - Sentence: precise sync, cheap retries, worse prosody.
   - Paragraph or scene: better continuity, bigger blast radius on failure.
   - Multi-speaker scene: natural dialogue, hardest to align.
5. **Sync source.** Provider timestamps (Fish streaming endpoint only), on-device CTC alignment (CPU and model size cost), or sentence-level only.
6. **Speaker attribution.**
   - None or heuristic.
   - Cloud LLM: Haiku is about $0.10 per book, Sonnet about $1–2, with roughly 90%+ accuracy (my extrapolation from Llama-3 results; none measured).
   - Server-side encoder (ModernBookNLP, 94.5%).
   - In every case, a mandatory user review UI.
7. **Normalization.** Rules, an LLM pass, or a hybrid, plus a per-book lexicon. Language scope matters, since Fish only normalizes English and Chinese.
8. **EPUB engine.** Readium Kotlin is native and mature, so most heavy code should be Kotlin under either framework. A Dart or JS parser would be less mature.
9. **Codec and container.** Opus or AAC for in-app playback. Export as M4B (needs a custom `chpl` writer or the FFmpeg fork), MP3 per chapter, or EPUB3 Media Overlays.
10. **QA loop.** ASR verification and auto-regeneration ($0.36 per hour with Fish, or on-device Whisper at a battery cost) vs none.
11. **Offline fallback.** Android system TTS (free, variable quality) vs sherpa-onnx Kokoro (80–330 MB models, slower than real time on mid-range phones) or Kitten.
12. **Legal posture.** Personal-use only, no sharing, a clear DRM refusal, AI disclosure, and voice-cloning consent plus a reporting flow.