package app.kepler.feature.study

import app.kepler.core.model.AiPrefetch
import app.kepler.core.model.AiService
import app.kepler.core.model.AiStreaming
import app.kepler.core.model.AnnotationRepository
import app.kepler.core.model.AskAnswer
import app.kepler.core.model.AskEvent
import app.kepler.core.model.BookId
import app.kepler.core.model.CardRepository
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.PositionRepository
import app.kepler.core.model.ReviewStatsStore
import app.kepler.core.model.Scheduler
import app.kepler.core.model.SpineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.ZoneId

/** What the study screens need from the rest of the app. */
interface StudyDeps {
    val ai: AiService
    val cards: CardRepository
    val scheduler: Scheduler
    val annotations: AnnotationRepository
    val positions: PositionRepository
    val spine: SpineRepository
    val library: LibraryRepository
    val reviewStats: ReviewStatsStore

    /**
     * Streaming Ask, Explain, Define and Recap. The app passes the OpenRouter service; when [ai] is not a streaming
     * service (tests, previews) each call arrives as one finished event, so the screens behave the same.
     */
    val streaming: AiStreaming get() = ai as? AiStreaming ?: BlockingStreaming(ai)

    /** Builds the packs behind Concepts, Map and takeaways while the person reads. Null = nothing to prepare. */
    val prefetch: AiPrefetch? get() = ai as? AiPrefetch

    /**
     * Writes one document through the system file picker (the app wires SAF) and throws when the user
     * cancels or the write fails.
     */
    val writeDocument: suspend (name: String, mime: String, text: String) -> Unit

    val clock: () -> Long get() = System::currentTimeMillis
    val zone: ZoneId get() = ZoneId.systemDefault()
}

/** [AiStreaming] over the suspend calls: one delta and one terminal event. */
internal class BlockingStreaming(private val ai: AiService) : AiStreaming {
    override fun askStream(bookId: BookId, question: String): Flow<AskEvent> = once { ai.ask(bookId, question) }

    override fun explainStream(bookId: BookId, ordinal: Int, selection: String): Flow<AskEvent> =
        once { AskAnswer(ai.explain(bookId, ordinal, selection), emptyList()) }

    override fun defineStream(bookId: BookId, ordinal: Int, term: String): Flow<AskEvent> =
        once { AskAnswer(ai.define(bookId, ordinal, term), emptyList()) }

    override fun recapStream(bookId: BookId): Flow<String> = flow { emit(ai.recap(bookId)) }

    private fun once(block: suspend () -> AskAnswer): Flow<AskEvent> = flow {
        val answer = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(AskEvent.Failed(e)); return@flow
        }
        emit(AskEvent.Delta(answer.text))
        if (answer.citations.isNotEmpty()) emit(AskEvent.Citations(answer.citations))
        emit(AskEvent.Done(answer))
    }
}
