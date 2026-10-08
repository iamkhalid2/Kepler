package app.kepler.core.model

import kotlinx.serialization.Serializable

typealias BookId = Long

enum class ContentType { FICTION, NONFICTION, MIXED }
enum class SectionKind { FRONT, BODY, BACK, NOTES, INDEX }
enum class PositionKind { READING, LISTENING }
enum class AnnotationVia { READ, LISTEN, HEADSET, AI }

/** A book in the library. `ordinal` everywhere below is the sentence ordinal (the Text Spine). */
data class Book(
    val id: BookId,
    val title: String,
    val authors: String,
    val language: String?,
    val contentType: ContentType,
    val epubPath: String,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val sentenceCount: Int,
    /** 0..1 fraction of sentences consumed by reading / by listening. */
    val readFraction: Float,
    val listenFraction: Float,
)

data class Section(
    val id: Long,
    val bookId: BookId,
    val parentId: Long?,
    val index: Int,
    val level: Int,
    val title: String,
    val href: String,
    val kind: SectionKind,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val included: Boolean,
)

/** One sentence of the Text Spine. `before`/`after` are short context snippets used to build Readium text locators. */
data class Sentence(
    val bookId: BookId,
    val ordinal: Int,
    val sectionId: Long,
    val href: String,
    val text: String,
    val before: String,
    val after: String,
    val paragraphIndex: Int,
)

data class Position(val bookId: BookId, val kind: PositionKind, val ordinal: Int, val updatedAt: Long)

data class Annotation(
    val id: Long,
    val bookId: BookId,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val quote: String,
    /** 0..5, mapped to colours by the UI. */
    val color: Int,
    val note: String?,
    val via: AnnotationVia,
    val createdAt: Long,
)

data class Bookmark(val id: Long, val bookId: BookId, val ordinal: Int, val label: String?, val createdAt: Long)

// ---------------- ingest (implemented by :engine:epub, persisted by :core:database) ----------------

data class SectionDraft(
    val index: Int, val parentIndex: Int?, val level: Int, val title: String, val href: String,
    val kind: SectionKind, val firstOrdinal: Int, val lastOrdinal: Int,
)

data class SentenceDraft(
    val ordinal: Int, val sectionIndex: Int, val href: String, val text: String,
    val before: String, val after: String, val paragraphIndex: Int,
)

data class IngestedBook(
    val title: String, val authors: String, val language: String?, val contentType: ContentType,
    /** Canonical EPUB copied into app storage (workDir). */
    val epubPath: String,
    val coverPath: String?,
    val sections: List<SectionDraft>,
    val sentences: List<SentenceDraft>,
)

class IngestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Implemented by :engine:epub. `sourceUri` is a content:// or file path; files are written below `workDir`. */
interface BookIngestor {
    @Throws(IngestException::class)
    suspend fun ingest(sourceUri: String, workDir: String): IngestedBook
}

// ---------------- audio (produced by :engine:tts, consumed by :engine:playback) ----------------

@Serializable
data class VoiceRef(val provider: String, val id: String, val name: String)

/** A cached audio file covering sentences [startOrdinal, endOrdinal] inclusive. */
data class AudioSegment(
    val id: Long, val bookId: BookId, val startOrdinal: Int, val endOrdinal: Int,
    val filePath: String, val durationMs: Long, val voice: VoiceRef, val take: Int,
)

/** Timing of one sentence inside its segment file. */
data class SyncRow(val bookId: BookId, val ordinal: Int, val segmentId: Long, val startMs: Long, val endMs: Long)
