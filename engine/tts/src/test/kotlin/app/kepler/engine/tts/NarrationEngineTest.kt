package app.kepler.engine.tts

import app.kepler.core.model.AppSettings
import app.kepler.core.model.BookId
import app.kepler.core.model.NarrationConfig
import app.kepler.core.model.NarrationState
import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import app.kepler.core.model.Sentence
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.SpineRepository
import app.kepler.core.model.VoiceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import app.kepler.core.model.NarrationBlockedException
import app.kepler.core.model.PauseReason
import app.kepler.core.model.pauseReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    private var sections = emptyList<Section>()

    private val spine = object : SpineRepository {
        override suspend fun sections(bookId: BookId) = sections
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
        val cancelled = mutableListOf<Long>()
        override fun cancel(bookId: BookId) { cancelled += bookId }
    }

    private val config = NarrationConfig(VoiceRef("fake", "v1", "V"))

    /** After the free Fish period, so confirmations count. */
    private val afterSwitch = Instant.parse("2026-12-05T10:00:00Z")

    private class MutableClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = at
    }

    private val transcoder = FakeTranscoder()

    private fun engine(
        provider: FakeProvider, repo: FakeAudioRepo, cap: Int = 0, scheduler: NarrationScheduler = RecordingScheduler(),
        clock: Clock = Clock.fixed(afterSwitch, ZoneOffset.UTC),
    ) = NarrationEngine(
        RuntimeEnvironment.getApplication(), FakeSettings(cap), spine, repo, clock = clock,
        transcoder = transcoder, providers = listOf(provider), scheduler = scheduler,
    )

    @After fun cleanUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("narration_engine", 0).edit().clear().commit()
        java.io.File(RuntimeEnvironment.getApplication().filesDir, "audio").deleteRecursively()
    }

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
        val e = engine(provider, repo, cap = 1).also { it.confirmPaidGeneration() }
        assertEquals(NarrationEngine.JobResult.Paused, e.runJob(1, config, 0))
        assertTrue(provider.calls.get() in 1..2)
        assertEquals(NarrationState.Paused(1, NarrationEngine.REASON_CAP), e.state(1).first())
    }

    @Test fun paidGenerationWaitsForConfirmation() = runBlocking {
        val provider = FakeProvider(pricePerByte = 0.0001)
        val e = engine(provider, FakeAudioRepo())
        assertEquals(NarrationEngine.JobResult.Paused, e.runJob(1, config, 0))
        assertEquals(0, provider.calls.get())
        assertEquals(NarrationState.Paused(1, NarrationEngine.REASON_PAID_CONFIRM), e.state(1).first())
    }

    @Test fun unsetCapStillStopsPaidGeneration() = runBlocking {
        // cap 0 = unset; the 500 cent default applies, so a price of 100 cents per byte stops it at once.
        val provider = FakeProvider(pricePerByte = 100.0)
        val e = engine(provider, FakeAudioRepo(), cap = 0).also { it.confirmPaidGeneration() }
        assertEquals(NarrationEngine.JobResult.Paused, e.runJob(1, config, 0))
        assertEquals(0, provider.calls.get())
        assertEquals(NarrationState.Paused(1, NarrationEngine.REASON_CAP), e.state(1).first())
    }

    @Test fun narrationIsAlwaysSynthesizedAtNormalSpeed() = runBlocking {
        val provider = FakeProvider()
        engine(provider, FakeAudioRepo()).runJob(1, config.copy(speed = 1.5f), 0)
        assertTrue(provider.requests.isNotEmpty() && provider.requests.all { it.speed == 1f })
    }

    @Test fun excludedSectionsAreNeverNarrated() = runBlocking {
        sections = listOf(
            Section(1, 1, null, 0, 0, "Front", "f", SectionKind.FRONT, 0, 3, included = false),
            Section(2, 1, null, 1, 0, "One", "b", SectionKind.BODY, 4, 11, included = true),
        )
        val provider = FakeProvider()
        val repo = FakeAudioRepo()
        val e = engine(provider, repo)
        assertEquals(2, e.estimate(1, config).requests)
        assertEquals(NarrationEngine.JobResult.Done, e.runJob(1, config, 0))
        assertEquals((4 until 12).toList(), repo.rows.map { it.ordinal }.sorted())
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

    // ---- F4: the job waits for just-in-time renders instead of skipping their sentences

    @Test fun jobAwaitsInFlightRendersAndRedoesWhatFailed() = runBlocking {
        val provider = FakeProvider()
        val repo = FakeAudioRepo()
        val e = engine(provider, repo)
        val gate = CompletableDeferred<Unit>()
        val failed = AtomicBoolean(false)
        // The first background chunk (after the micro segment) holds until released, then fails once.
        provider.beforeAccept = { req ->
            if (req.sentences.first().startsWith("word2")) {
                gate.await()
                if (failed.compareAndSet(false, true)) throw TtsException.Engine("boom")
            }
        }
        e.ensureAhead(1, config, ordinal = 0, minutes = 1)
        val job = async { e.runJob(1, config, 0) }
        e.state(1).first { it is NarrationState.Running }
        gate.complete(Unit)
        assertEquals(NarrationEngine.JobResult.Done, job.await())
        assertTrue(failed.get())
        assertEquals((0 until 12).toList(), repo.rows.map { it.ordinal }.sorted())
        assertEquals(0, e.estimate(1, config).requests)
    }

    // ---- F6: billing accounting

    @Test fun spendIsBookedWhenTheResponseSucceedsEvenIfDecodingFails() = runBlocking {
        val provider = FakeProvider(pricePerByte = 0.001)
        val e = engine(provider, FakeAudioRepo()).also { it.confirmPaidGeneration() }
        transcoder.decodeFailure = TtsException.Audio("cannot decode")
        val r = e.runJob(1, config, 0)
        assertTrue(r is NarrationEngine.JobResult.Failed)
        assertTrue(e.spentThisMonthCents() > 0)
        assertTrue(e.spentOnBookCents(1) > 0)
        assertTrue(e.requestsThisMonth() >= 1)
    }

    @Test fun nothingIsBookedWhenTheRequestFailsBeforeAcceptance() = runBlocking {
        val provider = FakeProvider(pricePerByte = 0.001)
        val e = engine(provider, FakeAudioRepo()).also { it.confirmPaidGeneration() }
        provider.beforeAccept = { throw TtsException.RateLimited() }
        assertTrue(e.runJob(1, config, 0) is NarrationEngine.JobResult.Failed)
        assertEquals(0.0, e.spentThisMonthCents(), 0.0)
        assertEquals(0, e.requestsThisMonth())
    }

    @Test fun parallelJustInTimeChunksCannotOvershootTheCap() = runBlocking {
        // ~0.58 cents per two-sentence chunk; a 2 cent cap fits three of them, however many run in parallel.
        val provider = FakeProvider(pricePerByte = 0.012)
        val e = engine(provider, FakeAudioRepo(), cap = 2).also { it.confirmPaidGeneration() }
        e.ensureAhead(1, config, ordinal = 0, minutes = 5)
        assertEquals(NarrationEngine.JobResult.Paused, e.runJob(1, config, 0))
        assertTrue("spent ${e.spentThisMonthCents()}", e.spentThisMonthCents() <= 2.0)
        assertTrue(provider.calls.get() in 1..3)
    }

    @Test fun perBookCapStopsABookEvenWithRoomInTheMonth() = runBlocking {
        // Each request costs ~150 cents: two fit under the 300 cent book cap, a third does not; the month cap is 10 000.
        val provider = FakeProvider(pricePerByte = 1.5)
        val e = engine(provider, FakeAudioRepo(), cap = 10_000).also { it.confirmPaidGeneration() }
        assertEquals(NarrationEngine.JobResult.Paused, e.runJob(1, config, 0))
        assertEquals(PauseReason.BOOK_CAP, (e.state(1).first() as NarrationState.Paused).pauseReason)
        assertTrue(e.spentOnBookCents(1) <= NarrationEngine.PER_BOOK_CAP_CENTS)
    }

    @Test fun previewIsCappedToo() = runBlocking {
        val provider = FakeProvider(pricePerByte = 100.0)
        val e = engine(provider, FakeAudioRepo(), cap = 1).also { it.confirmPaidGeneration() }
        try {
            e.preview(config.voice, "Hello there")
            fail()
        } catch (ex: NarrationBlockedException) {
            assertEquals(PauseReason.MONTHLY_CAP, ex.reason)
        }
        assertEquals(0, provider.calls.get())
    }

    // ---- F3: the free-to-paid switch is never silent

    @Test fun aConfirmationGivenBeforeTheSwitchDateDoesNotCount() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-01T00:00:00Z"))
        val provider = FakeProvider(pricePerByte = 0.0001)
        val e = engine(provider, FakeAudioRepo(), clock = clock)
        e.confirmPaidGeneration()
        clock.at = afterSwitch
        assertFalse(e.paidGenerationConfirmed())
        assertEquals(NarrationEngine.JobResult.Paused, e.runJob(1, config, 0))
        assertEquals(0, provider.calls.get())
        assertEquals(PauseReason.PAID_CONFIRM, (e.state(1).first() as NarrationState.Paused).pauseReason)
        e.confirmPaidGeneration()
        assertTrue(e.paidGenerationConfirmed())
        assertEquals(NarrationEngine.JobResult.Done, e.runJob(1, config, 0))
    }

    @Test fun justInTimePlaybackIsNotReadyWithAReasonAndNeverSpends() = runBlocking {
        val provider = FakeProvider(pricePerByte = 0.0001)
        val e = engine(provider, FakeAudioRepo())
        try {
            e.ensureAhead(1, config, ordinal = 3, minutes = 1)
            fail()
        } catch (ex: NarrationBlockedException) {
            assertEquals(PauseReason.PAID_CONFIRM, ex.reason)
        }
        assertEquals(0, provider.calls.get())
        assertEquals(NarrationState.Paused(1, "paid_confirm"), e.state(1).first())
    }

    @Test fun controlsExposeCapsAndSwitchStatus() = runBlocking {
        val e = engine(FakeProvider(), FakeAudioRepo(), cap = 0)
        assertEquals(500, e.effectiveMonthlyCapCents())
        assertEquals(300, e.perBookCapCents())
        assertEquals(app.kepler.core.model.PaidSwitchStatus.PAID, e.paidSwitchStatus())
        assertEquals(1200, engine(FakeProvider(), FakeAudioRepo(), cap = 1200).effectiveMonthlyCapCents())
    }

    // ---- F7: a deleted book stops the job quietly

    @Test fun constraintFailureOnInsertMeansTheBookIsGone() = runBlocking {
        val provider = FakeProvider()
        val repo = FakeAudioRepo().apply {
            insertFailure = android.database.sqlite.SQLiteConstraintException("FOREIGN KEY constraint failed")
        }
        val e = engine(provider, repo)
        assertEquals(NarrationEngine.JobResult.BookGone, e.runJob(1, config, 0))
        assertEquals(NarrationState.Idle, e.state(1).first())
        val dir = java.io.File(RuntimeEnvironment.getApplication().filesDir, "audio/1")
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun otherInsertFailuresStillFail() = runBlocking {
        val repo = FakeAudioRepo().apply { insertFailure = IllegalStateException("disk full") }
        try {
            engine(FakeProvider(), repo).runJob(1, config, 0)
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
    }

    @Test fun cancelBookStopsJustInTimeRendersAndResetsState() = runBlocking {
        val provider = FakeProvider()
        val scheduler = RecordingScheduler()
        val e = engine(provider, FakeAudioRepo(), scheduler = scheduler)
        val gate = CompletableDeferred<Unit>()
        provider.beforeAccept = { req -> if (req.sentences.first().startsWith("word2")) gate.await() }
        e.ensureAhead(1, config, ordinal = 0, minutes = 1)
        e.cancelBook(1)
        gate.complete(Unit)
        assertEquals(NarrationState.Idle, e.state(1).first())
        assertEquals(listOf(1L), scheduler.cancelled)
    }
}
