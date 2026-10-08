package app.betterbooks.core.database.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.SkipQueryVerification
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface BookDao {
    @Insert suspend fun insert(book: BookEntity): Long

    /** Books still being imported have sentenceCount = 0 and stay hidden. */
    @Query("SELECT * FROM book WHERE sentenceCount > 0 ORDER BY COALESCE(lastOpenedAt, addedAt) DESC, id DESC")
    fun all(): Flow<List<BookEntity>>

    @Query("SELECT * FROM book WHERE id = :id AND sentenceCount > 0")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM book WHERE id = :id") suspend fun get(id: Long): BookEntity?
    @Query("SELECT * FROM book WHERE sha256 = :sha AND sentenceCount > 0") suspend fun byHash(sha: String): BookEntity?
    @Query("UPDATE book SET sentenceCount = :count WHERE id = :id") suspend fun setSentenceCount(id: Long, count: Int)
    @Query("UPDATE book SET lastOpenedAt = :at WHERE id = :id") suspend fun setOpened(id: Long, at: Long)
    @Query("UPDATE book SET readFraction = :read, listenFraction = :listen WHERE id = :id")
    suspend fun setFractions(id: Long, read: Float, listen: Float)
    @Query("DELETE FROM book WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT id, epubPath FROM book WHERE sentenceCount = 0") suspend fun incomplete(): List<IdPath>
    @Query("SELECT sentenceCount FROM book WHERE id = :id") fun sentenceCountFlow(id: Long): Flow<Int?>
}

internal data class IdPath(val id: Long, val epubPath: String)

@Dao
internal interface SectionDao {
    @Insert suspend fun insert(section: SectionEntity): Long
    @Query("UPDATE section SET parentId = :parentId WHERE id = :id") suspend fun setParent(id: Long, parentId: Long)
    @Query("SELECT * FROM section WHERE bookId = :bookId ORDER BY sectionIndex") suspend fun forBook(bookId: Long): List<SectionEntity>
    @Query("SELECT bookId FROM section WHERE id = :id") suspend fun bookIdOf(id: Long): Long?
    @Query("UPDATE section SET included = :included WHERE id = :id") suspend fun setIncluded(id: Long, included: Boolean)
}

@Dao
internal interface SentenceDao {
    @Insert suspend fun insertAll(rows: List<SentenceEntity>)

    @Query("SELECT * FROM sentence WHERE bookId = :bookId AND ordinal = :ordinal")
    suspend fun get(bookId: Long, ordinal: Int): SentenceEntity?

    @Query("SELECT * FROM sentence WHERE bookId = :bookId AND ordinal BETWEEN :from AND :to ORDER BY ordinal")
    suspend fun range(bookId: Long, from: Int, to: Int): List<SentenceEntity>

    @Query("SELECT COUNT(*) FROM sentence WHERE bookId = :bookId") suspend fun count(bookId: Long): Int

    /** `match` must already be a safe FTS5 expression (see [Fts.matchExpression]). */
    @SkipQueryVerification // sentence_fts is created in raw SQL, Room cannot see it at compile time
    @Query(
        "SELECT s.* FROM sentence_fts JOIN sentence s ON s.id = sentence_fts.rowid " +
            "WHERE sentence_fts MATCH :match AND s.bookId = :bookId AND s.ordinal <= :maxOrdinal " +
            "ORDER BY bm25(sentence_fts), s.ordinal LIMIT :limit",
    )
    suspend fun search(bookId: Long, match: String, maxOrdinal: Int, limit: Int): List<SentenceEntity>
}

@Dao
internal interface ConsumedDao {
    @Query(
        "SELECT * FROM consumed_interval WHERE bookId = :bookId AND kind = :kind " +
            "AND startOrdinal <= :to + 1 AND endOrdinal >= :from - 1 ORDER BY startOrdinal",
    )
    suspend fun touching(bookId: Long, kind: String, from: Int, to: Int): List<ConsumedIntervalEntity>

    @Query("SELECT * FROM consumed_interval WHERE bookId = :bookId AND kind = :kind ORDER BY startOrdinal")
    suspend fun all(bookId: Long, kind: String): List<ConsumedIntervalEntity>

    @Query("SELECT * FROM consumed_interval WHERE bookId = :bookId ORDER BY startOrdinal")
    suspend fun allKinds(bookId: Long): List<ConsumedIntervalEntity>

