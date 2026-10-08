package app.betterbooks.feature.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterbooks.core.model.Annotation
import app.betterbooks.core.model.AppSettings
import app.betterbooks.core.model.AnnotationVia
import app.betterbooks.core.model.Book
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.Bookmark
import app.betterbooks.core.model.PositionKind
import app.betterbooks.core.model.Section
import app.betterbooks.engine.epub.reader.ConsumptionTracker
import app.betterbooks.engine.epub.reader.LocatorOrdinalMapper
import app.betterbooks.engine.epub.reader.PublicationLoader
import app.betterbooks.engine.epub.reader.ReadiumReaderEngine
import app.betterbooks.engine.epub.reader.ReaderStyle
import app.betterbooks.engine.epub.reader.SearchHit
import app.betterbooks.engine.epub.reader.TextAnchor
import app.betterbooks.engine.epub.reader.TextMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal sealed interface ReaderLoad {
    data object Loading : ReaderLoad
    data class Failed(val technical: String?) : ReaderLoad
    class Ready(
        val book: Book,
        val engine: ReadiumReaderEngine,
        val mapper: LocatorOrdinalMapper,
        val index: BookIndex,
        val sections: List<Section>,
        val initial: TextAnchor?,
    ) : ReaderLoad
}

@OptIn(FlowPreview::class)
internal class ReaderViewModel(
    context: Context,
    private val deps: ReaderDeps,
    val bookId: BookId,
) : ViewModel() {
    private val app = context.applicationContext
    private val prefsStore = ReaderPrefsStore(app)
    val localPrefs: StateFlow<ReaderLocalPrefs> = prefsStore.prefs

    private val _load = MutableStateFlow<ReaderLoad>(ReaderLoad.Loading)
    val load: StateFlow<ReaderLoad> = _load.asStateFlow()

    /** Sentence ordinal at the top of the visible page. */
    private val _ordinal = MutableStateFlow<Int?>(null)
    val ordinal: StateFlow<Int?> = _ordinal.asStateFlow()

    /** In READ_LISTEN: the reader moved the page away from the spoken sentence. */
    private val _detached = MutableStateFlow(false)
    val detached: StateFlow<Boolean> = _detached.asStateFlow()

    val style: StateFlow<ReaderStyle> = combine(deps.settings.settings, prefsStore.prefs, ::readerStyle)
        .stateIn(viewModelScope, SharingStarted.Eagerly, readerStyle(AppSettings(), prefsStore.prefs.value))

    val annotations: StateFlow<List<Annotation>> = deps.annotations.annotations(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = deps.annotations.bookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** End of the contiguous consumed run (the spoiler fence), -1 if none. */
    private val _frontier = MutableStateFlow(-1)
    val frontier: StateFlow<Int> = _frontier.asStateFlow()

    private val _search = MutableStateFlow<SearchState>(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private var tracker: ConsumptionTracker? = null
    private var pendingJump = false
    /** Location changes before this time come from our own navigation (read-along follow, restyle). */
    private var programmaticUntil = 0L
    private var listening = false

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val book = deps.library.book(bookId).filterNotNull().first()
        val prepared = runCatching {
            withContext(Dispatchers.Default) {
                val count = deps.spine.sentenceCount(bookId)
                val sentences = if (count > 0) deps.spine.sentences(bookId, 0, count - 1) else emptyList()
                val sections = deps.spine.sections(bookId)
                Triple(LocatorOrdinalMapper(sentences), BookIndex(sentences, sections), sections)
            }
        }.getOrElse { _load.value = ReaderLoad.Failed(it.message); return }
        val (mapper, index, sections) = prepared
        val publication = PublicationLoader(app).open(book.epubPath)
            .getOrElse { _load.value = ReaderLoad.Failed(it.message); return }
        val engine = ReadiumReaderEngine(publication)
        val saved = deps.positions.position(bookId, PositionKind.READING).first()?.ordinal
        _ordinal.value = saved
        tracker = ConsumptionTracker(words = index::words)
        _load.value = ReaderLoad.Ready(book, engine, mapper, index, sections, saved?.let(mapper::anchorFor))
        runCatching { deps.library.markOpened(bookId) }
        refreshFrontier()

        engine.location.filterNotNull()
            .map { mapper.ordinalAt(it) }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { onPageOrdinal(it) }
            .launchIn(viewModelScope)
        _ordinal.filterNotNull().debounce(400).distinctUntilChanged()
            .onEach { deps.positions.savePosition(bookId, PositionKind.READING, it) }
            .launchIn(viewModelScope)
    }

    private suspend fun onPageOrdinal(ordinal: Int) {
        val now = System.currentTimeMillis()
        val ours = now < programmaticUntil
        if (listening && !ours) _detached.value = true
        _ordinal.value = ordinal
        // Our own moves (read-along follow, reflow after a style change) are not reading.
        val jump = pendingJump || ours
        pendingJump = false
        val consumed = tracker?.onPage(ordinal, now, jump).orEmpty()
        consumed.forEach { deps.positions.markConsumed(bookId, it.first, it.last, PositionKind.READING) }
        if (consumed.isNotEmpty()) refreshFrontier()
    }

    private suspend fun refreshFrontier() {
        runCatching { deps.positions.frontier(bookId) }.onSuccess { _frontier.value = it }
    }

    /** Where the navigator should open after its view is recreated. */
    fun currentAnchor(): TextAnchor? {
        val r = ready() ?: return null
        return _ordinal.value?.let(r.mapper::anchorFor) ?: r.initial
    }

    /** Paints saved highlights; [tints] are the six ARGB highlight colours from the palette. */
    suspend fun paintHighlights(list: List<Annotation>, tints: List<Int>) {
        val r = ready() ?: return
        val marks = list.mapNotNull { a ->
            val anchor = r.mapper.anchorForQuote(a.startOrdinal, a.endOrdinal, a.quote) ?: return@mapNotNull null
            TextMark(id = a.id.toString(), anchor = anchor, tint = tints[a.color.coerceIn(0, tints.lastIndex)])
        }
        r.engine.applyMarks(ReadiumReaderEngine.GROUP_HIGHLIGHTS, marks)
    }

    /** Paints the band on the sentence being spoken, or clears it when [ordinal] is null. */
    suspend fun paintReadAlong(ordinal: Int?, tint: Int) {
        val r = ready() ?: return
        val anchor = ordinal?.let(r.mapper::anchorFor)
        val marks = if (anchor == null) emptyList() else listOf(TextMark("s$ordinal", anchor, tint, band = true))
        r.engine.applyMarks(ReadiumReaderEngine.GROUP_READ_ALONG, marks)
    }

    /** The current selection mapped onto the spine, or null when nothing usable is selected. */
    suspend fun selection(): ReaderSelection? {
        val r = ready() ?: return null
        val sel = runCatching { r.engine.selection() }.getOrNull() ?: return null
        val text = sel.anchor.highlight?.trim().orEmpty()
        if (text.isEmpty()) return null
        val range = r.mapper.rangeAt(sel.anchor) ?: _ordinal.value?.let { it..it } ?: return null
        return ReaderSelection(range, text, sel.top, sel.bottom)
    }

    fun clearSelection() {
        ready()?.engine?.clearSelection()
    }

    private fun ready(): ReaderLoad.Ready? = _load.value as? ReaderLoad.Ready

    /** Navigation that is not reading: TOC, search, bookmarks, highlights list. */
    fun jumpTo(ordinal: Int, fallbackHref: String? = null) {
        val r = ready() ?: return
        val anchor = r.mapper.anchorFor(ordinal) ?: fallbackHref?.let { TextAnchor(it, progression = 0.0) } ?: return
        jumpTo(anchor)
    }

    fun jumpTo(anchor: TextAnchor) {
        val r = ready() ?: return
        pendingJump = true
        r.engine.go(anchor, animated = false)
    }

    fun onLinkJump() {
        pendingJump = true
    }

    /** Programmatic follow of the spoken sentence; does not detach. */
    fun follow(ordinal: Int) {
        val r = ready() ?: return
        val anchor = r.mapper.anchorFor(ordinal) ?: return
        programmaticUntil = System.currentTimeMillis() + PROGRAMMATIC_WINDOW_MS
        r.engine.go(anchor, animated = true)
    }

    fun reattach() {
        _detached.value = false
    }

    fun setListening(on: Boolean) {
        listening = on
        if (!on) _detached.value = false
    }

    fun pauseTracking() = tracker?.pause()
    fun resumeTracking() = tracker?.resume(System.currentTimeMillis())

    fun updateStyle(app: (AppSettings) -> AppSettings) {
        programmaticUntil = System.currentTimeMillis() + PROGRAMMATIC_WINDOW_MS
        viewModelScope.launch { deps.settings.update(app) }
    }

    fun updateLocal(transform: (ReaderLocalPrefs) -> ReaderLocalPrefs) {
        programmaticUntil = System.currentTimeMillis() + PROGRAMMATIC_WINDOW_MS
        prefsStore.update(transform)
    }

    fun addHighlight(range: IntRange, quote: String, color: Int = localPrefs.value.lastHighlightColor) {
        if (color != localPrefs.value.lastHighlightColor) prefsStore.update { it.copy(lastHighlightColor = color) }
        viewModelScope.launch {
            deps.annotations.add(
                Annotation(
                    id = 0, bookId = bookId, startOrdinal = range.first, endOrdinal = range.last,
                    quote = quote, color = color, note = null, via = AnnotationVia.READ,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun updateAnnotation(a: Annotation) {
        if (a.color != localPrefs.value.lastHighlightColor) prefsStore.update { it.copy(lastHighlightColor = a.color) }
        viewModelScope.launch { deps.annotations.update(a) }
    }

    fun deleteAnnotation(id: Long) {
        viewModelScope.launch { deps.annotations.delete(id) }
    }

    fun toggleBookmark(pageOrdinal: Int) {
        val existing = bookmarks.value.firstOrNull { it.ordinal in pageOrdinal..pageOrdinal + BOOKMARK_SLACK }
        viewModelScope.launch {
            if (existing != null) {
                deps.annotations.deleteBookmark(existing.id)
            } else {
                val label = ready()?.mapper?.sentence(pageOrdinal)?.text?.take(80)
                deps.annotations.addBookmark(Bookmark(0, bookId, pageOrdinal, label, System.currentTimeMillis()))
            }
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { deps.annotations.deleteBookmark(id) }
    }

    fun runSearch(query: String) {
        val r = ready() ?: return
        _search.value = SearchState(query = query, running = true)
        viewModelScope.launch {
            val hits = runCatching { r.engine.search(query) }.getOrDefault(emptyList())
            if (_search.value.query == query) _search.value = SearchState(query, false, hits)
        }
    }

    override fun onCleared() {
        val r = ready()
        val o = _ordinal.value
        if (o != null) {
            // The debounced save may not have run yet; this is a single small write.
            runBlocking(NonCancellable) { runCatching { deps.positions.savePosition(bookId, PositionKind.READING, o) } }
        }
        r?.engine?.close()
    }

    companion object {
        private const val PROGRAMMATIC_WINDOW_MS = 1_200L
        const val BOOKMARK_SLACK = 2
    }
}

internal data class ReaderSelection(val range: IntRange, val text: String, val top: Float?, val bottom: Float?)

internal data class SearchState(
    val query: String = "",
    val running: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
)
