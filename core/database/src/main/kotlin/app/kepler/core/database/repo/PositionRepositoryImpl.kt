package app.kepler.core.database.repo

import androidx.room3.withWriteTransaction
import app.kepler.core.database.ConsumedIntervals
import app.kepler.core.database.OrdinalRange
import app.kepler.core.database.db.KeplerDatabase
import app.kepler.core.database.db.ConsumedIntervalEntity
import app.kepler.core.database.db.SectionEntity
import app.kepler.core.database.toModel
import app.kepler.core.model.BookId
import app.kepler.core.model.Position
import app.kepler.core.model.PositionKind
import app.kepler.core.model.PositionRepository
import app.kepler.core.model.SectionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class PositionRepositoryImpl(
    private val db: KeplerDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : PositionRepository {
    private val positions = db.positions()
    private val consumed = db.consumed()
    private val sections = db.sections()
    private val books = db.books()

    override fun position(bookId: BookId, kind: PositionKind): Flow<Position?> =
        positions.observe(bookId, kind.name).map { it?.toModel() }

    override suspend fun savePosition(bookId: BookId, kind: PositionKind, ordinal: Int) {
        db.withWriteTransaction {
            positions.save(bookId, kind.name, ordinal.coerceAtLeast(0), clock())
            refreshPositionFraction(bookId, kind)
        }
    }

    /** "Where you are": the most recent saved position of either kind over the sentence count (decision #111). */
    private suspend fun refreshPositionFraction(bookId: BookId, preferKind: PositionKind) {
        val count = books.get(bookId)?.sentenceCount ?: return
        val latest = positions.latest(bookId, preferKind.name) ?: return
        books.setPositionFraction(bookId, positionFraction(latest.ordinal, count))
    }

    override suspend fun markConsumed(bookId: BookId, fromOrdinal: Int, toOrdinal: Int, via: PositionKind) {
        val lo = minOf(fromOrdinal, toOrdinal).coerceAtLeast(0)
        val hi = maxOf(fromOrdinal, toOrdinal)
        if (hi < 0) return
        db.withWriteTransaction {
            val touching = consumed.touching(bookId, via.name, lo, hi)
            val merged = ConsumedIntervals.add(touching.map { OrdinalRange(it.startOrdinal, it.endOrdinal) }, OrdinalRange(lo, hi))
            // Nothing to do when the new range was already fully inside one stored interval.
            if (touching.size == 1 && merged.single().let { it.start == touching[0].startOrdinal && it.end == touching[0].endOrdinal }) {
                return@withWriteTransaction
            }
            if (touching.isNotEmpty()) consumed.deleteIds(touching.map { it.id })
            consumed.insert(ConsumedIntervalEntity(bookId = bookId, kind = via.name, startOrdinal = merged.single().start, endOrdinal = merged.single().end))
            refreshFractions(bookId)
        }
    }

    override suspend fun frontier(bookId: BookId): Int {
        val secs = sections.forBook(bookId)
        val start = frontierStart(secs)
        val ranges = consumed.allKinds(bookId).map { OrdinalRange(it.startOrdinal, it.endOrdinal) }
        return ConsumedIntervals.frontier(ranges, start)
    }

    /** Recomputes Book.readFraction / listenFraction from the stored intervals. */
    suspend fun refreshFractions(bookId: BookId) {
        val scope = fractionScope(sections.forBook(bookId), books.get(bookId)?.sentenceCount ?: 0)
        suspend fun fraction(kind: PositionKind) =
            ConsumedIntervals.fraction(consumed.all(bookId, kind.name).map { OrdinalRange(it.startOrdinal, it.endOrdinal) }, scope)
        books.setFractions(bookId, fraction(PositionKind.READING), fraction(PositionKind.LISTENING))
    }

    private fun positionFraction(ordinal: Int, sentenceCount: Int): Float =
        if (sentenceCount <= 0) 0f else (ordinal.toFloat() / sentenceCount).coerceIn(0f, 1f)

    private fun frontierStart(secs: List<SectionEntity>): Int =
        secs.filter { it.included && it.kind == SectionKind.BODY.name }.minByOrNull { it.sectionIndex }?.startOrdinal
            ?: secs.minOfOrNull { it.startOrdinal } ?: 0

    /** Sentences that count towards progress: included BODY sections, or the whole book when there are none. */
    private fun fractionScope(secs: List<SectionEntity>, sentenceCount: Int): List<OrdinalRange> {
        val body = secs.filter { it.included && it.kind == SectionKind.BODY.name && it.startOrdinal <= it.endOrdinal }
            .map { OrdinalRange(it.startOrdinal, it.endOrdinal) }
        return body.ifEmpty { if (sentenceCount > 0) listOf(OrdinalRange(0, sentenceCount - 1)) else emptyList() }
    }
}
