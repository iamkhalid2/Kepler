package app.betterbooks.feature.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterbooks.core.model.Annotation
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, readerStyle(app.betterbooks.core.model.AppSettings(), prefsStore.prefs.value))

    val annotations: StateFlow<List<Annotation>> = deps.annotations.annotations(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = deps.annotations.bookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _search = MutableStateFlow<SearchState>(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private var tracker: ConsumptionTracker? = null
    private var pendingJump = false
    /** Location changes before this time come from our own navigation (read-along follow, restyle). */
    private var programmaticUntil = 0L
    var listening = false

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
        val jump = pendingJump
        pendingJump = false
        tracker?.onPage(ordinal, now, jump)?.forEach {
            deps.positions.markConsumed(bookId, it.first, it.last, PositionKind.READING)
        }
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

    fun updateStyle(app: (app.betterbooks.core.model.AppSettings) -> app.betterbooks.core.model.AppSettings) {
        programmaticUntil = System.currentTimeMillis() + PROGRAMMATIC_WINDOW_MS
        viewModelScope.launch { deps.settings.update(app) }
    }

    fun updateLocal(transform: (ReaderLocalPrefs) -> ReaderLocalPrefs) {
        programmaticUntil = System.currentTimeMillis() + PROGRAMMATIC_WINDOW_MS
        prefsStore.update(transform)
    }

    fun addHighlight(range: IntRange, quote: String, color: Int) {
        prefsStore.update { it.copy(lastHighlightColor = color) }
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

internal data class SearchState(
    val query: String = "",
    val running: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
)
