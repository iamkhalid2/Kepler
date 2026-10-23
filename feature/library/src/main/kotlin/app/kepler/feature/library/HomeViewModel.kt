package app.kepler.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.AppSettings
import app.kepler.core.model.Book
import app.kepler.core.model.ListenPace
import app.kepler.core.model.BookId
import app.kepler.core.model.ImportResult
import app.kepler.core.model.NarrationState
import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One day of the review week; the first dot is Monday. */
data class WeekDot(val day: DayOfWeek, val reviewed: Boolean, val today: Boolean)

data class HomeHighlight(val id: Long, val bookId: BookId, val quote: String, val bookTitle: String, val createdAt: Long)

/** The "Previously on" card: hidden, waiting for the first token, streaming, finished or failed. */
sealed interface RecapUi {
    data object None : RecapUi
    data object Loading : RecapUi
    data class Streaming(val text: String) : RecapUi
    data class Done(val text: String) : RecapUi
    data object Failed : RecapUi
}

data class HomeUi(
    val loaded: Boolean = false,
    /** The book to resume: the one opened most recently, else the newest. Null when the library is empty. */
    val current: Book? = null,
    val mode: HubMode = HubMode.Read,
    val started: Boolean = false,
    val voiceName: String? = null,
    val speed: Float = 1f,
    val dueCount: Int = 0,
    val reviewedToday: Int = 0,
    val week: List<WeekDot> = emptyList(),
    /** Narration progress of [current] while it is being generated. */
    val generating: Float? = null,
    /** Days since [current] was last opened, when it is two or more ("Previously on"). */
    val daysAway: Int? = null,
    /** Title of the chapter [current] is in, when the book has been read or listened to. */
    val chapterTitle: String? = null,
    /** Estimated minutes left in [current] from where you are (at the listening speed when listening). */
    val minutesLeft: Int? = null,
    val recap: RecapUi = RecapUi.None,
    /** Other books for the Continue carousel, most recent first. */
    val carousel: List<Book> = emptyList(),
    val highlights: List<HomeHighlight> = emptyList(),
    val importing: Boolean = false,
    val importFailed: Boolean = false,
)

private const val DAY_MS = 86_400_000L

/** Picks the book Home leads with: most recently opened, then most recently added. */
fun currentBook(books: List<Book>): Book? =
    books.maxWithOrNull(compareBy<Book>({ it.lastOpenedAt ?: Long.MIN_VALUE }, { it.addedAt }))

/** Whether the book has any reading or listening progress. */
fun hasStarted(book: Book): Boolean = book.positionFraction > 0f || book.readFraction > 0f || book.listenFraction > 0f

/** "Previously on" appears after at least three days away from a started book. */
fun daysAway(book: Book?, nowMs: Long): Int? {
    val opened = book?.lastOpenedAt ?: return null
    if (!hasStarted(book)) return null
    val days = ((nowMs - opened) / DAY_MS).toInt()
    return days.takeIf { it >= 3 }
}

/** Review time estimate shown on Home: about 30 s a card, at least a minute. */
fun reviewMinutes(due: Int): Int = ((due + 1) / 2).coerceAtLeast(1)

/**
 * Minutes left from the saved position: reading pace, or when listening the narration pace the player uses
 * ([ListenPace.msPerSentence] over the book's generated audio), scaled by speed.
 */
fun minutesLeft(book: Book, speed: Float, listening: Boolean, msPerSentence: Double = ListenPace.DEFAULT_MS_PER_SENTENCE): Int {
    val left = (book.sentenceCount * (1f - book.positionFraction.coerceIn(0f, 1f))).toInt()
    if (!listening) return readMinutes(left)
    return (left * msPerSentence / 60_000.0 / speed.coerceIn(0.5f, 3f)).toInt().coerceAtLeast(1)
}

/** The seven days of the week containing [today], Monday first. */
fun weekDots(today: LocalDate, reviewDays: Set<String>, reviewedToday: Boolean): List<WeekDot> {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return List(7) { i ->
        val d = monday.plusDays(i.toLong())
        WeekDot(d.dayOfWeek, d.toString() in reviewDays || (d == today && reviewedToday), d == today)
    }
}

/** The body section that holds [ordinal]; falls back to the last one before it. */
fun chapterAt(sections: List<Section>, ordinal: Int): Section? {
    val body = sections.filter { it.kind == SectionKind.BODY && it.included }
    return body.firstOrNull { ordinal in it.startOrdinal..it.endOrdinal }
        ?: body.lastOrNull { it.startOrdinal <= ordinal }
        ?: body.firstOrNull()
}

