# Kepler: Content, Audio & Intelligence Pipelines

> Part of the master plan, [`../PLAN.md`](../PLAN.md). The module and interface names below match [`architecture.md`](architecture.md). Binding inputs are [`../decisions.md`](../decisions.md) and [`../research/`](../research/).

---

## 1. Ingest: from file to Text Spine (`:engine:ingest`)

```
SAF URI ──copy+hash──▶ detect format ──▶ [convert to EPUB3 if TXT/MD/HTML] ──▶ Readium open
   └─ encrypted? (rights.xml / license.lcpl / non-font encryption.xml entries)
        └─ yes → neutral error: "This file is encrypted and can't be opened."  (#38, no other copy)
   font-obfuscation-only encryption.xml → normal path (Readium deobfuscates)
──▶ structure ──▶ blocks ──▶ sentences (ordinals) ──▶ FTS5 ──▶ profile heuristics ──▶ ready
                                                     └─(idle) embeddings job (if model present)
```

1. **Copy and identify.**
   - Stream the file into `files/books/<id>/original.<ext>`, computing SHA-256 and the kosync partial MD5 on the way.
   - If the file was already imported, reuse that book and offer to merge positions.
2. **Convert** (`:engine:convert`). Formats other than EPUB become a minimal EPUB 3: nav document, one XHTML file per chapter, metadata from the file name or front matter.
   - TXT: split chapters on heading patterns ("Chapter 1", "CHAPTER ONE", Roman numerals).
   - Markdown: commonmark-java.
   - HTML: jsoup clean-up.
   - PDF does **not** convert. It gets its own spine through `PdfReaderEngine` (§1.6).
3. **Structure.**
   - **TOC to spine mapping.** Each nav/NCX entry, including `#fragment`s inside one file, is resolved to a (spine index, element) start point. A section runs until the next entry starts.
   - Nesting depth becomes `section.level`. If the TOC is missing, sections are built from `h1`–`h4`.
   - Spine items with `linear="no"` become note-like sections, excluded from linear reading and from the frontier.
4. **Classify each section** as FRONT / BODY / BACK / NOTES / INDEX / BIBLIOGRAPHY / GLOSSARY / ACK, in this order:
   1. EPUB3 `epub:type` (frontmatter, bodymatter, backmatter, toc, copyright-page, index, footnotes, endnotes, bibliography, glossary, acknowledgments).
   2. nav `landmarks`, then the EPUB2 `<guide>`.
   3. Title heuristics ("Contents", "Copyright", "Notes", "Index", "Bibliography", "About the Author"…).
   4. Density heuristics (numbered short lines suggest an index; lines full of URLs and years suggest a bibliography).
   - Default `included` flags follow #31 and the non-fiction rules. The user can toggle any section in Setup.
5. **Extract blocks** by walking the XHTML DOM (Readium Content iterator as the primary source, with jsoup as a fallback for structure). Each block gets a `kind` and `metaJson`:

