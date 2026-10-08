package app.betterbooks.feature.study

import app.betterbooks.core.model.AiService
import app.betterbooks.core.model.AnnotationRepository
import app.betterbooks.core.model.CardRepository
import app.betterbooks.core.model.LibraryRepository
import app.betterbooks.core.model.PositionRepository
import app.betterbooks.core.model.ReviewStatsStore
import app.betterbooks.core.model.Scheduler
import app.betterbooks.core.model.SpineRepository
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
     * Writes one document through the system file picker (the app wires SAF) and throws when the user
     * cancels or the write fails.
     */
    val writeDocument: suspend (name: String, mime: String, text: String) -> Unit

    val clock: () -> Long get() = System::currentTimeMillis
    val zone: ZoneId get() = ZoneId.systemDefault()
}
