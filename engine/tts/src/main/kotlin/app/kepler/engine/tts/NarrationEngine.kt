package app.kepler.engine.tts

import android.content.Context
import android.content.SharedPreferences
import app.kepler.core.model.AudioRepository
import app.kepler.core.model.AudioSegment
import app.kepler.core.model.BookId
import app.kepler.core.model.NarrationBlockedException
import app.kepler.core.model.NarrationConfig
import app.kepler.core.model.NarrationControls
import app.kepler.core.model.NarrationEstimate
import app.kepler.core.model.NarrationLimits
import app.kepler.core.model.NarrationService
import app.kepler.core.model.NarrationState
import app.kepler.core.model.PaidSwitchStatus
import app.kepler.core.model.PauseReason
import app.kepler.core.model.paidSwitchStatus as paidSwitchStatusAt
import app.kepler.core.model.Sentence
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.SpineRepository
import app.kepler.core.model.VoiceOption
import app.kepler.core.model.VoiceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/**
 * The narration engine: providers, per-paragraph generation, the audio cache and the background job.
 *
 * Create one per process (normally in Application.onCreate) so [NarrationWorker] can find it.
 * WorkManager's default initializer is enough; nothing else must be configured.
 */
class NarrationEngine(
    context: Context,
    private val settings: SettingsStore,
    private val spine: SpineRepository,
    private val audio: AudioRepository,
    private val clock: Clock = Clock.systemUTC(),
    fishConcurrency: Int = FishProvider.DEFAULT_CONCURRENCY,
    transcoder: AudioTranscoder = MediaCodecTranscoder(),
    providers: List<TtsProvider>? = null,
    private val scheduler: NarrationScheduler? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : NarrationService, NarrationControls {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scratch = File(appContext.cacheDir, "tts-scratch")

    private val ledger = SpendLedger(prefs, clock) { settings.settings.first().monthlyCapCents }

    // Every provider sits behind the ledger: caps are enforced at each request, spend is booked on acceptance.
    private val providers: Map<String, TtsProvider> = (
        providers ?: listOf(
            FishProvider({ settings.fishKey() }, ModelPolicy(clock), concurrency = fishConcurrency),
            SystemTtsProvider(appContext, scratch),
        )
        ).map<TtsProvider, TtsProvider> { MeteredProvider(it, ledger) }.associateBy { it.id }

    private val renderer = SegmentRenderer(File(appContext.filesDir, "audio"), scratch, transcoder, audio)
    private val states = ConcurrentHashMap<BookId, MutableStateFlow<NarrationState>>()
    private val works = ConcurrentHashMap<String, VoiceWork>()
    private val bookScopes = ConcurrentHashMap<BookId, CoroutineScope>()
    private val throughput = ConcurrentHashMap<String, ThroughputEwma>()

    init {
        instance = this
    }

    // ---------------------------------------------------------------- NarrationService

    override fun state(bookId: BookId): Flow<NarrationState> = stateFlow(bookId).asStateFlow()

    override suspend fun voices(provider: String): List<VoiceOption> = provider(provider).voices(null)

    override suspend fun estimate(bookId: BookId, config: NarrationConfig, fromOrdinal: Int): NarrationEstimate {
        val provider = provider(config.voice.provider)
        val covered = covered(bookId, config.voice)
        val count = spine.sentenceCount(bookId)
        val narrated = narrated(bookId, count)
        val chunks = ArrayList<Chunk>()
        for (range in jobOrder(fromOrdinal, count)) {
            pages(range).forEach { (a, b) ->
                val page = spine.sentences(bookId, a, b).filter { narrated[it.ordinal] }
                chunks += Chunker.plan(page, { covered[it] }, provider.capabilities.maxChars)
            }
        }
        return Estimator.estimate(chunks, provider.centsPerByte(), SYNTH_SPEED, aggregateThroughput(provider))
    }

    override fun start(bookId: BookId, config: NarrationConfig, fromOrdinal: Int) {
        stateFlow(bookId).value = NarrationState.Running(bookId, 0, 0, 0)
        (scheduler ?: WorkManagerScheduler(appContext)).enqueue(bookId, config, fromOrdinal)
    }

    override fun stop(bookId: BookId) {
        (scheduler ?: WorkManagerScheduler(appContext)).cancel(bookId)
        stateFlow(bookId).value = NarrationState.Idle
    }

    override fun cancelBook(bookId: BookId) {
        (scheduler ?: WorkManagerScheduler(appContext)).cancel(bookId)
        bookScopes.remove(bookId)?.cancel()
        works.keys.removeAll { it.startsWith("$bookId|") }
        ledger.forgetBook(bookId)
        states.remove(bookId)?.value = NarrationState.Idle
    }

    override suspend fun ensureAhead(bookId: BookId, config: NarrationConfig, ordinal: Int, minutes: Int) {
        val provider = provider(config.voice.provider)
        val work = work(bookId, config.voice)
        val count = spine.sentenceCount(bookId)
        if (ordinal !in 0 until count) return
        val blocked = ledger.blockReason(bookId, 0.0, paid = provider.centsPerByte() > 0)
        if (blocked != null) {
            stateFlow(bookId).value = NarrationState.Paused(bookId, blocked.code)
            // Audio that already exists plays on; only a missing start point is "not ready".
            if (!covered(bookId, config.voice)[ordinal]) throw NarrationBlockedException(blocked)
            return
        }
        val narrated = narrated(bookId, count)

        val window = ArrayList<Sentence>()
        var seconds = 0.0
        var from = ordinal
        val target = minutes.coerceAtLeast(1) * 60.0
        while (from < count && seconds < target) {
            val page = spine.sentences(bookId, from, minOf(count - 1, from + 199))
            if (page.isEmpty()) break
            for (s in page) {
                if (seconds >= target) break
                if (!narrated[s.ordinal]) continue
                window += s
                seconds += Estimator.audioSeconds(s.text, SYNTH_SPEED)
            }
            from = page.last().ordinal + 1
        }

        val covered = covered(bookId, config.voice)
        val waitFor: Deferred<AudioSegment>?
        if (covered[ordinal]) {
            launchAll(bookId, work, claim(work, window, covered, 0), provider, config)
            return
        }
        val existing = work.inFlightCovering(ordinal)
        val launched = launchStaged(bookId, work, claim(work, window, covered, MICRO_SENTENCES), ordinal, provider, config)
        waitFor = existing ?: launched.firstOrNull { (c, _) -> ordinal in c.startOrdinal..c.endOrdinal }?.second
        if (waitFor != null) {
            waitFor.await()
            return
        }
        // Claimed by the background job but not started yet: wait for it to land.
        withTimeoutOrNull(JIT_WAIT_MS) {
            while (true) {
                work.inFlightCovering(ordinal)?.let { it.await(); return@withTimeoutOrNull }
                val seg = audio.segmentFor(bookId, ordinal)
                if (seg != null && seg.voice.provider == config.voice.provider && seg.voice.id == config.voice.id) {
                    return@withTimeoutOrNull
                }
                delay(250)
            }
        }
    }

    override suspend fun preview(voice: VoiceRef, sampleText: String): String {
        val provider = provider(voice.provider)
        val text = sampleText.trim().ifEmpty { throw TtsException.Engine("Nothing to preview") }
        val key = CacheKeys.of(text, provider.id, provider.currentModel(), voice.id, 1f)
        val out = File(File(appContext.cacheDir, "previews"), "$key.m4a")
        if (out.exists()) return out.absolutePath
        val result = provider.synthesize(SynthesisRequest(listOf(text), voice.id))
        withContext(Dispatchers.Default) {
            val pcm = renderer.transcoder.decode(result.audio, scratch)
            val trim = AudioDsp.findTrim(pcm.samples, pcm.sampleRate)
            val samples = AudioDsp.normalize(pcm.samples.copyOfRange(trim.startSample, trim.endSample))
            renderer.transcoder.encodeAac(SynthAudio.Pcm(samples, pcm.sampleRate), out)
        }
        return out.absolutePath
    }

    // ---------------------------------------------------------------- background job

    sealed interface JobResult {
        data object Done : JobResult
        data object Paused : JobResult
        /** The book was deleted while the job ran; nothing to report. */
        data object BookGone : JobResult
        data class Failed(val error: Throwable, val retry: Boolean) : JobResult
    }

    /** Runs the whole-book job; called by [NarrationWorker]. Checkpointed per segment, so it resumes on rerun. */
    suspend fun runJob(bookId: BookId, config: NarrationConfig, fromOrdinal: Int): JobResult {
        val flow = stateFlow(bookId)
        val provider = try {
            provider(config.voice.provider)
        } catch (e: TtsException) {
            flow.value = NarrationState.Failed(bookId, e.message.orEmpty())
            return JobResult.Failed(e, retry = false)
        }
        val work = work(bookId, config.voice)
        val bookCount = spine.sentenceCount(bookId)
        val narrated = narrated(bookId, bookCount)
        val count = narrated.cardinality()
        val done = AtomicInteger(covered(bookId, config.voice).apply { and(narrated) }.cardinality())
        val tp = aggregateThroughput(provider)
        val capHit = AtomicBoolean(false)
        var stopReason = REASON_CAP
        // Seconds of audio per sentence, refined per page; ETA covers everything still missing.
        var secondsPerSentence = 6.0

        fun publish() {
            val remaining = (count - done.get()).coerceAtLeast(0)
            val eta = if (tp > 0) (remaining * secondsPerSentence / tp).roundToLong() else 0
            flow.value = NarrationState.Running(bookId, done.get().coerceAtMost(count), count, eta)
        }

        suspend fun renderAll(chunks: List<Chunk>) {
            val gate = Semaphore(provider.capabilities.concurrency)
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        try {
                            gate.withPermit {
                                if (capHit.get()) return@withPermit
                                try {
                                    renderTracked(bookId, work, chunk, provider, config)
                                } catch (e: NarrationBlockedException) {
                                    if (!capHit.getAndSet(true)) stopReason = e.reason.code
                                    return@withPermit
                                }
                                done.addAndGet(chunk.sentences.size)
                                publish()
                            }
                        } finally {
                            // Also covers chunks cancelled while waiting for a permit.
                            work.release(chunk)
                        }
                    }
                }.awaitAll()
            }
        }

        try {
            for (range in jobOrder(fromOrdinal, bookCount)) {
                for ((a, b) in pages(range)) {
                    if (capHit.get()) break
                    val page = spine.sentences(bookId, a, b).filter { narrated[it.ordinal] }
                    var rounds = 0
                    while (!capHit.get()) {
                        val chunks = claim(work, page, covered(bookId, config.voice), 0)
                        if (rounds == 0) {
                            val pageSentences = chunks.sumOf { it.sentences.size }
                            if (pageSentences > 0) {
                                secondsPerSentence = chunks.sumOf { Estimator.audioSeconds(it.text, SYNTH_SPEED) } / pageSentences
                            }
                        }
                        publish()
                        renderAll(chunks)
                        // Sentences claimed by just-in-time renders are not ours to skip: wait for them, then look
                        // again, because a failed render leaves its sentences uncovered.
                        val pending = work.inFlightOverlapping(a, b)
                        pending.forEach { it.join() }
                        if (chunks.isEmpty() && pending.isEmpty()) break
                        if (++rounds >= MAX_PAGE_ROUNDS) break
                    }
                    done.set(covered(bookId, config.voice).apply { and(narrated) }.cardinality())
                    publish()
                }
            }
            if (!capHit.get()) {
                val have = covered(bookId, config.voice)
                if ((0 until bookCount).any { narrated[it] && !have[it] }) {
                    val e = TtsException.Engine("Some parts of the narration could not be made.")
                    flow.value = NarrationState.Failed(bookId, e.message.orEmpty())
                    return JobResult.Failed(e, retry = true)
                }
            }
        } catch (e: TtsException.BookGone) {
            flow.value = NarrationState.Idle
            return JobResult.BookGone
        } catch (e: TtsException) {
            flow.value = NarrationState.Failed(bookId, e.message.orEmpty())
            return JobResult.Failed(e, retry = e.retryable)
        }
        return if (capHit.get()) {
            flow.value = NarrationState.Paused(bookId, stopReason)
            JobResult.Paused
        } else {
            flow.value = NarrationState.Done(bookId)
            JobResult.Done
        }
    }

    /**
     * Called when the system stops the job (Android 15+ foreground-service timeout, OEM kill).
     * When [resumeAfterSeconds] is set the job is queued again; finished segments are not redone.
     */
    fun onJobInterrupted(bookId: BookId, config: NarrationConfig, fromOrdinal: Int, reason: String, resumeAfterSeconds: Long?) {
        stateFlow(bookId).value = NarrationState.Paused(bookId, reason)
        if (resumeAfterSeconds != null) {
            (scheduler ?: WorkManagerScheduler(appContext)).enqueue(bookId, config, fromOrdinal, resumeAfterSeconds)
        }
    }

    // ---------------------------------------------------------------- internals

    private fun stateFlow(bookId: BookId) = states.getOrPut(bookId) { MutableStateFlow(NarrationState.Idle) }

    private fun provider(id: String): TtsProvider =
        providers[id] ?: throw TtsException.Engine("Unknown narration provider: $id")

    private fun work(bookId: BookId, voice: VoiceRef) =
        works.getOrPut("$bookId|${voice.provider}|${voice.id}") { VoiceWork() }

    /** Ordinals in included sections; excluded front and back matter is never narrated. */
    private suspend fun narrated(bookId: BookId, count: Int): BitSet {
        val bits = BitSet(count)
        val included = spine.sections(bookId).filter { it.included && it.startOrdinal <= it.endOrdinal }
        if (included.isEmpty()) {
            bits.set(0, count)
        } else {
            included.forEach { bits.set(it.startOrdinal.coerceIn(0, count), (it.endOrdinal + 1).coerceIn(0, count)) }
        }
        return bits
    }

    private suspend fun covered(bookId: BookId, voice: VoiceRef): BitSet {
        val set = BitSet()
        audio.segments(bookId).first()
            .filter { it.voice.provider == voice.provider && it.voice.id == voice.id }
            .forEach { set.set(it.startOrdinal, it.endOrdinal + 1) }
        return set
    }

    private fun claim(work: VoiceWork, sentences: List<Sentence>, covered: BitSet, micro: Int): List<Chunk> =
        synchronized(work) {
            val chunks = Chunker.plan(sentences, { covered[it] || work.claimed[it] }, Chunker.MAX_CHARS, micro)
            chunks.forEach { work.claimed.set(it.startOrdinal, it.endOrdinal + 1) }
            chunks
        }

    private fun bookScope(bookId: BookId): CoroutineScope = bookScopes.compute(bookId) { _, cur ->
        cur?.takeIf { it.isActive } ?: CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    }!!

    /**
     * Creates the background renders and registers them as in flight right away (so nobody can miss them),
     * but does not start them yet.
     */
    private fun prepare(
        bookId: BookId, work: VoiceWork, chunks: List<Chunk>, provider: TtsProvider, config: NarrationConfig,
    ): List<Pair<Chunk, Deferred<AudioSegment>>> = chunks.map { chunk ->
        val range = chunk.startOrdinal..chunk.endOrdinal
        lateinit var job: Deferred<AudioSegment>
        job = bookScope(bookId).async(start = CoroutineStart.LAZY) {
            try {
                renderTracked(bookId, work, chunk, provider, config)
            } finally {
                work.inFlight.remove(range, job)
            }
        }
        work.inFlight[range] = job
        chunk to job
    }

    private fun launchAll(
        bookId: BookId, work: VoiceWork, chunks: List<Chunk>, provider: TtsProvider, config: NarrationConfig,
    ) = prepare(bookId, work, chunks, provider, config).onEach { it.second.start() }

    /**
     * Starts the chunk that holds [ordinal] first and the rest only once it is done, so the chunk the listener is
     * waiting for is always the first to claim budget and a connection slot. Returns the first chunk's job.
     */
    private fun launchStaged(
        bookId: BookId, work: VoiceWork, chunks: List<Chunk>, ordinal: Int, provider: TtsProvider, config: NarrationConfig,
    ): List<Pair<Chunk, Deferred<AudioSegment>>> {
        val (first, rest) = chunks.partition { ordinal in it.startOrdinal..it.endOrdinal }
        val started = launchAll(bookId, work, first, provider, config)
        val later = prepare(bookId, work, rest, provider, config)
        if (later.isNotEmpty()) {
            bookScope(bookId).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    started.forEach { it.second.join() }
                    later.forEach { it.second.start() }
                } catch (e: Throwable) {
                    // Never leave claimed sentences that nobody will render.
                    later.forEach { (chunk, job) ->
                        job.cancel()
                        work.inFlight.remove(chunk.startOrdinal..chunk.endOrdinal, job)
                        work.release(chunk)
                    }
                    throw e
                }
            }
        }
        return started
    }

    private suspend fun renderTracked(
        bookId: BookId, work: VoiceWork, chunk: Chunk, provider: TtsProvider, config: NarrationConfig,
    ): AudioSegment {
        try {
            val outcome = renderer.render(bookId, chunk, provider, config.voice, SYNTH_SPEED)
            if (outcome.requested) {
                meter(provider).record(outcome.segment.durationMs, outcome.wallMs)
                prefs.edit().putFloat(TP_PREFIX + provider.id, meter(provider).value.toFloat()).apply()
            }
            return outcome.segment
        } catch (e: NarrationBlockedException) {
            stateFlow(bookId).value = NarrationState.Paused(bookId, e.reason.code)
            throw e
        } finally {
            // Once rendered the repository marks these sentences as covered, so the claim can go.
            work.release(chunk)
        }
    }

    private fun meter(provider: TtsProvider) = throughput.getOrPut(provider.id) {
        val default = if (provider.capabilities.sentenceAccurate) 8.0 else 4.0
        ThroughputEwma(prefs.getFloat(TP_PREFIX + provider.id, default.toFloat()).toDouble())
    }

    private fun aggregateThroughput(provider: TtsProvider) = meter(provider).value * provider.capabilities.concurrency

    // ---------------------------------------------------------------- NarrationControls

    override fun paidSwitchStatus(): PaidSwitchStatus = paidSwitchStatusAt(clock.instant())

    override fun spentThisMonthCents(): Double = ledger.spentThisMonthCents()

    override fun spentOnBookCents(bookId: BookId): Double = ledger.spentOnBookCents(bookId)

    /** Requests the service accepted this (UTC) month, free ones included. */
    fun requestsThisMonth(): Int = ledger.requestsThisMonth()

    /** True once the user accepted, after the free model ended, that generation now bills the paid model. */
    override fun paidGenerationConfirmed(): Boolean = ledger.paidConfirmed()

    /** Called by the UI after the user accepts the paid-model prompt. Until then paid generation stays paused. */
    override fun confirmPaidGeneration() = ledger.confirm()

    override suspend fun effectiveMonthlyCapCents(): Int =
        settings.settings.first().monthlyCapCents.takeIf { it > 0 } ?: NarrationLimits.DEFAULT_MONTHLY_CAP_CENTS

    override fun perBookCapCents(): Int = NarrationLimits.PER_BOOK_CAP_CENTS

    internal class VoiceWork {
        val claimed = BitSet()
        val inFlight = ConcurrentHashMap<IntRange, Deferred<AudioSegment>>()

        fun inFlightCovering(ordinal: Int): Deferred<AudioSegment>? =
            inFlight.entries.firstOrNull { ordinal in it.key }?.value

        fun inFlightOverlapping(from: Int, to: Int): List<Deferred<AudioSegment>> =
            inFlight.entries.filter { it.key.first <= to && it.key.last >= from }.map { it.value }

        fun release(chunk: Chunk) = synchronized(this) { claimed.clear(chunk.startOrdinal, chunk.endOrdinal + 1) }
    }

    companion object {
        /** Audio is always rendered at 1.0x; playback speed is applied only by the player. */
        const val SYNTH_SPEED = 1f
        const val MICRO_SENTENCES = 2
        // Pause reason codes; see [PauseReason] for the typed form.
        const val REASON_CAP = "monthly_cap"
        const val REASON_BOOK_CAP = "book_cap"
        const val REASON_PAID_CONFIRM = "paid_confirm"
        const val DEFAULT_MONTHLY_CAP_CENTS = NarrationLimits.DEFAULT_MONTHLY_CAP_CENTS
        const val PER_BOOK_CAP_CENTS = 300.0
        const val REASON_SYSTEM = "system_stop"
        const val REASON_OFFLINE = "offline"
        private const val PREFS = "narration_engine"
        private const val TP_PREFIX = "tp_"
        private const val PAGE = 500
        private const val MAX_PAGE_ROUNDS = 4
        private const val JIT_WAIT_MS = 120_000L

        @Volatile
        internal var instance: NarrationEngine? = null

        internal fun jobOrder(from: Int, count: Int): List<IntRange> {
            val start = from.coerceIn(0, count)
            return listOf(start until count, 0 until start).filter { !it.isEmpty() }
        }

        internal fun pages(range: IntRange): List<Pair<Int, Int>> =
            (range.first..range.last step PAGE).map { it to minOf(range.last, it + PAGE - 1) }
    }
}
