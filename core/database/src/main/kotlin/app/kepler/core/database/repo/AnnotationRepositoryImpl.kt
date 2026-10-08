package app.kepler.core.database.repo

import app.kepler.core.database.db.KeplerDatabase
import app.kepler.core.database.db.BookmarkEntity
import app.kepler.core.database.toEntity
import app.kepler.core.database.toModel
import app.kepler.core.model.Annotation
import app.kepler.core.model.AnnotationRepository
import app.kepler.core.model.BookId
import app.kepler.core.model.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class AnnotationRepositoryImpl(db: KeplerDatabase) : AnnotationRepository {
    private val dao = db.annotations()

    override fun annotations(bookId: BookId): Flow<List<Annotation>> = dao.forBook(bookId).map { r -> r.map { it.toModel() } }
    override fun allAnnotations(): Flow<List<Annotation>> = dao.all().map { r -> r.map { it.toModel() } }
    override suspend fun add(a: Annotation): Long = dao.insert(a.toEntity().copy(id = 0))
    override suspend fun update(a: Annotation) = dao.update(a.toEntity())
    override suspend fun delete(id: Long) = dao.delete(id)

    override fun bookmarks(bookId: BookId): Flow<List<Bookmark>> = dao.bookmarks(bookId).map { r -> r.map { it.toModel() } }
    override suspend fun addBookmark(b: Bookmark): Long =
        dao.insertBookmark(BookmarkEntity(bookId = b.bookId, ordinal = b.ordinal, label = b.label, createdAt = b.createdAt))
    override suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)
}
