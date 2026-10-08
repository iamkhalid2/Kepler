package app.betterbooks.core.database.repo

import app.betterbooks.core.database.db.BetterBooksDatabase
import app.betterbooks.core.database.toEntity
import app.betterbooks.core.database.toModel
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.Card
import app.betterbooks.core.model.CardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

internal class CardRepositoryImpl(
    db: BetterBooksDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : CardRepository {
    private val dao = db.cards()

    override fun dueCards(nowMs: Long, limit: Int): Flow<List<Card>> = dao.due(nowMs, limit).map { r -> r.map { it.toModel() } }
    override fun cards(bookId: BookId?): Flow<List<Card>> =
        (if (bookId == null) dao.all() else dao.forBook(bookId)).map { r -> r.map { it.toModel() } }
    override fun dueCount(nowMs: Long): Flow<Int> = dao.dueCount(nowMs)
    override suspend fun add(card: Card): Long = dao.insert(card.toEntity().copy(id = 0))
    override suspend fun update(card: Card) = dao.update(card.toEntity())
    override suspend fun delete(id: Long) = dao.delete(id)

    /** Cards reviewed since local midnight of the day containing [nowMs]. */
    override suspend fun reviewedToday(nowMs: Long): Int {
        val startOfDay = Instant.ofEpochMilli(nowMs).atZone(zone()).toLocalDate().atStartOfDay(zone()).toInstant().toEpochMilli()
        return dao.reviewedSince(startOfDay)
    }
}