/** Recaps already written this session, keyed by book and whole percent, so coming back to Home never asks again. */
internal object RecapCache {
    private val map = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 8
    }
    @Synchronized fun get(key: String): String? = map[key]
    @Synchronized fun put(key: String, text: String) { map[key] = text }
    fun key(book: Book) = "${book.id}:${percentOf(book.positionFraction)}"
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val deps: LibraryDeps,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val local = MutableStateFlow(HomeUi())
    private val recap = MutableStateFlow<RecapUi>(RecapUi.None)
    private val retry = MutableStateFlow(0)
    private val sections = HashMap<BookId, List<Section>>()

    private val books = deps.library.books
    private val current = books.map(::currentBook).distinctUntilChanged()
    private val pace: Flow<Double> = current.map { it?.id }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else deps.audio.segments(id) }
        .map(ListenPace::msPerSentence).distinctUntilChanged()

    private val narration = current.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf<NarrationState>(NarrationState.Idle) else deps.narration.state(id)
    }

    private val week: Flow<List<WeekDot>> = combine(
        deps.reviewStats?.reviewDays ?: flowOf(emptySet()),
        deps.cards.reviewedTodayFlow(now()),
    ) { days, today -> weekDots(Instant.ofEpochMilli(now()).atZone(zone).toLocalDate(), days, today > 0) }

    private val highlights: Flow<List<HomeHighlight>> = combine(
        deps.annotations?.allAnnotations() ?: flowOf(emptyList()), books,
    ) { all, list ->
        val titles = list.associate { it.id to it.title }
        all.filter { it.quote.isNotBlank() && it.bookId in titles }
            .sortedByDescending { it.createdAt }.take(3)
            .map { HomeHighlight(it.id, it.bookId, it.quote.trim(), titles.getValue(it.bookId), it.createdAt) }
    }

    private val chapter: Flow<String?> = current.map { book ->
        val spine = deps.spine
        if (book == null || spine == null || !hasStarted(book)) null else {
            val list = sections.getOrPut(book.id) { runCatching { spine.sections(book.id) }.getOrDefault(emptyList()) }
            chapterAt(list, (book.positionFraction * book.sentenceCount).toInt())?.title?.takeIf { it.isNotBlank() }
        }
    }

    private val extras = combine(week, highlights, chapter, recap) { w, h, c, r -> Extras(w, h, c, r) }

    val state: StateFlow<HomeUi> = combine(
        current, books, deps.settings.settings, deps.cards.dueCount(now()), narration,
    ) { book, all, settings: AppSettings, due, n -> Base(book, all, settings, due, n) }
        .combine(deps.cards.reviewedTodayFlow(now())) { b, done -> b to done }
        .combine(pace) { pair, pace -> pair to pace }
        .combine(extras) { (pair, pace), x ->
            val (b, done) = pair
            val book = b.book
            val mode = book?.let(::primaryMode) ?: HubMode.Read
            HomeUi(
                loaded = true,
                current = book,
                mode = mode,
                started = book != null && hasStarted(book),
                voiceName = b.settings.voice?.name,
                speed = b.settings.speed,
                dueCount = b.due,
                reviewedToday = done,
                week = x.week,
                generating = (b.narration as? NarrationState.Running)?.let { r ->
                    if (r.totalSentences > 0) (r.doneSentences.toFloat() / r.totalSentences).coerceIn(0f, 1f) else 0f
                },
                daysAway = daysAway(book, now()),
                chapterTitle = x.chapter,
                minutesLeft = book?.takeIf { hasStarted(it) }?.let { minutesLeft(it, b.settings.speed, mode == HubMode.Listen, pace) },
                recap = x.recap,
                carousel = b.all.filter { it.id != book?.id }
                    .sortedByDescending { it.lastOpenedAt ?: it.addedAt }.take(10),
                highlights = x.highlights,
            )
        }
        .combine(local) { ui, l -> ui.copy(importing = l.importing, importFailed = l.importFailed) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUi())

    private class Base(val book: Book?, val all: List<Book>, val settings: AppSettings, val due: Int, val narration: NarrationState)
    private class Extras(val week: List<WeekDot>, val highlights: List<HomeHighlight>, val chapter: String?, val recap: RecapUi)

    init {
        // The recap starts as soon as Home knows the reader has been away, so it is written before they look.
        viewModelScope.launch {
            combine(current.map { b -> b?.takeIf { daysAway(it, now()) != null } }.distinctUntilChanged { a, b -> a?.id == b?.id && a?.let(RecapCache::key) == b?.let(RecapCache::key) }, retry) { b, _ -> b }
                .collectLatest { book -> writeRecap(book) }
        }
    }

    private suspend fun writeRecap(book: Book?) {
        val ai = deps.aiStreaming
        if (book == null || ai == null) { recap.value = RecapUi.None; return }
        val key = RecapCache.key(book)
        RecapCache.get(key)?.let { recap.value = RecapUi.Done(it); return }
        if (deps.settings.openRouterKey() == null) { recap.value = RecapUi.None; return }
        recap.value = RecapUi.Loading
        val text = StringBuilder()
        var failed = false
        ai.recapStream(book.id)
            .catch { e -> if (e is CancellationException) throw e; failed = true }
            .collect { delta -> text.append(delta); recap.value = RecapUi.Streaming(text.toString()) }
        recap.value = when {
            failed && text.isBlank() -> RecapUi.Failed
            text.isBlank() -> RecapUi.None
            else -> { RecapCache.put(key, text.toString()); RecapUi.Done(text.toString()) }
        }
    }

    fun retryRecap() { retry.update { it + 1 } }

    fun import(uri: String) {
        if (local.value.importing) return
        local.update { it.copy(importing = true, importFailed = false) }
        viewModelScope.launch {
            val failed = try {
                deps.library.importBook(uri) is ImportResult.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
            local.update { it.copy(importing = false, importFailed = failed) }
        }
    }

    fun dismissMessage() = local.update { it.copy(importFailed = false) }
}
