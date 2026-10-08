# Reader-engine evaluation for BetterBooks (2026-10-08)

**Bottom line:** For v1, keep EPUB on Readium 3.4.0's older Fragment-based navigator, and keep the Compose navigator behind an interface of your own. Use androidx.pdf instead of Readium for PDFs. Convert other reflowable formats to EPUB when they are imported. For retrieval, use FTS5 plus EmbeddingGemma 2, with brute-force vector search done in Kotlin.

## 1. Readium Kotlin Toolkit
- **Version and upkeep.** The current release is **3.4.0 (2026-09-11)**: minSdk 24, compileSdk 37, Kotlin 2.4.20.
  - Earlier releases: 3.3.0 (2026-06-02) and 3.2.0 (2026-05-13).
  - Development is active (last commit 2026-09-11, 64 open issues). License is BSD-3, which is GPL-compatible.
  - https://github.com/readium/kotlin-toolkit/blob/develop/CHANGELOG.md
- **Two EPUB navigators now exist.**
  - **Older one:** `EpubNavigatorFragment`, Fragment-based and stable. It handles reflowable EPUB (paginated or scrolled) and fixed-layout EPUB.
  - **New one:** `readium-navigator-web-reflowable` / `-fixedlayout` 3.4.0, a set of Compose functions (`ReflowableWebRendition`, `FixedWebRendition`). Every API is `@ExperimentalReadiumApi`, and Readium says it is "not battle tested".
  - https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/web-navigators.md
- **What the Compose navigator adds:**
  - Continuous scrolling across chapters.
  - Highlights (decorations) and text selection on fixed-layout pages.
  - Line-length-based layout.
  - Link colours.
- **What the Compose navigator is missing, from Readium's own feature-parity table:**
  - Custom JavaScript interfaces and `evaluateJavascript`.
  - `firstVisibleElementLocator()`, which you need to start read-aloud from the current page.
  - Image-tap info, key events, drag events and animated page turns.
  - Error reporting.
  - Issue #830, which asks for these, is still open.
  - https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/epub-feature-parity.md
  - https://github.com/readium/kotlin-toolkit/issues/830
- **Compose interop.** Host the older Fragment with the `AndroidFragment` function from `androidx.fragment:fragment-compose` 1.9.1.
- **PDF in Readium.**
  - Readium offers only two PDF adapters: `pdfium` (built on the `com.github.marain87:PdfiumAndroid` 1.9.8 fork, served from JitPack) and the commercial PSPDFKit.
  - Readium itself calls PdfiumAndroid "currently unmaintained".
  - For PDF, Readium has no search, no highlights and no read-aloud.
  - It has no adapter for Android's built-in PdfRenderer or for androidx.pdf.
  - Since 3.4.0, Pdfium defaults to paginated mode. If you store Pdfium locators, they must be migrated with `migrateLegacyPdfiumLocator()`.
  - Issue #824 asks for a Compose PDF navigator.
  - https://github.com/readium/kotlin-toolkit/blob/develop/readium/adapters/pdfium/README.md
- **Read-aloud and synced audio.**
  - The TTS navigator and the audio navigator ship as `readium-navigator-media-tts` and `-audio` 3.4.0.
  - **Media Overlays (text synced to recorded audio) are "Planned" and not implemented.** Sentence-level read-along with Fish audio therefore has to be built yourself: your own Media3 timeline drives a sentence highlight.
  - Avoid `readium-navigator-media2`, which is obsolete. (That is my inference from the module list on Maven Central.)
- **Highlights, search and text extraction.**
  - Highlights and underlines work, including custom HTML templates and highlights you can tap.
  - 3.2.0 added an experimental mode that draws highlights behind the text.
  - Search works on reflowable and fixed-layout EPUB.
  - The `Content` iterator gives semantic elements with locators. It is marked experimental, and `content.text()` is expensive, so cache the result.
- **Reader settings.**
  - Font family, size and weight; line height; paragraph and letter spacing; hyphens; ligatures.
  - Text alignment, colour, background, typeScale, columnCount, vertical text and imageFilter (dark mode only).
  - The advanced settings require `publisherStyles=false`.
  - Bundled fonts are OpenDyslexic, AccessibleDfA and iA Writer Duospace. You can add your own fonts with `fontFamilyDeclarations`.
- **Page turns.** 3.2.0 added an animated slide in paginated mode (`animatedTransition`), on the older navigator only. There is no curl.
- **Accessibility.** 3.1 added display of accessibility metadata. The text is in a WebView, which TalkBack can read.
- **LCP DRM.** It needs EDRLab's private `liblcp` binary and certification. Keep it out of the FOSS build.