| DOM signal | Block kind | Extra captured |
|---|---|---|
| `p`, `div` with text | PARAGRAPH | dialogue flag (opening quote or em-dash) |
| `h1`–`h6` | HEADING | level |
| `li` | LIST_ITEM | list type and index |
| `blockquote`, `epub:type=epigraph` | QUOTE / EPIGRAPH | attribution |
| `table` | TABLE | cells matrix, caption |
| `figure`, `img` | FIGURE | alt text, caption, image href |
| `pre`, `code` | CODE | language hint |
| `math` (MathML), `\(…\)` | MATH | source |
| `aside`, `epub:type=sidebar` | SIDEBAR | title |
| `epub:type=footnote/endnote/rearnote` | NOTE | note ID, back-references |
| verse (`.poem`, `.verse`, line-broken `br` blocks) | VERSE_LINE | stanza index |

   - **Noise is stripped or kept as metadata:** `epub:type=pagebreak` becomes a print page marker (#56); hidden (`display:none`) text and `noteref` superscripts become citation markers; drop-cap spans are re-joined so "T he" doesn't happen.
   - **Ruby:** keep the base text and put the `rt` reading into a lexicon hint.
6. **Segment.** `BreakIterator` sentence instance per block language, guarded against abbreviation splits, gives dense global ordinals. Headings, list items and verse lines are always a single "sentence" each.
7. **Index.** FTS5 is populated in batched transactions of 2,000 rows.
8. **Profile heuristics** (no LLM):
   - Signals: BISAC/subject metadata, dialogue density, the share of NOTE/TABLE/FIGURE blocks, citation markers per 1,000 words.
   - Output: a provisional `contentType` and default profile. The first chapter pack (§4.3) confirms or corrects it.

**Target:** a 100k-word EPUB ingests in 8 s or less on the Find X9. Ingest runs in a WorkManager expedited job, and the reader can open as soon as the structure step is done.

### 1.6 PDF text spine (`:engine:pdf`)
- Get per-page text and character boxes from `PdfDocument.getPageContent()`.
- Remove lines that repeat in the top or bottom 8% across 3 or more pages, which catches running headers, footers and page numbers.
- Re-join hyphenated line ends where the joined word is in the WordNet lexicon.
- Merge lines into paragraphs using vertical gap and indent statistics. Headings are detected from relative font size only when the API provides it, and from short centred lines otherwise.
- Map each sentence to (page, charStart, charEnd) for highlights.
- Scanned PDFs (no text) get a neutral "no text layer" status. Reading works; read-aloud and AI are disabled until OCR arrives (later phase).

---

## 2. Normalization & pronunciation (#30, #31, non-fiction rules)

Normalization produces a **spoken text for each sentence**, plus an offset map back to the source characters. It is computed lazily per segment and cached.

1. **Deterministic rules** (JVM, golden-tested): numbers (cardinal, ordinal, year-like, decimals, ranges), currency, percentages, dates and times, units, Roman numerals in context ("Chapter IV" → "Chapter four", "George IV" → "George the Fourth", "Part ii" → "Part two"), common abbreviations, URLs and emails (spoken as domain only, or skipped), symbols, ellipses and dashes into pause hints.
2. **Citation handling** (per profile):
   - Strip superscript and bracketed reference markers.
   - Parenthetical author–year citations ("(Smith, 2019; Lee 2020)") are kept, shortened to "Smith and Lee", or skipped. The non-fiction default is **skip**.
3. **Notes:**
   - INLINE: read after the sentence that references them.
   - SECTION_END or CHAPTER_END: queued (the default).
   - SKIP.
   - The voice says "Note:" before each note, and a different tone can be used for notes when the cast allows.
4. **Tables:** ROWS ("Row one: Country, France; Population, …"), SUMMARY (an LLM-written spoken summary, cached), or SKIP with a mention ("A table follows; see the book."). The non-fiction default is SUMMARY when the LLM is available, otherwise a mention.
5. **Figures:** ALT (alt text and caption), DESCRIBE (vision LLM, cached), or SKIP with a mention. The default is ALT, falling back to a mention.
6. **Code and math:** SPEAK (simple MathML rendered as speech; code as a short description) or MENTION. The default is MENTION.
7. **Structure cues:** heading announcement ("Chapter 3. The Origin of Variation."), followed by a section-level pause; natural list enumeration; "Quote." / "End quote." only when no quote voice is assigned; announced sidebars ("Sidebar: …").
8. **Lexicon:** layers are global, then series, then book (later layers win).
   - Each entry renders through the provider: Fish `pronunciation_dictionary` (inline, up to 5,000 entries) or a `<|phoneme_start|>…<|phoneme_end|>` Arpabet override. System TTS falls back to replacement spelling.
   - Auto-flagging (#30): pronunciation candidates from the chapter pack go into the editor with a "Preview in context" button, which renders one sentence (1 TTS request).
9. **LLM-assisted normalization**, only for ambiguous spans the rules flag (heteronyms, "St." meaning Saint or Street): batched into the chapter pack request and returned as `{spanId, reading}` pairs.

---

## 3. Audio generation (#13–#24, #26–#29)

### 3.1 From blocks to ScriptIR
For each included block, in reading order:
- Apply the profile's rules from §2 to get spoken sentences.
- Attach `Speaker`:
  - NARRATOR by default.
  - QUOTE voice for QUOTE and EPIGRAPH blocks if enabled (the non-fiction "quote voice").
  - In full-cast mode, `script_line` attribution (§4.5).
- Attach `Style`: the emotion tags (#26) scaled by the **intensity slider**. 0 means no tags. 1 means section-level mood only. 2 means line-level tags. 3 means paralinguistic cues too ([laughing], [sighing]).
- Insert `Pause` nodes for structural boundaries. Their durations are rendered as playlist silence, not as tags, so they stay deterministic.

**Segmentation into requests:**
- One request per paragraph (#20).
- Very long paragraphs (more than 1,200 characters) split at sentence boundaries.
- A multi-speaker paragraph in full-cast mode becomes one request with `<|speaker:N|>` and a `reference_id` array when that paragraph's speaker count is within the provider's limit. Otherwise it splits into speaker runs.

### 3.2 Listen-now flow (#18, #69)
1. The user taps **Listen** (or Read + Listen) at sentence *s*.
2. If a cached segment covers *s*, play immediately.
3. **Else, with a Fish key and a network connection:**
   - Render a **micro-segment**: the first 1–2 sentences on their own, to reach the first byte fastest.
   - Then the rest of that paragraph, then continue paragraph by paragraph.
   - The playlist appends each segment as soon as it is encoded.
   - The just-in-time window target is 10 minutes ahead at 1x speed, scaled by playback speed.
   - The UI shows a buffer ring with "generated ahead: 6:20".
   - If the buffer runs dry, playback pauses with a spinner on the play button, and haptics signal when it resumes.
4. **Else:** system TTS starts within 500 ms of the tap (sentence-level files).
5. **Hot-swap** from system TTS to Fish at a paragraph boundary is v1.x.

### 3.3 Background generation (#18)
- Triggered from Setup ("Generate whole book" or "Generate next N chapters") or automatically ("keep 2 chapters ahead").
- `GenerationWorker` with a `dataSync` foreground service. Work is ordered by priority: the current chapter, then the next chapters, then the rest.
- Checkpoint after each segment. On a 6 h timeout or a ColorOS kill, the job resumes later with no duplicate requests, because the cache key is checked first.
- **Pre-flight estimate:** total UTF-8 bytes, cost (Fish $0 until 2026-11-30, then $15/1M bytes), estimated time, requests and storage (AAC 64k ≈ 28.8 MB/h).
- The user confirms. Caps apply (#17).

### 3.4 Voices & casting (#24, #27–#29, #41h)
- **Voice picker:**
  - Fish library filtered to `licensed=true`, with language, gender and age filters, and 6-second previews rendered from *this book's* text (1 request each, cached).
  - "Design a voice" turns a text description into 4 candidates through Fish voice design ($0.01 each), and the chosen one becomes a persistent model.
- **Narration style presets** set the intensity, speed and pause scale:
  - Lecture/Clear: non-fiction default; intensity 0–1, pauses ×1.1.
  - Conversational: intensity 1.
  - Dramatic: fiction full-cast default; intensity 2–3.
- **Narrator A/B audition** (#41h): pick 2 voices, render the same passage with both, then play A, B, A, B and choose. Switching narrators mid-book re-renders ahead of the playhead and keeps the earlier takes.
- **Series memory** (#29): `cast_assignment` and the lexicon are copied from the previous book in the series, keyed by `seriesId`. Characters are matched by name and alias.

---

## 4. Intelligence layer (`:engine:ai`)

### 4.1 Principles
- **Fence first** (#73). No passage past the fence ever enters a prompt. This is enforced in `ContextBuilder`, and the planted-marker test checks it.
- **Requests are budgeted and cached forever** (#94). One chapter pack covers most features.
- **The model never rewrites the book.** Outputs are IDs, labels, short generated prose (summaries, explanations), and questions.
- **Every claim cites its passage.** Generated items carry sentence ordinal references, rendered as tappable citation chips.
- **The user stays in the loop on learning artifacts** (#79, #82, #93). The AI proposes; the user answers, accepts or edits.

### 4.2 Book profile (1 request at import, after the first open)
Input is the metadata, the TOC, and samples from chapters 1, the middle, and the last chapter *before* the fence (in fiction, never the ending). Output:
```json
{ "contentType": "NONFICTION", "genre": "history of science", "audience": "general",
  "suggestedStyle": "LECTURE", "studyModeSuggestion": "STUDY",
  "structureNotes": ["notes are endnotes per chapter", "tables carry data"],
  "language": "en" }
```

### 4.3 Chapter pack (1 request per chapter, prefetched one ahead; #78)
Built when the user reaches 50% of chapter *n−1*, or on demand. Long-context models get several chapters per request.

**Non-fiction schema** (validated):
```json
{ "chapter": 7, "gist": "…2–3 sentences…",
  "preQuestions": [{"q": "…", "answerOrdinals": [1203, 1210]}],
  "keyIdeas": [{"id": "k1", "claim": "…", "evidenceOrdinals": [1203], "rubric": ["mentions X", "explains why Y"]}],
  "entities": [{"name": "…", "kind": "CONCEPT|PERSON|ORG|PLACE|TERM", "aliases": [], "definition": "…", "firstOrdinal": 1190}],
  "edges": [{"from": "…", "to": "…", "relation": "causes|contrasts|example_of|part_of|influenced", "evidenceOrdinal": 1240}],
  "cardCandidates": [{"q": "…", "sourceOrdinals": [1203, 1204], "type": "QA|CLOZE"}],
  "applyIt": ["…"], "takeaways": ["…"],
  "sectionCheckIns": [{"sectionId": 88, "prompt": "In one sentence, what was the claim?", "gist": "…", "rubric": ["…"]}],
  "pronunciations": [{"term": "Galápagos", "suggestion": "guh-LAH-puh-gohs"}],
  "ambiguousSpans": [{"spanId": "s1207:3-5", "reading": "Saint"}],
  "recapSummary": "…≤150 words, for stitching recaps…" }
```

**The fiction schema** replaces keyIdeas with `events` and the edges with relationships (ally, family, rival, romance, mentor).
- Its preQuestions are **prediction prompts**, generated only when the opt-in is on (#83).
- It adds `characters` (name, aliases, gender, age band, description, speech style) for casting, and `places` and `terms` for the X-Ray.

**Request math:**
- A 30-chapter non-fiction book takes about 30 pack requests, or fewer with multi-chapter packs.
- Add about 1 card-comparison batch per chapter and about 1 recall grading per chapter.
- That is roughly **90 requests for the whole book**, spread over days of reading. That is trivial at 1,000/day and fits 50/day at 3–5 chapters a day.

### 4.4 Feature services
| Service | Trigger | Requests | Output |
|---|---|---|---|
| **Explain / Define** (#60) | selection menu | 0 if WordNet has the word or a cached answer exists, otherwise 1 | definition in context, a "why might this be true?" prompt shown first in Study mode, and a concrete example |
| **Ask the book** (#41b, #89) | Learn → Ask, spark button | 1 per question | answer with `[s1234]` citations, built on fenced hybrid retrieval |
| **Previously on…** (#41a) | returning after 3 days or more, or on request | 0–1 | recap stitched from cached `recapSummary` up to the fence, plus 1 request for the partial current chapter. Fiction gets a story recap; non-fiction gets key ideas. |
| **Takeaways** | end of chapter, Learn tab | 0 (from the pack) | 3–7 bullets with citations |
| **People / Concepts cards** (#41c) | Learn tab, tap on an entity in text | 0 (from the pack) | card with description, facts up to the fence, first mention and related nodes |
| **Map** (#41c, #91) | Learn → Map | 0 | force-directed graph of entities and edges up to the fence. The user can pin, drag, rename, add their own links and annotate them. |
| **Table summary / Figure description** | ingest of non-fiction chapters (BACKGROUND priority) | 1 per table or figure (batched per chapter) | cached spoken text |
| **Card comparison** (#79) | user answers a suggested card | 1 per batch of up to 10 cards | for each card, matches / partially matches / misses, plus what was missing |
| **Free recall grading** (#82) | end-of-chapter recall | 1 | rubric checklist hits; the user confirms, then sets the FSRS rating |
| **Attribution** (full cast, #27) | generating a fiction chapter in full-cast mode | about 1 per chapter (bundled with the pack where possible) | `[{lineId, speakerId, confidence, style, intensity}]` |

### 4.5 Full-cast pipeline (fiction, #24, #26, #27)
1. Characters and aliases come from the packs, merged across chapters with alias resolution (string similarity plus LLM confirmation of uncertain merges).
2. Deterministic quote detection: curly, straight and « » quotes, em-dash dialogue, multi-paragraph quotes that stay open. This yields `lineId`s.
3. Attribution runs in windows over the chapter with the character list, and returns IDs only.
4. Lines below 0.7 confidence go into the **review queue** in Cast Review. The user can reassign a single line or every line in a scene.
5. Voices are auto-cast by gender and age band against licensed voices, and the user can change any assignment.
6. Emotion tags are rendered through the intensity slider (§3.1).

### 4.6 The internalization loop (#77–#93)
| Stage | Non-fiction (Study, default) | Fiction (Light, default) |
|---|---|---|
| **Before a chapter** | Primer card: 3–5 pre-questions, skippable, never a summary | Off. Opt-in "What do you think happens next?" |
| **During: section check-ins** (#84) | Major sections (1,500 words or more) end with "In one sentence, what was the claim?" The user types or skips, then sees the gist. In Listen mode the prompt is spoken, followed by a 6-second pause with tap-to-answer. | Off |
| **During: selection** | Highlight · Explain · Ask · Card · Define (WordNet → LLM) | Highlight · Who/What is this? (X-Ray) · Define · Card |
| **During: vocabulary** (#86) | Looked-up words are saved with their context sentence and become VOCAB cards (accepted automatically, capped) | same |
| **After a chapter** | Takeaways (from the pack) **after** an optional free-recall prompt: predict your score, write or say what you remember, see the rubric hits and misses with links | Optional "Retell it", which feeds the recap. Character cards update. |
| **Cards** (#79) | Highlights and pack candidates become suggested questions. The user answers in their own words, the LLM compares, and the user accepts. At most 10 new cards a day. | Character, term and quote cards, opt-in |
| **Daily review** (#80, #85) | Mixed FSRS queue in Notebook → Review. Widget "N due". Optional daily notification at the user's chosen time. Hint before answer. | same |
| **Habit** (#65) | Weekly goal of review days with 2 freezes. Progress counts retrievals, not pages (#93). | same |
| **v1.x** | Teach-it-back (voice), audio review playlist, "apply it" with a 7-day follow-up, book synthesis, links across books | Themes reflection |

**Anti-patterns that are blocked by design** (#93):
- Recaps are not shown when a book opens unless the user has been away 3 days or more, or asks.
- "Summarize this book" is not a default action.
- Bulk decks are not auto-generated.
- Quiz items always carry citations.
- The stats show predicted vs actual scores.

### 4.7 Positional fence details (#73)
- **Consumed intervals:**
  - Reading counts a page as consumed after a dwell of `0.6 × words / readerWPM`.
  - Listening counts sentences that were actually played.
  - Intervals merge.
- **Frontier** = the end of the contiguous run that starts at book start, or at the user's "started here" marker for books that don't start at chapter 1.
  - A jump (TOC, search, link, scrub) does not extend the frontier until about 2 pages have been read contiguously there, or the user taps "Count as read".
  - Back matter never counts.
- **Fence modes per book:** FRONTIER (fiction default); WHOLE_BOOK (non-fiction default for Ask and search); CURRENT; CUSTOM (a marker dragged on the Book Map); and the REREADING toggle (whole book).
- **Cards and quizzes** always use FRONTIER, whatever the mode.
- **Strict mode:** the title and author are removed from prompts, to reduce leakage from what the model already knows about the book.

---

## 5. Imported audio & export

### 5.1 Imported audiobooks (#37, #75)
- **M4B/MP3/M4A/Opus import:** chapters come from embedded chapter atoms (Nero `chpl` or the QuickTime chapter track) or from file boundaries. The audiobook plays through the same ReadAlongPlayer as an `ImportedSource`.
- **Pairing with an EPUB of the same book** (matched by title and author, or picked by the user): v1 uses chapter-level mapping (audio chapter *k* ↔ section *k*), confirmed by the user.
  - **Media Overlays EPUBs** (e.g. from Storyteller): SMIL `par` elements become sync rows through text-ID resolution to ordinals, giving exact read-along.
- **v1.x:** cloud speech-to-text (Fish ASR, word timestamps) per chapter, then the §5.5 aligner (architecture.md), then sentence sync rows.

### 5.2 Export (#39)
- **M4B per book:**
  1. Concatenate the chosen takes and silence per chapter with Media3 Transformer (audio-only sequence), keeping AAC as it is.
  2. Our **Mp4ChapterWriter** (pure Kotlin) rewrites `moov`. It adds `udta/chpl` (Nero chapters, up to 255) and `ilst` metadata (©nam, ©ART, ©alb, ©gen, covr) and fixes the `stco`/`co64` offsets if `moov` precedes `mdat`.
  3. The QuickTime chapter track (an Apple-friendly text track) follows in v1.x.
- **M4A per chapter** is in v1. **MP3 per chapter** comes in v1.x through jump3r (pure-Java LAME port, LGPL).
- **Read-aloud EPUB (Media Overlays)** is a later phase (#76).
