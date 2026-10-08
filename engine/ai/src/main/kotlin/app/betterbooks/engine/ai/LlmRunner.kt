package app.betterbooks.engine.ai

import app.betterbooks.core.model.AiBudget
import app.betterbooks.core.model.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Runs one logical LLM call: picks the model, spends budget, requests JSON (schema, JSON mode or JSON-in-prompt
 * depending on what the model supports), validates it, and does ONE repair retry on a bad reply.
 * A model that is gone or refuses the request is skipped and the next ranked candidate is tried.
 */
internal class LlmRunner(
    private val client: OpenRouterClient,
    private val catalog: ModelCatalog,
    private val budgeter: RequestBudgeter,
    private val settings: SettingsStore,
    private val maxModelAttempts: Int = 3,
) {
    @Volatile var lastModelUsed: String? = null
        private set

    private suspend fun apiKey(): String = settings.openRouterKey()?.takeIf { it.isNotBlank() } ?: throw AiError.MissingKey()

    private suspend fun candidates(key: String): List<ModelInfo> {
        catalog.keyInfo(key)?.let { budgeter.setTier(it.isFreeTier) }
        return catalog.candidates(key, settings.settings.first().openRouterModel)
    }

    suspend fun budget(): AiBudget {
        val key = settings.openRouterKey()?.takeIf { it.isNotBlank() }
        val model = if (key == null) null else runCatching { candidates(key).firstOrNull()?.id }.getOrNull()
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
            budgeter.acquire(priority)
            val first = client.chat(key, model.id, messages, format, maxTokens)
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
            val second = client.chat(key, model.id, messages, format, maxTokens)
            val (fixed, problem2) = parseReply(second.content, spec)
            fixed ?: throw AiError.InvalidResponse(problem2 ?: "unusable reply")
        }

    suspend fun text(priority: Priority, system: String, user: String, maxTokens: Int = 700): String =
        attempt(priority) { model, key ->
            budgeter.acquire(priority)
            val r = client.chat(
                key, model.id, listOf(ChatMessage("system", system), ChatMessage("user", user)),
                ResponseFormat.Text, maxTokens, temperature = 0.4,
            )
            val out = r.content?.replace(Regex("(?is)<think>.*?</think>"), "")?.trim().orEmpty()
            if (out.isEmpty()) throw AiError.InvalidResponse("empty reply")
            out
        }

    private suspend fun <R> attempt(priority: Priority, block: suspend (ModelInfo, String) -> R): R {
        val key = apiKey()
        val models = candidates(key).take(maxModelAttempts)
        var last: AiError? = null
        for (m in models) {
            try {
                return block(m, key).also { lastModelUsed = m.id }
            } catch (e: AiError.ModelUnavailable) {
                catalog.markFailing(m.id); last = e
            } catch (e: AiError.InvalidResponse) {
                catalog.markFailing(m.id, 2 * 60_000L); last = e
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
}