## 2. PDF options and GPL-3.0
- **androidx.pdf 1.0.0-beta01 (2026-08-26). Apache-2.0, so it is compatible.**
  - Artifacts: `pdf-viewer`, `pdf-viewer-fragment`, `pdf-compose`, `pdf-document-service`, `pdf-core`, `pdf-ink` and `pdf-ocr-play-services`.
  - alpha18 backported reading and rendering to **minSdk 28**, which matches your minSdk.
  - Features: search that returns results as they arrive, selection across pages, links, form filling, ink and highlighter annotations, and a two-page layout.
  - `PdfDocument.getPageContent()` returns page text and images; `searchDocument()` and `getSelectionBounds()` are also available.
  - The `PdfViewer` Compose function is still `@ExperimentalPdfApi`.
  - Using it from Compose needs AGP 9.2 or later.
  - https://developer.android.com/jetpack/androidx/releases/pdf
  - https://developer.android.com/reference/kotlin/androidx/pdf/PdfDocument
- **Built-in PdfRenderer (API 35).** Adds `getTextContents`, `searchText`, `selectContent`, `getLinkContents`, `getGotoLinks`, `getImageContents` and form editing.
  - https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page
- **MuPDF 1.28.5 (Maven, 2026-09-25). AGPL-3.0.**
  - The FSF says: "You can always link GPLv3-covered modules with AGPLv3-covered modules."
  - Artifex goes further: if you use MuPDF, the app may not include Google Play Services, Crashlytics or any other proprietary component. **That rules out ML Kit in any build that contains MuPDF.**
  - It reads PDF, XPS, EPUB, MOBI, FB2, CBZ and Markdown. Its structured text output (blocks, lines, fonts and boxes) is the best input for rebuilding PDF text into paragraphs.
  - https://mupdf.readthedocs.io/en/latest/guide/using-with-android.html
  - https://www.gnu.org/licenses/gpl-faq.html#AGPLGPL
- **pdf.js 6.4.299 (2026-10-03). Apache-2.0.** It is a workable fallback in a WebView. It is probably slower than native on large scanned PDFs (UNVERIFIED).
- **Getting usable text out of PDFs for read-aloud and AI.**
  - The built-in and androidx APIs return text page by page with no structure.
  - You need your own clean-up: strip headers and footers that repeat on every page, rejoin words hyphenated across lines, merge lines into paragraphs, and use font size to find headings (sizes need MuPDF or PDFium).
  - Scanned PDFs need OCR: ML Kit through `pdf-ocr-play-services`, which is proprietary, or Tesseract, which is UNVERIFIED on Android.

## 3. Other formats
- **Convert to EPUB 3 on import and keep the original.** Then highlights, locators, read-aloud and AI all run through one pipeline.
- **MOBI/AZW3:** libmobi v0.12 (LGPL-3.0, released 2024-06, little activity) through the NDK. Its `mobitool -e` turns KF8 files into EPUB. https://github.com/bfabiszewski/libmobi
- **FB2:** it is XML, so a converter you write yourself is enough. MuPDF is the other option.
- **TXT and Markdown:** commonmark-java 0.30.0 (BSD-2). **HTML:** wrap the file into an EPUB.
- **DOCX:** java-mammoth (BSD-2) converts it to HTML, which you then package as EPUB.
- **CBZ:** Readium supports it only partly. **CBR:** libarchive 3.8.9 can read RAR5; Readium does not plan CBR support. junrar's licence is UNVERIFIED (probably non-free).

## 4. Page-turn animations
- **Built in:** the Readium slide on the older navigator.
- **Curl over a WebView is possible but takes work:**
  1. Capture the current page with `PixelCopy`.
  2. Move to the next page without animation, underneath that image.
  3. Capture the new page.
  4. Animate the curl over the two images with an AGSL shader (API 33+), falling back to a slide on API 28–32.
  - A curl that follows the finger needs the next page captured before the drag starts. That means either some delay (UNVERIFIED) or a second, hidden navigator.
  - The pagecurl library (Apache-2.0, Compose, last update 2024-02) is a useful reference. https://github.com/oleksandrbalan/pagecurl
- **How other readers do it.** KOReader lays out text with its own engines (crengine and MuPDF) and has a page-turn-animation on/off setting. https://www.mobileread.com/forums/showthread.php?p=4227527 Moon+ Reader probably uses its own text engine rather than a WebView (UNVERIFIED).

