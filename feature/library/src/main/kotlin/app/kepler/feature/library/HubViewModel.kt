package app.kepler.feature.library

import kotlinx.coroutines.flow.map
import app.kepler.core.model.ListenPace
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.Book
import app.kepler.core.model.BookId
import app.kepler.core.model.NarrationState
import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HubMode { Read, Listen }

sealed interface NarrationStatus {
    data object None : NarrationStatus
    /** Audio exists for part of the book (or generation is running). [running] = generation is in progress. */
    data class Binding(val fraction: Float, val running: Boolean, val etaSeconds: Long?) : NarrationStatus
    data object Bound : NarrationStatus
    data class Paused(val reason: String) : NarrationStatus
    data class Failed(val message: String) : NarrationStatus
}

enum class ChapterState { Done, Current, Upcoming }

/** One chapter row of the Hub: where you are in it, by the saved position. */
data class HubChapter(val id: Long, val title: String, val state: ChapterState)

/** Chapters (body sections) with their state relative to the saved position ordinal. */
fun hubChapters(sections: List<Section>, positionOrdinal: Int, started: Boolean): List<HubChapter> {
    val body = sections.filter { it.kind == SectionKind.BODY && it.included && it.title.isNotBlank() }
    val current = if (started) chapterAt(sections, positionOrdinal)?.id else null
    val currentIndex = body.indexOfFirst { it.id == current }
    return body.mapIndexed { i, s ->
        HubChapter(
            s.id, s.title,
            when {
                currentIndex < 0 -> ChapterState.Upcoming
                i < currentIndex -> ChapterState.Done
                i == currentIndex -> ChapterState.Current
                else -> ChapterState.Upcoming
            },
        )
    }
}

data class HubUi(
    val loaded: Boolean = false,
    val book: Book? = null,
    val primary: HubMode = HubMode.Read,
    /** Some reading or listening progress exists, so the key says "Continue"/"Resume" instead of "Start". */
    val started: Boolean = false,
    val narration: NarrationStatus = NarrationStatus.None,
    val voiceName: String? = null,
    val cardCount: Int = 0,
    val chapters: List<HubChapter> = emptyList(),
    /** Estimated minutes of listening left from the saved position, at the chosen speed. */
    val minutesToListen: Int? = null,
)

/** Primary key goes to the mode the reader used most; reading wins a tie. */
fun primaryMode(book: Book): HubMode = if (book.listenFraction > book.readFraction) HubMode.Listen else HubMode.Read

fun narrationStatus(state: NarrationState, coverage: Float): NarrationStatus = when (state) {
    is NarrationState.Running -> NarrationStatus.Binding(
        fraction = if (state.totalSentences > 0) (state.doneSentences.toFloat() / state.totalSentences).coerceIn(0f, 1f) else coverage,
        running = true, etaSeconds = state.etaSeconds,
    )
    is NarrationState.Paused -> NarrationStatus.Paused(state.reason)
    is NarrationState.Failed -> NarrationStatus.Failed(state.message)
    is NarrationState.Done -> NarrationStatus.Bound
    NarrationState.Idle -> when {
        coverage >= 0.995f -> NarrationStatus.Bound
        coverage > 0.001f -> NarrationStatus.Binding(coverage, running = false, etaSeconds = null)
        else -> NarrationStatus.None
    }
}

class HubViewModel(private val deps: LibraryDeps, val bookId: BookId) : ViewModel() {
    private val sections = MutableStateFlow<List<Section>>(emptyList())

    val state: StateFlow<HubUi> = combine(
        deps.library.book(bookId),
        deps.narration.state(bookId),
        deps.audio.coverage(bookId),
        deps.settings.settings,
        deps.cards.cards(bookId),
    ) { book, narration, coverage, settings, cards -> Parts(book, narration, coverage, settings, cards) }
        .combine(sections) { x, sec -> x to sec }
        .combine(deps.audio.segments(bookId).map(ListenPace::msPerSentence)) { (x, sec), pace ->
        val book = x.book
        val narration = x.narration
        val coverage = x.coverage
        val settings = x.settings
        val cards = x.cards
        HubUi(
            loaded = true,
            book = book,
            primary = book?.let(::primaryMode) ?: HubMode.Read,
            started = book != null && hasStarted(book),
            narration = narrationStatus(narration, coverage),
            voiceName = settings.voice?.name,
            cardCount = cards.size,
            chapters = book?.let { hubChapters(sec, (it.positionFraction * it.sentenceCount).toInt(), hasStarted(it)) }.orEmpty(),
            minutesToListen = book?.let { minutesLeft(it, settings.speed, listening = true, msPerSentence = pace) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HubUi())

    private class Parts(
        val book: Book?, val narration: NarrationState, val coverage: Float,
        val settings: app.kepler.core.model.AppSettings, val cards: List<app.kepler.core.model.Card>,
    )

    init {
        viewModelScope.launch { deps.library.markOpened(bookId) }
        deps.spine?.let { spine ->
            viewModelScope.launch { sections.value = runCatching { spine.sections(bookId) }.getOrDefault(emptyList()) }
        }
    }

    /** Stops playback and narration for this book, then deletes it. [done] runs once it is gone. */
    fun remove(done: () -> Unit = {}) {
        viewModelScope.launch {
            deps.removeBook(bookId)
            done()
        }
    }
}
