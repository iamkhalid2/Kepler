package app.betterbooks.engine.tts

import android.content.Context
import android.content.SharedPreferences
import app.betterbooks.core.model.AudioRepository
import app.betterbooks.core.model.AudioSegment
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.NarrationConfig
import app.betterbooks.core.model.NarrationEstimate
import app.betterbooks.core.model.NarrationService
import app.betterbooks.core.model.NarrationState
import app.betterbooks.core.model.Sentence
import app.betterbooks.core.model.SettingsStore
import app.betterbooks.core.model.SpineRepository
import app.betterbooks.core.model.VoiceOption
import app.betterbooks.core.model.VoiceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
) : NarrationService {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scratch = File(appContext.cacheDir, "tts-scratch")

    private val providers: Map<String, TtsProvider> = (
        providers ?: listOf(
            FishProvider({ settings.fishKey() }, ModelPolicy(clock), concurrency = fishConcurrency),
            SystemTtsProvider(appContext, scratch),
        )
        ).associateBy { it.id }

    private val renderer = SegmentRenderer(File(appContext.filesDir, "audio"), scratch, transcoder, audio)
    private val states = ConcurrentHashMap<BookId, MutableStateFlow<NarrationState>>()
    private val works = ConcurrentHashMap<String, VoiceWork>()
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
        val chunks = ArrayList<Chunk>()
        for (range in jobOrder(fromOrdinal, count)) {
            pages(range).forEach { (a, b) ->
                chunks += Chunker.plan(spine.sentences(bookId, a, b), { covered[it] }, provider.capabilities.maxChars)
            }
        }
        return Estimator.estimate(chunks, provider.centsPerByte(), config.speed, aggregateThroughput(provider))
    }

    override fun start(bookId: BookId, config: NarrationConfig, fromOrdinal: Int) {
        stateFlow(bookId).value = NarrationState.Running(bookId, 0, 0, 0)
        (scheduler ?: WorkManagerScheduler(appContext)).enqueue(bookId, config, fromOrdinal)
    }

    override fun stop(bookId: BookId) {
        (scheduler ?: WorkManagerScheduler(appContext)).cancel(bookId)
        stateFlow(bookId).value = NarrationState.Idle
    }

    override suspend fun ensureAhead(bookId: BookId, config: NarrationConfig, ordinal: Int, minutes: Int) {
        val provider = provider(config.voice.provider)
        if (overCap(provider, 0.0)) {
            stateFlow(bookId).value = NarrationState.Paused(bookId, REASON_CAP)
            return
        }
        val work = work(bookId, config.voice)
        val count = spine.sentenceCount(bookId)
        if (ordinal !in 0 until count) return

        val window = ArrayList<Sentence>()
        var seconds = 0.0
        var from = ordinal
        val target = minutes.coerceAtLeast(1) * 60.0
        while (from < count && seconds < target) {
            val page = spine.sentences(bookId, from, minOf(count - 1, from + 199))
            if (page.isEmpty()) break
            for (s in page) {
                if (seconds >= target) break
                window += s
                seconds += Estimator.audioSeconds(s.text, config.speed)
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
        val launched = launchAll(bookId, work, claim(work, window, covered, MICRO_SENTENCES), provider, config)
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
        account(provider, text.toByteArray(Charsets.UTF_8).size.toLong())
        return out.absolutePath
    }

    // ---------------------------------------------------------------- background job

    sealed interface JobResult {
        data object Done : JobResult
        data object Paused : JobResult
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
        val count = spine.sentenceCount(bookId)
        val covered = covered(bookId, config.voice)
        val done = AtomicInteger(covered.cardinality())
        val tp = aggregateThroughput(provider)
        val capHit = AtomicBoolean(false)
        var remainingSeconds = 0.0

        fun publish() {
            val eta = if (tp > 0) (remainingSeconds / tp).roundToLong() else 0
            flow.value = NarrationState.Running(bookId, done.get().coerceAtMost(count), count, eta)
        }

        try {
            for (range in jobOrder(fromOrdinal, count)) {
                for ((a, b) in pages(range)) {
                    if (capHit.get()) break
                    val chunks = claim(work, spine.sentences(bookId, a, b), covered(bookId, config.voice), 0)
                    remainingSeconds = chunks.sumOf { Estimator.audioSeconds(it.text, config.speed) }
                    publish()
                    val gate = Semaphore(provider.capabilities.concurrency)
                    coroutineScope {
                        chunks.map { chunk ->
                            async {
                                gate.withPermit {
                                    val cost = chunk.text.toByteArray(Charsets.UTF_8).size * provider.centsPerByte()
                                    if (capHit.get() || overCap(provider, cost)) {
                                        capHit.set(true)
                                        work.release(chunk)
                                        return@withPermit
                                    }
                                    renderTracked(bookId, work, chunk, provider, config)
                                    done.addAndGet(chunk.sentences.size)
                                    remainingSeconds -= Estimator.audioSeconds(chunk.text, config.speed)
                                    publish()
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        } catch (e: TtsException) {
            flow.value = NarrationState.Failed(bookId, e.message.orEmpty())
            return JobResult.Failed(e, retry = e.retryable)
        }
        return if (capHit.get()) {
            flow.value = NarrationState.Paused(bookId, REASON_CAP)
            JobResult.Paused
        } else {
            flow.value = NarrationState.Done(bookId)
            JobResult.Done
        }
    }

    /** Called when the system stops the job (Android 15+ foreground-service timeout, OEM kill). */
    fun onJobInterrupted(bookId: BookId, reason: String) {
        stateFlow(bookId).value = NarrationState.Paused(bookId, reason)
    }

    // ---------------------------------------------------------------- internals

    private fun stateFlow(bookId: BookId) = states.getOrPut(bookId) { MutableStateFlow(NarrationState.Idle) }

    private fun provider(id: String): TtsProvider =
        providers[id] ?: throw TtsException.Engine("Unknown narration provider: $id")

    private fun work(bookId: BookId, voice: VoiceRef) =
        works.getOrPut("$bookId|${voice.provider}|${voice.id}") { VoiceWork() }

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

    private fun launchAll(
        bookId: BookId, work: VoiceWork, chunks: List<Chunk>, provider: TtsProvider, config: NarrationConfig,
    ): List<Pair<Chunk, Deferred<AudioSegment>>> = chunks.map { chunk ->
        chunk to scope.async { renderTracked(bookId, work, chunk, provider, config) }
    }

    private suspend fun renderTracked(
        bookId: BookId, work: VoiceWork, chunk: Chunk, provider: TtsProvider, config: NarrationConfig,
    ): AudioSegment {
        val self = kotlinx.coroutines.CompletableDeferred<AudioSegment>()
        work.inFlight[chunk.startOrdinal..chunk.endOrdinal] = self
        try {
            val outcome = renderer.render(bookId, chunk, provider, config.voice, config.speed)
            if (outcome.requested) {
                account(provider, outcome.utf8Bytes)
                meter(provider).record(outcome.segment.durationMs, outcome.wallMs)
                prefs.edit().putFloat(TP_PREFIX + provider.id, meter(provider).value.toFloat()).apply()
            }
            self.complete(outcome.segment)
            return outcome.segment
        } catch (e: Throwable) {
            work.release(chunk)
            self.completeExceptionally(e)
            throw e
        } finally {
            work.inFlight.remove(chunk.startOrdinal..chunk.endOrdinal)
        }
    }

    private fun meter(provider: TtsProvider) = throughput.getOrPut(provider.id) {
        val default = if (provider.capabilities.sentenceAccurate) 8.0 else 4.0
        ThroughputEwma(prefs.getFloat(TP_PREFIX + provider.id, default.toFloat()).toDouble())
    }

    private fun aggregateThroughput(provider: TtsProvider) = meter(provider).value * provider.capabilities.concurrency

    // Monthly spend, in micro-cents, keyed by UTC month.
    private fun monthKey() = SPEND_PREFIX + DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC).format(clock.instant())

    fun spentThisMonthCents(): Double = prefs.getLong(monthKey(), 0) / 1000.0

    private fun account(provider: TtsProvider, bytes: Long) {
        val cents = bytes * provider.centsPerByte()
        if (cents <= 0) return
        synchronized(prefs) {
            val key = monthKey()
            prefs.edit().putLong(key, prefs.getLong(key, 0) + (cents * 1000).roundToLong()).apply()
        }
    }

    private suspend fun overCap(provider: TtsProvider, nextCents: Double): Boolean {
        if (provider.centsPerByte() <= 0) return false
        val cap = settings.settings.first().monthlyCapCents
        return cap > 0 && spentThisMonthCents() + nextCents > cap
    }

    internal class VoiceWork {
        val claimed = BitSet()
        val inFlight = ConcurrentHashMap<IntRange, Deferred<AudioSegment>>()

        fun inFlightCovering(ordinal: Int): Deferred<AudioSegment>? =
            inFlight.entries.firstOrNull { ordinal in it.key }?.value

        fun release(chunk: Chunk) = synchronized(this) { claimed.clear(chunk.startOrdinal, chunk.endOrdinal + 1) }
    }

    companion object {
        const val MICRO_SENTENCES = 2
        const val REASON_CAP = "monthly_cap"
        const val REASON_SYSTEM = "system_stop"
        private const val PREFS = "narration_engine"
        private const val TP_PREFIX = "tp_"
        private const val SPEND_PREFIX = "spend_"
        private const val PAGE = 500
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
