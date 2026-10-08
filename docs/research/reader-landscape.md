# Ebook reader feature catalog and BetterBooks name check (as of 2026-10-08)

Tags: **[TS]** table-stakes · **[DIFF]** differentiator · **[NOVEL]** novel. "Best:" names the app that does it best. Audiobook and player features are already in `docs/research/feature-landscape.md` and are not repeated here.

## 0. State of the field
- **Open-source readers**
  - Readest: v0.12.12 (2026-10-04), AGPL, cross-platform. It is the closest open-source rival. Its README still lists AI summaries and "Advanced Reading Stats" as in progress. https://github.com/readest/readest , https://newreleases.io/project/github/readest/readest/release/v0.12.6
  - KOReader: 2026.07.1, AGPL. https://f-droid.org/packages/org.koreader.launcher.fdroid/
  - Librera: GPL-3, 9.0.5, last updated 2025-07. https://f-droid.org/en/packages/com.foobnix.pro.pdf.reader/
- **Closed Android readers:** Moon+ 10.5 (March 2026) and ReadEra 26.05. Lithium looks stalled: its last build, 0.24.6.1, is from January 2025. https://www.apkmirror.com/?p=12901303 , https://www.apkmirror.com/?p=8246592
- **Recap and ask-the-book are now table-stakes at the big platforms:**
  - Kindle "Story So Far" recaps up to your position. US only, on e-readers and iOS (June 2026). https://bgr.com/2192985/kindle-cool-new-feature-story-so-far-june/
  - Kindle "Ask This Book" is iOS-only in the US. Android "by end of 2026" is UNVERIFIED.
  - Play Books "Book insights" offers "Catch me up" plus Q&A on more than 1M English ebooks, on Android and web, and on iOS since 2026-09-02. https://blog.google/products-and-platforms/platforms/google-play/book-insights-ios-million-ebooks/
  - Gemini Notebook "Expert Intelligence" (2026-08-27) turns owned Play Books into quizzes, mind maps and audio overviews, with citations. https://9to5google.com/2026/08/27/gemini-notebook-play-books/
  - Kobo has announced an AI recap (150 words, authors can opt out) with no date. https://www.androidauthority.com/kobo-storygraph-integration-live-3682595/
  - Boox NeoReader (firmware 4.2) lets you ask about highlighted text or areas circled with the stylus. https://shop.boox.com/en-ca/pages/firmware
- **Closest rival to the "internalize" vision: BetterReads (iOS).** It has Explain, a glossary, Character Web, Narrative Arc, Theme Heatmap, and "Today's Review" spaced recall where you answer before seeing coaching. https://apps.apple.com/app/betterreads/id6758775255
- **What's left to win on:** retention (spaced recall), non-fiction structure, any book with your own key (BYOK), and open data.

## 1. Formats
- [TS] **EPUB 2/3 reflowable and fixed layout.** Readium handles both. It does not do Media Overlays yet, and CBZ is only partial. CBR is "not planned", and MOBI and FB2 are not supported. https://github.com/readium/kotlin-toolkit
- [TS] **PDF.** Readium has no TTS or highlight decorations for PDF.
  - Best for scanned PDFs: KOReader, which reflows them with k2pdfopt.
  - Boox NeoReader runs OCR in the background while you keep reading.
  - KOReader README: https://github.com/koreader/koreader
- [DIFF] **Widest format list: Moon+.** It covers txt, html, epub, pdf, djvu, mobi, azw3, fb2, docx, odt, rtf, chm, cbz/cbr, Markdown and mht. https://www.moondownload.com/
  - Readest covers EPUB, PDF, MOBI, AZW3, FB2, CBZ, TXT, HTML and Markdown.
  - ReadEra adds DOC/DOCX/RTF, DjVu and CHM.
