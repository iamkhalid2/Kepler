package app.kepler.engine.ai

import app.kepler.core.model.AiBudget
import app.kepler.core.model.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one logical LLM call: picks the model, spends budget, requests JSON (schema, JSON mode or JSON-in-prompt
 * depending on what the model supports), validates it, and does ONE repair retry on a bad reply.
 * A model that is gone, overloaded or refuses the request is skipped and the next candidate is tried.
 * Candidates are ordered by measured first-token latency when a [ModelProber] is present.
 * Interactive text is streamed: 8 s to the first token, 30 s in total, then the next candidate at once.
 */
internal class LlmRunner(
    private val client: OpenRouterClient,
    private val catalog: ModelCatalog,
    private val budgeter: RequestBudgeter,
    private val settings: SettingsStore,
    private val maxModelAttempts: Int = 3,
    private val prober: ModelProber? = null,
    private val limits: StreamLimits = StreamLimits(),
) {
    @Volatile var lastModelUsed: String? = null
        private set

    private suspend fun apiKey(): String = settings.openRouterKey()?.takeIf { it.isNotBlank() } ?: throw AiError.MissingKey()

    private suspend fun candidates(key: String, interactive: Boolean = false): List<ModelInfo> {
        catalog.keyInfo(key)?.let { budgeter.setTier(it.isFreeTier) }
        val override = settings.settings.first().openRouterModel
        val list = catalog.candidates(key, override, interactive)
        // An explicit model choice is never reordered or dropped.
        if (prober == null || !override.isNullOrBlank()) return list
        return prober.order(key, list, awaitFirstUse = interactive)
    }

    /** Refreshes the latency numbers in the background if they are older than a day. No-op without a key. */
    suspend fun warmUp() {
        val key = settings.openRouterKey()?.takeIf { it.isNotBlank() } ?: return
        if (prober == null || !settings.settings.first().openRouterModel.isNullOrBlank()) return
        val all = runCatching { catalog.all(key) }.getOrNull() ?: return
        prober.warmUp(key, all)
    }

    suspend fun budget(): AiBudget {
        val key = settings.openRouterKey()?.takeIf { it.isNotBlank() }
        // Only reads the catalog: showing the budget must not start a latency probe.
        val model = lastModelUsed ?: if (key == null) null else runCatching {
            catalog.keyInfo(key)?.let { budgeter.setTier(it.isFreeTier) }
            catalog.candidates(key, settings.settings.first().openRouterModel, interactive = true).firstOrNull()?.id
        }.getOrNull()
        return budgeter.budget(model)
    }

    suspend fun <T> json(priority: Priority, spec: JsonSpec<T>, system: String, user: String, maxTokens: Int = 2000): T =
        attempt(priority) { model, key ->
            val format = when {
                model.supportsStructured -> ResponseFormat.Schema(spec.name, spec.schema)
                model.supportsResponseFormat -> ResponseFormat.JsonObject
                else -> ResponseFormat.Text
            }
            val sys = if (format is ResponseFormat.Schema) system else system + "\n\n" + jsonInstruction(spec)
            val messages = mutableListOf(ChatMessage("system", sys), ChatMessage("user", user))
            val first = chatTimed(priority, key, model, messages, format, maxTokens)
            val (value, problem) = parseReply(first.content, spec)
            if (value != null) return@attempt value
            // One repair retry: show the model its own reply and what was wrong with it.
            messages += ChatMessage("assistant", first.content.orEmpty().take(6000))
            messages += ChatMessage(
                "user",
                "That reply was not usable: $problem. Reply again with ONLY one valid JSON object that follows the " +
                    "required structure exactly, with no commentary and no code fences.",
            )
            budgeter.acquire(priority)
            val second = chatTimed(priority, key, model, messages, format, maxTokens)
            val (fixed, problem2) = parseReply(second.content, spec)
            fixed ?: throw AiError.InvalidResponse(problem2 ?: "unusable reply")
        }

    /** A non-streamed request with a deadline: 30 s when the user is waiting, 60 s for background work. */
    private suspend fun chatTimed(
        priority: Priority, key: String, model: ModelInfo, messages: List<ChatMessage>, format: ResponseFormat, maxTokens: Int,
    ): ChatResult {
        val limit = if (priority == Priority.INTERACTIVE) limits.totalMs else BACKGROUND_TIMEOUT_MS
        return withTimeoutOrNull(limit) {
            client.chat(key, model.id, messages, format, maxTokens, reasoning = model.reasoningRequest())
        } ?: throw AiError.ModelUnavailable("no reply within ${limit / 1000} s")
    }

    suspend fun text(priority: Priority, system: String, user: String, maxTokens: Int = 700): String {
        val out = textStream(priority, system, user, maxTokens).toList().joinToString("").trim()
        if (out.isEmpty()) throw AiError.InvalidResponse("empty reply")
        return out
    }

    /**
     * Streams a plain-text answer. The first candidate that produces a token wins; one that errors, is rate limited or
     * stays silent for the first-token limit is skipped at once. After the first token a failure is thrown (the
     * caller already showed text), except the total time limit, which just ends the stream with what arrived and
     * clears [outcome]'s complete flag so the caller does not cache the cut answer.
     */
    fun textStream(
        priority: Priority, system: String, user: String, maxTokens: Int = 700, outcome: StreamOutcome? = null,
    ): Flow<String> = flow {
        val key = apiKey()
        val models = candidates(key, interactive = true).take(maxModelAttempts)
        // One logical request is charged once; a model that answered 429 or never connected is not charged again.
        budgeter.acquire(priority)
        var last: AiError? = null
        for (m in models) {
            val started = System.nanoTime()
            val strip = ThinkStripper()
            var emitted = false
            outcome?.complete = true
            try {
                client.chatStream(
                    key, m.id, listOf(ChatMessage("system", system), ChatMessage("user", user)),
                    maxTokens, temperature = 0.4, reasoning = m.reasoningRequest(), limits = limits, outcome = outcome,
                ).collect { raw ->
                    val piece = strip.feed(raw)
                    if (piece.isEmpty()) return@collect
                    // The model's own ttft: request start to first visible character.
                    val lead = if (emitted) piece else piece.trimStart()
                    if (lead.isEmpty()) return@collect
                    if (!emitted) { emitted = true; prober?.recordSuccess(m.id, (System.nanoTime() - started) / 1_000_000) }
                    emit(lead)
                }
                val tail = strip.finish().let { if (emitted) it else it.trimStart() }
                if (tail.isNotEmpty()) { emitted = true; emit(tail) }
                if (!emitted) throw AiError.InvalidResponse("empty reply")
                lastModelUsed = m.id
                return@flow
            } catch (e: FirstTokenTimeout) {
                skip(m, "slow", 3 * 60_000L)
                last = AiError.ModelUnavailable("${m.id} gave no answer in ${limits.firstTokenMs / 1000} s")
            } catch (e: AiError) {
                if (emitted) throw e
                when (e) {
                    is AiError.ModelUnavailable -> { skip(m, "unavailable"); last = e }
                    is AiError.InvalidResponse -> { skip(m, "invalid", 2 * 60_000L); last = e }
                    is AiError.RateLimited -> { skip(m, "429"); last = e }
                    is AiError.BudgetExhausted -> {
                        if (e.dailyLimit == 0) { budgeter.markExhausted(); throw AiError.BudgetExhausted(budgeter.dailyLimit) }
                        throw e
                    }
                    else -> throw e
                }
            }
        }
        throw last ?: AiError.ModelUnavailable("no model available")
    }

    private suspend fun skip(m: ModelInfo, reason: String, forMs: Long = 10 * 60_000L) {
        catalog.markFailing(m.id, forMs)
        prober?.recordFailure(m.id, reason)
    }

    private suspend fun <R> attempt(priority: Priority, block: suspend (ModelInfo, String) -> R): R {
        val key = apiKey()
        val models = candidates(key).take(maxModelAttempts)
        // One logical request is charged once; falling back to the next model after a failure is not charged again.
        budgeter.acquire(priority)
        var last: AiError? = null
        for (m in models) {
            try {
                return block(m, key).also { lastModelUsed = m.id }
            } catch (e: AiError.ModelUnavailable) {
                catalog.markFailing(m.id); last = e
            } catch (e: AiError.InvalidResponse) {
                catalog.markFailing(m.id, 2 * 60_000L); last = e
            } catch (e: AiError.RateLimited) {
                catalog.markFailing(m.id); last = e
            } catch (e: AiError.BudgetExhausted) {
                // A 429 naming the daily quota arrives with limit 0; the local budgeter knows the real one.
                if (e.dailyLimit == 0) { budgeter.markExhausted(); throw AiError.BudgetExhausted(budgeter.dailyLimit) }
                throw e
            }
        }
        throw last ?: AiError.ModelUnavailable("no model available")
    }

    private fun jsonInstruction(spec: JsonSpec<*>) =
        "Reply with ONLY one JSON object (no code fences, no commentary) that follows this JSON schema exactly:\n" +
            AiJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), spec.schema)

    private companion object {
        const val BACKGROUND_TIMEOUT_MS = 60_000L
    }
}
