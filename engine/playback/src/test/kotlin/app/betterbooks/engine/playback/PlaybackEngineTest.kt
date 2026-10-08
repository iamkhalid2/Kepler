package app.betterbooks.engine.playback

import org.robolectric.RuntimeEnvironment
import app.betterbooks.core.model.AppSettings
import app.betterbooks.core.model.AudioRepository
import app.betterbooks.core.model.AudioSegment
import app.betterbooks.core.model.Book
import app.betterbooks.core.model.ContentType
import app.betterbooks.core.model.ImportResult
import app.betterbooks.core.model.LibraryRepository
import app.betterbooks.core.model.NarrationConfig
import app.betterbooks.core.model.NarrationEstimate
import app.betterbooks.core.model.NarrationService
import app.betterbooks.core.model.NarrationState
import app.betterbooks.core.model.Position
import app.betterbooks.core.model.PositionKind
import app.betterbooks.core.model.PositionRepository
import app.betterbooks.core.model.Section
import app.betterbooks.core.model.SectionKind
import app.betterbooks.core.model.SettingsStore
import app.betterbooks.core.model.Sentence
import app.betterbooks.core.model.SpineRepository
import app.betterbooks.core.model.SyncRow
import app.betterbooks.core.model.VoiceOption
import app.betterbooks.core.model.VoiceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

private val voice = VoiceRef("fish", "ava", "Ava")

private class Fakes(segments: List<AudioSegment>, val rows: List<SyncRow>) {
    val segmentFlow = MutableStateFlow(segments)
    val saved = mutableListOf<Int>()
    val consumed = mutableListOf<IntRange>()
    val ensured = mutableListOf<Int>()

    val book = Book(1, "Meditations", "Marcus Aurelius", "en", ContentType.NONFICTION, "/x.epub", null, 0, null, 40, 0f, 0f)
    val sections = listOf(
        Section(1, 1, null, 0, 0, "Book I", "a", SectionKind.BODY, 0, 19, true),
        Section(2, 1, null, 1, 0, "Book II", "b", SectionKind.BODY, 20, 39, true),
    )

