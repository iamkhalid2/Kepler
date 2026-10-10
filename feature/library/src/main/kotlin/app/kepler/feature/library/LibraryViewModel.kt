package app.kepler.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.Book
import app.kepler.core.model.ContentType
import app.kepler.core.model.ImportResult
import app.kepler.core.model.NarrationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibrarySort { Recent, Title, Author }
enum class LibraryLayout { Grid, Bookcase }
enum class LibraryFilter { All, Reading, Listening, Finished, NonFiction }

/** What a cover shows in its top-right corner. */
sealed interface BookBadge {
    data object None : BookBadge
    /** Some or all of the book has narration. */
    data object Narrated : BookBadge
    /** Narration is being generated right now. */
    data class Generating(val fraction: Float) : BookBadge
}

sealed interface LibraryMessage {
    /** Neutral technical failure; the UI shows the standard "can't be opened" line. */
    data object ImportFailed : LibraryMessage
    data class Duplicate(val title: String) : LibraryMessage
    data class Imported(val title: String) : LibraryMessage
}

data class LibraryUi(
    val loaded: Boolean = false,
    /** Filtered and sorted. */
    val books: List<Book> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.All,
    val sort: LibrarySort = LibrarySort.Recent,
    val layout: LibraryLayout = LibraryLayout.Grid,
    val badges: Map<Long, BookBadge> = emptyMap(),
    val importing: Boolean = false,
    val message: LibraryMessage? = null,
    /** The book whose long-press actions are open. */
    val menuFor: Book? = null,
    val pendingDelete: Book? = null,
) {
    val isEmpty: Boolean get() = loaded && total == 0
    val noMatches: Boolean get() = loaded && total > 0 && books.isEmpty()
}

private data class Prefs(
    val query: String = "", val filter: LibraryFilter = LibraryFilter.All, val sort: LibrarySort = LibrarySort.Recent,
    val layout: LibraryLayout = LibraryLayout.Grid, val importing: Boolean = false,
    val message: LibraryMessage? = null, val menuFor: Book? = null, val pendingDelete: Book? = null,
)

/** Title/author match on every typed word, case-insensitive. */
fun matchesQuery(book: Book, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = (book.title + " " + book.authors).lowercase()
    return words.all { hay.contains(it) }
}

/** A book is finished once you are, or have read or heard, nearly all of it. */
fun isFinished(book: Book): Boolean = maxOf(book.positionFraction, book.readFraction, book.listenFraction) >= 0.97f

fun matchesFilter(book: Book, filter: LibraryFilter): Boolean = when (filter) {
    LibraryFilter.All -> true
    LibraryFilter.Reading -> hasStarted(book) && !isFinished(book) && primaryMode(book) == HubMode.Read
    LibraryFilter.Listening -> hasStarted(book) && !isFinished(book) && primaryMode(book) == HubMode.Listen
    LibraryFilter.Finished -> isFinished(book)
    LibraryFilter.NonFiction -> book.contentType == ContentType.NONFICTION
}

fun sortBooks(books: List<Book>, sort: LibrarySort): List<Book> = when (sort) {
    LibrarySort.Recent -> books.sortedByDescending { it.lastOpenedAt ?: it.addedAt }
    LibrarySort.Title -> books.sortedBy { it.title.lowercase() }
    LibrarySort.Author -> books.sortedWith(compareBy({ it.authors.lowercase() }, { it.title.lowercase() }))
}

fun badgeFor(coverage: Float, narration: NarrationState): BookBadge = when {
    narration is NarrationState.Running ->
        BookBadge.Generating(if (narration.totalSentences > 0) (narration.doneSentences.toFloat() / narration.totalSentences).coerceIn(0f, 1f) else coverage)
    coverage > 0.001f -> BookBadge.Narrated
    else -> BookBadge.None
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val deps: LibraryDeps) : ViewModel() {
    private val prefs = MutableStateFlow(
        Prefs(layout = if (deps.viewPrefs.bookcase) LibraryLayout.Bookcase else LibraryLayout.Grid),
    )

    /** One entry per book, live: narration appears on a cover the moment its first audio lands. */
    private val badges: Flow<Map<Long, BookBadge>> = deps.library.books.map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map { id -> combine(deps.audio.coverage(id), deps.narration.state(id)) { c, n -> id to badgeFor(c, n) } }) { it.toMap() }
        }

    val state: StateFlow<LibraryUi> = combine(deps.library.books, prefs, badges) { books, p, b ->
        LibraryUi(
            loaded = true,
            books = sortBooks(books.filter { matchesQuery(it, p.query) && matchesFilter(it, p.filter) }, p.sort),
            total = books.size,
            query = p.query, filter = p.filter, sort = p.sort, layout = p.layout, badges = b,
            importing = p.importing, message = p.message, menuFor = p.menuFor, pendingDelete = p.pendingDelete,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUi())

    fun setQuery(q: String) = prefs.update { it.copy(query = q) }
    fun setFilter(f: LibraryFilter) = prefs.update { it.copy(filter = f) }
    fun setSort(s: LibrarySort) = prefs.update { it.copy(sort = s) }
    fun toggleLayout() {
        val next = if (prefs.value.layout == LibraryLayout.Grid) LibraryLayout.Bookcase else LibraryLayout.Grid
        deps.viewPrefs.bookcase = next == LibraryLayout.Bookcase
        prefs.update { it.copy(layout = next) }
    }

    fun dismissMessage() = prefs.update { it.copy(message = null) }

    fun import(uri: String) {
        if (prefs.value.importing) return
        prefs.update { it.copy(importing = true, message = null) }
        viewModelScope.launch {
            val message = try {
                when (val r = deps.library.importBook(uri)) {
                    is ImportResult.Imported -> LibraryMessage.Imported(r.book.title)
                    is ImportResult.Duplicate -> LibraryMessage.Duplicate(r.book.title)
                    is ImportResult.Failed -> LibraryMessage.ImportFailed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LibraryMessage.ImportFailed
            }
            prefs.update { it.copy(importing = false, message = message) }
        }
    }

    fun openMenu(book: Book) = prefs.update { it.copy(menuFor = book) }
    fun closeMenu() = prefs.update { it.copy(menuFor = null) }
    fun requestDelete(book: Book) = prefs.update { it.copy(menuFor = null, pendingDelete = book) }
    fun cancelDelete() = prefs.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val book = prefs.value.pendingDelete ?: return
        prefs.update { it.copy(pendingDelete = null) }
        viewModelScope.launch { deps.removeBook(book.id) }
    }
}

/**
 * Splits spines into shelf rows. Each entry is a spine width in dp; [gap] sits between neighbours.
 * A spine wider than a whole row still gets a row of its own.
 */
fun packShelfRows(widths: List<Float>, available: Float, gap: Float = 2f): List<List<Int>> {
    val rows = mutableListOf<MutableList<Int>>()
    var used = 0f
    for ((i, w) in widths.withIndex()) {
        val need = if (rows.isEmpty() || rows.last().isEmpty()) w else w + gap
        if (rows.isEmpty() || used + need > available) {
            rows += mutableListOf(i); used = w
        } else {
            rows.last() += i; used += need
        }
    }
    return rows
}