    @Query("DELETE FROM consumed_interval WHERE id IN (:ids)") suspend fun deleteIds(ids: List<Long>)
    @Insert suspend fun insert(row: ConsumedIntervalEntity): Long
}

@Dao
internal interface PositionDao {
    @Query("SELECT * FROM position WHERE bookId = :bookId AND kind = :kind")
    fun observe(bookId: Long, kind: String): Flow<PositionEntity?>

    @Query("INSERT OR REPLACE INTO position (bookId, kind, ordinal, updatedAt) VALUES (:bookId, :kind, :ordinal, :at)")
    suspend fun save(bookId: Long, kind: String, ordinal: Int, at: Long)
}

@Dao
internal interface AnnotationDao {
    @Query("SELECT * FROM annotation WHERE bookId = :bookId ORDER BY startOrdinal, id")
    fun forBook(bookId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotation ORDER BY createdAt DESC, id DESC") fun all(): Flow<List<AnnotationEntity>>
    @Insert suspend fun insert(a: AnnotationEntity): Long
    @Update suspend fun update(a: AnnotationEntity)
    @Query("DELETE FROM annotation WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY ordinal, id")
    fun bookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert suspend fun insertBookmark(b: BookmarkEntity): Long
    @Query("DELETE FROM bookmark WHERE id = :id") suspend fun deleteBookmark(id: Long)
}

@Dao
internal interface AudioDao {
    @Insert suspend fun insertSegment(s: AudioSegmentEntity): Long
    @Insert suspend fun insertRows(rows: List<SyncRowEntity>)

    @Query("SELECT * FROM audio_segment WHERE bookId = :bookId ORDER BY startOrdinal, id")
    fun segments(bookId: Long): Flow<List<AudioSegmentEntity>>

    @Query(
        "SELECT * FROM audio_segment WHERE bookId = :bookId AND :ordinal BETWEEN startOrdinal AND endOrdinal " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun segmentFor(bookId: Long, ordinal: Int): AudioSegmentEntity?

    /** One row per sentence: the one belonging to the same segment that [segmentFor] would return. */
    @Query(
        "SELECT r.* FROM sync_row r WHERE r.bookId = :bookId AND r.ordinal BETWEEN :from AND :to " +
            "AND r.segmentId = (SELECT s.id FROM audio_segment s WHERE s.bookId = r.bookId " +
            "AND r.ordinal BETWEEN s.startOrdinal AND s.endOrdinal ORDER BY s.id DESC LIMIT 1) ORDER BY r.ordinal",
    )
    suspend fun syncRows(bookId: Long, from: Int, to: Int): List<SyncRowEntity>

    @Query("SELECT filePath FROM audio_segment WHERE bookId = :bookId") suspend fun filePaths(bookId: Long): List<String>
    @Query("DELETE FROM audio_segment WHERE bookId = :bookId") suspend fun deleteSegments(bookId: Long)
}

@Dao
internal interface CardDao {
    @Query("SELECT * FROM card WHERE dueAt <= :now ORDER BY dueAt, id LIMIT :limit")
    fun due(now: Long, limit: Int): Flow<List<CardEntity>>

    @Query("SELECT * FROM card ORDER BY createdAt DESC, id DESC") fun all(): Flow<List<CardEntity>>
    @Query("SELECT * FROM card WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC") fun forBook(bookId: Long): Flow<List<CardEntity>>
    @Query("SELECT COUNT(*) FROM card WHERE dueAt <= :now") fun dueCount(now: Long): Flow<Int>
    @Insert suspend fun insert(c: CardEntity): Long
    @Update suspend fun update(c: CardEntity)
    @Query("DELETE FROM card WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM card WHERE lastReviewAt >= :since") suspend fun reviewedSince(since: Long): Int
}

@Dao
internal interface AiDao {
    @Query("SELECT json FROM ai_cache WHERE kind = :kind AND `key` = :key") suspend fun get(kind: String, key: String): String?
    @Query("INSERT OR REPLACE INTO ai_cache (kind, `key`, json) VALUES (:kind, :key, :json)")
    suspend fun put(kind: String, key: String, json: String)

    @Query("SELECT COALESCE(SUM(count), 0) FROM ai_usage WHERE day = :day AND provider = :provider")
    suspend fun requestsOn(day: String, provider: String): Int

    @Query(
        "INSERT INTO ai_usage (day, provider, count) VALUES (:day, :provider, :count) " +
            "ON CONFLICT(day, provider) DO UPDATE SET count = count + :count",
    )
    suspend fun addRequests(day: String, provider: String, count: Int)
}
