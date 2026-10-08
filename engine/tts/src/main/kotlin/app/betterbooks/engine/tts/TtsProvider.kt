package app.betterbooks.engine.tts

import app.betterbooks.core.model.VoiceOption

enum class MarkupDialect { NONE, FISH_S2, FISH_S1 }

data class TtsCapabilities(
    val streaming: Boolean,
    /** Word-level timestamps come back with the audio. */
    val timestamps: Boolean,
    /** Sentence spans are exact (one synthesis per sentence). */
    val sentenceAccurate: Boolean,
    val maxChars: Int,
    val markup: MarkupDialect,
    val speedRange: ClosedFloatingPointRange<Float>,
    val concurrency: Int,
)

data class SynthesisRequest(
    /** Sentences spoken in order; providers join them with a single space. */
    val sentences: List<String>,
    val voiceId: String,
    val speed: Float = 1f,
) {
    val text: String get() = sentences.joinToString(" ")
}

data class WordTiming(val text: String, val startMs: Long, val endMs: Long)

sealed interface SynthAudio {
    /** Compressed audio as returned by a network provider; `extension` is mp3, opus, wav... */
    class Encoded(val bytes: ByteArray, val extension: String) : SynthAudio

    /** 16-bit mono PCM. */
    class Pcm(val samples: ShortArray, val sampleRate: Int) : SynthAudio {
        val durationMs: Long get() = samples.size * 1000L / sampleRate
    }
}

data class SynthesisResult(
    val audio: SynthAudio,
    /** Word timings relative to the start of the audio, or null when the provider has none. */
    val words: List<WordTiming>?,
    /** Exact per-sentence spans (start, end) in ms, when the provider renders sentence by sentence. */
    val sentenceSpans: List<Pair<Long, Long>>? = null,
    /** Model identifier that produced the audio (part of the cache key). */
    val model: String,
)

/** A text-to-speech backend. Implementations: [FishProvider], [SystemTtsProvider]. */
interface TtsProvider {
    val id: String
    val capabilities: TtsCapabilities

    /** Model that the next request will use (fixed per provider for system TTS). */
    fun currentModel(): String

    /** Price in US cents per UTF-8 byte for the current model (0 when free). */
    fun centsPerByte(): Double

    suspend fun voices(language: String? = null): List<VoiceOption>

    @Throws(TtsException::class)
    suspend fun synthesize(request: SynthesisRequest): SynthesisResult
}

/** Neutral, technical error taxonomy. Messages are safe to show in the UI. */
sealed class TtsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingKey : TtsException("No Fish Audio API key is set")
    class InvalidKey : TtsException("The Fish Audio API key was rejected (401)")
    class PaymentRequired : TtsException("Fish Audio needs a payment method or credit (402)")
    class RateLimited : TtsException("Fish Audio is limiting concurrent requests (429)")
    class ServerBusy(code: Int) : TtsException("Fish Audio is temporarily unavailable ($code)")
    class Network(cause: Throwable) : TtsException("Network error: ${cause.javaClass.simpleName}", cause)
    class Http(val code: Int) : TtsException("Fish Audio returned HTTP $code")
    class EndpointUnavailable(val code: Int) : TtsException("Endpoint unavailable ($code)")
    class Engine(message: String) : TtsException(message)
    class Audio(message: String, cause: Throwable? = null) : TtsException(message, cause)

    val retryable: Boolean get() = this is RateLimited || this is ServerBusy || this is Network
}
