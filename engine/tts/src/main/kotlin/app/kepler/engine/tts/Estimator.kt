package app.kepler.engine.tts

import app.kepler.core.model.NarrationEstimate
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.roundToLong

object CacheKeys {
    /** Bump when rendering, DSP or encoding changes in a way that should invalidate cached audio. */
    const val RENDERER_VERSION = 1

    fun of(text: String, provider: String, model: String, voice: String, speed: Float): String {
        val params = "speed=${"%.2f".format(java.util.Locale.ROOT, speed)};r=$RENDERER_VERSION"
        return sha256("$text|$provider|$model|$voice|$params")
    }

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

object Estimator {
    /** Narration pace used for planning: about 2.6 words per second at 1x. */
    const val WORDS_PER_SECOND = 2.6

    fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    fun audioSeconds(text: String, speed: Float = 1f): Double = words(text) / WORDS_PER_SECOND / speed.coerceAtLeast(0.1f)

    fun estimate(
        chunks: List<Chunk>,
        centsPerByte: Double,
        speed: Float,
        throughput: Double,
    ): NarrationEstimate {
        var chars = 0L
        var bytes = 0L
        var seconds = 0.0
        for (c in chunks) {
            val t = c.text
            chars += t.length
            bytes += t.toByteArray(Charsets.UTF_8).size
            seconds += audioSeconds(t, speed)
        }
        val eta = if (throughput > 0) ceil(seconds / throughput).toLong() else 0L
        return NarrationEstimate(
            characters = chars,
            utf8Bytes = bytes,
            costCents = bytes * centsPerByte,
            requests = chunks.size,
            audioSeconds = seconds.roundToLong(),
            etaSeconds = eta,
            freeTier = centsPerByte == 0.0,
        )
    }

    /** AAC-LC 64 kbps mono: 8 KB per second. */
    fun storageBytes(audioSeconds: Long): Long = audioSeconds * 8_000
}

/** Exponentially weighted throughput, in seconds of audio generated per wall-clock second. */
class ThroughputEwma(initial: Double, private val alpha: Double = 0.3) {
    @Volatile
    var value: Double = initial
        private set

    fun record(audioMs: Long, wallMs: Long) {
        if (audioMs <= 0 || wallMs <= 0) return
        val sample = audioMs.toDouble() / wallMs
        value = alpha * sample + (1 - alpha) * value
    }
}
