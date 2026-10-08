package app.kepler.core.database.repo

import androidx.room3.withWriteTransaction
import app.kepler.core.database.ConsumedIntervals
import app.kepler.core.database.OrdinalRange
import app.kepler.core.database.db.AudioSegmentEntity
import app.kepler.core.database.db.KeplerDatabase
import app.kepler.core.database.db.SyncRowEntity
import app.kepler.core.database.toModel
import app.kepler.core.model.AudioRepository
import app.kepler.core.model.AudioSegment
import app.kepler.core.model.BookId
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.SyncRow
import app.kepler.core.model.VoiceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

internal class AudioRepositoryImpl(
    private val db: KeplerDatabase,
    private val settings: SettingsStore,
) : AudioRepository {
    private val dao = db.audio()
    private val books = db.books()

    override fun segments(bookId: BookId): Flow<List<AudioSegment>> = dao.segments(bookId).map { r -> r.map { it.toModel() } }

    override suspend fun addSegment(segment: AudioSegment, rows: List<SyncRow>): Long = db.withWriteTransaction {
        val id = dao.insertSegment(
            AudioSegmentEntity(
                bookId = segment.bookId, startOrdinal = segment.startOrdinal, endOrdinal = segment.endOrdinal,
                filePath = segment.filePath, durationMs = segment.durationMs, voiceProvider = segment.voice.provider,
                voiceId = segment.voice.id, voiceName = segment.voice.name, take = segment.take,
            ),
        )
        rows.chunked(2000).forEach { chunk ->
            dao.insertRows(chunk.map { SyncRowEntity(id, segment.bookId, it.ordinal, it.startMs, it.endMs) })
        }
        id
    }

    override suspend fun segmentFor(bookId: BookId, ordinal: Int): AudioSegment? = dao.segmentFor(bookId, ordinal)?.toModel()

    override suspend fun syncRows(bookId: BookId, fromOrdinal: Int, toOrdinal: Int): List<SyncRow> =
        if (toOrdinal < fromOrdinal) emptyList() else dao.syncRows(bookId, fromOrdinal, toOrdinal).map { it.toModel() }

    /** Share of the book's sentences covered by segments of the active voice (any voice while none is chosen). */
    override fun coverage(bookId: BookId): Flow<Float> =
        combine(dao.segments(bookId), settings.settings.map { it.voice }.distinctUntilChanged(), books.sentenceCountFlow(bookId)) { segs, voice, total ->
            if (total == null || total <= 0) return@combine 0f
            val own = if (voice == null) segs else segs.filter { it.sameVoice(voice) }
            val ranges = own.map { OrdinalRange(it.startOrdinal, it.endOrdinal) }.filter { it.start <= it.end }
            ConsumedIntervals.fraction(ranges, listOf(OrdinalRange(0, total - 1)))
        }.distinctUntilChanged()

    override suspend fun deleteSegments(bookId: BookId) {
        val files = dao.filePaths(bookId)
        dao.deleteSegments(bookId)
        files.forEach { runCatching { File(it).delete() } }
    }

    private fun AudioSegmentEntity.sameVoice(v: VoiceRef) = voiceProvider == v.provider && voiceId == v.id
}
