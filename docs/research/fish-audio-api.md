## Fish Audio TTS API research (as of 2026-10-08)

### 1. Models and identifiers
- **Model IDs.** Pass the model in the HTTP/WS header `model`. Allowed values: `s2.1-pro` (default), `s2.1-pro-free`, `s2-pro`, `s1`, `drama-3-preview`. **Gotcha:** if the header is missing or misspelled, Fish silently falls back to the paid `s2.1-pro`. A typo in `s2.1-pro-free` gets billed at the paid rate. Source: OpenAPI spec https://docs.fish.audio/api-reference/openapi.json
- **Release dates** (changelog https://docs.fish.audio/developer-guide/getting-started/changelog.md):
  - S2 (`s2-pro`): March 2026. It introduced `[bracket]` cues.
  - S2.1 Pro and `s2.1-pro-free`: June 23, 2026.
  - `drama-3-preview`: Sept 23, 2026. Behavior may change.
  - S1 is legacy but still supported.
  - Gateways (Vercel, OpenRouter) list July 28–29, 2026, which is probably their own listing dates.
- **Languages.** S2.1 Pro covers 83 languages, S2-Pro 80+, S1 13. https://docs.fish.audio/developer-guide/models-pricing/models-overview.md
- **Quality claims (vendor's own):**
  - 61% win rate vs S2 Pro.
  - About 70–90 ms time to first audio (the blog contradicts itself; the chart shows 73 ms p50).
  - Over 2x throughput under concurrency.
  - Artificial Analysis gives S2.1 Pro an Arena Elo of about 1139. I could not read its rank. https://fish.audio/blog/s2-1-pro-free-api/, https://artificialanalysis.ai/text-to-speech/models/s2-1-pro
- **Paid vs free.** `s2.1-pro` comes with "TTFA and DPA guarantees". `s2.1-pro-free` is the same model without them. TTFA/DPA are not defined anywhere. DPA probably means Data Processing Agreement (UNVERIFIED).
- **Open weights.** S2-Pro weights are published under the *Fish Audio Research License* (non-commercial only; commercial use needs a license from business@fish.audio). Fish calls them "open weights", not open source. Not a viable path for on-device use in a commercial app. https://huggingface.co/fishaudio/s2-pro/blob/main/LICENSE.md

### 2. Endpoints and request schema
- **REST:** `POST https://api.fish.audio/v1/tts`. Body is JSON or MessagePack (MessagePack is required for inline reference audio). The response is chunked raw audio. https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech.md
- **WebSocket:** `wss://api.fish.audio/v1/tts/live`. Client sends `start` (with a `request` object), `text`, `flush`, `stop`. Server sends `audio` and `finish` (reason `stop` or `error`). https://docs.fish.audio/api-reference/endpoint/websocket/tts-live.md
- **Request fields (defaults and ranges):**
  - `text` (required).
  - Voice: `reference_id` (string, or an array for multi-speaker); `references` (`[{audio, text}]` or a 2D array).
  - Sampling: `temperature` 0.7 (0–1); `top_p` 0.7 (0–1); `repetition_penalty` 1.2.
  - Chunking: `chunk_length` 300 (100–300; the guide page says the default is 200, a doc inconsistency); `min_chunk_length` 50 (0–100); `max_new_tokens` 1024 per chunk; `condition_on_previous_chunks` true; `early_stop_threshold` 1.
  - Text handling: `normalize` true (English and Chinese only).
  - Output:
    - `format` wav/pcm/mp3/opus.
    - `sample_rate`: wav/pcm 8–44.1 kHz 16-bit; mp3 32 or 44.1 kHz; opus 48 kHz only.
    - `mp3_bitrate` 64/128/192.
    - `opus_bitrate` -1000 (auto)/24k/32k/48k/64k.
    - All output is mono.
  - `latency` `normal` (best quality) / `balanced` / `low`.
  - `prosody`: `speed` 0.5–2.0, `volume` ±20 dB, `normalize_loudness` true (S2 family only).
  - `features: ["quality-guard"]` (depends on the backend).
  - `pronunciation_dictionary`: up to 3 per request. Inline form allows up to 5,000 entries, each key ≤256 chars and value ≤1,024 chars, plain substring match, leftmost-longest wins. Unresolvable references are **silently dropped**.
- **Max text per request: not documented anywhere** (UNVERIFIED). The server splits text into chunks of 100–300 characters itself.
  - **Gotcha:** `condition_on_previous_chunks` only keeps the voice consistent *inside* one request. Consistency across chapter or paragraph requests has to be managed by the app (for example a fixed `reference_id`, low temperature, re-render on drift).
  - The 15k/30k "max chars per generation" figures belong to the web subscription plans, not the API.
- **Errors:**
  - 401, 402 (payment required), 503 (high load).
  - 429 at the concurrency cap, with **no `Retry-After` header**. Use exponential backoff.
- **Phoneme control:** wrap a word in `<|phoneme_start|>…<|phoneme_end|>`. English uses CMU Arpabet, Chinese tone-number pinyin, Japanese OpenJTalk romaji. https://docs.fish.audio/developer-guide/core-features/fine-grained-control.md

### 3. Expressive control
- **S2 / S2.1 cue syntax** uses `[square brackets]`. https://docs.fish.audio/developer-guide/core-features/emotions.md
  - 49 named emotions.
  - Tones: `[whispering]`, `[shouting]`, `[soft tone]`, `[emphasis]`, etc.
  - Effects: `[laughing]`, `[sighing]`, `[sobbing]`, `[clear throat]`, etc.
  - Pauses: `[break]`, `[long-break]`.
  - Free-form natural language works (`[warm and happy]`), as do intensity modifiers (`[very excited]`) and stacking (at most 3 per sentence is recommended).
  - Sentence-level cues work best at the start of the sentence.
- **S1 uses `(parentheses)`**, and the wrong delimiter fails. An adapter has to translate tags per model.
  - The emotions page still says "All 13 languages", which is stale S1 text.
- **Drama 3 (`drama-3-preview`)** is directed in plain language, e.g. `[warm and relaxed, like talking to an old friend]`. https://fish.audio/drama-3/
  - It has scoped paired tags (Whisper/Slower/Stress…). **The API markup for these is not documented** (UNVERIFIED).
  - "Auto Tag" exists in the web editor only. Results vary between takes; Fish recommends generating several. Pricing is not stated.
- **Multi-speaker in one request** (S2 family and Drama 3, not S1):
  - Set `reference_id` to an array of voice IDs.
  - Mark turns in the text with `<|speaker:0|>`, `<|speaker:1|>`, …
  - For zero-shot voices, `references` takes a 2D array.
  - Maximum number of speakers: UNVERIFIED.

### 4. Voices
- **Voice library listing:** `GET /model`. Filters: `title`, `tag`, `language`, `author_id`, `self`; `sort_by` score/task_count/created_at; `page_size` ≤100.
  - **`licensed=true`** returns only voices "licensed by Fish Audio (rights secured from the voice owner)". This implies most community voices are *not* rights-cleared. Many are likely celebrity or character clones, which is a distribution risk. Source: OpenAPI spec.
- **Persistent clone:** `POST /model` with `type=tts` and `train_mode=fast` (usable almost instantly).
  - `voices`: 1–20 audio files (wav/mp3/m4a/opus, ≥10 s each; 1–2 minutes recommended).
  - `texts` is optional; Fish runs ASR if it is missing.
  - `enhance_audio_quality` defaults to true.
  - `visibility`: private (default) or unlist. A "public" request via the API is downgraded to private.
- **Instant clone:** inline `references` with each request, MessagePack only, 10–30 s of audio plus an exact transcript. Fish recommends persistent models for quality and latency.
- **Voice Design:** `POST /v1/voice-design` (`voice-design-1`, $0.01 per call).
  - `instruction` up to 2,000 chars, `n` up to 4 candidates, `reference_text` up to 150 chars, plus speed, seed and guidance settings.
  - Candidate signatures can be fed into `POST /model`. Useful for designing per-character voices.
- **Consent:** no voice-cloning consent requirement appears in the docs or ToS.

### 5. Timestamps and alignment: available, but documented only in the OpenAPI spec
- **`POST /v1/tts/stream/with-timestamp`** returns Server-Sent Events. Each event carries `audio_base64`, `alignment` (`audio_duration` plus `segments[{text,start,end}]`), `chunk_seq` and `chunk_audio_offset_sec`.
- **`wss://api.fish.audio/v1/tts/live/with-timestamp`** uses MessagePack binary frames with the same `start`/`text`/`flush`/`stop` protocol. Audio arrives as raw `bin` here, not base64 as in the SSE endpoint.
- **How to read alignment:**
  - Alignment is a *cumulative snapshot* per `chunk_seq`. Replace the stored snapshot for that chunk rather than appending.
  - Absolute time = `chunk_audio_offset_sec + segment.start`.
  - Never use `chunk_seq` to decide which chunk audio bytes belong to.
- **Gotcha:** the segment text is normalized. The spec's own example shows "its" and "Ive" with punctuation and apostrophes stripped. Word highlighting therefore needs fuzzy alignment back to the EPUB DOM.
- Not verified: whether all models (`s2.1-pro-free`, `drama-3-preview`) support these endpoints, and how alignment interacts with bracket tags or multi-speaker text (UNVERIFIED).
- **Fallback or QA:** ASR `transcribe-1-pro` (`POST /v1/asr`, $0.36 per audio hour, up to 60 minutes) returns timestamps. It could re-check generated audio for skipped or hallucinated text.

### 6. Pricing, limits and the free offer
- **Price** (https://docs.fish.audio/developer-guide/models-pricing/pricing-and-rate-limits.md):
  - `s2.1-pro`, `s2-pro` and `s1` cost **$15 per 1M UTF-8 bytes**. `s2.1-pro-free` costs $0.
  - Fish's estimate: 1M bytes ≈ 180k English words ≈ 12 hours of speech. A 90k-word novel is roughly $7.50.
  - Because billing is per byte, CJK text (3 bytes per character) and Cyrillic/Arabic (2 bytes) cost more per character.
  - Whether bracket tags are billed is UNVERIFIED; the docs only say they "don't count toward token limits".
- **Concurrency is the only rate limit** (no RPM cap). It is per account, with ASR sharing the same pool.

  | Tier | Paid to date | Concurrent requests |
  |---|---|---|
  | Starter | under $100 | 5 |
  | Elevated | $100 or more | 15 |
  | High Volume | $1,000 or more | 50 |

  Fish's own example: a 60-second synthesis allows about 5 requests per minute at 5 concurrent requests. A whole book is a queueing problem.
- **The free offer** ("S2.1 Pro Free API", model `s2.1-pro-free`):
  - Open to every developer: no card, no waitlist, "no hard character cap". It runs under a Fair Use Policy whose numeric limits are not published.
  - No SLA and no latency guarantee. It is aimed at "experimentation and prototyping".
  - **Requests may be used to improve the model.**
  - "Some commercial scenarios may have restrictions". Products above $1M ARR must contact Fish first.
  - **End date: the changelog says Nov 30, 2026, about 7 weeks from now.** The blog contradicts itself (July 24 and Aug 31 extensions, and also Nov 30).
  - Whether apps distributed to end users may use it is **not addressed** (UNVERIFIED). Sources: https://fish.audio/blog/s2-1-pro-free-api/ and the changelog.
- **Auth:** a bearer API key created at fish.audio/app/api-keys, optionally with an expiry. **There is no OAuth or "Sign in with Fish Audio"** (none found).

### 7. Terms of service (https://fish.audio/terms/, effective Aug 18, 2024, which predates S2)
- **Copyrighted input:** you may not use content you don't own "without the prior consent of the owner". User-supplied copyrighted EPUBs fall into this zone; Fish puts the risk on the user.
- **Free plan:** "internal, personal, non-commercial use" only. Paid users are "licensed to use the Services for commercial uses". The pricing page lists "Commercial use" on the Free card but its FAQ says non-commercial, a contradiction (https://fish.audio/plan/).
- **Output ownership:** not explicitly granted.
- **AI disclosure:** disclosure is encouraged, and you must not present AI output as "entirely human generated".
- **Training:** content and usage data may be used to train models, and no opt-out is described. Zero Data Retention is Enterprise-only.
- **API keys:** you may not "buy, sell, or transfer API keys". Business credentials must not be shared outside your organization. Licenses are non-sublicensable.
  - **Implication:** shipping one developer key inside the APK breaks the ToS and exposes the key. The workable options are users bringing their own key, or a backend proxy where you pay and accept the commercial terms.
- Using output to build competing models and scraping are both prohibited.
- No API-specific or reseller terms were found. "Additional Terms" are incorporated by reference but I could not locate them.

### 8. SDKs and Story Studio
- **Official SDKs:**
  - Python: `fish-audio-python` (Apache-2.0, updated Oct 2, 2026) https://github.com/fishaudio
  - JS/TS: npm package `fish-audio` (`FishAudioClient`). React Native compatibility is UNVERIFIED; the SDK likely assumes Node or the browser.
- **Dart/Kotlin:** no official or community client found. .NET (NuGet `FishAudio`) and PHP clients are third-party. For Flutter or Kotlin you would hand-roll a client (http plus WebSocket plus MessagePack) or generate one from the OpenAPI spec.
- **Story Studio** is Fish's web project editor for long-form, multi-voice audiobooks with chapter export. **None of its REST endpoints appear in the public OpenAPI spec**, which otherwise covers TTS, ASR, voice-design, model management, wallet and an agent platform.
  - Fish's hosted MCP connector exposes `studio_import_document`, `studio_enhance_text`, `studio_recast_speaker`, `studio_list_takes` / `studio_adopt_take` and `studio_export`. I saw these tool names in this environment; I did not call them.
  - That suggests a server-side pipeline of document import, automatic tagging, speaker casting, multiple takes and export, which is not publicly available to third-party apps.

### 9. Other providers worth an adapter (UNVERIFIED = third-party source only)
- **ElevenLabs:**
  - `eleven_v4`: 10k chars/request, 90+ languages, Text-to-Dialogue.
  - `eleven_v3`: 5k chars, 70+ languages, audio tags.
  - `eleven_v4_turbo`: real-time.
  - The `/with-timestamps` endpoint returns **character-level** alignment, but which models support it is undocumented. Dialogue endpoints reportedly lack timestamps (UNVERIFIED).
  - Price about $100 per 1M characters for v3 (UNVERIFIED). Best voice library. https://elevenlabs.io/docs/models
- **OpenAI:** `tts-1`, `tts-1-hd` and both `gpt-4o-mini-tts` snapshots were **deprecated 2026-10-01 and shut down 2027-01-06**. The replacement, `gpt-realtime-2.1-mini`, runs on the Realtime API, not `/v1/audio/speech`. Low priority. https://developers.openai.com/api/docs/deprecations
- **Google Gemini:**
  - `gemini-3.8-flash-tts` and `gemini-3.8-flash-lite-tts`. **At most 2 speakers per request.**
  - 30 prebuilt voices plus an extended library and voice design/replication; 130+ languages (100+ for Lite).
  - Style via a `speech_metadata.style` field plus inline `<angle>` events. Output is 24 kHz PCM/WAV.
  - No timestamps documented.
  - About $9 per 1M audio tokens (about $0.81 per hour of audio) on promo pricing to Dec 31, then $18 (UNVERIFIED). https://ai.google.dev/gemini-api/docs/speech-generation
- **Azure:** Dragon HD Omni emits **word boundary events** (word, audio offset, *text offset*), giving the cleanest EPUB highlighting. Full SSML. Older HD voices had no word boundaries. HD price $30 per 1M characters at the preview era (UNVERIFIED).
- **Cartesia:** `sonic-3.6` (pinned snapshot `sonic-3.6-2026-08-27`), 44 languages. WebSocket supports `add_timestamps` (word) and `add_phoneme_timestamps`. Emotion is limited to 5 values; speed 0.6–1.5. Price not verified. https://docs.cartesia.ai/api-reference/tts/websocket
- **Hume Octave 2:** still a preview, 11 languages. Natural-language "acting instructions" (≤100 chars in Pipecat; the feature table marks them "coming soon", a conflict). Word and phoneme timestamps (UNVERIFIED). Price unpublished.
- **Inworld:** `inworld-tts-2` and `-2-flash`. WORD/CHARACTER timestamps plus phonemes and visemes (https://docs.inworld.ai/tts/capabilities/timestamps). About $25 and $15 per 1M characters, and markup tags are billed (UNVERIFIED).
- **MiniMax:** `speech-2.8-hd` at $100 per 1M characters, turbo $60. **Async long-text API takes up to 1M characters per request** and returns sentence-level timestamps, so a whole chapter can go in one call. Voice cloning $1.50 per voice. https://platform.minimax.io/docs/guides/speech-t2a-async.md
- **Kokoro-82M (on-device):**
  - Apache-2.0, 54 voices, 8 languages, no emotion control. https://huggingface.co/hexgrad/Kokoro-82M
  - Runs on Android through sherpa-onnx (Kotlin/Java API, Flutter `sherpa_onnx`, `react-native-sherpa-onnx`).
  - About 20–40 s of processing per minute of audio on a Snapdragon 8 Gen 3 (self-reported). Possible GPL issue from espeak-ng (UNVERIFIED).
  - The offline, free fallback.

### Decision points to raise with the developer
1. `s2.1-pro-free` is expected to end Nov 30, 2026. After that, who pays $15 per 1M bytes: users with their own key, or a backend proxy?
2. The free tier allows data training, has no SLA, and its commercial terms are unclear. Is the app commercial or monetized?
3. Users' EPUBs may be copyrighted. Is generated audio personal-only, or can users share or export it?
4. Word highlighting needs the timestamp endpoints plus fuzzy re-alignment. Is it mandatory?
5. Multi-voice casting: use Fish's in-request `<|speaker:N|>`, or render per character and stitch? Who adds emotion tags (an LLM pass, on the device or on a server)?
6. Concurrency is capped at 5. Is background whole-book generation acceptable, or does playback need to stream while generating?
7. Community voices: restrict to `licensed=true`? Allow voice cloning from the user's own recordings, and with what consent?
8. Should there be an offline mode (Kokoro)?
9. Framework: no Dart or Kotlin SDK exists, and the JS SDK's React Native compatibility is unknown. Either way you implement REST plus WebSocket plus MessagePack yourself.