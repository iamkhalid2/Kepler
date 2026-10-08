package app.kepler.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.Annotation
import app.kepler.core.model.Book
import app.kepler.core.model.Card
import app.kepler.core.model.CardType
import app.kepler.core.model.Section
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SectionHighlights(val section: Section?, val items: List<Annotation>)
data class BookHighlights(val book: Book, val sections: List<SectionHighlights>) {
    val count get() = sections.sumOf { it.items.size }
}
data class BookCards(val book: Book, val cards: List<Card>)

/** Highlights per book in library order, per section in book order, newest-first never: book order wins. */
fun groupHighlights(books: List<Book>, sectionsByBook: Map<Long, List<Section>>, annotations: List<Annotation>): List<BookHighlights> {
    val byBook = annotations.groupBy { it.bookId }
    return books.mapNotNull { b ->
        val list = byBook[b.id] ?: return@mapNotNull null
        BookHighlights(b, highlightsInBookOrder(sectionsByBook[b.id].orEmpty(), list).map { SectionHighlights(it.first, it.second) })
    }
}

data class NotebookData(
    val loaded: Boolean = false,
    val highlights: List<BookHighlights> = emptyList(),
    val cards: List<BookCards> = emptyList(),
    val words: List<BookCards> = emptyList(),
    val dueCount: Int = 0,
    val week: WeekProgress? = null,
)

enum class ExportFormat { MARKDOWN, WEB_ANNOTATION }
enum class ExportStatus { IDLE, WORKING, DONE, FAILED }

data class ExportState(val status: ExportStatus = ExportStatus.IDLE, val fileName: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class NotebookViewModel(private val deps: StudyDeps) : ViewModel() {
    val data: StateFlow<NotebookData> = combine(
        deps.annotations.allAnnotations(),
        deps.library.books,
        deps.cards.cards(null),
        deps.cards.dueCount(deps.clock()),
        combine(deps.reviewStats.reviewDays, deps.reviewStats.weeklyGoalDays) { d, g -> d to g },
    ) { ann, books, cards, due, stats -> Inputs(ann, books, cards, due, stats.first, stats.second) }
        .mapLatest { i ->
            val sections = i.annotations.map { it.bookId }.distinct().associateWith { deps.spine.sections(it) }
            val titles = i.books.associateBy { it.id }
            fun grouped(list: List<Card>) = list.groupBy { it.bookId }.mapNotNull { (id, cs) -> titles[id]?.let { BookCards(it, cs.sortedBy { c -> c.sourceStart }) } }
            val today = java.time.Instant.ofEpochMilli(deps.clock()).atZone(deps.zone).toLocalDate()
            NotebookData(
                loaded = true,
                highlights = groupHighlights(i.books, sections, i.annotations),
                cards = grouped(i.cards.filter { it.type != CardType.VOCAB }),
                words = grouped(i.cards.filter { it.type == CardType.VOCAB }),
                dueCount = i.due,
                week = weekProgress(today, i.days, i.goal),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotebookData())

    private class Inputs(
        val annotations: List<Annotation>, val books: List<Book>, val cards: List<Card>,
        val due: Int, val days: Set<String>, val goal: Int,
    )

    private val _export = MutableStateFlow(ExportState())
    val export: StateFlow<ExportState> = _export

    fun resetExport() { _export.value = ExportState() }

    fun deleteCard(id: Long) { viewModelScope.launch { deps.cards.delete(id) } }

    fun deleteHighlight(id: Long) { viewModelScope.launch { deps.annotations.delete(id) } }

    fun saveNote(a: Annotation, note: String) {
        viewModelScope.launch { deps.annotations.update(a.copy(note = note.trim().ifEmpty { null })) }
    }

    /** Builds the document for one book and hands it to the app's writer. */
    fun export(book: Book, format: ExportFormat) {
        if (_export.value.status == ExportStatus.WORKING) return
        _export.value = ExportState(ExportStatus.WORKING)
        viewModelScope.launch {
            val doc = try {
                val ann = deps.annotations.annotations(book.id).first()
                when (format) {
                    ExportFormat.MARKDOWN -> MarkdownExporter.export(book, deps.spine.sections(book.id), ann, deps.clock())
                    ExportFormat.WEB_ANNOTATION -> WebAnnotationExporter.export(book, ann)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {
                _export.value = ExportState(ExportStatus.FAILED); return@launch
            }
            try {
                deps.writeDocument(doc.name, doc.mime, doc.text)
                _export.value = ExportState(ExportStatus.DONE, doc.name)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {
                _export.value = ExportState(ExportStatus.FAILED, doc.name)
            }
        }
    }
}
