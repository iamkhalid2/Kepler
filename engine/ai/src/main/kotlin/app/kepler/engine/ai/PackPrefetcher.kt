package app.kepler.engine.ai

import app.kepler.core.model.BookId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns "the reader is at ordinal N" signals into background pack builds. Requests are debounced and conflated
 * (only the newest position matters), and one build runs at a time, so paging quickly or switching books never
 * queues a pile of model calls. [work] does the building at BACKGROUND priority, inside the request budget.
 */
class PackPrefetcher(
    scope: CoroutineScope,
    private val debounceMs: Long = 1_500,
    private val work: suspend (BookId, Int) -> Unit,
) {
    private val requests = Channel<Pair<BookId, Int>>(Channel.CONFLATED)

    init {
        scope.launch {
            for (first in requests) {
                delay(debounceMs)
                val latest = requests.tryReceive().getOrNull() ?: first
                try {
                    work(latest.first, latest.second)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Background work never surfaces errors; the next signal tries again.
                }
            }
        }
    }

    /** Returns at once. */
    fun prefetch(bookId: BookId, ordinal: Int) {
        requests.trySend(bookId to ordinal)
    }
}
