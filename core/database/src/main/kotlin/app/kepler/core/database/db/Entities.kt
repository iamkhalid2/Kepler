package app.kepler.core.database.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "book", indices = [Index(value = ["sha256"], unique = true)])
internal data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sha256: String,
    val title: String,
    val authors: String,
    val language: String?,
    val contentType: String,
    val epubPath: String,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val sentenceCount: Int,
    val readFraction: Float,
    val listenFraction: Float,
    /** Where the reader/listener is now (most recent saved position / sentenceCount). Added in schema 2. */
    @ColumnInfo(defaultValue = "0") val positionFraction: Float = 0f,
)

@Entity(
    tableName = "section",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "sectionIndex", unique = true)],
)
internal data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val parentId: Long?,
    val sectionIndex: Int,
    val level: Int,
    val title: String,
    val href: String,
    val kind: String,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val included: Boolean,
)

/** The surrogate `id` is the rowid the external-content FTS5 table points at (see [Fts]). */
@Entity(
    tableName = "sentence",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "ordinal", unique = true)],
)
internal data class SentenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val ordinal: Int,
    val sectionId: Long,
    val href: String,
    val text: String,
    val ctxBefore: String,
    val ctxAfter: String,
    val paragraphIndex: Int,
)

@Entity(
    tableName = "position",
    primaryKeys = ["bookId", "kind"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
internal data class PositionEntity(val bookId: Long, val kind: String, val ordinal: Int, val updatedAt: Long)

@Entity(
    tableName = "consumed_interval",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "kind", "startOrdinal")],
)
internal data class ConsumedIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val kind: String,
    val startOrdinal: Int,
    val endOrdinal: Int,
)

@Entity(
    tableName = "annotation",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "startOrdinal")],
)
internal data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val quote: String,
    val color: Int,
    val note: String?,
    val via: String,
    val createdAt: Long,
)

@Entity(
    tableName = "bookmark",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "ordinal")],
)
internal data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val ordinal: Int,
    val label: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "audio_segment",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "startOrdinal", "endOrdinal")],
)
internal data class AudioSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val filePath: String,
    val durationMs: Long,
    val voiceProvider: String,
    val voiceId: String,
    val voiceName: String,
    val take: Int,
)

@Entity(
    tableName = "sync_row",
    primaryKeys = ["segmentId", "ordinal"],
    foreignKeys = [ForeignKey(AudioSegmentEntity::class, ["id"], ["segmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "ordinal")],
)
internal data class SyncRowEntity(
    val segmentId: Long,
    val bookId: Long,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long,
)

@Entity(
    tableName = "card",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index("dueAt"), Index("lastReviewAt")],
)
internal data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val type: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val front: String,
    val back: String,
    val userAnswer: String?,
    val dueAt: Long,
    val stability: Double,
    val difficulty: Double,
    val reps: Int,
    val lapses: Int,
    val state: Int,
    val lastReviewAt: Long?,
    val createdAt: Long,
)

@Entity(tableName = "ai_cache", primaryKeys = ["kind", "key"])
internal data class AiCacheEntity(
    val kind: String,
    @ColumnInfo(name = "key") val key: String,
    val json: String,
)

@Entity(tableName = "ai_usage", primaryKeys = ["day", "provider"])
internal data class AiUsageEntity(val day: String, val provider: String, val count: Int)
