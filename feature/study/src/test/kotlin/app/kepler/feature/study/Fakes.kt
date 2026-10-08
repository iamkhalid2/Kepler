package app.kepler.feature.study

import app.kepler.core.model.*
import app.kepler.core.model.Annotation
import app.kepler.engine.ai.AiError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.ZoneId

val UTC: ZoneId = ZoneId.of("UTC")
const val T0 = 1_760_000_000_000L // 2025-10-09 08:53 UTC (a Thursday)

fun book(id: Long = 1, type: ContentType = ContentType.NONFICTION, title: String = "Meditations") = Book(
    id, title, "Marcus", "en", type, "/x.epub", null, 0, null, 100, 0f, 0f,
)

fun section(id: Long, index: Int, start: Int, end: Int, title: String = "S$index", kind: SectionKind = SectionKind.BODY) =
    Section(id, 1, null, index, 1, title, "h$index", kind, start, end, true)

fun card(id: Long, front: String = "Q$id", back: String = "A$id", type: CardType = CardType.QA, created: Long = T0, state: Int = 0, due: Long = T0) =
    Card(id, 1, type, 1, 1, front, back, null, due, 0.0, 0.0, 0, 0, state, null, created)

fun annotation(id: Long, start: Int, quote: String = "quote $id", note: String? = null, bookId: Long = 1) =
    Annotation(id, bookId, start, start, quote, 0, note, AnnotationVia.READ, T0)

class FakeAi : AiService {
    var failure: Throwable? = null
    var left = 18
    val asked = mutableListOf<String>()
    var explainCalls = 0
    var check = AnswerCheck("correct", listOf("Claim A"), listOf("Claim B"))
    var checkIn = CheckIn("What was the claim?", "The gist.", listOf("Claim A", "Claim B"))
    var suggestions = listOf(CardSuggestion("Why?", 5, 6, "Because."))
    private fun maybeFail() { failure?.let { throw it } }
    override suspend fun budget() = AiBudget(left, 20, true, "m")
    override suspend fun explain(bookId: BookId, ordinal: Int, selection: String): String { maybeFail(); explainCalls++; return "explained" }
    override suspend fun define(bookId: BookId, ordinal: Int, term: String): String { maybeFail(); return "1. a thing" }
    override suspend fun ask(bookId: BookId, question: String): AskAnswer { asked += question; maybeFail(); left--; return AskAnswer("Answer [1]", listOf(Citation(7, "Book I"))) }
    override suspend fun recap(bookId: BookId): String { maybeFail(); return "recap" }
    override suspend fun takeaways(bookId: BookId, sectionId: Long): List<String> { maybeFail(); return listOf("t1") }
    override suspend fun concepts(bookId: BookId): ConceptMap { maybeFail(); return ConceptMap(listOf(ConceptNode("a", "Alpha", "concept", "d", 1), ConceptNode("b", "Beta", "person", "d", 2)), listOf(ConceptEdge("a", "b", "rel", 3))) }
    override suspend fun suggestCards(bookId: BookId, sectionId: Long): List<CardSuggestion> { maybeFail(); return suggestions }
    override suspend fun checkAnswer(question: String, userAnswer: String, sourceText: String): AnswerCheck { maybeFail(); return check }
    override suspend fun checkIn(bookId: BookId, sectionId: Long): CheckIn { maybeFail(); return checkIn }
}

class FakeCards(initial: List<Card> = emptyList()) : CardRepository {
    val all = MutableStateFlow(initial)
    private var nextId = 100L
    override fun dueCards(nowMs: Long, limit: Int): Flow<List<Card>> = all.map { l -> l.filter { it.dueAt <= nowMs }.take(limit) }
    override fun cards(bookId: BookId?): Flow<List<Card>> = all.map { l -> l.filter { bookId == null || it.bookId == bookId } }
    override fun dueCount(nowMs: Long): Flow<Int> = all.map { l -> l.count { it.dueAt <= nowMs } }
    override suspend fun add(card: Card): Long { val id = nextId++; all.value = all.value + card.copy(id = id); return id }
    override suspend fun update(card: Card) { all.value = all.value.map { if (it.id == card.id) card else it } }
    override suspend fun delete(id: Long) { all.value = all.value.filterNot { it.id == id } }
    override suspend fun reviewedToday(nowMs: Long) = 0
}