- [DIFF] **Comics.** Play Books Bubble Zoom uses ML to enlarge speech bubbles. Readest has Webtoon mode, 50–300% contrast and tap-to-zoom. https://readest.com/docs/reading
- [DIFF] **Web, RSS and transcripts.** Best: Readwise Reader (articles, RSS, YouTube transcripts, X threads). KOReader has Wallabag and a news downloader. Readest has web clipping. https://readwise.io/read

## 2. Typography
- [TS] **Font, size, weight, line and paragraph spacing, margins, justification, hyphenation.**
  - In Readium, hyphens, letter/word spacing, lineHeight, paragraphSpacing/Indent, textAlign, typeScale and ligatures all require `publisherStyles=false`.
  - Readium's docs say the UI should make that clear, so it becomes an "Override publisher styles" toggle.
  - https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/preferences.md
- [TS] **Custom font import:** ReadEra Premium ("My fonts"), KOReader, Moon+, Readest. https://apkcombo.com/readera-premium-ebook-reader/org.readera.premium/
- [DIFF] **Per-theme customization.** Best: Apple Books. It has six themes, bold text, and character, word and line spacing plus margins and justify, all saved per theme, with "Reset Theme". https://www.igeeksblog.com/how-to-customize-themes-reading-books-ipad-iphone/
- [DIFF] **Global custom CSS:** Calibre viewer, Librera, KOReader. https://manual.calibre-ebook.com/viewer.html
- [DIFF] **Moon+ extras:** text shadow, fading edges, "intelligent paragraphs", removing blank lines.
- [DIFF] **Readest extras:** proofread (text-replacement) rules, and syntax highlighting for code in technical books. Both are good for non-fiction.
- [NOVEL-ish] **Bionic reading.** I found no built-in support in KOReader, Readest, Moon+ or Librera; only the CrossInk e-ink project has a toggle. A secondary source cites a Readwise test that found no speed gain.

## 3. Layout and page turns
- [TS] **Paginated vs scroll.** Readium 3.2 added animated page turns. https://blog.readium.org/release-note-kotlin-toolkit-version-3-2-0/
- [DIFF] **Realistic page curl and 5 flip animations:** Moon+.
- [TS] **Two-page landscape.** Moon+ has dual-page mode. Readest does 1–4 columns. Readium's `columnCount` works in paginated mode only.
- [TS] **Tap zones and volume keys.** Best: Moon+, with 24 actions mapped to 15 events, plus camera, search and back keys for turning pages. KOReader has a gestures plugin.
- [DIFF] **Auto-scroll:** Moon+ (5 modes), Librera "Musician's mode", Readest.
- [DIFF] **Speed reading:** Librera RSVP, Readest reading ruler and paragraph mode, KOReader perception-expander plugin.
- [DIFF] **Parallel Read** (two books side by side, e.g. a translation and the original): Readest.
- [DIFF] **Brightness by sliding along the left edge:** Moon+ and Readest.

## 4. Themes
- [TS] **Day, sepia, night and AMOLED black.**
- [DIFF] **Custom themes:** Moon+ (10+), Lithium Pro.
- Readium's `imageFilter` dims images in dark mode.
- [DIFF] **Play Books Night Light:** a blue-light filter that follows sunlight. https://support.google.com/googleplay/answer/9755756

## 5. Navigation
- [TS] **Basics:** TOC, go to page or percent, progress bar with chapter ticks (Readest).
- [TS] **Time left in chapter or book, from your own reading speed.** Best: Kindle. Readest and Calibre also show it. https://goodereader.com/blog/?p=262073
- [DIFF] **Real print page numbers:** Kindle, Readest, Calibre paper-page sync.
- [TS] **Browser-style back/forward after a link jump:** Calibre.
- [TS] **Footnote popups whose inner links work:** Readest. KOReader and Apple Books are UNVERIFIED. Readium doesn't mention popups, so expect to build this ourselves.
- [DIFF] **Whole-book overview.** KOReader's Book Map shows structure and what you've read, alongside its Page Browser; this is best for non-fiction. Kindle has Page Flip. https://flathub.org/apps/rocks.koreader.KOReader
- [NOVEL for mobile] **Calibre Reference mode** numbers paragraphs so you can cite them, and it can copy `calibre://` deep links to a location.

