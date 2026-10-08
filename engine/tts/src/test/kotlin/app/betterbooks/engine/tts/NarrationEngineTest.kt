package app.betterbooks.engine.tts

import app.betterbooks.core.model.AppSettings
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.NarrationConfig
import app.betterbooks.core.model.NarrationState
import app.betterbooks.core.model.Section
import app.betterbooks.core.model.Sentence
import app.betterbooks.core.model.SettingsStore
import app.betterbooks.core.model.SpineRepository
import app.betterbooks.core.model.VoiceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NarrationEngineTest {

    /** 3 paragraphs of 4 sentences, 4 words each. */
    private val book: List<Sentence> = List(12) { sentence(it, "word$it alpha beta gamma.", paragraph = it / 4) }

    private val spine = object : SpineRepository {
        override suspend fun sections(bookId: BookId) = emptyList<Section>()
        override suspend fun sentence(bookId: BookId, ordinal: Int) = book.getOrNull(ordinal)
        override suspend fun sentences(bookId: BookId, fromOrdinal: Int, toOrdinal: Int) =
            book.filter { it.ordinal in fromOrdinal..toOrdinal }
        override suspend fun sentenceCount(bookId: BookId) = book.size
        override suspend fun search(bookId: BookId, query: String, maxOrdinal: Int?, limit: Int) = emptyList<Sentence>()
    }

    private class FakeSettings(cap: Int) : SettingsStore {
        override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings(monthlyCapCents = cap))
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        override suspend fun fishKey(): String? = null
        override suspend fun setFishKey(key: String?) = Unit
        override suspend fun openRouterKey(): String? = null
        override suspend fun setOpenRouterKey(key: String?) = Unit
    }

    private class RecordingScheduler : NarrationScheduler {
        val enqueued = mutableListOf<Long>()
        override fun enqueue(bookId: BookId, config: NarrationConfig, fromOrdinal: Int, delaySeconds: Long) {
            enqueued += delaySeconds
        }
        override fun cancel(bookId: BookId) = Unit
    }

    private val config = NarrationConfig(VoiceRef("fake", "v1", "V"))

    private fun engine(provider: FakeProvider, repo: FakeAudioRepo, cap: Int = 0, scheduler: NarrationScheduler = RecordingScheduler()) =
        NarrationEngine(
            RuntimeEnvironment.getApplication(), FakeSettings(cap), spine, repo,
            transcoder = FakeTranscoder(), providers = listOf(provider), scheduler = scheduler,
        )

    @Test fun ensureAheadRendersAMicroSegmentFirst() = runBlocking {
        val provider = FakeProvider()
        val repo = FakeAudioRepo()
        engine(provider, repo).ensureAhead(1, config, ordinal = 0, minutes = 1)
        assertNotNull(repo.segmentFor(1, 0))
        val first = synchronized(provider.requests) { provider.requests.first { it.sentences.first().startsWith("word0") } }
        assertTrue(first.sentences.size <= NarrationEngine.MICRO_SENTENCES)
    }

    @Test fun jobRendersEverythingOnceAndResumesWithoutRepeating() = runBlocking {
        val provider = FakeProvider()
        val repo = FakeAudioRepo()
        val e = engine(provider, repo)
        assertEquals(NarrationEngine.JobResult.Done, e.runJob(1, config, 0))
        assertEquals(3, provider.calls.get()) // one request per paragraph
        assertEquals((0 until 12).toList(), repo.rows.map { it.ordinal }.sorted())
        assertEquals(NarrationState.Done(1), e.state(1).first())

        assertEquals(NarrationEngine.JobResult.Done, e.runJob(1, config, 5))
        assertEquals(3, provider.calls.get())
    }

    @Test fun monthlyCapPausesTheJob() = runBlocking {
        // Each paragraph is ~100 bytes, so 0.6 cents; a 1 cent cap allows at most one more request.
        val provider = FakeProvider(pricePerByte = 0.006)
        val repo = FakeAudioRepo()
        val e = engine(provider, repo, cap = 1)
        assertEquals(NarrationEngine.JobResult.Paused, e.runJob(1, config, 0))
        assertTrue(provider.calls.get() in 1..2)
        assertEquals(NarrationState.Paused(1, NarrationEngine.REASON_CAP), e.state(1).first())
    }

    @Test fun estimateCountsOnlyMissingAudio() = runBlocking {
        val provider = FakeProvider()
        val repo = FakeAudioRepo()
        val e = engine(provider, repo)
        val before = e.estimate(1, config)
        assertEquals(3, before.requests)
        assertTrue(before.freeTier)
        e.ensureAhead(1, config, ordinal = 0, minutes = 1)
        e.runJob(1, config, 0)
        assertEquals(0, e.estimate(1, config).requests)
    }

    @Test fun foregroundTimeoutQueuesAContinuation() {
        val scheduler = RecordingScheduler()
        val e = engine(FakeProvider(), FakeAudioRepo(), scheduler = scheduler)
        e.onJobInterrupted(1, config, 0, NarrationEngine.REASON_SYSTEM, 900)
        assertEquals(listOf(900L), scheduler.enqueued)
    }
}
