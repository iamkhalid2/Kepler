package app.kepler.engine.ai

import app.kepler.core.model.BookId
import app.kepler.core.model.ContentType
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.PositionRepository
import app.kepler.core.model.Section
import app.kepler.core.model.Sentence
import app.kepler.core.model.SpineRepository
import kotlinx.coroutines.flow.first

enum class FenceFeature { ASK, EXPLAIN, DEFINE, RECAP, TAKEAWAYS, CONCEPTS, CARDS, CHECK_ANSWER, CHECK_IN }

enum class FenceMode { FRONTIER, WHOLE_BOOK }

/**
 * Decides the highest sentence ordinal a feature may see, before any text is fetched.
 * Defaults: FRONTIER everywhere; non-fiction Ask may use the whole book; cards and quizzes are always FRONTIER.
 * [modeFor] lets the app apply a per-book choice (e.g. the "Rereading" toggle sets WHOLE_BOOK).
 */
class FenceService(
    private val positions: PositionRepository,
    private val library: LibraryRepository,
    private val spine: SpineRepository,
    private val modeFor: suspend (BookId) -> FenceMode? = { null },
) {
    /** Highest allowed ordinal, or -1 when nothing may be used yet. */
    suspend fun maxOrdinal(bookId: BookId, feature: FenceFeature): Int {
        val frontier = positions.frontier(bookId)
        if (feature == FenceFeature.CARDS || feature == FenceFeature.CHECK_ANSWER || feature == FenceFeature.CHECK_IN) {
            return frontier
        }
        val mode = modeFor(bookId) ?: if (feature == FenceFeature.ASK) {
            val book = library.book(bookId).first()
            if (book?.contentType == ContentType.NONFICTION) FenceMode.WHOLE_BOOK else FenceMode.FRONTIER
        } else FenceMode.FRONTIER
        return if (mode == FenceMode.WHOLE_BOOK) spine.sentenceCount(bookId) - 1 else frontier
    }
}

/** A section's text clipped to the fence. */
class SectionText(val section: Section, val sentences: List<Sentence>, val complete: Boolean, val rendered: String) {
    val firstOrdinal get() = sentences.first().ordinal
    val lastOrdinal get() = sentences.last().ordinal
    val hash: String by lazy { sha256(section.id.toString(), rendered) }
}

/**
 * The only door to book text for prompts. Everything fetched is clamped to `fence` and filtered again
 * on the way out, so a misbehaving repository cannot leak text past the fence.
 */
class ContextBuilder(private val spine: SpineRepository, private val maxSectionChars: Int = 120_000) {

    suspend fun sectionText(bookId: BookId, section: Section, fence: Int): SectionText? {
        val end = minOf(section.endOrdinal, fence)
        if (fence < 0 || end < section.startOrdinal) return null
        val all = spine.sentences(bookId, section.startOrdinal, end)
            .filter { it.ordinal in section.startOrdinal..end }.sortedBy { it.ordinal }
        if (all.isEmpty()) return null
        var chars = 0
        val kept = all.takeWhile { chars += it.text.length + 8; chars <= maxSectionChars }.ifEmpty { all.take(1) }
        val complete = end == section.endOrdinal && kept.size == all.size
        return SectionText(section, kept, complete, render(kept))
    }

    suspend fun window(bookId: BookId, center: Int, before: Int, after: Int, fence: Int): List<Sentence> {
        if (fence < 0) return emptyList()
        val from = maxOf(0, center - before)
        val to = minOf(center + after, fence)
        if (to < from) return emptyList()
        return spine.sentences(bookId, from, to).filter { it.ordinal in from..to }.sortedBy { it.ordinal }
    }

    suspend fun search(bookId: BookId, query: String, fence: Int, limit: Int): List<Sentence> {
        if (fence < 0 || query.isBlank()) return emptyList()
        return spine.search(bookId, query, fence, limit).filter { it.ordinal <= fence }
    }

    fun render(sentences: List<Sentence>): String =
        sentences.joinToString("\n") { "[${it.ordinal}] ${it.text.trim()}" }
}
