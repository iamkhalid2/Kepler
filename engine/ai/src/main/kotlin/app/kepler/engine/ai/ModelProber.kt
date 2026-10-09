package app.kepler.engine.ai

import app.kepler.core.model.AiStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
internal class ProbeStat(
    /** Most recent time-to-first-token samples in ms, newest last (probes and real requests). */
    val samples: List<Long> = emptyList(),
    /** When the model last failed, 0 = it has not failed since its last success. */
    val failedAt: Long = 0,
    val failure: String = "",
)

@Serializable
internal class ProbeState(val lastProbeAt: Long = 0, val models: Map<String, ProbeStat> = emptyMap())

/**
 * Measures how fast each free model starts answering and orders candidates by it. The free roster changes weekly and
 * size says nothing about latency (a 1M-context model took 15 s to the first token, a 120B one 0.7 s), so we ask:
 * a tiny streamed prompt to a handful of free models, at most once per 24 h and on first use, p50 of the last
 * samples persisted in [AiStore]. Real requests feed the same numbers. A probe costs about six of the 50 daily
 * free requests and goes through the [RequestBudgeter] like everything else.
 */
class ModelProber internal constructor(
    private val client: OpenRouterClient,
    private val store: AiStore,
    private val budgeter: RequestBudgeter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val probeCount: Int = 6,
    /** Fastest already-measured models re-probed every refresh; the other slots explore unmeasured ones. */
    private val keepMeasured: Int = 3,
    private val refreshMs: Long = 24 * 3_600_000L,
    private val failureTtlMs: Long = 3 * 3_600_000L,
    private val probeFirstTokenMs: Long = 8_000,
    /** On first use an Ask waits at most this long for the first probe to answer. */
    private val firstUseWaitMs: Long = 6_000,
    private val maxSamples: Int = 5,
) {
    private val mutex = Mutex()
    private var state: ProbeState? = null
    private var probing: Boolean = false

    /** p50 time to first token in ms, or null while unmeasured. */
    suspend fun p50(modelId: String): Long? = mutex.withLock { load().models[modelId]?.takeIf { it.samples.isNotEmpty() }?.let(::median) }

    /** Why each recently failed model was dropped, for diagnostics. */
    suspend fun failures(): Map<String, String> = mutex.withLock {
        load().models.filterValues { it.failedAt > 0 }.mapValues { it.value.failure }
    }

    /** Measured p50 per model id (ms), for diagnostics and tests. */
    suspend fun measurements(): Map<String, Long> = mutex.withLock {
        load().models.filterValues { it.samples.isNotEmpty() }.mapValues { median(it.value) }
    }

    /**
     * [models] in their preferred order, reordered: measured models by p50 TTFT, then unmeasured ones (preferred order
     * kept), with models that failed recently dropped. Starts a probe when none ran in the last 24 h; when nothing
     * is measured yet and [awaitFirstUse] is set, waits briefly for the first probe so the first request is fast.
     */
    suspend fun order(apiKey: String, models: List<ModelInfo>, awaitFirstUse: Boolean): List<ModelInfo> {
        val pending = ensureFresh(apiKey, models, awaitFirstUse)
        if (pending != null) withTimeoutOrNull(firstUseWaitMs) { pending.await() }
        return mutex.withLock { sorted(models, load()) }
    }

    /** Starts a background probe when the last one is older than 24 h (or never ran). Returns at once. */
    fun warmUp(apiKey: String, models: List<ModelInfo>) {
        scope.launch { ensureFresh(apiKey, models, false) }
    }

    /** Waits for the full probe round to finish (tests and the live check). */
    suspend fun probeNow(apiKey: String, models: List<ModelInfo>) {
        mutex.withLock { probing = true }
        runProbe(apiKey, ModelRanker.eligible(models, ModelRanker.MIN_INTERACTIVE_CONTEXT), CompletableDeferred())
        mutex.withLock { probing = false }
    }

    /** A deferred that completes on the first good probe answer (or when the round ends), only when this was a first-ever probe. */
    private suspend fun ensureFresh(apiKey: String, models: List<ModelInfo>, awaitFirstUse: Boolean): CompletableDeferred<Unit>? {
        val (due, firstEver) = mutex.withLock {
            val s = load()
            val due = !probing && (s.lastProbeAt == 0L || clock() - s.lastProbeAt >= refreshMs)
            if (due) probing = true
            due to s.models.values.none { it.samples.isNotEmpty() }
        }
        if (!due) return null
        val pool = ModelRanker.eligible(models, ModelRanker.MIN_INTERACTIVE_CONTEXT)
        val firstOk = CompletableDeferred<Unit>()
        scope.launch {
            try { runProbe(apiKey, pool, firstOk) } finally {
                mutex.withLock { probing = false }
                firstOk.complete(Unit)
            }
        }
        return if (firstEver && awaitFirstUse) firstOk else null
    }

    private suspend fun runProbe(apiKey: String, pool: List<ModelInfo>, firstOk: CompletableDeferred<Unit>) {
        val targets = mutex.withLock { pickTargets(pool, load()) }
        if (targets.isEmpty()) return
        var aborted = false
        coroutineScope {
            targets.map { m ->
                async {
                    try {
                        budgeter.acquire(Priority.INTERACTIVE)
                        val t0 = System.nanoTime()
                        val first = client.chatStream(
                            apiKey, m.id,
                            listOf(ChatMessage("system", "Reply with one short sentence."), ChatMessage("user", "Say hello.")),
                            maxTokens = 24, temperature = 0.0, reasoning = m.reasoningRequest(),
                            limits = StreamLimits(probeFirstTokenMs, probeFirstTokenMs + 4_000),
                        ).firstOrNull { it.isNotBlank() }
                        if (first == null) recordFailure(m.id, "empty") else {
                            recordSuccess(m.id, (System.nanoTime() - t0) / 1_000_000)
                            firstOk.complete(Unit)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: FirstTokenTimeout) {
                        recordFailure(m.id, "slow")
                    } catch (e: AiError.ModelUnavailable) {
                        recordFailure(m.id, "unavailable")
                    } catch (e: AiError.RateLimited) {
                        recordFailure(m.id, "429")
                    } catch (e: AiError.InvalidResponse) {
                        recordFailure(m.id, "invalid")
                    } catch (e: AiError.BudgetExhausted) {
                        // Limit 0 = the provider's own per-model or per-day 429, not our budget running out.
                        if (e.dailyLimit == 0) recordFailure(m.id, "429-daily") else aborted = true
                    } catch (e: AiError) {
                        // Offline, bad key, spent budget: not the model's fault, and no reason to wait a day to retry.
                        aborted = true
                    }
                }
            }.awaitAll()
        }
        if (!aborted) mutex.withLock { state = ProbeState(clock(), load().models); save() }
    }

    /** The fastest known models (to keep their numbers fresh) plus unmeasured ones, skipping recent failures. */
    private fun pickTargets(pool: List<ModelInfo>, s: ProbeState): List<ModelInfo> {
        val live = pool.filter { !failedRecently(s.models[it.id]) }
        val measured = live.filter { s.models[it.id]?.samples?.isNotEmpty() == true }.sortedBy { median(s.models.getValue(it.id)) }
        val unmeasured = live.filter { s.models[it.id]?.samples.isNullOrEmpty() }
        val keep = measured.take(keepMeasured)
        val explore = unmeasured.take(probeCount - keep.size)
        val fill = (measured - keep.toSet()).take((probeCount - keep.size - explore.size).coerceAtLeast(0))
        return keep + explore + fill
    }

    suspend fun recordSuccess(modelId: String, ttftMs: Long) = mutex.withLock {
        val s = load()
        val old = s.models[modelId] ?: ProbeStat()
        state = ProbeState(s.lastProbeAt, s.models + (modelId to ProbeStat((old.samples + ttftMs).takeLast(maxSamples))))
        save()
    }

    suspend fun recordFailure(modelId: String, reason: String) = mutex.withLock {
        val s = load()
        val old = s.models[modelId] ?: ProbeStat()
        state = ProbeState(s.lastProbeAt, s.models + (modelId to ProbeStat(old.samples, clock(), reason)))
        save()
    }

    private fun failedRecently(stat: ProbeStat?) = stat != null && stat.failedAt > 0 && clock() - stat.failedAt < failureTtlMs

    private fun sorted(models: List<ModelInfo>, s: ProbeState): List<ModelInfo> {
        val alive = models.filter { !failedRecently(s.models[it.id]) }
        if (alive.isEmpty()) return models
        val (measured, unmeasured) = alive.partition { s.models[it.id]?.samples?.isNotEmpty() == true }
        return measured.sortedBy { median(s.models.getValue(it.id)) } + unmeasured
    }

    private fun median(stat: ProbeStat): Long {
        val v = stat.samples.sorted()
        return if (v.isEmpty()) Long.MAX_VALUE else v[v.size / 2]
    }

    private suspend fun load(): ProbeState {
        state?.let { return it }
        val loaded = runCatching {
            store.get(KIND, KEY)?.let { AiJson.decodeFromString(ProbeState.serializer(), it) }
        }.getOrNull() ?: ProbeState()
        state = loaded
        return loaded
    }

    private suspend fun save() {
        val s = state ?: return
        runCatching { store.put(KIND, KEY, AiJson.encodeToString(ProbeState.serializer(), s)) }
    }

    private companion object {
        const val KIND = "model-probe"
        const val KEY = "v1"
    }
}
