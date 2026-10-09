package app.kepler.core.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.kepler.core.database.db.KeplerDatabase
import app.kepler.core.database.db.KeplerDatabase_Impl
import app.kepler.core.database.db.Fts
import app.kepler.core.database.repo.SourceOpener
import app.kepler.core.database.settings.DataStoreSettingsStore
import app.kepler.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import androidx.datastore.preferences.core.PreferenceDataStoreFactory

/** Layout: FRONT 0-1, ch1 2-5, ch2 6-9 (child of ch1), BACK 10-11. */
private class FakeIngestor(var failWith: IngestException? = null, var empty: Boolean = false) : BookIngestor {
    override suspend fun ingest(sourceUri: String, workDir: String): IngestedBook {
        failWith?.let { throw it }
        val epub = File(workDir, "book.epub").also { it.writeText("epub") }
        val sections = listOf(
            SectionDraft(0, null, 0, "Cover matter", "f.xhtml", SectionKind.FRONT, 0, 1),
            SectionDraft(1, null, 0, "One", "c1.xhtml", SectionKind.BODY, 2, 5),
            SectionDraft(2, 1, 1, "Two", "c2.xhtml", SectionKind.BODY, 6, 9),
            SectionDraft(3, null, 0, "Notes", "b.xhtml", SectionKind.BACK, 10, 11),
        )
        val words = listOf("alpha", "river", "river bend", "mountain", "storm", "calm", "river delta", "ocean", "ocean river", "end", "note river", "index")
        val sentences = if (empty) emptyList() else (0..11).map { o ->
            val sec = sections.last { it.firstOrdinal <= o }
            SentenceDraft(o, sec.index, sec.href, "The ${words[o]} sentence $o.", "b", "a", o / 3)
        }
        return IngestedBook("Title $sourceUri".take(40), "Author", "en", ContentType.NONFICTION, epub.absolutePath, null, sections, sentences)
    }
}

