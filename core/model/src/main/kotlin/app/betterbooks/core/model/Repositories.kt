package app.betterbooks.core.model

import kotlinx.coroutines.flow.Flow

sealed interface ImportResult {
    data class Imported(val book: Book) : ImportResult
    data class Duplicate(val book: Book) : ImportResult
    /** A neutral, technical failure message. Never a lecture about copyright or DRM. */
    data class Failed(val message: String) : ImportResult
}

/** Implemented by :core:database (uses [BookIngestor] for parsing). */
interface LibraryRepository {
    val books: Flow<List<Book>>
    fun book(id: BookId): Flow<Book?>
    suspend fun importBook(sourceUri: String): ImportResult
    suspend fun delete(id: BookId)
    suspend fun markOpened(id: BookId)
}

/** The Text Spine: sections and sentences of a book. */
interface SpineRepository {
    suspend fun sections(bookId: BookId): List<Section>
    suspend fun sentence(bookId: BookId, ordinal: Int): Sentence?
    suspend fun sentences(bookId: BookId, fromOrdinal: Int, toOrdinal: Int): List<Sentence>
    suspend fun sentenceCount(bookId: BookId): Int
    /** Full-text search (FTS5). `maxOrdinal` is the spoiler fence: never return sentences beyond it. */
    suspend fun search(bookId: BookId, query: String, maxOrdinal: Int? = null, limit: Int = 30): List<Sentence>
}

interface PositionRepository {
    fun position(bookId: BookId, kind: PositionKind): Flow<Position?>
    suspend fun savePosition(bookId: BookId, kind: PositionKind, ordinal: Int)
    /** Records sentences really consumed (read past / played), merged into intervals. */
    suspend fun markConsumed(bookId: BookId, fromOrdinal: Int, toOrdinal: Int, via: PositionKind)
    /** The spoiler fence: end of the contiguous consumed run from the start of the book (-1 if none). */
    suspend fun frontier(bookId: BookId): Int
}

interface AnnotationRepository {
    fun annotations(bookId: BookId): Flow<List<Annotation>>
    fun allAnnotations(): Flow<List<Annotation>>
    suspend fun add(a: Annotation): Long
    suspend fun update(a: Annotation)
    suspend fun delete(id: Long)
    fun bookmarks(bookId: BookId): Flow<List<Bookmark>>
    suspend fun addBookmark(b: Bookmark): Long
    suspend fun deleteBookmark(id: Long)
}

/** Audio cache metadata. Implemented by :core:database; written by :engine:tts, read by :engine:playback. */
interface AudioRepository {
    fun segments(bookId: BookId): Flow<List<AudioSegment>>
    suspend fun addSegment(segment: AudioSegment, rows: List<SyncRow>): Long
    suspend fun segmentFor(bookId: BookId, ordinal: Int): AudioSegment?
    suspend fun syncRows(bookId: BookId, fromOrdinal: Int, toOrdinal: Int): List<SyncRow>
    /** 0..1 share of the book's sentences that have audio for the active voice. */
    fun coverage(bookId: BookId): Flow<Float>
    suspend fun deleteSegments(bookId: BookId)
}

/** User preferences and the user's own API keys (encrypted at rest). Implemented by :core:database. */
interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun fishKey(): String?
    suspend fun setFishKey(key: String?)
    suspend fun openRouterKey(): String?
    suspend fun setOpenRouterKey(key: String?)
}

data class AppSettings(
    val onboarded: Boolean = false,
    val seed: String = "Cobalt",
    val dark: Boolean = true,
    val readerTheme: String = "night",
    val fontScale: Float = 1f,
    val fontFamily: String = "Literata",
    val lineHeight: Float = 1.6f,
    val voice: VoiceRef? = null,
    val speed: Float = 1.0f,
    /** Monthly spend cap in USD cents; 0 = unlimited. */
    val monthlyCapCents: Int = 0,
    val openRouterModel: String? = null,
    val haptics: Boolean = true,
)

/** Persistent cache + usage counters for the AI layer. Implemented by :core:database. */
interface AiStore {
    suspend fun get(kind: String, key: String): String?
    suspend fun put(kind: String, key: String, json: String)
    /** Requests made on `day` (yyyy-MM-dd, device time) to the given provider. */
    suspend fun requestsOn(day: String, provider: String): Int
    suspend fun addRequests(day: String, provider: String, count: Int)
}

/** Reader chrome modes. */
enum class ReaderMode { READ, READ_LISTEN }