## 6. Search
- [TS] **In-book search.**
- [DIFF] **Search modes:** contains, whole word, nearby words, regex, with diacritic options. Best: Calibre and Readest.
- [DIFF] **Library-wide search.** Readest searches the current shelf. Readwise has offline full-text search.
- [DIFF→NOVEL] **Semantic chat across the whole library with per-passage citations:** Readwise Global Ghostreader (cloud only). https://docs.readwise.io/reader/guides/ghostreader/global

## 7. Annotations
- [TS] **Highlight colors, notes and bookmarks.** ReadEra Premium adds extra colors and an all-books notes screen.
  - Readest adds underline and squiggly styles, Markdown notes, quote excerpts, and "apply to every occurrence".
- [DIFF] **Export.**
  - KOReader can export to html, joplin, json, markdown, my_clippings, nextcloud, readwise, text and xmnote. https://github.com/koreader/koreader/tree/master/plugins/exporter.koplugin/target
  - Readest exports Markdown, TXT and JSON with Jinja-style templates, can include the surrounding sentence, and sends to Readwise and Hardcover.
  - BookFusion's Obsidian plugin orders highlights by the book's TOC, uses Liquid templates, and supports atomic notes. It also syncs to Notion. https://github.com/BookFusion/obsidian-plugin
  - Kobo exports PDF, HTML, TXT or Markdown, but only for books bought on Kobo, and the files are deleted after 30 days.
  - Best pipeline: Readwise. It syncs to Obsidian, Notion and Logseq and has an MCP server.
- [DIFF] **Import from other apps:** Readest imports Moon+ and ReadEra annotations, which gives users a migration path.
- [DIFF] **Ghostreader custom prompts.** Prompts can run on the whole document, a paragraph or a word, can run automatically, and can apply action tags. https://docs.readwise.io/reader/guides/ghostreader/custom-prompts

## 8. Lookup, vocabulary and translation
- [TS] **Offline dictionaries.**
  - KOReader uses StarDict.
  - Readest imports Yomitan ZIP and RDICT packs and looks up Wiktionary and Wikipedia.
  - Moon+ hands off to ColorDict and other apps.
- [DIFF] **Vocabulary with spaced repetition.** Best: KOReader. Its vocabulary builder keeps the context sentence, stores words in a SQLite file, and can sync. Kindle's Vocabulary Builder makes flashcards. https://git.blob42.xyz/Archives/koreader/wiki/Vocabulary-builder
- [DIFF] **Kindle Word Wise and X-Ray.** Word Wise shows simple definitions above hard words. X-Ray covers characters, places and terms.
- [DIFF] **Translation.**
  - Readest supports DeepL and Yandex, plus your own providers with a prompt library, and an "AI context dictionary" (latest release).
  - ML Kit translates on the device in 50+ languages, but it pivots through English and calls itself "casual" quality. It is proprietary, and whether it needs Play services is UNVERIFIED. https://developers.google.com/ml-kit/language/translation

## 9. Library and import
- [TS] **Organization.**
  - Moon+ shelves by favorites, downloads, authors and tags.
  - ReadEra has 4 library views and scans folders automatically.
  - Bookmory has collections, series, tags and barcode entry.
- [DIFF] **Metadata editing:** Calibre is best (desktop). KOReader can search Calibre metadata.
- [DIFF] **OPDS:** KOReader, Readest, Moon+, Librera PRO (the F-Droid build drops it).
  - Gutenberg's OPDS 1.x Atom feed with OpenSearch works: https://www.gutenberg.org/ebooks.opds/
  - Standard Ebooks keeps most feeds for Patrons Circle members. New releases are open, and open-source projects can ask for access. https://standardebooks.org/feeds
