package app.betterbooks.core.database.db

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Room annotations cannot express FTS5, so the index is plain SQL. It is an external-content table over
 * `sentence`, kept in sync by triggers, so every sentence insert/delete (including FK cascades when a book is
 * deleted) updates the index inside the same transaction.
 */
internal object Fts {
    private val statements = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS sentence_fts USING fts5(" +
            "text, content='sentence', content_rowid='id', tokenize='unicode61 remove_diacritics 2')",
        "CREATE TRIGGER IF NOT EXISTS sentence_fts_ai AFTER INSERT ON sentence BEGIN " +
            "INSERT INTO sentence_fts(rowid, text) VALUES (new.id, new.text); END",
        "CREATE TRIGGER IF NOT EXISTS sentence_fts_ad AFTER DELETE ON sentence BEGIN " +
            "INSERT INTO sentence_fts(sentence_fts, rowid, text) VALUES ('delete', old.id, old.text); END",
        "CREATE TRIGGER IF NOT EXISTS sentence_fts_au AFTER UPDATE OF text ON sentence BEGIN " +
            "INSERT INTO sentence_fts(sentence_fts, rowid, text) VALUES ('delete', old.id, old.text); " +
            "INSERT INTO sentence_fts(rowid, text) VALUES (new.id, new.text); END",
    )

    val callback = object : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            statements.forEach { connection.execSQL(it) }
        }
    }

    /**
     * Turns free user text into a safe FTS5 expression: every word becomes a quoted term (so operators and
     * punctuation typed by the user cannot break the query) and the last word is a prefix match.
     * Returns null when there is nothing searchable.
     */
    fun matchExpression(query: String): String? {
        val words = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.mapIndexed { i, w -> if (i == words.lastIndex) "\"$w\"*" else "\"$w\"" }.joinToString(" ")
    }
}
