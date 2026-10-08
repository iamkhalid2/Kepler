package app.betterbooks.engine.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object AudioDsp {
    const val SILENCE_DBFS = -50.0
    const val TARGET_DBFS = -18.0
    private const val PEAK_CEILING = 0.89 // about -1 dBFS

    data class Trim(val startSample: Int, val endSample: Int)

    /**
     * Finds the first/last 10 ms window louder than [thresholdDb], keeping [padMs] of air on both
     * sides. Returns the whole range when everything is silent.
     */
    fun findTrim(samples: ShortArray, sampleRate: Int, thresholdDb: Double = SILENCE_DBFS, padMs: Int = 40): Trim {
        if (samples.isEmpty()) return Trim(0, 0)
        val win = max(1, sampleRate / 100)
        val threshold = 32768.0 * 10.0.pow(thresholdDb / 20.0)
        fun loud(from: Int): Boolean {
            val to = min(samples.size, from + win)
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            return sqrt(sum / (to - from)) > threshold
        }
        var start = 0
        while (start < samples.size && !loud(start)) start += win
        if (start >= samples.size) return Trim(0, samples.size)
        var end = ((samples.size - 1) / win) * win
        while (end > start && !loud(end)) end -= win
        val pad = sampleRate * padMs / 1000
        return Trim(max(0, start - pad), min(samples.size, end + win + pad))
    }

    fun rmsDbfs(samples: ShortArray): Double {
        if (samples.isEmpty()) return -120.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size) / 32768.0
        return if (rms <= 0) -120.0 else 20 * log10(rms)
    }

    /**
     * Rough loudness match: gain toward [targetDb] RMS (a stand-in for -18 LUFS on speech),
     * limited so the peak stays under about -1 dBFS.
     */
    fun normalize(samples: ShortArray, targetDb: Double = TARGET_DBFS): ShortArray {
        val current = rmsDbfs(samples)
        if (current <= -100.0) return samples
        var gain = 10.0.pow((targetDb - current) / 20.0)
        var peak = 0
        for (s in samples) peak = max(peak, kotlin.math.abs(s.toInt()))
        if (peak > 0) gain = min(gain, PEAK_CEILING * 32767.0 / peak)
        return ShortArray(samples.size) { i ->
            (samples[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun silence(ms: Int, sampleRate: Int) = ShortArray(sampleRate * ms / 1000)

    /** Parses a PCM 16-bit RIFF/WAVE file and downmixes to mono. */
    fun parseWav(bytes: ByteArray): SynthAudio.Pcm {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) throw TtsException.Audio("Not a WAV file")
        var pos = 12
        var channels = 1
        var rate = 22050
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            var size = buf.getInt(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = buf.getShort(body + 2).toInt()
                rate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (bits != 16) throw TtsException.Audio("Unsupported WAV bit depth $bits")
                if (size < 0 || body + size > bytes.size) size = bytes.size - body
                val frames = size / 2 / channels
                val out = ShortArray(frames)
                for (f in 0 until frames) {
                    var acc = 0
                    for (c in 0 until channels) acc += buf.getShort(body + (f * channels + c) * 2)
                    out[f] = (acc / channels).toShort()
                }
                return SynthAudio.Pcm(out, rate)
            }
            pos = body + size + (size and 1)
        }
        throw TtsException.Audio("WAV file has no data")
    }

    fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }
}
