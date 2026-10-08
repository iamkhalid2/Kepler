package app.kepler.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.AppSettings
import app.kepler.core.model.Book
import app.kepler.core.model.ImportResult
import app.kepler.core.model.NarrationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUi(
    val loaded: Boolean = false,
    /** The book to resume: the one opened most recently, else the newest. Null when the library is empty. */
    val current: Book? = null,
    val mode: HubMode = HubMode.Read,
    val started: Boolean = false,
    val voiceName: String? = null,
    val speed: Float = 1f,
    val dueCount: Int = 0,
    /** Narration progress of [current] while it is being generated. */
    val generating: Float? = null,
    /** Days since [current] was last opened, when it is three or more ("Previously on"). */
    val daysAway: Int? = null,
    val importing: Boolean = false,
    val importFailed: Boolean = false,
)

private const val DAY_MS = 86_400_000L

/** Picks the book Home leads with: most recently opened, then most recently added. */
fun currentBook(books: List<Book>): Book? =
    books.maxWithOrNull(compareBy<Book>({ it.lastOpenedAt ?: Long.MIN_VALUE }, { it.addedAt }))

/** "Previously on" appears only after at least three days away from a started book. */
fun daysAway(book: Book?, nowMs: Long): Int? {
    val opened = book?.lastOpenedAt ?: return null
    if (book.readFraction <= 0f && book.listenFraction <= 0f) return null
    val days = ((nowMs - opened) / DAY_MS).toInt()
    return days.takeIf { it >= 3 }
}

/** Review time estimate shown on Home: about 30 s a card, at least a minute. */
fun reviewMinutes(due: Int): Int = ((due + 1) / 2).coerceAtLeast(1)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val deps: LibraryDeps, private val now: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val local = MutableStateFlow(HomeUi())

    private val current = deps.library.books.map(::currentBook).distinctUntilChanged()

    private val narration = current.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf<NarrationState>(NarrationState.Idle) else deps.narration.state(id)
    }

    val state: StateFlow<HomeUi> = combine(
        current, deps.settings.settings, deps.cards.dueCount(now()), narration, local,
    ) { book, settings: AppSettings, due, n, l ->
        HomeUi(
            loaded = true,
            current = book,
            mode = book?.let(::primaryMode) ?: HubMode.Read,
            started = book != null && (book.readFraction > 0f || book.listenFraction > 0f),
            voiceName = settings.voice?.name,
            speed = settings.speed,
            dueCount = due,
            generating = (n as? NarrationState.Running)?.let { r ->
                if (r.totalSentences > 0) (r.doneSentences.toFloat() / r.totalSentences).coerceIn(0f, 1f) else 0f
            },
            daysAway = daysAway(book, now()),
            importing = l.importing,
            importFailed = l.importFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUi())

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