## 5. Lookups
- **`ACTION_PROCESS_TEXT` (API 23).**
  - The WebView's selection menu shows actions from other apps. You need a `<queries>` entry for it on Android 11+.
  - Also register BetterBooks itself as a target, for example "Explain in BetterBooks".
- **Offline dictionaries.**
  - **Wiktionary:** the Kaikki English all-senses file is 3.1 GB and deprecated (extracted 2026-10-03). Wiktionary content is CC BY-SA, so it must be pre-processed into a small SQLite database with attribution. https://kaikki.org/dictionary/English/index.html
  - **WordNet / Open English WordNet:** a small, permissively licensed default (UNVERIFIED sizes).
  - **StarDict:** let users import their own dictionaries. Licensing is unclear for most StarDict dictionaries, so don't bundle any.
- **Wikipedia.** The `rest_v1` summary endpoint runs on RESTBase, which is being retired with no date set. Prefer the Action API (`prop=extracts&exintro`) with a descriptive User-Agent. https://www.mediawiki.org/wiki/RESTBase/deprecation
- **ML Kit translation.** `com.google.mlkit:translate:17.0.3`, more than 50 languages, about 30 MB per language model, minSdk 23. It is proprietary, so it belongs only in a Play build. https://developers.google.com/ml-kit/language/translation/android
- **AI definitions in context.**
  - Check the on-device dictionary first. Use the LLM only when the user taps "explain in context".
  - Cache answers per book and term.
  - Gloss a whole chapter's vocabulary in one request, because the free tier allows only 50 requests a day.

## 6. Sync and integrations
- **KOReader sync (kosync).**
  - It is a progress-only protocol. Authentication is an MD5 of the password, sent in headers.
  - The document ID is a partial MD5 made from 12 samples of 1,024 bytes.
  - For EPUB, `progress` is a **crengine XPointer**, not a Readium locator. You need to write a converter in each direction, or sync percentage only.
  - A CC0 specification and conformance tester exist; the reference server is AGPL. Reimplement from the spec.
  - https://github.com/pid1/kosync-conformance
- **OPDS 1.2 and 2.0.** Readium's `readium-opds` module already parses both. Calibre's content server and Calibre-Web both publish OPDS feeds.
- **WebDAV.** OkHttp PROPFIND requests, or sardine-android (Apache-2.0, last pushed 2024).
- **Google Drive.**
  - The `drive.file` and `drive.appdata` scopes are non-sensitive. Full Drive access is restricted and needs verification, plus a security assessment if the data passes through a server.
  - Android Auto Backup covers the app's database with no API work (25 MB cap, UNVERIFIED).
  - https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- **Readwise.**
  - Send highlights with `POST /api/v2/highlights/` and the header `Authorization: Token …`.
  - Use `highlight_url` so a repeat send updates the highlight rather than duplicating it.
  - Limit: 240 requests/min. https://readwise.io/api_deets
- **Obsidian.** Write Markdown files with YAML front matter into a vault folder the user picks (SAF tree access). This is already decided.
- **Anki.**
  - AnkiDroid's instant-add API: JitPack artifact `api-v1.1.0` (LGPL), permission `com.ichi2.anki.permission.READ_WRITE_DATABASE`, plus a `<queries>` entry for package `com.ichi2.anki`.
  - Fallback: the `ACTION_SEND` share intent.
  - For desktop Anki, export CSV/TSV with Anki's file headers. Generating `.apkg` files directly is later (UNVERIFIED whether the old format still imports).
  - https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API

## 7. E-ink
- **Onyx SDK.** `onyxsdk-device` 1.1.11, served over plain HTTP from `repo.boox.com`. No licence is stated, so treat it as proprietary. Its `EpdController` exposes refresh modes (DU, GU, REGAL and others).
- **Boox devices already let users set refresh mode per app**, so the app does not need the SDK for that.
- **Do instead:**
  - Detect e-ink devices from the manufacturer string.
  - Offer a mode with no animation (respect the system animator-duration scale), paginated reading only, and pure black and white with no ripples or gradients.
  - Optionally trigger a full refresh through reflection (UNVERIFIED).
- https://github.com/onyx-intl/OnyxAndroidDemo

## 8. Search and retrieval on the device
- **Keyword search.** `androidx.sqlite:sqlite-bundled` 2.7.1 compiles in `SQLITE_ENABLE_FTS5`. Room's annotations stop at FTS4, so create the FTS5 tables with raw SQL.
  - https://github.com/androidx/androidx/blob/androidx-main/sqlite/sqlite-bundled/build.gradle
