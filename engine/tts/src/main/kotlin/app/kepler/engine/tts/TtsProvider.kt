package app.kepler.engine.tts

import app.kepler.core.model.BookId
import app.kepler.core.model.VoiceOption

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
    /** The book being narrated (spend is accounted per book); null for previews. */
    val bookId: BookId? = null,
    /**
     * Called by a provider that bills per request the moment the service accepted the request (HTTP 2xx),
     * before the audio is decoded. Spend is recorded there, so a later failure cannot hide a charge.
     */
    val onAccepted: () -> Unit = {},
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
    class MissingKey : TtsException("Add a Fish Audio key in Settings to use Fish voices.")
    class InvalidKey : TtsException("Fish Audio didn’t accept the key. Check it in Settings.")
    class PaymentRequired : TtsException("Fish Audio needs credit on the account to continue.")
    class RateLimited : TtsException("Fish Audio is busy. Narration tries again shortly.")
    class ServerBusy(code: Int) : TtsException("Fish Audio is unavailable right now. Try again in a little while.")
    /** [billed]: the service had already accepted (and charged) the request when the connection broke. */
    class Network(cause: Throwable, val billed: Boolean = false) :
        TtsException("No connection. Narration that is already made still plays.", cause)
    class Http(val code: Int) : TtsException("Fish Audio couldn’t make this part (error $code).")
    class EndpointUnavailable(val code: Int) : TtsException("Endpoint unavailable ($code)")
    class Engine(message: String) : TtsException(message)
    class Audio(message: String, cause: Throwable? = null) : TtsException(message, cause)

    /** The book was deleted while its narration was being made; not an error to show. */
    class BookGone : TtsException("The book was removed.")

    /** Never repeats a request that was already billed: the user starts it again deliberately. */
    val retryable: Boolean get() = this is RateLimited || this is ServerBusy || (this is Network && !billed)
}
