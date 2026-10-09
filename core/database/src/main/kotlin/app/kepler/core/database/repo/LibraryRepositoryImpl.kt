package app.kepler.core.database.repo

import androidx.room3.withWriteTransaction
import app.kepler.core.database.db.KeplerDatabase
import app.kepler.core.database.db.BookEntity
import app.kepler.core.database.db.SectionEntity
import app.kepler.core.database.db.SentenceEntity
import app.kepler.core.database.toModel
import app.kepler.core.model.AiCacheKeys
import app.kepler.core.model.Book
import app.kepler.core.model.BookId
import app.kepler.core.model.BookIngestor
import app.kepler.core.model.ImportResult
import app.kepler.core.model.IngestException
import app.kepler.core.model.IngestedBook
import app.kepler.core.model.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Opens the bytes behind an import source (content:// uri, file:// uri or plain path). */
internal fun interface SourceOpener {
    fun open(sourceUri: String): InputStream
}

internal class LibraryRepositoryImpl(
    private val db: KeplerDatabase,
    private val ingestor: BookIngestor,
    private val booksDir: File,
    private val opener: SourceOpener,
    /** Rendered narration lives in `<audioDir>/<bookId>/`; null when the layout has none. */
    private val audioDir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : LibraryRepository {
    private val bookDao = db.books()
    private val sectionDao = db.sections()
    private val sentenceDao = db.sentences()
    private val audioDao = db.audio()
    private val aiDao = db.ai()

    /** Serializes imports and the start-up cleanup of half-imported books. */
    private val importLock = Mutex()

    override val books: Flow<List<Book>> = bookDao.all().map { rows -> rows.map { it.toModel() } }

    override fun book(id: BookId): Flow<Book?> = bookDao.observe(id).map { it?.toModel() }

    override suspend fun importBook(sourceUri: String): ImportResult = importLock.withLock {
        var workDir: File? = null
        var bookId: Long? = null
        try {
            withContext(Dispatchers.IO) {
                val sha = sha256(sourceUri)
                bookDao.byHash(sha)?.let { return@withContext ImportResult.Duplicate(it.toModel()) }

                val dir = File(booksDir, UUID.randomUUID().toString()).also { it.mkdirs(); workDir = it }
                val ingested = ingestor.ingest(sourceUri, dir.absolutePath)
                if (ingested.sentences.isEmpty()) {
                    return@withContext ImportResult.Failed("No readable text was found in this file.")
                }
                val id = persist(ingested, sha).also { bookId = it }
                ImportResult.Imported(checkNotNull(bookDao.get(id)).toModel())
            }.also { result ->
                if (result !is ImportResult.Imported) workDir?.deleteRecursively()
            }
        } catch (e: CancellationException) {
            cleanUpFailedImport(bookId, workDir)
            throw e
        } catch (e: IngestException) {
            cleanUpFailedImport(bookId, workDir)
            ImportResult.Failed(e.message ?: "The file could not be read.")
        } catch (e: Throwable) {
            cleanUpFailedImport(bookId, workDir)
            ImportResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The file could not be imported.")
        }
    }

    private suspend fun persist(b: IngestedBook, sha: String): Long {
        val bookId = bookDao.insert(
            BookEntity(
                sha256 = sha, title = b.title, authors = b.authors, language = b.language,
                contentType = b.contentType.name, epubPath = b.epubPath, coverPath = b.coverPath,
                addedAt = clock(), lastOpenedAt = null, sentenceCount = 0, readFraction = 0f, listenFraction = 0f,
            ),
        )
        val sectionIds = HashMap<Int, Long>()
        db.withWriteTransaction {
            for (s in b.sections) {
                sectionIds[s.index] = sectionDao.insert(
                    SectionEntity(
                        bookId = bookId, parentId = null, sectionIndex = s.index, level = s.level, title = s.title,
                        href = s.href, kind = s.kind.name, startOrdinal = s.firstOrdinal, endOrdinal = s.lastOrdinal,
                        included = s.kind == app.kepler.core.model.SectionKind.BODY,
                    ),
                )
            }
            for (s in b.sections) {
                val parent = s.parentIndex?.let(sectionIds::get) ?: continue
                sectionDao.setParent(sectionIds.getValue(s.index), parent)
            }
        }
        for (chunk in b.sentences.chunked(MAX_ROWS_PER_TX)) {
            db.withWriteTransaction {
                sentenceDao.insertAll(
                    chunk.map { s ->
                        SentenceEntity(
                            bookId = bookId, ordinal = s.ordinal,
                            sectionId = sectionIds[s.sectionIndex]
                                ?: throw IngestException("Sentence ${s.ordinal} refers to a missing section."),
                            href = s.href, text = s.text, ctxBefore = s.before, ctxAfter = s.after,
                            paragraphIndex = s.paragraphIndex,
                        )
                    },
                )
            }
        }
        // Setting the count last is what makes the book visible in the library.
        bookDao.setSentenceCount(bookId, b.sentences.size)
        return bookId
    }

    private suspend fun cleanUpFailedImport(bookId: Long?, workDir: File?) {
        runCatching { withContext(kotlinx.coroutines.NonCancellable) { bookId?.let { bookDao.delete(it) } } }
        workDir?.deleteRecursively()
    }

    override suspend fun delete(id: BookId) {
        withContext(Dispatchers.IO) {
            val book = bookDao.get(id) ?: return@withContext
            val audioFiles = audioDao.filePaths(id)
            bookDao.delete(id)
            aiDao.deleteScoped(AiCacheKeys.prefix(id))
            audioFiles.forEach { runCatching { File(it).delete() } }
            // The whole per-book folder also takes out sidecar files and renders that never got registered.
            audioDir?.let { runCatching { File(it, id.toString()).deleteRecursively() } }
            ownedDir(book.epubPath)?.deleteRecursively()
        }
    }

    override suspend fun markOpened(id: BookId) = bookDao.setOpened(id, clock())

    /** Removes books whose import never completed (process died mid-import). Call once at start-up. */
    suspend fun removeIncompleteImports() = importLock.withLock {
        withContext(Dispatchers.IO) {
            for (row in bookDao.incomplete()) {
                bookDao.delete(row.id)
                ownedDir(row.epubPath)?.deleteRecursively()
            }
        }
    }

    /** The per-book work directory, only if it really lives below [booksDir]. */
    private fun ownedDir(epubPath: String): File? {
        val root = booksDir.canonicalFile
        var dir = File(epubPath).canonicalFile.parentFile
        while (dir != null && dir.parentFile?.canonicalFile != root) dir = dir.parentFile
        return dir?.takeIf { it != root }
    }

    private fun sha256(sourceUri: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        opener.open(sourceUri).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_ROWS_PER_TX = 2000
    }
}
