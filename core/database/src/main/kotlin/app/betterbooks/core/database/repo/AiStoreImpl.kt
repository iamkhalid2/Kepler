package app.betterbooks.core.database.repo

import app.betterbooks.core.database.db.BetterBooksDatabase
import app.betterbooks.core.model.AiStore

internal class AiStoreImpl(db: BetterBooksDatabase) : AiStore {
    private val dao = db.ai()

    override suspend fun get(kind: String, key: String): String? = dao.get(kind, key)
    override suspend fun put(kind: String, key: String, json: String) = dao.put(kind, key, json)
    override suspend fun requestsOn(day: String, provider: String): Int = dao.requestsOn(day, provider)
    override suspend fun addRequests(day: String, provider: String, count: Int) {
        if (count != 0) dao.addRequests(day, provider, count)
    }
}
