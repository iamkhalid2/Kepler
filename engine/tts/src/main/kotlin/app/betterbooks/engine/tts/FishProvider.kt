package app.betterbooks.engine.tts

import app.betterbooks.core.model.VoiceOption
import app.betterbooks.core.model.VoiceRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/** Fish Audio over HTTPS. Every request carries the `model` header from [ModelPolicy]. */
class FishProvider(
    private val apiKey: suspend () -> String?,
    private val policy: ModelPolicy = ModelPolicy(),
    engine: HttpClientEngine = OkHttp.create(),
    concurrency: Int = DEFAULT_CONCURRENCY,
    private val maxAttempts: Int = 6,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : TtsProvider {

    override val id: String = ID
    override val capabilities = TtsCapabilities(
        streaming = true, timestamps = true, sentenceAccurate = false, maxChars = 1200,
        markup = MarkupDialect.FISH_S2, speedRange = 0.5f..2f, concurrency = concurrency,
    )

    private val permits = Semaphore(concurrency)

    @Volatile
    private var timestampEndpointAvailable = true

    private val modelHeader = createClientPlugin("FishModelHeader") {
        onRequest { request, _ -> request.headers[MODEL_HEADER] = policy.model() }
    }

    internal val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(modelHeader)
    }

    override fun currentModel(): String = policy.model()
    override fun centsPerByte(): Double = policy.centsPerByte()

    override suspend fun voices(language: String?): List<VoiceOption> = withRetry {
        val key = key()
        val response = client.get("$BASE/model") {
            auth(key)
            parameter("licensed", true)
            parameter("page_size", 100)
            parameter("sort_by", "score")
            if (language != null) parameter("language", language)
        }
        check(response)
        val page = FishSseAccumulator.json.decodeFromString(FishModelPage.serializer(), response.bodyAsText())
        page.items.map {
            VoiceOption(
                ref = VoiceRef(ID, it.id, it.title),
                description = it.description,
                language = it.languages.firstOrNull(),
                previewUrl = it.samples.firstOrNull()?.audio,
            )
        }
    }

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult = withRetry {
        val key = key()
        if (timestampEndpointAvailable) {
            try {
                return@withRetry streamWithTimestamps(request, key)
            } catch (e: TtsException.EndpointUnavailable) {
                timestampEndpointAvailable = false
            }
        }
        plain(request, key)
    }

    private suspend fun streamWithTimestamps(request: SynthesisRequest, key: String): SynthesisResult {
        val model = policy.model()
        return client.preparePost("$BASE/v1/tts/stream/with-timestamp") { body(request, key) }.execute { response ->
            if (response.status.value == 404 || response.status.value == 405) {
                throw TtsException.EndpointUnavailable(response.status.value)
            }
            check(response)
            val acc = FishSseAccumulator()
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readLine() ?: break
                acc.onLine(line)
            }
            acc.finish()
            val bytes = acc.audioBytes()
            if (bytes.isEmpty()) throw TtsException.Engine("Fish Audio returned no audio")
            SynthesisResult(SynthAudio.Encoded(bytes, FORMAT), acc.words(), model = model)
        }
    }

    private suspend fun plain(request: SynthesisRequest, key: String): SynthesisResult {
        val model = policy.model()
        return client.preparePost("$BASE/v1/tts") { body(request, key) }.execute { response ->
            check(response)
            val bytes = response.readRawBytes()
            if (bytes.isEmpty()) throw TtsException.Engine("Fish Audio returned no audio")
            SynthesisResult(SynthAudio.Encoded(bytes, FORMAT), words = null, model = model)
        }
    }

    private fun HttpRequestBuilder.body(request: SynthesisRequest, key: String) {
        auth(key)
        header(HttpHeaders.Accept, "*/*")
        val speed = request.speed.coerceIn(capabilities.speedRange)
        val json = buildJsonObject {
            put("text", request.text)
            put("reference_id", request.voiceId)
            put("format", FORMAT)
            put("mp3_bitrate", 128)
            put("sample_rate", 44100)
            put("latency", "normal")
            put("temperature", 0.5)
            put("top_p", 0.7)
            put("normalize", true)
            putJsonObject("prosody") {
                put("speed", speed)
                put("normalize_loudness", true)
            }
            putJsonArray("features") { add("quality-guard") }
        }
        setBody(TextContent(json.toString(), ContentType.Application.Json))
    }

    private suspend fun key(): String = apiKey()?.takeIf { it.isNotBlank() } ?: throw TtsException.MissingKey()

    private fun HttpRequestBuilder.auth(key: String) {
        header(HttpHeaders.Authorization, "Bearer $key")
    }

    private fun check(response: HttpResponse) {
        when (val code = response.status.value) {
            in 200..299 -> Unit
            401, 403 -> throw TtsException.InvalidKey()
            402 -> throw TtsException.PaymentRequired()
            429 -> throw TtsException.RateLimited()
            500, 502, 503, 504 -> throw TtsException.ServerBusy(code)
            else -> throw TtsException.Http(code)
        }
    }

    /** Exponential backoff with full jitter; Fish sends no Retry-After header. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            val error: TtsException = try {
                return permits.withPermit { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TtsException) {
                e
            } catch (e: IOException) {
                TtsException.Network(e)
            }
            attempt++
            if (!error.retryable || attempt >= maxAttempts) throw error
            sleep(backoffMs(attempt, random))
        }
    }

    companion object {
        const val ID = "fish"
        const val BASE = "https://api.fish.audio"
        const val MODEL_HEADER = "model"
        const val FORMAT = "mp3"
        const val DEFAULT_CONCURRENCY = 5

        internal fun backoffMs(attempt: Int, random: Random): Long {
            val cap = min(30_000L, 500L shl min(attempt, 10))
            return cap / 2 + random.nextLong(cap / 2 + 1)
        }
    }
}

@Serializable
internal data class FishModelPage(val items: List<FishModel> = emptyList())

@Serializable
internal data class FishModel(
    @SerialName("_id") val id: String,
    val title: String = "",
    val description: String = "",
    val languages: List<String> = emptyList(),
    val samples: List<FishSample> = emptyList(),
)

@Serializable
internal data class FishSample(val audio: String? = null)
