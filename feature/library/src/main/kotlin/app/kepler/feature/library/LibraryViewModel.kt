package app.kepler.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.Book
import app.kepler.core.model.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibrarySort { Recent, Title, Author }
enum class LibraryLayout { Shelf, List }

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
    val searching: Boolean = false,
    val sort: LibrarySort = LibrarySort.Recent,
    val layout: LibraryLayout = LibraryLayout.Shelf,
    val importing: Boolean = false,
    val message: LibraryMessage? = null,
    val pendingDelete: Book? = null,
) {
    val isEmpty: Boolean get() = loaded && total == 0
    val noMatches: Boolean get() = loaded && total > 0 && books.isEmpty()
}

private data class Prefs(
    val query: String = "", val searching: Boolean = false, val sort: LibrarySort = LibrarySort.Recent,
    val layout: LibraryLayout = LibraryLayout.Shelf, val importing: Boolean = false,
    val message: LibraryMessage? = null, val pendingDelete: Book? = null,
)

/** Title/author match on every typed word, case-insensitive. */
fun matchesQuery(book: Book, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = (book.title + " " + book.authors).lowercase()
    return words.all { hay.contains(it) }
}

fun sortBooks(books: List<Book>, sort: LibrarySort): List<Book> = when (sort) {
    LibrarySort.Recent -> books.sortedByDescending { it.lastOpenedAt ?: it.addedAt }
    LibrarySort.Title -> books.sortedBy { it.title.lowercase() }
    LibrarySort.Author -> books.sortedWith(compareBy({ it.authors.lowercase() }, { it.title.lowercase() }))
}

class LibraryViewModel(private val deps: LibraryDeps) : ViewModel() {
    private val prefs = MutableStateFlow(Prefs())

    val state: StateFlow<LibraryUi> = combine(deps.library.books, prefs) { books, p ->
        LibraryUi(
            loaded = true,
            books = sortBooks(books.filter { matchesQuery(it, p.query) }, p.sort),
            total = books.size,
            query = p.query, searching = p.searching, sort = p.sort, layout = p.layout,
            importing = p.importing, message = p.message, pendingDelete = p.pendingDelete,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUi())

    fun setQuery(q: String) = prefs.update { it.copy(query = q) }
    fun toggleSearch() = prefs.update { if (it.searching) it.copy(searching = false, query = "") else it.copy(searching = true) }
    fun setSort(s: LibrarySort) = prefs.update { it.copy(sort = s) }
    fun toggleLayout() = prefs.update {
        it.copy(layout = if (it.layout == LibraryLayout.Shelf) LibraryLayout.List else LibraryLayout.Shelf)
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

    fun requestDelete(book: Book) = prefs.update { it.copy(pendingDelete = book) }
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
