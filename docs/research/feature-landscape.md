# OpenAudiobook: feature catalog for a best-in-class audiobook and AI-narration app (researched 2026-10-08)

Legend: **[TS]** table-stakes · **[DIFF]** differentiator · **[NOVEL]** novel idea · "Best:" is the app that does it best. Most of these facts come from search summaries and secondary listings, with primary sources linked where I could get them.

## 1. Library and import
- [TS] Add a folder once and each subfolder becomes one book. Supports M4B/MP3/M4A/OGG/OGA/OPUS. Filters for new, started and finished; search by title or author; grid or list view. Best: Voice (GPLv3, Kotlin/Compose) — [GitHub](https://github.com/PaulWoitaschek/Voice), [F-Droid](https://f-droid.org/packages/de.ph1b.audiobook/)
- [TS for us] EPUB parsing with section detection (copyright, dedication, prologue, epilogue) and a checkbox to include or exclude each section. ElevenLabs warns that its detection is imperfect. Best: ElevenLabs Studio — [docs](https://elevenlabs.io/docs/eleven-creative/products/audiobooks)
- [DIFF] Other inputs: PDF, DOCX, TXT, HTML, URLs, Google Docs, scanned pages (ElevenReader — [help](https://elevenlabs.io/docs/help-center/product/distribution-publishing/eleven-reader/what-is-eleven-reader)); MOBI/AZW3/FB2 and OCR of images (ebook2audiobook — [GitHub](https://github.com/DrewThomasson/ebook2audiobook)).
- [DIFF] Collections, series, reading list (Prologue); hidden books (VoicePlus fork — [F-Droid](https://f-droid.org/zh_Hans/packages/com.github.mistermo_vibecode.voiceplus/)).
- [DIFF] Clients for self-hosted servers: Audiobookshelf, Jellyfin, Emby (BookPlayer, Plappa, Prologue v4). Automatic Hardcover tracking (BookPlayer — [GitHub](https://github.com/TortugaPower/BookPlayer)).
- Gotcha: every open-source converter requires DRM-free EPUB ([epub2tts](https://github.com/aedocw/epub2tts)).

## 2. Metadata and covers
- [TS] Cover from embedded art, a local file, or an online search (Voice).
- [DIFF] Metadata and cover lookup from multiple providers (third-party pages name Audible, iTunes, Google Books); a chapter editor with lookup via Audnexus; merge files into one M4B; embed tags with Tone. Best: Audiobookshelf — [docs](https://audiobookshelf.org/docs)
- [DIFF] Narrator from the composer tag (Plappa). One user complaint: Prologue can't filter by narrator ([iphone-ticker](https://www.iphone-ticker.de/hoerbuch-app-prologue-unterstuetzt-jetzt-auch-audiobookshelf-server-271074/)).
- [DIFF] Player theme taken from the cover art (Prologue).

## 3. Playback controls
- [TS] Speed with pitch correction. Ranges: Libby 0.6–3.0x, Pocket Casts 0.5–3.0x, ElevenReader 0.25–4x (third-party), Speechify up to 4.5x.
- [DIFF] A separate pitch slider and a noise-reduction checkbox in the EQ dialog. Best: Smart AudioBook Player — [changelogs](https://www.apkmirror.com/apk/alex-kravchenko/smart-audiobook-player/)
- [TS] Skip or trim silence, and volume boost (Voice, Pocket Casts). Pocket Casts' boost also cuts background noise — [support](https://support.pocketcasts.com/article/playback-effects/)
- [TS] Per-book overrides for effects and speed. Best: Pocket Casts (per-show overrides); Prologue (per-book settings).
- [TS] Auto-rewind on resume. Google Play Books' "Smart Resume" rewinds to the start of the word or sentence you stopped on. This is easy for us because we know the sentence boundaries — [9to5google](https://9to5google.com/2018/03/29/google-play-books-audiobooks-update/)
- [TS] Custom skip intervals (Plappa allows 1–2 s). Headset buttons can be mapped to skip, bookmark or chapter actions. The forward button can be set to skip a whole chapter. Best: Pocket Casts — [headphone controls](https://support.pocketcasts.com/knowledge-base/headphone-controls/)
- [TS] Sleep timer:
  - by duration or end of chapter, with fade-out and shake-to-extend (Voice, Audible)
  - a daily auto-pause window, e.g. 10 PM–6 AM (Voice listing)
  - "fell asleep" auto-pause, with a shake to resume (Smart ABP)
  - turns itself off when connected to Android Auto (Smart ABP)
  - a Live Activity for the timer (Prologue, iOS)
  - Best: Smart ABP and Voice.
- [TS] Chapter navigation, jump to start of book (BookPlayer), skip the start and end of each file (Smart ABP).
- [TS] Bookmarks with names and notes (Voice, Audible); a bookmark from headphones, with an optional title (Pocket Casts — a paid feature).
- [DIFF] Clips: shareable audio up to 45 s that recipients can play without an account. Audible; whether it still exists in 2026 is UNVERIFIED — [iMore](https://www.imore.com/audible-now-lets-you-share-short-clips-your-favorite-audiobooks)
- [DIFF] Character list you build by hand (Smart ABP, VoicePlus).
- [DIFF] Listening log and playback history (VoicePlus, Prologue). I found no source that clearly shows any app doing undo-seek well (UNVERIFIED gap).

## 4. Read-along, text sync, immersion reading
- [DIFF, TS for us] Word-by-word highlighting synced to narration, with a toggle between "Listen" and "Read & Listen". Position syncs between ebook and audio. Best: Amazon (Immersion Reading / Whispersync) — [Audible help](https://help.audible.com/s/article/what-is-immersion-reading)
- [DIFF] Switch between reading and listening anywhere in a book (Storytel).
- [NOVEL] Page Match: photograph a printed page to jump to that point in the audio, or the reverse. Spotify, launched Jan–Feb 2026, works by OCR — [GSMArena](https://m.gsmarena.com/spotify_launches_page_match_syncing_your_audiobook_progress_with_physical_books_and_ebooks-news-71430.php). Trivial for us, since we own the text.
- [TS for readers] Readest — [docs](https://readest.com/docs/listen):
  - TTS starts from the current paragraph; you can read only a selection
  - skip by sentence or paragraph
  - switches voice automatically in multilingual books
  - highlights are anchored with EPUB CFI
- Standard: EPUB 3 Media Overlays — SMIL 3.0 `par` pairs of text#id and audio clip, at paragraph-to-word granularity, with tap-to-play. Apple Books supports read-aloud only in fixed-layout books — [IDPF](https://idpf.org/epub/30/spec/epub30-mediaoverlays-20110215.html), [W3C](https://www.w3.org/TR/epub/)
- **Gotcha:** I found no documented word-timestamp output from Fish S2 TTS (UNVERIFIED). Options: forced alignment, or Fish STT (which has word-level timestamps) run on the generated audio — [Fish STT](https://fish.audio/stt/). epub2tts already uses Whisper to check audio against text.

## 5. Generation and production controls
- [DIFF] Single-cast vs multi-cast, plus Character Casting. Best: ElevenLabs Studio — [docs](https://elevenlabs.io/docs/eleven-creative/products/audiobooks)
  - AI detects characters and proposes a voice for each
  - previews play real dialogue from the book
  - recasting a character updates all their lines and clears the existing audio
  - up to 150 voices per book
  - minor or unnamed speakers can be missed
- [DIFF] A project-wide default voice and model, overridable per paragraph or section (ElevenLabs).
- [DIFF] Pronunciation:
  - ElevenLabs auto-flags names and invented terms; rules are aliases or phonemes (phonemes on English models only); preview plays the term in its sentence; .pls dictionary upload is third-party-reported; first matching rule wins
  - @Voice: regex speech corrections — [APKMirror](https://APKmirror.com/apk/hyperionics-technology/voice-aloud-reader-tts-reader/)
  - Google auto-narration: choose among several pronunciations — [Google](https://play.google.com/books/publish/autonarrated)
- [DIFF] Emotion and direction:
  - Fish S2 takes free-form inline `[bracket]` tags (15k+ cited) and switches speakers within one generation with `<|speaker:1|>` — [Fish S2](https://fish.audio/s2/)
  - Telnyx docs (third-party host of Fish models) report intensity modifiers such as "slightly sad" — [Telnyx](https://developers.telnyx.com/docs/voice/tts/providers/fishaudio)
  - The MultiActor-Audiobook paper has an LLM write delivery instructions for each sentence — [arXiv](https://arxiv.org/pdf/2505.13082)
- [DIFF] ElevenLabs production tools:
  - generates per paragraph, with bars showing what's generated
  - "generate ahead" while playing
  - regenerate one section; generation history
  - per-paragraph volume and fades
  - Actor Mode: record a reference read to steer delivery
  - music and SFX timeline tracks
- [TS for converters] Pause tags `[break]`/`[pause:N]`, MusicGen interludes, translate before synthesis, resume after a crash (ebook2audiobook). Parallel chapter threads, Whisper QA (`--minratio`), pick start/end chapters (epub2tts). Interactive chapter picking with Kokoro-82M (audiblez — [GitHub](https://github.com/santinic/audiblez)).
- [DIFF] Listener-chosen narrator at playback: ElevenLabs "dynamic narration"; Storytel VoiceSwitcher (human vs AI voices). Storytel's own stat: 89% of its listeners have abandoned a book over the voice — [Storytel](https://press.storytel.com/post/storytel-launches-voiceswitcher-feature)
- Credits: in ElevenLabs, previews with custom audio cost credits, and export fills in ungenerated sections automatically. **I found no app that shows a cost estimate before generating** (gap → DIFF).
- Speaker attribution research:
  - Llama-3-8B beat BookNLP+ by about 12 points but did slightly worse on an unseen novel; a hybrid looks best (my inference from the papers) — [arXiv](https://arxiv.org/pdf/2406.11380)
  - VoxNovel uses BookNLP — [GitHub](https://github.com/DrewThomasson/VoxNovel)
  - gutenberg-reader runs locally on Ollama and fixes misattributed back-and-forth dialogue — [link](https://awesome.ecosyste.ms/projects/github.com%2Fanders94%2Fgutenberg-reader)

## 6. Export
- [TS] M4B with chapters, cover and metadata (ebook2audiobook defaults to it; epub2tts; audiblez via ffmpeg). ElevenLabs offers only MP3/WAV, as one file or a chapter ZIP, plus subtitles and AAF — no M4B.
- **Gotcha:**
  - FFmpegKit was retired and archived in April 2025.
  - Google Play has required 16 KB page sizes since November 2025.
  - Community forks exist, but check that they include an AAC encoder and check GPL licensing.
  - Media3 Transformer writes MP4, but chapter atoms are UNVERIFIED.
  - M4B should use QuickTime-style chapters.
  - Sources: [ffmpeg-kit README](https://cdn.jsdelivr.net/gh/arthenica/ffmpeg-kit@main/README.md), [Mp4Muxer](https://developer.android.com/reference/androidx/media3/muxer/Mp4Muxer)
- [DIFF] ACX: 192 kbps CBR MP3, opening and closing credits, retail sample of 5 min or less. Whether ACX accepts AI narration is UNVERIFIED; one third-party checklist says human narration only unless authorized — [ACX](https://help.acx.com/s/article/acx-audio-submission-requirements)
- [DIFF] Push to Audiobookshelf (ebook2audiobook); RSS feeds (Audiobookshelf).
- [NOVEL] Export EPUB 3 with Media Overlays, i.e. a read-along book that works in other reading apps.

## 7. Sync and backup
- [TS] Each book keeps its own position (Voice). Sync across devices: Audiobookshelf per user; Libby syncs loans, notes, bookmarks and progress; BookPlayer Pro cloud sync; Plappa via iCloud.
- [DIFF] KOReader sync (progress via sync.koreader.rocks; full annotation sync via a plugin); highlights to Readwise (Readest — [docs](https://readest.com/docs/sync)). Download your notes (Libby).
- [TS] Automated backups (Audiobookshelf). Privacy as a selling point: no account, ads or analytics (Voice, VoicePlus).

## 8. Android integrations
- [TS] Media3 MediaSession drives the notification and lockscreen controls.
- [TS] Android Auto through a MediaLibraryService browse tree: one root; containers are browsable; a chapter is a playable leaf. Targeting SDK 34+ needs the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission. Android Automotive needs its own module — [Android for Cars](https://developer.android.com/media/implement/surfaces/cars)
- [TS] Home-screen widget (Voice, Smart ABP).
- [DIFF] Wear OS: Horologist media toolkit on Media3, MediaDownloadService, Wi-Fi-only downloads via WorkManager, keep audio off the watch speaker — [Wear OS](https://developer.android.com/media/implement/surfaces/wear-os)
- [DIFF] Chromecast (Audiobookshelf, Smart ABP).
- **Gotcha:**
  - Google Assistant is being replaced by Gemini on Android from 2026-09-04.
  - Its successor, AppFunctions, needs Android 16+ and was in private preview as of May 2026.
  - Gemini lacks some third-party media integrations, and its Android Auto voice support was incomplete in mid-2026. Treat voice control as unstable — [byteiota](https://byteiota.com/google-assistant-ends-september-4-what-developers-must-do-now/)
  - Android 16 Live Updates were limited to rideshare, delivery and navigation as of May 2025, so media probably doesn't qualify (inference).

## 9. Accessibility
- [TS] TalkBack labels (Plappa had VoiceOver ordering issues); large controls; shake gestures.
- [DIFF] OpenDyslexic font (Libby); highlighting marketed for dyslexia and ADHD (ElevenReader); discounts for students and people with disabilities (ElevenReader).

## 10. Stats, streaks, goals
- [DIFF] Real-time stats, monthly insights, shareable stats (Audible — [help](https://help.audible.com/s/article/view-your-listening-stats)). Badges were reported in 2017; whether they still exist is UNVERIFIED.
- [DIFF] Streaks (Prologue); listening goal plus reading stats (Storytel); listening log (VoicePlus).

## 11. AI companion moves by incumbents (2025–26)
- Spotify Recaps (Nov 2025 beta): unlocks after about 15–20 min, covers only what you've heard, iOS, English — [Spotify](https://authors.spotify.com/blog/recaps)
- Kindle:
  - Recaps (Apr 2025): series summaries
  - Ask this Book (Dec 2025): answers only up to your current position; US, iOS; Android in 2026 is UNVERIFIED — [Engadget](https://engadget.com/ai/kindles-in-book-ai-assistant-can-answer-all-your-questions-without-spoilers-190609961.html)
- Audible (beta from 2026-10-01) — [Android Authority](https://androidauthority.com/audible-character-guide-visual-explorer-interactive-story-3718603):
  - Character Guide: real-time "who's speaking" with spoiler-free character cards
  - Interactive Story: chat with a character (Renfield)
  - Visual Explorer: maps and photos at key points without pausing (coming soon)
- ElevenReader GenFM: a two-host podcast generated from a document. Speechify: scan a page, AI chat, celebrity voices.

## 12. Settings, customization, offline
- Kids mode with a PIN (Storytel). Long-press to save a setting as the default for new books (Smart ABP).
- Offline is a paid feature in ElevenReader (Ultra plan, third-party sources), and its free tier is 10 h/month. **Fully offline playback once a book is generated is a DIFF for us.**
- Discovery is mostly N/A for bring-your-own EPUB. OPDS and public-domain catalogs were not researched (UNVERIFIED).

## "And then some": ideas no current app does well
1. **LLM casting director** [NOVEL]: BookNLP+LLM speaker attribution, a character bible (age, gender, accent, temperament), automatic matching to the provider's voices, and a confidence score per line with a queue of uncertain lines to review.
2. **Series casting memory**: the same character gets the same voice and pronunciations in every book of a series.
3. **"Previously on…" recap** for any EPUB, read in the narrator's voice, limited to what you've heard, offered automatically after N days away.
4. **Ask-the-book by voice**, fenced at your position, with answers that cite timestamps you can jump to.
5. **Live character cards and relationship graph** (Audible's Character Guide for any book), revealing only what you've heard.
6. **Adaptive scene direction**: an LLM tags each scene's mood and tension, which becomes Fish emotion tags and pacing. A "performance intensity" slider from flat narration to radio drama.
7. **Ambient soundscapes and SFX** per scene with ducking under the voice, plus an intensity slider (ElevenLabs has a manual timeline; nobody does it automatically).
8. **Pre-generation cost and ETA estimate**: characters × provider rate, remaining quota, budget caps, and just-in-time generation that stays N chapters ahead of the listener.
9. **Automatic pronunciation lexicon**: an LLM flags invented names and proposes IPA, you preview in context, and dictionaries can be exported per series.
10. **QA guardian**: transcribe the generated audio back to text to catch skipped words, mispronunciations and artifacts, then regenerate only the flagged sentences.
11. **Smart handling of non-prose content**: footnotes read inline, deferred, or skipped; images and tables narrated via LLM alt-text; code and math handled sensibly.
12. **Narrator hot-swap and A/B auditions** on any passage, with fast regeneration (dynamic narration controlled by the listener).
13. **Page Match for any book**: OCR of a printed page, fuzzy-matched against our own EPUB text.
14. **Dialogue-aware navigation**: skip to the next line of dialogue, "this character's lines only", and per-character volume or speed normalization.
15. **Language-learning mode**: bilingual read-along, translate-then-narrate, loop a sentence, shadowing.
16. **Smart bedtime mode**: stop at chapter end, gradually lower speed, pitch and volume, and let the soundscape continue after the voice stops.
17. **Headset-gesture smart bookmark**: saves an LLM summary of the last 30 s, and voice notes on bookmarks are transcribed.
18. **Shareable text+audio clips**: waveform plus captions video (an extension of Audible's clips).
19. **Chat with a character** fenced at your position (Audible's Interactive Story for any book), and an **auto Visual Explorer** with a map, glossary and timeline.
20. **Shareable casting and direction presets** (configs only, not audio, to avoid copyright problems).
21. **EPUB3 Media Overlay export**, so the generated audio becomes a read-along book in other readers.

## Decision points to raise with the developer
- **Fish licensing:**
  - Open weights are free only for research and non-commercial use; commercial use needs a license.
  - "s2.1-pro free to developers" is UNVERIFIED. Fish's S2 page doesn't list a free tier; Telnyx lists `s2.1-pro`.
- **Word timestamps** for highlighting (TTS output vs STT or forced alignment).
- **On-device M4B muxing** strategy, given FFmpegKit's retirement.
- **Legal posture** on DRM and on sharing audio generated from copyrighted books.
- **Voice-assistant scope**, now that Gemini has replaced Assistant.
- **Whether ACX or other distribution is a goal.**