- [DIFF] **Calibre wireless connection:** KOReader `calibre.koplugin` is best.
- [DIFF] **Cloud drives.** PocketBook connects Dropbox and Google Drive. KOReader's `cloudstorage` plugin uses Dropbox, WebDAV or FTP. Kindle Scribe imports from Google Drive and OneDrive. https://goodereader.com/blog/kindle/here-are-the-new-features-on-the-kindle-scribe-colorsoft-and-kindle-scribe-3rd-gen
- [DIFF] **Send to device:** Send-to-Kindle by email. PocketBook's email equivalent is UNVERIFIED.
- [DIFF] **Shared link** that expires after 1, 3 or 7 days and can carry your position: Readest.

## 10. Sync
- [DIFF] **KOReader sync (kosync).** https://github.com/koreader/koreader-sync-server , https://github.com/readest/readest/wiki/Sync-with-Koreader-devices
  - Each document is identified by a 32-hex MD5 hash.
  - The server stores percentage, a progress string and the device name.
  - Requests carry `x-auth-user` and `x-auth-key` headers; the key is an MD5 hash made on the client.
  - Self-host with `docker koreader/kosync`. The server is AGPL.
  - It syncs progress only. Annotations need Readest's plugin, and Readest's wiki advises matching books by file content.
- [DIFF] **Google Drive:** ReadEra Premium, Lithium Pro (positions and notes, not the book files), Librera.
- [DIFF] **WebDAV and Dropbox:** Moon+.
- [DIFF] **Reading trackers.**
  - Kobo now syncs to StoryGraph (June 2026), but StoryGraph has no public API; on its roadmap the API is "Long-term". https://roadmap.thestorygraph.com/features/posts/an-api
  - Hardcover has a GraphQL API with token auth. https://docs.hardcover.app/api/getting-started

## 11. Stats, goals, e-ink, accessibility, widgets
- [TS] **Time and speed per book:** Moon+, KOReader statistics.
- [DIFF] **Goals and streaks.**
  - Apple Books: daily minutes (default 5) and books per year (default 3), streak records, and "Coaching" notifications you can turn off. https://support.apple.com/en-me/guide/iphone/iph6013e96f4/ios
  - Bookmory: streaks, Annual Rewind, OCR quotes from photos, and a "random note" that resurfaces old notes.
  - StoryGraph: goals in pages or hours, did-not-finish with pages read, custom charts.
- [DIFF] **E-ink.** KOReader is best (no animation, contrast control). Readwise has an e-ink mode for BOOX and Daylight. Readest has CrossPoint e-reader support.
- [TS] **Accessibility.** Readest supports full keyboard navigation and VoiceOver, TalkBack, NVDA and Orca. Calibre has a "hints" mode for following links by keyboard.
- [DIFF] **Widgets:** Moon+ Pro has a widget shelf and home-screen book shortcuts. Other apps are UNVERIFIED.

## 12. "BetterBooks" name collisions (reported plainly)
- **Google Play:** no app named BetterBooks or Better Books. Nearby names are "Better Bookshelf" and "Book Better". https://play.google.com/store/search?q=betterbooks&c=apps
- **App Store: BetterReads (developer "zhao lu").** It has a near-identical "understand and remember" vision, and its name is one word off. This is a confusion risk and a direct competitor.
- **Bookkeeping uses the same words:**
  - usebetterbooks.com, an "autonomous bookkeeping" AI at $99/month. Its DNS doesn't resolve now.
  - Bookkeeping firms at betterbooks.co and betterbooks.business.
  - betterbooks.app (registered 2023) redirects to debits.com, a tax and bookkeeping product.
  - Search results for the name will be crowded.
- **Other uses:**
  - betterbooks.in is an Indian marketplace for used exam-prep books.
  - Dealroom lists a Toronto "BetterBooks" as a "platform for serious readers to… track learning, and maximize comprehension". That is the same space; its status is unknown. https://app.dealroom.co/companies/betterbooks_1/team
