package app.betterbooks.engine.ai

import app.betterbooks.core.model.AiStore
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bundled quality hints: a model id containing [match] gets [tier] (higher is better). Updated each release. */
object ModelHints {
    val default: List<Pair<String, Int>> = listOf(
        "gpt-oss-120b" to 5, "deepseek" to 5, "qwen3-235b" to 5, "qwen3-next" to 5, "kimi" to 5,
        "glm-4" to 4, "llama-3.3-70b" to 4, "gemma-3-27b" to 4, "gemma-4" to 4, "mistral-small" to 3,
        "nemotron" to 3, "gpt-oss-20b" to 3, "qwen3" to 3, "llama-4" to 3, "gemma" to 2, "llama" to 2,
    )
}

object ModelRanker {
    private val excluded = Regex("(?i)(guard|embed|image|audio|lyria|tts|moderation|safeguard|ocr)")
    const val MIN_CONTEXT = 64_000

    fun eligible(models: List<ModelInfo>): List<ModelInfo> = models.filter {
        it.free && it.textOutput && it.contextLength >= MIN_CONTEXT && !excluded.containsMatchIn(it.id) &&
            it.id != "openrouter/free"
    }

    /** Order: structured-output support, then context window bucket, then hint tier, then recency. */
    fun rank(models: List<ModelInfo>, hints: List<Pair<String, Int>> = ModelHints.default): List<ModelInfo> {
        fun json(m: ModelInfo) = if (m.supportsStructured) 2 else if (m.supportsResponseFormat) 1 else 0
        fun ctx(m: ModelInfo) = when {
            m.contextLength >= 256_000 -> 2
            m.contextLength >= 128_000 -> 1
            else -> 0
        }
        fun tier(m: ModelInfo) = hints.firstOrNull { m.id.contains(it.first, ignoreCase = true) }?.second ?: 0
        return eligible(models).sortedWith(
            compareByDescending<ModelInfo> { json(it) }
                .thenByDescending { ctx(it) }
                .thenByDescending { tier(it) }
                .thenByDescending { it.created }
                .thenBy { it.id },
        )
    }
}

@Serializable
private class StoredModels(val fetchedAt: Long, val models: List<StoredModel>)

@Serializable
private class StoredModel(
    val id: String, val name: String, val ctx: Int, val created: Long, val free: Boolean,
    val structured: Boolean, val responseFormat: Boolean, val vision: Boolean, val text: Boolean,
)

/** Fetches, filters and caches OpenRouter's model list. The free roster changes often, so nothing is hardcoded. */
class ModelCatalog(
    private val client: OpenRouterClient,
    private val store: AiStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 6 * 3_600_000L,
    private val hints: List<Pair<String, Int>> = ModelHints.default,
) {
    private val mutex = Mutex()
    private var cached: List<ModelInfo>? = null
    private var cachedAt = 0L
    private var keyInfo: KeyInfo? = null
    private var keyInfoAt = 0L
    private val failing = HashMap<String, Long>()

    suspend fun all(apiKey: String): List<ModelInfo> = mutex.withLock {
        val now = clock()
        cached?.let { if (now - cachedAt < ttlMs) return@withLock it }
        val fresh = try {
            client.models(apiKey).also { persist(it, now) }
        } catch (e: AiError) {
            // Offline or OpenRouter hiccup: fall back to the last persisted list.
            cached ?: loadPersisted() ?: throw e
        }
        cached = fresh; cachedAt = now
        fresh
    }

    /** Models to try, best first. A user-selected model overrides the ranking and is used alone. */
    suspend fun candidates(apiKey: String, override: String?): List<ModelInfo> {
        val all = try { all(apiKey) } catch (e: AiError) { if (override == null) throw e else emptyList() }
        if (!override.isNullOrBlank()) {
            return listOf(all.firstOrNull { it.id == override } ?: unknownModel(override))
        }
        val now = clock()
        val ranked = ModelRanker.rank(all, hints)
        val healthy = ranked.filter { (failing[it.id] ?: 0L) < now }
        val list = healthy.ifEmpty { ranked }
        // Last resort: OpenRouter's own free router.
        return list.ifEmpty { listOf(unknownModel("openrouter/free")) }
    }

    /** Briefly skip a model that just failed so the next request does not hit it again. */
    fun markFailing(id: String, forMs: Long = 10 * 60_000L) { failing[id] = clock() + forMs }

    suspend fun keyInfo(apiKey: String): KeyInfo? = mutex.withLock {
        val now = clock()
        keyInfo?.let { if (now - keyInfoAt < 3_600_000L) return@withLock it }
        try { client.keyInfo(apiKey).also { keyInfo = it; keyInfoAt = now } } catch (e: AiError) { keyInfo }
    }

    private fun unknownModel(id: String) = ModelInfo(id, id, 0, 0, free = id.endsWith(":free") || id == "openrouter/free",
        supportsStructured = false, supportsResponseFormat = false, vision = false)

    private suspend fun persist(models: List<ModelInfo>, now: Long) {
        val stored = StoredModels(now, models.map {
            StoredModel(it.id, it.name, it.contextLength, it.created, it.free, it.supportsStructured,
                it.supportsResponseFormat, it.vision, it.textOutput)
        })
        runCatching { store.put("models", "openrouter", AiJson.encodeToString(StoredModels.serializer(), stored)) }
    }

    private suspend fun loadPersisted(): List<ModelInfo>? = runCatching {
        val raw = store.get("models", "openrouter") ?: return null
        AiJson.decodeFromString(StoredModels.serializer(), raw).models.map {
            ModelInfo(it.id, it.name, it.ctx, it.created, it.free, it.structured, it.responseFormat, it.vision, it.text)
        }
    }.getOrNull()
}