    val audio = object : AudioRepository {
        override fun segments(bookId: Long): Flow<List<AudioSegment>> = segmentFlow
        override suspend fun addSegment(segment: AudioSegment, rows: List<SyncRow>) = 0L
        override suspend fun segmentFor(bookId: Long, ordinal: Int) =
            segmentFlow.value.firstOrNull { ordinal in it.startOrdinal..it.endOrdinal }
        override suspend fun syncRows(bookId: Long, fromOrdinal: Int, toOrdinal: Int) =
            rows.filter { it.ordinal in fromOrdinal..toOrdinal }
        override fun coverage(bookId: Long) = flowOf(1f)
        override suspend fun deleteSegments(bookId: Long) = Unit
    }
    val spine = object : SpineRepository {
        override suspend fun sections(bookId: Long) = this@Fakes.sections
        override suspend fun sentence(bookId: Long, ordinal: Int) =
            Sentence(bookId, ordinal, if (ordinal < 20) 1 else 2, "a", "s$ordinal", "", "", ordinal / 5)
        override suspend fun sentences(bookId: Long, fromOrdinal: Int, toOrdinal: Int) = emptyList<Sentence>()
        override suspend fun sentenceCount(bookId: Long) = 40
        override suspend fun search(bookId: Long, query: String, maxOrdinal: Int?, limit: Int) = emptyList<Sentence>()
    }
    val positions = object : PositionRepository {
        override fun position(bookId: Long, kind: PositionKind): Flow<Position?> = flowOf(null)
        override suspend fun savePosition(bookId: Long, kind: PositionKind, ordinal: Int) { saved += ordinal }
        override suspend fun markConsumed(bookId: Long, fromOrdinal: Int, toOrdinal: Int, via: PositionKind) {
            consumed += fromOrdinal..toOrdinal
        }
        override suspend fun frontier(bookId: Long) = -1
    }
    val narration = object : NarrationService {
        override fun state(bookId: Long): Flow<NarrationState> = flowOf(NarrationState.Idle)
        override suspend fun voices(provider: String) = emptyList<VoiceOption>()
        override suspend fun estimate(bookId: Long, config: NarrationConfig, fromOrdinal: Int) =
            NarrationEstimate(0, 0, 0.0, 0, 0, 0, true)
        override fun start(bookId: Long, config: NarrationConfig, fromOrdinal: Int) = Unit
        override fun stop(bookId: Long) = Unit
        override suspend fun ensureAhead(bookId: Long, config: NarrationConfig, ordinal: Int, minutes: Int) {
            ensured += ordinal
        }
        override suspend fun preview(voice: VoiceRef, sampleText: String) = ""
    }
    val library = object : LibraryRepository {
        override val books = flowOf(listOf(book))
        override fun book(id: Long) = flowOf(book)
        override suspend fun importBook(sourceUri: String) = ImportResult.Failed("n/a")
        override suspend fun delete(id: Long) = Unit
        override suspend fun markOpened(id: Long) = Unit
    }
    val settings = object : SettingsStore {
        private val flow = MutableStateFlow(AppSettings(voice = voice, speed = 1.25f))
        override val settings: Flow<AppSettings> = flow
        override suspend fun update(transform: (AppSettings) -> AppSettings) { flow.value = transform(flow.value) }
        override suspend fun fishKey() = null
        override suspend fun setFishKey(key: String?) = Unit
        override suspend fun openRouterKey() = null
        override suspend fun setOpenRouterKey(key: String?) = Unit
    }
    fun deps() = PlaybackDependencies(audio, spine, positions, narration, library, settings)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEngineTest {
    private fun seg(id: Long, start: Int, end: Int) =
        AudioSegment(id, 1, start, end, "/nonexistent/$id.m4a", 10_000, voice, 0)

    private fun rowsFor(s: AudioSegment) = (s.startOrdinal..s.endOrdinal).map { o ->
        val i = o - s.startOrdinal
        SyncRow(1, o, s.id, i * 2_000L, i * 2_000L + 1_900)
    }

    private val segs = listOf(seg(1, 0, 4), seg(2, 5, 9), seg(3, 10, 19))
    private lateinit var fakes: Fakes
    private lateinit var engine: PlaybackEngine

    @Before fun setUp() {
        fakes = Fakes(segs, segs.flatMap(::rowsFor))
        PlaybackHub.initialize(fakes.deps())
        engine = PlaybackEngine(RuntimeEnvironment.getApplication(), fakes.deps())
        idle()
    }

    @After fun tearDown() = engine.release()

    private fun idle() = Shadows.idle()

    private object Shadows {
        fun idle() = ShadowLooper.idleMainLooper()
    }

    @Test fun playOpensTheBookAtTheRequestedSentence() {
        engine.play(1, 7)
        idle()
        val st = engine.state.value
        assertEquals(1L, st.bookId)
        assertEquals(7, st.ordinal)
        assertEquals("Meditations", st.title)
        assertFalse(st.buffering && st.error != null)
        assertEquals(1.25f, st.speed, 0f)
        val info = engine.info.value
        assertEquals(listOf("Book I", "Book II"), info.marks.map { it.title })
        assertEquals("Ava", info.voiceName)
        assertEquals(40, info.sentenceCount)
    }

    @Test fun seekAndSkipMoveBySentence() {
        engine.play(1, 0)
        idle()
        engine.seekToSentence(12)
        idle()
        assertEquals(12, engine.state.value.ordinal)
        engine.skipSentences(3)
        idle()
        assertEquals(15, engine.state.value.ordinal)
        engine.skipSentences(-1)
        idle()
        assertEquals(14, engine.state.value.ordinal)
    }

    @Test fun missingAudioAsksTheNarrationServiceFirst() {
        fakes.segmentFlow.value = listOf(segs[0])
        engine.play(1, 25)
        idle()
        assertEquals(listOf(25), fakes.ensured)
        // nothing appeared, so the player reports that the passage is not ready instead of hanging
        assertNotNull(engine.state.value.error)
        assertFalse(engine.state.value.buffering)
    }

    @Test fun speedIsClampedAndPersisted() {
        engine.play(1, 0)
        idle()
        engine.setSpeed(9f)
        idle()
        assertEquals(3.5f, engine.state.value.speed, 0f)
        engine.setSpeed(0.1f)
        assertEquals(0.5f, engine.state.value.speed, 0f)
    }

    @Test fun sleepTimerReportsTimeLeftAndTurnsOff() {
        engine.play(1, 0)
        idle()
        engine.setSleepTimer(5)
        idle()
        val left = engine.state.value.sleepRemainingMs
        assertNotNull(left)
        assertTrue(left!! in 299_000..300_000)
        engine.setSleepTimer(-1)
        assertTrue(engine.info.value.sleepAtSectionEnd)
        engine.setSleepTimer(0)
        idle()
        assertNull(engine.state.value.sleepRemainingMs)
        assertFalse(engine.info.value.sleepAtSectionEnd)
    }

    @Test fun pauseSavesTheListeningPosition() {
        engine.play(1, 3)
        idle()
        engine.pause()
        Thread.sleep(200)
        idle()
        assertTrue(fakes.saved.contains(3))
        assertFalse(engine.state.value.playing)
    }
}
