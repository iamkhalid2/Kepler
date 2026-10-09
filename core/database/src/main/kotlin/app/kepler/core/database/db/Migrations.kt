package app.kepler.core.database.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schema migrations. The database holds real user data (library, positions, cards), so every step must
 * be additive; there is no destructive fallback. Each step has a matching exported schema in `schemas/`.
 */
internal object KeplerMigrations {
    /**
     * 1 -> 2: `book.positionFraction`, "where you are" (decision #111). Backfilled from the stored
     * positions: the most recently updated of reading and listening, as ordinal / sentenceCount.
     */
    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `book` ADD COLUMN `positionFraction` REAL NOT NULL DEFAULT 0")
            connection.execSQL(
                "UPDATE `book` SET `positionFraction` = COALESCE((" +
                    "SELECT MIN(1.0, MAX(0.0, p.`ordinal` * 1.0 / `book`.`sentenceCount`)) FROM `position` p " +
                    "WHERE p.`bookId` = `book`.`id` ORDER BY p.`updatedAt` DESC LIMIT 1), 0) " +
                    "WHERE `sentenceCount` > 0",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(V1_TO_V2)
}