- **Vector search.** One book has roughly 1,000–3,000 chunks. **Store vectors as BLOBs and compute similarity in plain Kotlin**; that should take under 10 ms (estimate).
  - **sqlite-vec v0.1.9:** Apache/MIT, ships an Android aarch64 build, and loads through the bundled driver's `addExtension`. It is still pre-1.0. Keep it for search across the whole library.
  - **ObjectBox vector search:** the licence of its native core is UNVERIFIED (possibly closed source). Avoid it.
- **Embedding model: EmbeddingGemma 2 Text 270M.**
  - Apache-2.0, 8K-token context, 100+ languages.
  - Output size can be cut to 128, 256, 512 or 768 dimensions.
  - The CPU/GPU file is a **165 MB** download. An NPU-specific build for MT6993 is 386 MB; whether the Find X9's Dimensity 9500 is the MT6993 is UNVERIFIED.
  - Measured on an S26 Ultra CPU: **27 ms per 128-token input, about 334 MB RAM**.
  - It runs on LiteRT-LM (`litertlm-android` 0.18.0) or the MediaPipe text library (`tasks-text` 1.1.0).
  - Indexing one book should take about 30–60 s on the CPU (estimate). Run it as a WorkManager job.
  - https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm
  - https://ai.google.dev/gemma/docs/embeddinggemma
- **Remote option.** OpenRouter has `/api/v1/embeddings`. Which embedding models are free changes over time. Whether embedding calls count against the 50-requests/day limit is UNVERIFIED.
- **Combine** keyword and vector results with Reciprocal Rank Fusion, filtered by the reader's current position (the agreed spoiler fence).

## v1 vs later
| Area | v1 | Later |
|---|---|---|
| EPUB | Older Readium navigator inside `AndroidFragment`, behind a `ReaderEngine` interface; slide and none animations | Compose navigator once it has parity; curl animation |
| PDF | androidx.pdf beta01, own highlight overlay, page text for AI | MuPDF reflow / OCR |
| Formats | EPUB, PDF, CBZ, TXT, MD, HTML, MOBI/AZW3 (via libmobi) | FB2, DOCX, CBR, LCP |
| Lookups | PROCESS_TEXT, WordNet, Wikipedia, AI explanation in context | Wiktionary pack, ML Kit (Play build) |
| Integrations | Obsidian, Readwise, AnkiDroid + CSV, OPDS | kosync, WebDAV, Drive, Calibre wireless |
| Retrieval | FTS5 + EmbeddingGemma 2 + Kotlin cosine | sqlite-vec across books, knowledge graph |
| E-ink | Animation-free mode | Optional Onyx full refresh |

## Decision points for the developer
1. **Which EPUB navigator.** (a) Older Fragment, (b) Compose (experimental), (c) both behind an interface. **Default: (c), shipping (a)**, because read-along needs `firstVisibleElementLocator` and JavaScript hooks.
2. **PDF engine.** (a) androidx.pdf, (b) Readium Pdfium, (c) MuPDF, (d) pdf.js. **Default: (a)**. It is Apache-licensed and has search, selection and text extraction. Revisit (c) only if PDF reflow quality turns out to matter a lot.
3. **Proprietary libraries and build flavors.** (a) A FOSS-only build, (b) separate `foss` and `play` builds with a GPLv3 §7 exception for ML Kit and Play Services, (c) MuPDF, which forbids proprietary code entirely. **Default: (b)**. If you do (b), don't use MuPDF.
4. **Non-EPUB formats.** (a) Convert to EPUB on import, (b) render each format directly. **Default: (a)**, keeping the original file.
5. **Page curl in v1.** (a) No; Readium's slide plus no-animation, (b) curl built from page snapshots. **Default: (a)**. Prototype the curl after v1.
6. **Embeddings.** (a) On the device with EmbeddingGemma 2 (an optional 165 MB download), (b) OpenRouter embeddings, (c) keyword search only. **Default: (a), with (c) as the fallback before the model is downloaded.**
7. **Vector store.** (a) BLOBs plus Kotlin brute force, (b) sqlite-vec, (c) ObjectBox. **Default: (a)**, moving to (b) for search across the library.
8. **Dictionary.** (a) Bundle WordNet, (b) an optional Wiktionary download, (c) user-imported StarDict, (d) the LLM only. **Default: (a)+(c)+LLM, with (b) later.**
9. **Sync before accounts exist.** (a) Backup file plus Auto Backup, (b) WebDAV, (c) kosync, (d) Drive appdata. **Default: (a) in v1, then (b) and (c).**
10. **Anki.** (a) AnkiDroid API plus CSV, (b) `.apkg` generation. **Default: (a).**