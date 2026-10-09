package app.kepler.core.model

/**
 * Convention for `ai_cache` keys that belong to a book: `b<bookId>:<rest>`. It lets the data layer drop a
 * book's cached AI results when the book is deleted (the table has no book column). Keys of
 * book-independent entries (the model list) are left unscoped.
 */
object AiCacheKeys {
    fun scope(bookId: BookId, key: String): String = "${prefix(bookId)}$key"

    /** The LIKE prefix (without wildcard) shared by every cache key of [bookId]. */
    fun prefix(bookId: BookId): String = "b$bookId:"
}