/** Scheduler stub: Again comes back in 1 minute, Good in 3 days. */
class FakeScheduler : Scheduler {
    override fun review(card: Card, rating: Rating, nowMs: Long) = card.copy(
        reps = card.reps + 1, state = 2, lastReviewAt = nowMs,
        dueAt = nowMs + if (rating == Rating.AGAIN) 60_000L else 3 * 86_400_000L,
    )
    override fun preview(card: Card, nowMs: Long) = Rating.entries.associateWith { review(card, it, nowMs).dueAt - nowMs }
}

class FakeStats : ReviewStatsStore {
    val days = MutableStateFlow<Set<String>>(emptySet())
    val goal = MutableStateFlow(5)
    val recallList = MutableStateFlow<List<RecallResult>>(emptyList())
    override val reviewDays: Flow<Set<String>> = days
    override val weeklyGoalDays: Flow<Int> = goal
    override suspend fun recordReviewDay(day: String) { days.value = days.value + day }
    override suspend fun setWeeklyGoalDays(days: Int) { goal.value = days }
    override val recalls: Flow<List<RecallResult>> = recallList
    override suspend fun addRecall(result: RecallResult) { recallList.value = recallList.value + result }
}

class FakeAnnotations(initial: List<Annotation> = emptyList()) : AnnotationRepository {
    val all = MutableStateFlow(initial)
    override fun annotations(bookId: BookId) = all.map { l -> l.filter { it.bookId == bookId } }
    override fun allAnnotations(): Flow<List<Annotation>> = all
    override suspend fun add(a: Annotation): Long { all.value = all.value + a; return a.id }
    override suspend fun update(a: Annotation) { all.value = all.value.map { if (it.id == a.id) a else it } }
    override suspend fun delete(id: Long) { all.value = all.value.filterNot { it.id == id } }
    override fun bookmarks(bookId: BookId): Flow<List<Bookmark>> = MutableStateFlow(emptyList())
    override suspend fun addBookmark(b: Bookmark) = 0L
    override suspend fun deleteBookmark(id: Long) {}
}

class FakeSpine(val sections: List<Section> = emptyList()) : SpineRepository {
    override suspend fun sections(bookId: BookId) = sections
    override suspend fun sentence(bookId: BookId, ordinal: Int) =
        Sentence(bookId, ordinal, sections.firstOrNull()?.id ?: 1, "h", "Sentence $ordinal.", "", "", 0)
    override suspend fun sentences(bookId: BookId, fromOrdinal: Int, toOrdinal: Int) = emptyList<Sentence>()
    override suspend fun sentenceCount(bookId: BookId) = 100
    override suspend fun search(bookId: BookId, query: String, maxOrdinal: Int?, limit: Int) = emptyList<Sentence>()
}

class FakePositions(var frontier: Int = 40) : PositionRepository {
    override fun position(bookId: BookId, kind: PositionKind): Flow<Position?> = MutableStateFlow(null)
    override suspend fun savePosition(bookId: BookId, kind: PositionKind, ordinal: Int) {}
    override suspend fun markConsumed(bookId: BookId, fromOrdinal: Int, toOrdinal: Int, via: PositionKind) {}
    override suspend fun frontier(bookId: BookId) = frontier
}

class FakeDeps(
    override val ai: FakeAi = FakeAi(),
    override val cards: FakeCards = FakeCards(),
    override val scheduler: Scheduler = FakeScheduler(),
    override val annotations: FakeAnnotations = FakeAnnotations(),
    override val positions: FakePositions = FakePositions(),
    override val spine: FakeSpine = FakeSpine(listOf(section(1, 0, 0, 49, "Book I"), section(2, 1, 50, 99, "Book II"))),
    booksState: List<Book> = listOf(book()),
    override val reviewStats: FakeStats = FakeStats(),
) : StudyDeps {
    val booksFlow = MutableStateFlow(booksState)
    override val library: LibraryRepository = object : LibraryRepository {
        override val books: Flow<List<Book>> = booksFlow
        override fun book(id: BookId): Flow<Book?> = booksFlow.map { l -> l.firstOrNull { it.id == id } }
        override suspend fun importBook(sourceUri: String): ImportResult = ImportResult.Failed("n/a")
        override suspend fun delete(id: Long) {}
        override suspend fun markOpened(id: Long) {}
    }
    var now = T0
    val written = mutableListOf<Triple<String, String, String>>()
    var writeFails = false
    override val writeDocument: suspend (String, String, String) -> Unit = { n, m, t ->
        if (writeFails) error("cancelled")
        written += Triple(n, m, t)
    }
    override val clock: () -> Long = { now }
    override val zone: ZoneId = UTC
}

fun budgetError() = AiError.BudgetExhausted(20)
