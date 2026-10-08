## BetterBooks: learning science, product survey and a proposed internalization loop

### A. Learning science: what the evidence says and what it means for the app

**Strong evidence (build around these)**
- **Retrieval practice (testing yourself).**
  - Rowland 2014: average g=0.50 compared with restudying. Recall-type practice beats recognition, and the effect is larger when the final test comes a day or more later. [doi](https://doi.org/10.1037/a0037559)
  - Adesope et al. 2017: practice tests beat restudy and every other comparison condition. The overall g of about 0.61 is UNVERIFIED (secondary sources); the largest effects came with a 1–6 day lag. [PDF](https://education.wsu.edu/documents/2018/01/rethinking-use-tests.pdf/)
  - Karpicke & Blunt 2011 (*Science*): free recall after reading beat concept-mapping one week later, including on inference questions. Learners predicted the opposite. [Purdue](https://www.purdue.edu/newsroom/research/2011/110120KarpickeScience.html)
  - Feedback roughly doubles the effect (g 0.73 vs 0.39). This figure is UNVERIFIED (secondary).
- **Spacing.**
  - Cepeda 2006 meta-analysis: 839 assessments. The best gap between sessions grows with how long you need to remember. [abs](https://digitalcommons.usf.edu/psy_facpub/1771/)
  - Cepeda 2008: the optimal gap is about 20–40% of a 1-week test delay but only 5–10% of a 1-year delay. [PDF](https://www.evullab.org/pdf/CepedaVulRohrerWixtedPashler-PS-2008.pdf)
- **Dunlosky et al. 2013 overall ratings.**
  - High utility: practice testing and distributed practice.
  - Low utility: summarization, highlighting and rereading.
  - Moderate (my recollection, not confirmed in what I read): elaborative interrogation, self-explanation and interleaving. [APS](https://www.sciencedaily.com/releases/2013/01/130110111734.htm)

**Moderate evidence**
- **Self-explanation:** g=0.55 across 64 reports. It beat instructor-provided explanations, and extra time-on-task did not explain the gain. The authors call for computer-generated prompts. [Bisra 2018](https://www.Gwern.net/doc/psychology/spaced-repetition/2018-bisra.pdf)
- **Elaborative interrogation ("why is this true?"):** moderate in Dunlosky. d=0.56 is from Donoghue & Hattie 2021, read only through secondary summaries. It works better with precise, self-generated elaborations and higher prior knowledge. [summary](https://www.structural-learning.com/research-briefings/ask-why-and-how)
- **Generation effect:** d=0.40 over 86 studies. [Bertsch 2007](https://pubmed.ncbi.nlm.nih.gov/17645161/)
  - **Self-made flashcards beat premade ones:** d=0.45 on definition questions and 0.29 on application questions, with study time matched. Most students preferred the premade decks. [Pan et al., JARMAC](https://www.techlearning.com/news/tech-tools-provide-flashcards-to-students-thats-not-a-good-thing-says-new-research)
- **Learning by teaching:** g=0.35 for preparing to teach and 0.56 for actually teaching; interactive teaching works better. [Kobayashi](https://metatoc.com/papers/112234-learning-by-preparing-to-teach-and-teaching-a-meta-analysis)
- **Pre-questions:** g=0.54 for the information that was asked about, but only g=0.04 for everything else. Pre-questions narrow attention; they do not boost learning in general. [St. Hilaire 2024](https://par.nsf.gov/servlets/purl/10511812)
- **Concrete examples:** a replication found d=0.30. Examples that are *provided* beat examples learners generate themselves (Rawson & Dunlosky 2016). This is a legitimate place for the AI to do the work. [replication](https://cronfa.swan.ac.uk/Record/cronfa59139)
- **Outlines and graphic organizers made by the learner:** g≈0.59 for both memory and comprehension. Instructor-provided outlines: 0.61 for memory and 0.34 for comprehension. An AI-built concept map counts as "instructor-provided": helpful, but weaker than a map the user builds. [summary](https://sic.usach.cl/entities/publication/8e96b16d-63bf-4dd2-a130-a5eca2f7be48)

**Weak, mixed or harmful**
- **Highlighting:**
  - When the learner highlights: memory g=0.36, comprehension only 0.20.
  - When an instructor highlights: 0.44 for both. AI "key passage" marks might therefore help, but they remove the act of choosing. [EPR 2022](https://link.springer.com/10.1007/s10648-021-09654-1)
- **Interleaving:** g=0.42 overall, but blocking wins for word material (−0.39) and the effect is not significant for expository text. Mixing books in a review session is fine for scheduling, but do not market it as "interleaving". [Brunmair & Richter 2019](https://www.psychologie.uni-wuerzburg.de/fileadmin/06020400/2019/Brunmair_Richter_in_press__2019_META-ANALYSIS_OF_INTERLEAVED_LEARNING.pdf)
- **Note-taking:**
  - A pre-registered trial (405 students aged 14–15, tested 3 days later) found note-taking, alone or alongside an LLM, beat using an LLM alone for both comprehension and retention. Students still preferred the LLM. [C&E 2025](https://scale.stanford.edu/ai/repository/effects-llm-use-and-note-taking-reading-comprehension-and-memory-randomised)
  - The longhand-over-laptop advantage did not replicate (Urry 2021). [APS](https://www.psychologicalscience.org/news/releases/no-benefit-handwriting-notes.html)
- **The illusion of fluency with AI:**
  - Learning from an LLM synthesis left people with shallower knowledge than web search, even with the same facts (7 experiments, n=10,462; the depth measure may be partly self-reported). [Melumad & Yun, PNAS Nexus 2025](https://www.psypost.org/learning-from-ai-summaries-leads-to-shallower-knowledge-than-web-search/)
  - Unguarded GPT-4 raised practice scores by 48% but cut later exam scores by 17%. A tutor version with guardrails only avoided the harm. [Bastani, PNAS 2025](https://ideas.repec.org/a/nas/journl/v122y2025pe2422633122.html)
  - Students in active-learning classes learned more but *felt* they learned less. [Deslauriers 2019](https://openlab.citytech.cuny.edu/gamelab/files/2023/01/Deslauriersa-et-al-2019.pdf)
  - "Your Brain on ChatGPT" (n=54) is a preprint that has not been peer reviewed. Treat it as weak evidence. [arXiv](https://www.media.mit.edu/publications/your-brain-on-chatgpt/)
- **Grading free recall with an LLM:** moderate agreement with human raters (r≈0.65 on narrative recall, n=28). Checklist-style yes/no criteria agree better than graded scales. [PMC](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12739032/)
- **LLM-written quiz items:** psychometrically weaker than human-written ones, so each question should cite its source passage. [AIME-Con 2026](https://aclanthology.org/2026.aimecon-main.21/)

**Listening versus reading**
- **Overall:** no reliable difference (46 studies, g=0.07). Reading wins on inference questions (g=0.36) and when the reader sets the pace (g=0.13). [Clinton-Lisell, RER](https://files.eric.ed.gov/fulltext/EJ1403866.pdf)
- **Reading while listening (immersion reading):** g=0.18 overall. The benefit appears only when pacing is imposed (0.41) and disappears when self-paced (0.06). Struggling and second-language readers may benefit, but there are too few studies to say. [ERTP 2023](https://commons.und.edu/ehb-fac/75)
- **Mind wandering:** listening produced the most mind wandering and the worst memory of three formats (reading aloud, reading silently, listening). [Varao Sousa 2013](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3842750/)
- **Playback speed:** little cost up to 2x; performance declines beyond that. [Murphy, UCLA](https://castel.psych.ucla.edu/wp-content/uploads/sites/111/2022/03/Applied-Cognitive-Psychology-2021-Murphy-Learning-in-double-time-The-effect-of-lecture-video-speed-on-immediate-and.pdf)
- **What this means for the app:** audio needs built-in spoken check-ins to fight mind wandering. Immersion reading helps attention and pacing, not retention as such.

**FSRS (the spaced-repetition scheduler)**
- **Benchmark (log loss, excluding same-day reviews; lower is better):** FSRS-6 0.346, FSRS-5 0.356, FSRS-4.5 0.363. FSRS-7 variants are being tested. The README text I could read has no SM-2 row. [srs-benchmark](https://github.com/open-spaced-repetition/srs-benchmark)
  - The "20–30% fewer reviews than SM-2" claim comes from vendors and is UNVERIFIED.
- **Anki 25.07** shipped FSRS-6 with fsrs-rs 3→4.1.1. Whether FSRS is now on by default is UNVERIFIED. [release](https://github.com/ankitects/anki/releases/tag/25.07)
- **Kotlin and Java options:**

| Library | Algorithm | License | Notes |
|---|---|---|---|
| **FsrsKt** (TheRockYT) | FSRS-6 | GPL-3.0 (compatible with BetterBooks) | Kotlin Multiplatform, on Maven Central, started 2026 |
| **java-fsrs** (open-spaced-repetition org) | version not stated, 21 weights | MIT | Scheduler only; it cannot fit parameters to a user's history |
| **fsrs-kotlin** (LordDarthArt) | FSRS-5 | — | Kotlin Multiplatform |

  - Sources: [klibs](https://klibs.io/project/TheRockYT/FsrsKt), [java-fsrs](https://github.com/open-spaced-repetition/java-fsrs)
  - Fitting parameters to a user's own history needs fsrs-rs through JNI.
- **AnkiDroid instant-add API:** an LGPL library that adds notes through AnkiDroid's ContentProvider. It needs a runtime permission and a `<queries>` entry in the manifest. [wiki](https://github-wiki-see.page/m/ankidroid/Anki-Android/wiki/AnkiDroid-API)

### B. Existing products: how they actually work

- **Readwise**
  - Daily Review resurfaces highlights.
  - Mastery turns a highlight into a cloze or Q&A card on a half-life model: 7, 14 or 28 days at the start, and a card returns once predicted recall falls to 50% or below.
  - Frequency tuning per document; AI Themed Reviews pick highlights by embeddings and count toward the streak. [Mastery](https://help.readwise.io/article/26-how-does-the-readwise-spaced-repetition-algorithm-work), [Themed](https://docs.readwise.io/readwise/guides/themed-reviews)
  - **Ghostreader** (in Reader): define, simplify and summarize; custom prompts; bring-your-own OpenAI key; chat with one document or the whole library, with citations. [docs](https://docs.readwise.io/reader/guides/ghostreader/overview)
  - **Gap:** reading, review and AI live in separate apps, and there is no graded free recall.
- **RemNote:** select text, tap "Create AI Cards", preview, then save. Concept/descriptor cards; FSRS v6 or SM-2; target retention 90%. [AI cards](https://www.remnote.com/feature/ai-flashcards), [FSRS](https://www.remnote.com/feature/fsrs)
- **Mochi:** AI is a Pro feature ($5/mo) with monthly caps; MCP integrations are available. [pricing](https://mochi.cards/pricing/)
- **Kindle**
  - Recaps (April 2025) cover a whole series.
  - Story So Far (US, June 2026) is fenced at your position, but some recaps still contain spoilers, with a warning. Not on Android.
  - Ask this Book: iOS since December 2025; Android is listed as "later this year".
  - X-Ray lists characters and terms; Vocabulary Builder automatically saves words you look up. [Engadget](https://engadget.com/2191716/amazon-story-so-far-feature-is-finally-rolling-out-to-kindles), [Recaps](https://www.aboutamazon.com/md/news/books-and-authors/kindle-recaps-feature-ebook-series-refreshers)
- **NotebookLM, renamed Gemini Notebook on 2026-07-16** [Workspace](https://workspaceupdates.googleblog.com/2026/07/notebooklm-now-gemini-notebook.html)
  - Flashcards and quizzes (September 2025) with Easy/Medium/Hard and an "Explain" button that cites the source.
  - Learning Guide: a Socratic chat mode.
  - Audio Overview formats: Deep Dive, Brief, Critique, Debate. Also Study Guide reports and Video Overviews. Mind maps launched in 2025 (date UNVERIFIED). [Google](https://blog.google/technology/google-labs/notebooklm-student-features/)
  - **Gap:** no spaced scheduling and no position fencing.
- **Shortform:** guides with exercises, reflection prompts and comparisons across books. AI features UNVERIFIED. [Shortform](https://www.shortform.com/blog/shortform-vs-getabstract/)
- **Blinkist:** Guides with quizzes; Pro AI assistant; no native spaced repetition. **Headway:** select an insight to make a flashcard, plus streaks, challenges and a growth plan. [summary](https://www.befreed.ai/blog/blinkist-review-2026), [Headway](https://apps.apple.com/app/id1457185832)
- **Glasp:** an "AI Clone" grounded in your highlights, with citations; MCP; Markdown export. **Matter:** AI Co-Reader, Apple only. **Fabric:** a memory engine that links notes and flags contradictions. **Heptabase:** card whiteboards, AI over whiteboards, related-card suggestions, bring-your-own key. **LiquidText:** excerpts linked back to their source, ink links, no AI found. [Heptabase](https://wiki.heptabase.com/work-with-ai), [Glasp](https://glasp.ai/), [LiquidText](https://www.lawnext.com/2025/04/liquidtext-innovative-annotation-tool-for-complex-documents-adds-real-time-collaboration.html)
- **ElevenReader:** GenFM two-host podcast and a Voice Chat beta grounded in the book's text. **Speechify:** AI quizzes, podcast styles, chat. [ElevenLabs](https://elevenlabs.io/docs/help-center/product/mobile-apps/eleven-reader/how-does-voice-chat-in-eleven-reader-work)
- **Audible (limited beta from 2026-10-01):** Character Guide is live; Interactive Story and Visual Explorer are coming; asking questions is planned. [Android Authority](https://androidauthority.com/audible-character-guide-visual-explorer-interactive-story-3718603)
- **StoryGraph:** mood and pace stats, streaks, ML recommendations, no generative AI. **Oku:** tracking only.
- **Duolingo**
  - Allowing two streak freezes raised daily active learners by 0.38%. [blog](https://blog.duolingo.com/improving-the-streak)
  - The streak wager raised day-7 retention by 14%.
  - People on a 7-day streak return the next day at 2.4 times the rate of those without one (correlational).
- **No product closes the whole loop:** fenced reading/listening → self-made cards → FSRS → audio review → links across books.

### C. The proposed BetterBooks internalization loop

**How requests are counted:** one cached "chapter pack" per chapter, made in a single long-context call, returns JSON with:
- a gist
- 3–5 pre-questions
- 5–10 key ideas as yes/no rubric points
- concepts and people
- candidate cards
- "apply it" prompts

The pack is prefetched one chapter ahead, and several chapters can share one call if the model's context allows. Packing a 40-chapter book on import would use 40 requests and blow the 50/day budget; that is why it prefetches.

| # | Feature | Evidence | Requests | Fiction vs non-fiction |
|---|---|---|---|---|
| **Before** | | | | |
| 1 | Primer of 3–5 pre-questions on the chapter's central ideas, not a summary | Moderate but narrow | 0 extra (pack) | Non-fiction: on. Fiction: off; offer opt-in "predict what happens next" built only from text before the current position |
| 2 | "What do you already know about X?" prompt (activates prior knowledge) | Theory-backed | 0 | Non-fiction only |
| **During** | | | | |
| 3 | End-of-section check-in, spoken in audio: "One sentence: what was the claim?" User answers, then sees the gist | Retrieval: high | 0 if user self-checks | Non-fiction: default on. Fiction: off |
| 4 | Explain / simplify / example on a selection; offers "Why might this be true?" before answering | Concrete examples: moderate | 1 per tap, cached by passage hash | Fiction: fenced at position; mainly people, places and vocab (an X-Ray equivalent, mostly 0 from the pack) |
| 5 | Highlight → suggested card: AI proposes the question, the user types the answer in their own words, AI compares with the source. Accepting the AI's own answer is a secondary option | Generation and self-made cards: moderate | 1 per chapter, batched | Fiction: character, term and quote cards, opt-in |
| **After** | | | | |
| 6 | End-of-chapter free recall (typed or voice), scored by the LLM against the pack's rubric points; shows what was missed, with links to the passages | Retrieval: high; grading reliability moderate | 1 | Non-fiction: default on. Fiction: an optional "retell it" that feeds the recap |
| 7 | Teach-it-back (Feynman style): user explains by voice using on-device speech recognition; the LLM plays a confused student and asks one follow-up | Teaching: moderate | 1–2 per part or week | Non-fiction mainly |
| 8 | FSRS daily review mixing all books; each card cites and links its source passage | Spacing: high | 0 (local); optional typed-answer grading 1 per session, batched | Both |
| 9 | Cross-book concept graph: concepts come from packs, links come from on-device embeddings, the LLM explains a link only when tapped. Users can rearrange and annotate (learner-built maps beat provided ones) | Organizers: moderate; cross-book linking speculative | 0 + 1 per tap | Non-fiction mainly |
| 10 | "Apply it": write one implementation intention, get a 7-day follow-up nudge | Speculative / UNVERIFIED for books | 0; feedback 1 | Non-fiction only |
| 11 | Book synthesis: the user writes first, then the AI compares it with the packs' key ideas | Generation: moderate | 1–2 | Fiction: themes and characters reflection |
| **Resurfacing** | | | | |
| 12 | Glance widget showing "N due" with one card face; one daily notification at a time the user picks | Spacing | 0 | Both |
| 13 | Audio review playlist: prompt, pause, spoken answer, reveal. Uses Android system TTS to save Fish credits | Retrieval + spacing; audio-specific evidence UNVERIFIED | 0 | Both |

**Budget at 50 requests/day:** a non-fiction chapter costs about 8 requests (1 pack + 1 card batch + 1 recall + ~5 explains). That covers 3–5 chapters a day with room left for Ask and Recap. At 1,000/day the limit stops mattering. Pace background prefetch at about 1 request every 4 seconds or slower to stay under 20/min.

**Anti-patterns to block**
- **Summary instead of reading:** AI summaries as the default for books the user plans to read (Melumad & Yun; Bastani). Offer recaps only when the user comes back after a gap.
- **Bulk auto-generated cards:** the premade-deck penalty plus review debt. Cap new cards per day and require the user to accept each card.
- **Answering before the user tries in quizzes:** offer a hint first, then the answer.
- **Rewarding volume:** don't count pages or highlights as progress; count retrievals.
- **Unchecked fluency:** add a "predict your score" step before quizzes and show predicted vs actual (Deslauriers).
- **Highlight hoarding** without any follow-up processing.
- **Quiz items without a cited source passage.**

### Decision points for the developer

1. **How much AI study mode is on by default.** Options: Off / Light (recap, explain, takeaways) / Study (full loop). **Recommended:** set it per book from the detected content type. Fiction gets Light; non-fiction gets Study, with every prompt skippable in one tap.
2. **Who writes the cards.** Options: AI writes them / AI suggests the question and the user answers / manual. **Recommended:** AI suggests, the user answers, AI compares.
3. **FSRS library.** Options: FsrsKt / java-fsrs / port py-fsrs / fsrs-rs through JNI. **Recommended:** FsrsKt with default parameters behind an interface, after checking it against FSRS-6 test vectors. Add the parameter optimizer (fsrs-rs) later.
4. **Anki interoperability.** Options: built-in only / push to AnkiDroid / both. **Recommended:** built-in review plus optional AnkiDroid instant-add and CSV/Markdown export.
5. **Grading free recall.** Options: LLM score / LLM rubric checklist with the user confirming / self-grading. **Recommended:** the rubric checklist. The user always sets the FSRS rating.
6. **Pre-questions in fiction.** Options: off / prediction prompts / on. **Recommended:** off by default, with prediction prompts opt-in.
7. **When LLM work happens.** Options: on demand / one chapter ahead / whole book on import. **Recommended:** one chapter ahead, batched, cached forever, with a visible count of requests left today.
8. **Habit mechanics.** Options: none / daily streak / weekly goal. **Recommended:** a weekly review-days goal with 2 freezes and no guilt-inducing copy.
9. **Linking ideas across books.** Options: on-device embeddings / LLM. **Recommended:** on-device embeddings (which model is UNVERIFIED), with the LLM only explaining a link when tapped.
10. **Speech recognition for teach-back.** Options: Android SpeechRecognizer / cloud. **Recommended:** on-device with a typed fallback. Offline support on ColorOS is UNVERIFIED and needs a test on the Find X9.