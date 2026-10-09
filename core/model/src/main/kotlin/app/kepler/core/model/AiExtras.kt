package app.kepler.core.model

import kotlinx.coroutines.flow.Flow

/**
 * One step of a streamed answer (Ask, Explain, Define).
 *
 * Collect in order: any number of [Delta] (append `text` as it arrives), [Citations] whenever a new `[n]` marker
 * was resolved (cumulative list, ordinals already checked against the passages the model was given), then exactly
 * one terminal event: [Done] (its `answer.text` is the canonical final text, replace the streamed text with it)
 * or [Failed]. Nothing follows a terminal event.
 */
sealed interface AskEvent {
    data class Delta(val text: String) : AskEvent
    data class Citations(val citations: List<Citation>) : AskEvent
    data class Done(val answer: AskAnswer) : AskEvent
    /** [error] is always an `app.kepler.engine.ai.AiError` subclass (feature code maps it with `toUiError()`). */
    data class Failed(val error: Throwable) : AskEvent
}

/**
 * Streaming counterparts of [AiService]. Every flow is cold, runs the spoiler fence before any prompt is built,
 * and starts showing text as soon as the model produces its first token (typically 1 to 2 s on a free model).
 * Cancelling the collector cancels the HTTP request.
 */
interface AiStreaming {
    fun askStream(bookId: BookId, question: String): Flow<AskEvent>

    /** [AskEvent.Citations] is never emitted; explanations carry no passage numbers. */
    fun explainStream(bookId: BookId, ordinal: Int, selection: String): Flow<AskEvent>

    fun defineStream(bookId: BookId, ordinal: Int, term: String): Flow<AskEvent>

    /**
     * Text deltas of the "previously" recap: cached per-section summaries arrive immediately, then only the
     * section being read is generated. Failures are thrown from the flow as `AiError` (use `.catch`).
     */
    fun recapStream(bookId: BookId): Flow<String>
}

/** Background preparation of the study packs behind Concepts, Map, Takeaways and cards. */
interface AiPrefetch {
    /**
     * Call when the reader enters a new section (or opens a book). Returns immediately; at BACKGROUND priority
     * and inside the request budget it builds the packs for the section just finished and the one being read
     * (and the next one when the fence allows it), so those screens open without a model call.
     * Also makes sure the model latency probe is fresh. Safe to call often; calls are debounced.
     */
    fun prefetch(bookId: BookId, ordinal: Int)
}
