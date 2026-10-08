package app.kepler.core.database.repo

import app.kepler.core.database.db.KeplerDatabase
import app.kepler.core.database.db.Fts
import app.kepler.core.database.toModel
import app.kepler.core.model.BookId
import app.kepler.core.model.Section
import app.kepler.core.model.SectionEditor
import app.kepler.core.model.Sentence
import app.kepler.core.model.SpineRepository

internal class SpineRepositoryImpl(
    db: KeplerDatabase,
    /** Called after a section's `included` flag changes so derived fractions stay right. */
    private val onSectionsChanged: suspend (BookId) -> Unit = {},
) : SpineRepository, SectionEditor {
    private val sections = db.sections()
    private val sentences = db.sentences()

    override suspend fun sections(bookId: BookId): List<Section> = sections.forBook(bookId).map { it.toModel() }

    override suspend fun sentence(bookId: BookId, ordinal: Int): Sentence? = sentences.get(bookId, ordinal)?.toModel()

    override suspend fun sentences(bookId: BookId, fromOrdinal: Int, toOrdinal: Int): List<Sentence> =
        if (toOrdinal < fromOrdinal) emptyList() else sentences.range(bookId, fromOrdinal, toOrdinal).map { it.toModel() }

    override suspend fun sentenceCount(bookId: BookId): Int = sentences.count(bookId)

    override suspend fun search(bookId: BookId, query: String, maxOrdinal: Int?, limit: Int): List<Sentence> {
        val match = Fts.matchExpression(query) ?: return emptyList()
        if (maxOrdinal != null && maxOrdinal < 0) return emptyList()
        return sentences.search(bookId, match, maxOrdinal ?: Int.MAX_VALUE, limit.coerceAtLeast(1)).map { it.toModel() }
    }

    override suspend fun setIncluded(sectionId: Long, included: Boolean) {
        val bookId = sections.bookIdOf(sectionId) ?: return
        sections.setIncluded(sectionId, included)
        onSectionsChanged(bookId)
    }
}
