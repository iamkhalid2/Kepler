package app.kepler.engine.ai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class KeyInfo(val isFreeTier: Boolean, val label: String?, val usage: Double?, val limit: Double?)

data class ModelInfo(
    val id: String,
    val name: String,
    val contextLength: Int,
    val created: Long,
    val free: Boolean,
    val supportsStructured: Boolean,
    val supportsResponseFormat: Boolean,
    val vision: Boolean,
    val textOutput: Boolean = true,
    val reasoning: ReasoningSupport? = null,
)

/** From the model list's `reasoning` object: whether thinking can be turned off and which efforts exist. */
data class ReasoningSupport(val mandatory: Boolean, val efforts: List<String>)

/**
 * The `reasoning` request field that makes this model answer fast (no or minimal thinking), or null when the
 * model has no reasoning controls or insists on thinking with no cheap setting. Thinking tokens delay the first
 * visible token by seconds, and we ignore them anyway.
 */
internal fun ModelInfo.reasoningRequest(): kotlinx.serialization.json.JsonObject? {
    val r = reasoning ?: return null
    if (r.efforts.isNotEmpty()) {
        val cheap = listOf("none", "minimal", "low").firstOrNull { it in r.efforts && !(it == "none" && r.mandatory) }
        if (cheap != null) return buildJsonObject { put("effort", cheap) }
    }
    return if (r.mandatory) null else buildJsonObject { put("enabled", false) }
}

data class ChatMessage(val role: String, val content: String)

sealed interface ResponseFormat {
    data object Text : ResponseFormat
    data object JsonObject : ResponseFormat
    class Schema(val name: String, val schema: kotlinx.serialization.json.JsonObject) : ResponseFormat
}

class ChatResult(val content: String?, val finishReason: String?, val model: String?)

/** No content token arrived before the first-token deadline. The runner treats it as "try the next model". */
internal class FirstTokenTimeout : RuntimeException("no first token in time")

/** The deadlines for one streamed request. Reading stops at [totalMs] and keeps what arrived. */
class StreamLimits(val firstTokenMs: Long = 8_000, val totalMs: Long = 30_000)

/** Filled in while a stream runs: [complete] turns false when the total deadline cut the answer short. */
class StreamOutcome {
    @Volatile var complete: Boolean = true
}