class DataLayerTest {
    private lateinit var root: File
    private lateinit var layer: DataLayer
    private val ingestor = FakeIngestor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        root = Files.createTempDirectory("dl").toFile()
        val db = Room.inMemoryDatabaseBuilder<KeplerDatabase> { KeplerDatabase_Impl() }
            .setDriver(BundledSQLiteDriver())
            .addCallback(Fts.callback)
            .build()
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(root, "s.preferences_pb") }
        layer = DataLayer(db, File(root, "books"), SourceOpener { FileInputStream(it) }, ingestor, DataStoreSettingsStore(prefs, FakeCipher()))
    }

    @After fun tearDown() {
        layer.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        root.deleteRecursively()
    }

    private fun source(content: String) = File(root, "src-${content.hashCode()}.epub").also { it.writeText(content) }.absolutePath
    private suspend fun import(content: String = "one"): Book = (layer.library.importBook(source(content)) as ImportResult.Imported).book

    @Test fun importPersistsBookSectionsAndSentences() = runBlocking {
        val b = import()
        assertEquals(12, b.sentenceCount)
        assertEquals(listOf(b), layer.library.books.first())
        val secs = layer.spine.sections(b.id)
        assertEquals(4, secs.size)
        assertNull(secs[0].parentId)
        assertEquals(secs[1].id, secs[2].parentId)
        assertEquals(listOf(false, true, true, false), secs.map { it.included })
        assertEquals("The alpha sentence 0.", layer.spine.sentence(b.id, 0)?.text)
        assertEquals(secs[1].id, layer.spine.sentence(b.id, 3)?.sectionId)
        assertEquals(listOf(4, 5, 6), layer.spine.sentences(b.id, 4, 6).map { it.ordinal })
        assertEquals(12, layer.spine.sentenceCount(b.id))
        assertTrue(File(b.epubPath).exists())
    }

    @Test fun duplicateIsDetectedByContentHash() = runBlocking {
        val first = import("same")
        val again = File(root, "copy.epub").also { it.writeText("same") }
        val r = layer.library.importBook(again.absolutePath)
        assertTrue(r is ImportResult.Duplicate)
        assertEquals(first.id, (r as ImportResult.Duplicate).book.id)
        assertEquals(1, layer.library.books.first().size)
        // the duplicate attempt left no stray work directories
        assertEquals(1, File(root, "books").listFiles()!!.size)
    }

    @Test fun ingestFailureBecomesNeutralFailedResult() = runBlocking {
        ingestor.failWith = IngestException("The file is encrypted.")
        val r = layer.library.importBook(source("x"))
        assertEquals(ImportResult.Failed("The file is encrypted."), r)
        assertTrue(layer.library.books.first().isEmpty())
        assertTrue(File(root, "books").listFiles().orEmpty().isEmpty())
    }

    @Test fun unreadableSourceAndEmptyBookNeverThrow() = runBlocking {
        assertTrue(layer.library.importBook(File(root, "missing.epub").absolutePath) is ImportResult.Failed)
        ingestor.empty = true
        assertTrue(layer.library.importBook(source("empty")) is ImportResult.Failed)
        assertTrue(layer.library.books.first().isEmpty())
    }

    @Test fun deleteCascadesAndRemovesFilesAndFtsRows() = runBlocking {
        val b = import()
        val seg = File(root, "a.mp3").also { it.writeText("x") }
        layer.audio.addSegment(AudioSegment(0, b.id, 0, 3, seg.absolutePath, 1000, VoiceRef("fish", "v", "V"), 1), emptyList())
        layer.library.delete(b.id)
        assertTrue(layer.library.books.first().isEmpty())
        assertEquals(0, layer.spine.sentenceCount(b.id))
        assertTrue(layer.spine.sections(b.id).isEmpty())
        assertTrue(layer.spine.search(b.id, "river").isEmpty())
        assertFalse(seg.exists())
        assertFalse(File(b.epubPath).exists())
        // the same file can be imported again after deletion
        assertTrue(layer.library.importBook(source("one")) is ImportResult.Imported)
    }

    @Test fun deleteLeavesNothingBehind() = runBlocking {
        val b = import("gone")
        val keep = import("keep")
        val audioDir = File(root, "audio")
        // A registered segment, plus an orphan render and a sidecar the repository never heard of.
        val registered = File(audioDir, "${b.id}/k1.m4a").also { it.parentFile.mkdirs(); it.writeText("x") }
        File(audioDir, "${b.id}/k1.json").writeText("{}")
        File(audioDir, "${b.id}/orphan.m4a").writeText("x")
        val other = File(audioDir, "${keep.id}/k2.m4a").also { it.parentFile.mkdirs(); it.writeText("x") }
        layer.audio.addSegment(AudioSegment(0, b.id, 0, 3, registered.absolutePath, 1000, VoiceRef("fish", "v", "V"), 1), emptyList())
        layer.aiStore.put("ask", AiCacheKeys.scope(b.id, "abc"), "1")
        layer.aiStore.put("pack", AiCacheKeys.scope(b.id, "def"), "2")
        layer.aiStore.put("ask", AiCacheKeys.scope(keep.id, "abc"), "3")
        layer.aiStore.put("models", "openrouter", "4")

        layer.library.delete(b.id)

        assertFalse(File(audioDir, b.id.toString()).exists())
        assertFalse(File(b.epubPath).parentFile.exists())
        assertNull(layer.aiStore.get("ask", AiCacheKeys.scope(b.id, "abc")))
        assertNull(layer.aiStore.get("pack", AiCacheKeys.scope(b.id, "def")))
        assertEquals("3", layer.aiStore.get("ask", AiCacheKeys.scope(keep.id, "abc")))
        assertEquals("4", layer.aiStore.get("models", "openrouter"))
        assertTrue(other.exists())
    }

    @Test fun ftsSearchHonoursSpoilerFenceAndBook() = runBlocking {
        val b = import("a")
        val other = import("b")
        val all = layer.spine.search(b.id, "river").map { it.ordinal }
        assertEquals(setOf(1, 2, 6, 8, 10), all.toSet())
        assertEquals(setOf(1, 2), layer.spine.search(b.id, "river", maxOrdinal = 5).map { it.ordinal }.toSet())
        assertTrue(layer.spine.search(b.id, "river", maxOrdinal = -1).isEmpty())
        assertTrue(layer.spine.search(b.id, "river", maxOrdinal = 0).isEmpty())
        assertEquals(5, layer.spine.search(other.id, "river").size)
        assertEquals(2, layer.spine.search(b.id, "river", limit = 2).size)
    }

    @Test fun ftsQuerySyntaxIsSafeAndPrefixSearchWorks() = runBlocking {
        val b = import()
        assertEquals(setOf(7, 8), layer.spine.search(b.id, "ocea").map { it.ordinal }.toSet())
        assertEquals(setOf(8), layer.spine.search(b.id, "ocean river").map { it.ordinal }.toSet())
        assertTrue(layer.spine.search(b.id, "\"*(:-").isEmpty())
        assertTrue(layer.spine.search(b.id, "   ").isEmpty())
        assertTrue(layer.spine.search(b.id, "NEAR( OR NOT").isEmpty()) // operators are plain words, must not throw
    }

    @Test fun consumedIntervalsMergeAndFrontierFollowsContiguousRun() = runBlocking {
        val b = import()
        val p = layer.positions
        assertEquals(-1, p.frontier(b.id))
        p.markConsumed(b.id, 3, 4, PositionKind.READING) // does not include first body sentence (2)
        assertEquals(-1, p.frontier(b.id))
        p.markConsumed(b.id, 0, 1, PositionKind.READING) // front matter only
        assertEquals(-1, p.frontier(b.id))
        p.markConsumed(b.id, 2, 2, PositionKind.READING)
        assertEquals(4, p.frontier(b.id))
        p.markConsumed(b.id, 8, 9, PositionKind.LISTENING) // jump ahead: not contiguous
        assertEquals(4, p.frontier(b.id))
        p.markConsumed(b.id, 5, 7, PositionKind.LISTENING) // listening fills the gap, frontier uses both kinds
        assertEquals(9, p.frontier(b.id))
        p.markConsumed(b.id, 4, 6, PositionKind.READING) // overlapping rewrite stays consistent
        assertEquals(9, p.frontier(b.id))
    }

    @Test fun fractionsAreDerivedFromBodySectionsOnly() = runBlocking {
        val b = import()
        layer.positions.markConsumed(b.id, 0, 3, PositionKind.READING) // 2 body sentences of 8
        layer.positions.markConsumed(b.id, 2, 9, PositionKind.LISTENING)
        val book = layer.library.book(b.id).first()!!
        assertEquals(0.25f, book.readFraction, 1e-6f)
        assertEquals(1f, book.listenFraction, 1e-6f)
        // excluding chapter two shrinks the scope: read 2/4 now
        val ch2 = layer.spine.sections(b.id)[2]
        layer.sectionEditor.setIncluded(ch2.id, false)
        assertEquals(0.5f, layer.library.book(b.id).first()!!.readFraction, 1e-6f)
    }

    @Test fun positionsAreSavedPerKind() = runBlocking {
        val b = import()
        assertNull(layer.positions.position(b.id, PositionKind.READING).first())
        layer.positions.savePosition(b.id, PositionKind.READING, 5)
        layer.positions.savePosition(b.id, PositionKind.READING, 7)
        layer.positions.savePosition(b.id, PositionKind.LISTENING, 2)
        assertEquals(7, layer.positions.position(b.id, PositionKind.READING).first()!!.ordinal)
        assertEquals(2, layer.positions.position(b.id, PositionKind.LISTENING).first()!!.ordinal)
    }

    @Test fun annotationsAndBookmarks() = runBlocking {
        val b = import()
        val id = layer.annotations.add(Annotation(99, b.id, 2, 3, "q", 1, "n", AnnotationVia.READ, 10))
        val got = layer.annotations.annotations(b.id).first().single()
        assertEquals(id, got.id)
        layer.annotations.update(got.copy(note = "edited", color = 4))
        assertEquals("edited", layer.annotations.allAnnotations().first().single().note)
        layer.annotations.delete(id)
        assertTrue(layer.annotations.annotations(b.id).first().isEmpty())
        val bm = layer.annotations.addBookmark(Bookmark(0, b.id, 6, "here", 1))
        assertEquals(6, layer.annotations.bookmarks(b.id).first().single().ordinal)
        layer.annotations.deleteBookmark(bm)
        assertTrue(layer.annotations.bookmarks(b.id).first().isEmpty())
    }

    @Test fun audioSegmentsSyncRowsAndCoverage() = runBlocking {
        val b = import()
        val v = VoiceRef("fish", "v1", "V1")
        assertEquals(0f, layer.audio.coverage(b.id).first(), 0f)
        val s1 = layer.audio.addSegment(AudioSegment(0, b.id, 0, 2, "/a", 3000, v, 1), (0..2).map { SyncRow(b.id, it, 0, it * 1000L, it * 1000L + 900) })
        val s2 = layer.audio.addSegment(AudioSegment(0, b.id, 3, 5, "/b", 3000, v, 1), (3..5).map { SyncRow(b.id, it, 0, (it - 3) * 1000L, (it - 3) * 1000L + 900) })
        assertEquals(s1, layer.audio.segmentFor(b.id, 1)!!.id)
        assertEquals(s2, layer.audio.segmentFor(b.id, 4)!!.id)
        assertNull(layer.audio.segmentFor(b.id, 9))
        val rows = layer.audio.syncRows(b.id, 2, 3)
        assertEquals(listOf(s1 to 2, s2 to 3), rows.map { it.segmentId to it.ordinal })
        assertEquals(0.5f, layer.audio.coverage(b.id).first(), 1e-6f)
        // coverage follows the active voice
        layer.settings.update { it.copy(voice = VoiceRef("fish", "other", "O")) }
        assertEquals(0f, layer.audio.coverage(b.id).first(), 0f)
        layer.settings.update { it.copy(voice = v) }
        assertEquals(0.5f, layer.audio.coverage(b.id).first(), 1e-6f)
        layer.audio.deleteSegments(b.id)
        assertTrue(layer.audio.segments(b.id).first().isEmpty())
        assertTrue(layer.audio.syncRows(b.id, 0, 11).isEmpty())
    }

    @Test fun newerTakeWinsForSameSentences() = runBlocking {
        val b = import()
        val v = VoiceRef("fish", "v1", "V1")
        layer.audio.addSegment(AudioSegment(0, b.id, 0, 1, "/old", 100, v, 1), listOf(SyncRow(b.id, 0, 0, 0, 50)))
        val newer = layer.audio.addSegment(AudioSegment(0, b.id, 0, 1, "/new", 100, v, 2), listOf(SyncRow(b.id, 0, 0, 0, 60)))
        assertEquals(newer, layer.audio.segmentFor(b.id, 0)!!.id)
        assertEquals(listOf(60L), layer.audio.syncRows(b.id, 0, 1).map { it.endMs })
    }

    @Test fun cardsDueCountAndReviewedToday() = runBlocking {
        val b = import()
        fun card(due: Long, last: Long?) = Card(0, b.id, CardType.QA, 1, 2, "q", "a", null, due, 1.0, 5.0, 1, 0, 2, last, 1)
        val now = 1_800_000_000_000L
        layer.cards.add(card(now - 10, null))
        layer.cards.add(card(now + 100_000, now - 1000))
        val old = layer.cards.add(card(now + 5, now - 3L * 24 * 3600 * 1000))
        assertEquals(1, layer.cards.dueCount(now).first())
        assertEquals(1, layer.cards.dueCards(now, 10).first().size)
        assertEquals(3, layer.cards.cards(b.id).first().size)
        assertEquals(3, layer.cards.cards(null).first().size)
        assertEquals(1, layer.cards.reviewedToday(now))
        val c = layer.cards.cards(b.id).first().first { it.id == old }
        layer.cards.update(c.copy(front = "edited", stability = 9.5))
        assertEquals(9.5, layer.cards.cards(b.id).first().first { it.id == old }.stability, 0.0)
        layer.cards.delete(old)
        assertEquals(2, layer.cards.cards(b.id).first().size)
        layer.library.delete(b.id)
        assertTrue(layer.cards.cards(null).first().isEmpty())
    }

    @Test fun aiCacheAndUsageCounters() = runBlocking {
        assertNull(layer.aiStore.get("recap", "k"))
        layer.aiStore.put("recap", "k", "{\"a\":1}")
        layer.aiStore.put("recap", "k", "{\"a\":2}")
        assertEquals("{\"a\":2}", layer.aiStore.get("recap", "k"))
        assertNull(layer.aiStore.get("ask", "k"))
        assertEquals(0, layer.aiStore.requestsOn("2026-10-08", "openrouter"))
        layer.aiStore.addRequests("2026-10-08", "openrouter", 2)
        layer.aiStore.addRequests("2026-10-08", "openrouter", 3)
        layer.aiStore.addRequests("2026-10-08", "fish", 1)
        assertEquals(5, layer.aiStore.requestsOn("2026-10-08", "openrouter"))
        assertEquals(1, layer.aiStore.requestsOn("2026-10-08", "fish"))
        assertEquals(0, layer.aiStore.requestsOn("2026-10-09", "openrouter"))
    }
}
