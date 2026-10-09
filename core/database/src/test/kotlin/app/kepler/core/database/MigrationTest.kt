package app.kepler.core.database

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.kepler.core.database.db.KeplerDatabase
import app.kepler.core.database.db.KeplerDatabase_Impl
import app.kepler.core.database.db.KeplerMigrations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Builds a real version-1 database file from the exported schema, then lets Room migrate and validate it. */
class MigrationTest {
    private lateinit var dir: File
    private val driver = BundledSQLiteDriver()

    @Before fun setUp() {
        dir = Files.createTempDirectory("mig").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun schema(version: Int): JsonObject {
        val file = File("schemas/app.kepler.core.database.db.KeplerDatabase/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    /** Creates the exact v1 tables (and the room_master_table identity row) and user_version = 1. */
    private fun createV1(path: String, seed: (SQLiteConnection) -> Unit) {
        val db = schema(1)
        driver.open(path).use { c ->
            for (e in db["entities"]!!.jsonArray) {
                val entity = e.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                c.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (i in (entity["indices"] as? JsonArray).orEmpty()) {
                    c.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (q in db["setupQueries"]!!.jsonArray) c.execSQL(q.jsonPrimitive.content)
            seed(c)
            c.execSQL("PRAGMA user_version = 1")
        }
    }

    private fun insertBook(c: SQLiteConnection, id: Long, sentences: Int) = c.execSQL(
        "INSERT INTO book (id, sha256, title, authors, language, contentType, epubPath, coverPath, addedAt, lastOpenedAt, " +
            "sentenceCount, readFraction, listenFraction) VALUES ($id, 'h$id', 'T$id', 'A', 'en', 'NONFICTION', '/e', NULL, 1, NULL, $sentences, 0.1, 0.2)",
    )

    private fun migrated(path: String): KeplerDatabase =
        Room.databaseBuilder<KeplerDatabase>(path) { KeplerDatabase_Impl() }
            .setDriver(driver)
            .addMigrations(*KeplerMigrations.ALL)
            .build()

    @Test fun v1DataSurvivesAndPositionFractionIsBackfilled() = runBlocking {
        val path = File(dir, "v1.db").absolutePath
        createV1(path) { c ->
            insertBook(c, 1, 200) // reading at 50, listening at 150 saved later: listening wins
            insertBook(c, 2, 100) // only reading, at 25
            insertBook(c, 3, 100) // no position at all
            insertBook(c, 4, 10) // saved ordinal past the end clamps to 1
            c.execSQL("INSERT INTO position (bookId, kind, ordinal, updatedAt) VALUES (1, 'READING', 50, 1000), (1, 'LISTENING', 150, 2000)")
            c.execSQL("INSERT INTO position (bookId, kind, ordinal, updatedAt) VALUES (2, 'READING', 25, 500)")
            c.execSQL("INSERT INTO position (bookId, kind, ordinal, updatedAt) VALUES (4, 'READING', 99, 500)")
            c.execSQL("INSERT INTO consumed_interval (bookId, kind, startOrdinal, endOrdinal) VALUES (1, 'READING', 0, 50)")
        }
        val db = migrated(path)
        try {
            val books = db.books().all().first().associateBy { it.id }
            assertEquals(0.75f, books.getValue(1).positionFraction, 1e-6f)
            assertEquals(0.25f, books.getValue(2).positionFraction, 1e-6f)
            assertEquals(0f, books.getValue(3).positionFraction, 0f)
            assertEquals(1f, books.getValue(4).positionFraction, 0f)
            // existing data is untouched
            assertEquals(0.1f, books.getValue(1).readFraction, 1e-6f)
            assertEquals(0.2f, books.getValue(1).listenFraction, 1e-6f)
            assertEquals("T1", books.getValue(1).title)
            assertEquals(50, db.positions().observe(1, "READING").first()!!.ordinal)
            assertNotNull(db.consumed().all(1, "READING").singleOrNull())
        } finally {
            db.close()
        }
    }

    @Test fun migratedDatabaseAcceptsNewSavesAndReopens() = runBlocking {
        val path = File(dir, "v1b.db").absolutePath
        createV1(path) { insertBook(it, 1, 40) }
        val first = migrated(path)
        try {
            first.positions().save(1, "READING", 10, 5)
            first.books().setPositionFraction(1, 0.25f)
            assertEquals(0.25f, first.books().get(1)!!.positionFraction, 1e-6f)
        } finally {
            first.close()
        }
        // second open at version 2 needs no migration and keeps the value
        val second = migrated(path)
        try {
            assertEquals(0.25f, second.books().get(1)!!.positionFraction, 1e-6f)
        } finally {
            second.close()
        }
    }

    @Test fun exportedVersion2SchemaHasTheNewColumn() {
        val book = schema(2)["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "book" }
        val columns = book["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertEquals(true, "positionFraction" in columns)
    }
}