- **Domains:**
  - betterbooks.com: listed as "recently sold".
  - betterbooks.org: a personal book blog, registered 2001.
  - betterbooks.io: parked.
  - getbetterbooks.com: registered.
  - **betterbooks.dev: available** (RDAP 404 today).
- **GitHub:** the user `betterbooks` is taken (2 junk repos). About 38 repos use the name, none significant.
- **Trademarks:**
  - Aggregators show no live US "BETTER BOOKS" mark.
  - BEE BETTER BOOKS (abandoned 1999) disclaimed "BETTER BOOKS" as descriptive.
  - BETTERBOOK (Reg. 4394115, interactive non-fiction ebooks) was cancelled in 2020.
  - So the name is probably weak and hard to protect.
  - USPTO, EUIPO, UKIPO and CIPO were not searched directly (UNVERIFIED).
  - Sources: https://trademark.justia.com/753/55/bee-better-books-75355591.html , https://trademarks.justia.com/856/86/betterbook-85686088.html

## Decision points for the developer
1. **Name**
   - Options: (a) keep BetterBooks; (b) keep it with a tagline, e.g. "BetterBooks — read, listen, remember"; (c) choose a coined, distinctive name before the Play Store.
   - **Default: (b).** Use it as the working name for GitHub releases.
   - Claim betterbooks.dev and a GitHub org (e.g. `betterbooks-app`) now, with app ID `app.betterbooks`.
   - Re-check USPTO and EUIPO and weigh the BetterReads overlap before the Play Store listing.
2. **Formats in v1**
   - Options: (a) only what Readium supports (EPUB, fixed layout, PDF); (b) also convert TXT, Markdown and HTML to EPUB on import; (c) add MOBI/AZW3, FB2, DOCX, CBZ/CBR.
   - **Default: (b) in v1, then MOBI/AZW3 and CBZ next.**
   - DOCX, FB2 and CBR come later.
3. **PDF engine**
   - Options: Readium's Pdfium adapter; MuPDF; Android PdfRenderer.
   - MuPDF is AGPL. GPLv3 §13 allows combining it, but the AGPL terms carry over.
   - **Default: Pdfium**, with separate text extraction to feed the AI and TTS.
   - Accept that PDF highlights will be custom work.
4. **Sync without accounts**
   - Options: backup file only (decided); add a kosync progress client; add WebDAV or SAF-folder sync of annotations.
   - **Default:** a kosync client and WebDAV/folder sync in v1.x. This interoperates with KOReader and Readest for free.
5. **Annotation interop**
   - **Default:** v1 exports Markdown/Obsidian (decided) plus JSON and the Readwise API, with templates.
   - v1.x imports from KOReader, Moon+ and ReadEra.
6. **Dictionary and translation**
   - Options: ML Kit (proprietary, breaks F-Droid); an LLM via OpenRouter; offline StarDict or Wiktionary; handing text to installed apps with `ACTION_PROCESS_TEXT`.
   - **Default:** `PROCESS_TEXT` handoff, Wiktionary, optional StarDict import, and LLM "define in context".
   - Put ML Kit only in a Play-flavored build.
7. **Internalization beyond the decided AI set**
   - Options: (a) a daily review of your highlights and lookups where you answer before seeing the text; (b) LLM quizzes and flashcards per chapter; (c) "explain it back", graded by the LLM.
   - **Default: (a) plus (b) in v1.** Keep both fenced at your position, budget them for 50 requests/day, and share one review queue with vocabulary.
   - This is the gap that BetterReads, Readwise, KOReader's vocabulary builder and Gemini Notebook each cover only in part.
8. **Stats and goals**
   - **Default:** unified read-plus-listen stats; opt-in goals and streaks; no push nags.
9. **E-ink**
   - **Default:** an "E-ink mode" preset in v1.x: no animation, pure black and white, volume keys. No vendor SDK.
10. **Reading-tracker sync**
    - **Default:** opt-in Hardcover (it has an API). Skip StoryGraph until it ships one.
11. **Bionic reading**
    - **Default:** an experimental CSS toggle, low priority.