/** Thin OpenRouter REST client. Maps every failure to [AiError]; never logs keys or prompts. */
class OpenRouterClient(
    private val http: HttpClient = defaultHttpClient(),
    private val baseUrl: String = "https://openrouter.ai/api/v1",
) {
    suspend fun keyInfo(apiKey: String): KeyInfo {
        val root = request { http.get("$baseUrl/key") { header("Authorization", "Bearer $apiKey") } }.second
        val data = root["data"]?.asObj() ?: throw AiError.InvalidResponse("key info missing")
        return KeyInfo(
            isFreeTier = data["is_free_tier"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
            label = data["label"]?.jsonPrimitive?.contentOrNull,
            usage = data["usage"]?.jsonPrimitive?.doubleOrNull,
            limit = data["limit"]?.jsonPrimitive?.doubleOrNull,
        )
    }

    suspend fun models(apiKey: String?): List<ModelInfo> {
        val root = request {
            http.get("$baseUrl/models") { apiKey?.let { header("Authorization", "Bearer $it") } }
        }.second
        val arr = root["data"] as? JsonArray ?: throw AiError.InvalidResponse("model list missing")
        return arr.mapNotNull { parseModel(it) }
    }

    suspend fun chat(
        apiKey: String, model: String, messages: List<ChatMessage>,
        format: ResponseFormat = ResponseFormat.Text, maxTokens: Int = 1500, temperature: Double = 0.3,
        reasoning: kotlinx.serialization.json.JsonObject? = null,
    ): ChatResult {
        val body = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            reasoning?.let { put("reasoning", it) }
            put("messages", JsonArray(messages.map {
                buildJsonObject { put("role", it.role); put("content", it.content) }
            }))
            when (format) {
                ResponseFormat.Text -> {}
                ResponseFormat.JsonObject -> put("response_format", buildJsonObject { put("type", "json_object") })
                is ResponseFormat.Schema -> {
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", buildJsonObject {
                            put("name", format.name); put("strict", true); put("schema", format.schema)
                        })
                    })
                    put("provider", buildJsonObject { put("require_parameters", true) })
                }
            }
        }
        val (_, root) = request {
            http.post("$baseUrl/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                header("X-Title", "Kepler")
                contentType(ContentType.Application.Json)
                setBody(AiJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
            }
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.asObj()
            ?: throw AiError.InvalidResponse("no choices in reply")
        val content = choice["message"]?.asObj()?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
        return ChatResult(content, choice["finish_reason"]?.jsonPrimitive?.contentOrNull, root["model"]?.jsonPrimitive?.contentOrNull)
    }


    /**
     * Streams a chat completion (SSE) and emits the content deltas as they arrive; reasoning deltas and keep-alive
     * comments are ignored. Throws [FirstTokenTimeout] when no content shows up within [StreamLimits.firstTokenMs],
     * [AiError] for HTTP and in-stream errors, and simply ends after [StreamLimits.totalMs] with what arrived.
     * A server that ignores `stream` and answers with one JSON body still works (one delta).
     */
    internal fun chatStream(
        apiKey: String, model: String, messages: List<ChatMessage>, maxTokens: Int = 700, temperature: Double = 0.4,
        reasoning: kotlinx.serialization.json.JsonObject? = null, limits: StreamLimits = StreamLimits(),
        outcome: StreamOutcome? = null,
    ): Flow<String> = channelFlow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            reasoning?.let { put("reasoning", it) }
            put("messages", JsonArray(messages.map {
                buildJsonObject { put("role", it.role); put("content", it.content) }
            }))
        }
        val gotToken = AtomicBoolean(false)
        try {
            coroutineScope {
                val firstWatch = launch { delay(limits.firstTokenMs); if (!gotToken.get()) throw FirstTokenTimeout() }
                val totalWatch = launch { delay(limits.totalMs); throw StreamDeadline() }
                try {
                    http.preparePost("$baseUrl/chat/completions") {
                        header("Authorization", "Bearer $apiKey")
                        header("X-Title", "Kepler")
                        header("Accept", "text/event-stream")
                        contentType(ContentType.Application.Json)
                        setBody(AiJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
                    }.execute { response ->
                        val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
                        if (response.status.value !in 200..299) {
                            val text = response.bodyAsText()
                            val root = try { AiJson.parseToJsonElement(text).asObj() } catch (e: Exception) { null }
                            throw mapError(response.status.value, root, text, retryAfter)
                        }
                        val channel = response.bodyAsChannel()
                        val raw = StringBuilder()
                        while (true) {
                            val line = channel.readUTF8Line() ?: break
                            val delta = when {
                                line.startsWith("data:") -> {
                                    val data = line.removePrefix("data:").trim()
                                    if (data == "[DONE]") break
                                    streamDelta(data, retryAfter)
                                }
                                line.isBlank() || line.startsWith(":") || SSE_FIELD.containsMatchIn(line) -> null
                                else -> { raw.appendLine(line); null }
                            }
                            if (!delta.isNullOrEmpty()) { gotToken.set(true); send(delta) }
                        }
                        // Not an event stream after all: one complete JSON reply (or a JSON error with status 200).
                        if (raw.isNotBlank() && !gotToken.get()) {
                            val root = try { AiJson.parseToJsonElement(raw.toString()).asObj() } catch (e: Exception) { null }
                                ?: throw AiError.InvalidResponse("reply was not JSON")
                            root["error"]?.asObj()?.let { throw mapError(it["code"]?.jsonPrimitive?.intOrNull ?: 502, root, raw.toString(), retryAfter) }
                            val content = (root["choices"] as? JsonArray)?.firstOrNull()?.asObj()
                                ?.get("message")?.asObj()?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
                            if (!content.isNullOrEmpty()) { gotToken.set(true); send(content) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiError) {
                    throw e
                } catch (e: FirstTokenTimeout) {
                    throw e
                } catch (e: Exception) {
                    throw AiError.Network(e)
                }
                firstWatch.cancel(); totalWatch.cancel()
            }
        } catch (e: StreamDeadline) {
            if (!gotToken.get()) throw FirstTokenTimeout()
            outcome?.complete = false
        }
    }

    private class StreamDeadline : RuntimeException("stream deadline")

    /** Content of one SSE `data:` payload, or null for chunks without content (role, reasoning, usage). */
    private fun streamDelta(data: String, retryAfter: Int?): String? {
        val root = try { AiJson.parseToJsonElement(data).asObj() } catch (e: Exception) { return null } ?: return null
        root["error"]?.asObj()?.let { err ->
            throw mapError(err["code"]?.jsonPrimitive?.intOrNull ?: 502, root, data, retryAfter)
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.asObj() ?: return null
        val delta = choice["delta"]?.asObj() ?: return null
        return (delta["content"] as? JsonPrimitive)?.contentOrNull
    }

    private suspend fun request(call: suspend () -> HttpResponse): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val response = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiError.Network(e)
        }
        val code = response.status.value
        val text = try { response.bodyAsText() } catch (e: CancellationException) { throw e } catch (e: Exception) { throw AiError.Network(e) }
        val root = try { AiJson.parseToJsonElement(text).asObj() } catch (e: Exception) { null }
        val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
        if (code !in 200..299) throw mapError(code, root, text, retryAfter)
        if (root == null) throw AiError.InvalidResponse("reply was not JSON")
        // OpenRouter reports some upstream failures as 200 with an `error` object.
        root["error"]?.asObj()?.let { err ->
            val c = err["code"]?.jsonPrimitive?.intOrNull ?: 502
            throw mapError(c, root, text, retryAfter)
        }
        return code to root
    }

    internal fun mapError(code: Int, root: kotlinx.serialization.json.JsonObject?, rawBody: String, retryAfter: Int?): AiError {
        val detail = root?.get("error")?.asObj()?.get("message")?.jsonPrimitive?.contentOrNull
            ?: rawBody.take(160)
        val lower = detail.lowercase()
        return when {
            code == 401 || code == 403 -> AiError.Unauthorized()
            code == 402 -> AiError.InsufficientCredits()
            code == 429 && ("per-day" in lower || "per day" in lower || "daily" in lower) -> AiError.BudgetExhausted(0)
            code == 429 || code == 408 -> AiError.RateLimited(retryAfter)
            // 404 is also what OpenRouter returns when a free model's data-policy setting blocks every provider.
            code == 404 -> AiError.ModelUnavailable(detail)
            code >= 500 -> AiError.ModelUnavailable(detail)
            else -> AiError.ModelUnavailable("HTTP $code: $detail")
        }
    }

    private fun JsonElement.asObj(): kotlinx.serialization.json.JsonObject? = this as? kotlinx.serialization.json.JsonObject

    companion object {
        private val SSE_FIELD = Regex("^(event|id|retry):")

        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                // Streams enforce their own first-token and total deadlines; this only bounds a stuck socket.
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 45_000
            }
            expectSuccess = false
        }

        internal fun parseModel(el: JsonElement): ModelInfo? {
            val o = el as? kotlinx.serialization.json.JsonObject ?: return null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val pricing = o["pricing"] as? kotlinx.serialization.json.JsonObject
            fun price(k: String) = pricing?.get(k)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            val free = price("prompt") == 0.0 && price("completion") == 0.0
            val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
            val arch = o["architecture"] as? kotlinx.serialization.json.JsonObject
            fun mods(k: String) = (arch?.get(k) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val out = mods("output_modalities")
            val reasoningObj = o["reasoning"] as? kotlinx.serialization.json.JsonObject
            val reasoning = reasoningObj?.let { r ->
                ReasoningSupport(
                    mandatory = r["mandatory"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    efforts = (r["supported_efforts"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                )
            }
            return ModelInfo(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: id,
                contextLength = o["context_length"]?.jsonPrimitive?.intOrNull ?: 0,
                created = o["created"]?.jsonPrimitive?.longOrNull ?: 0L,
                free = free,
                supportsStructured = "structured_outputs" in params,
                supportsResponseFormat = "response_format" in params || "structured_outputs" in params,
                vision = "image" in mods("input_modalities"),
                textOutput = out.isEmpty() || out == listOf("text") || "text" in out && out.size == 1,
                reasoning = reasoning,
            )
        }
    }
}
