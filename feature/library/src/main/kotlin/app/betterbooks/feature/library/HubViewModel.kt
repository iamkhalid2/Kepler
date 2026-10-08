package app.betterbooks.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterbooks.core.model.Book
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.NarrationState
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

data class HubUi(
    val loaded: Boolean = false,
    val book: Book? = null,
    val primary: HubMode = HubMode.Read,
    /** Some reading or listening progress exists, so the key says "Continue"/"Resume" instead of "Start". */
    val started: Boolean = false,
    val narration: NarrationStatus = NarrationStatus.None,
    val voiceName: String? = null,
    val cardCount: Int = 0,
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
    val state: StateFlow<HubUi> = combine(
        deps.library.book(bookId),
        deps.narration.state(bookId),
        deps.audio.coverage(bookId),
        deps.settings.settings,
        deps.cards.cards(bookId),
    ) { book, narration, coverage, settings, cards ->
        HubUi(
            loaded = true,
            book = book,
            primary = book?.let(::primaryMode) ?: HubMode.Read,
            started = book != null && (book.readFraction > 0f || book.listenFraction > 0f),
            narration = narrationStatus(narration, coverage),
            voiceName = settings.voice?.name,
            cardCount = cards.size,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HubUi())

    init {
        viewModelScope.launch { deps.library.markOpened(bookId) }
    }
}
