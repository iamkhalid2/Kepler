package app.kepler.engine.playback

import kotlin.math.abs
import kotlin.math.sqrt

/** Result of one sleep-timer evaluation. */
data class SleepTick(val volume: Float, val expired: Boolean, val remainingMs: Long?)

/**
 * Sleep timer: a countdown in minutes, or "end of the current section". It fades the volume over the
 * last [fadeMs] and then reports that playback should stop. Pure: the caller supplies time and position.
 */
class SleepTimer(private val fadeMs: Long = 10_000) {
    private sealed interface Mode {
        data object Off : Mode
        data class Countdown(val endAtMs: Long) : Mode
        data object SectionEnd : Mode
    }

    private var mode: Mode = Mode.Off

    val active: Boolean get() = mode != Mode.Off
    val atSectionEnd: Boolean get() = mode == Mode.SectionEnd

    /** `minutes` > 0 timer, 0 off, -1 end of the current section. */
    fun set(minutes: Int, nowMs: Long) {
        mode = when {
            minutes > 0 -> Mode.Countdown(nowMs + minutes * 60_000L)
            minutes < 0 -> Mode.SectionEnd
            else -> Mode.Off
        }
    }

    /** Adds time to a running countdown, or starts one if the timer fired a moment ago. */
    fun extend(minutes: Int, nowMs: Long) {
        mode = when (val m = mode) {
            is Mode.Countdown -> Mode.Countdown(maxOf(m.endAtMs, nowMs) + minutes * 60_000L)
            Mode.SectionEnd -> Mode.Countdown(nowMs + minutes * 60_000L)
            Mode.Off -> Mode.Countdown(nowMs + minutes * 60_000L)
        }
    }

    /**
     * [wallToSectionEndMs]: wall-clock time until the section's audio ends (media time over speed), or
     * null when unknown; then [sectionEnded] says whether playback already passed the section.
     */
    fun tick(nowMs: Long, wallToSectionEndMs: Long?, sectionEnded: Boolean): SleepTick {
        val remaining: Long? = when (val m = mode) {
            Mode.Off -> return SleepTick(1f, false, null)
            is Mode.Countdown -> (m.endAtMs - nowMs).coerceAtLeast(0)
            Mode.SectionEnd -> if (sectionEnded) 0 else wallToSectionEndMs?.coerceAtLeast(0)
        }
        if (remaining == null) return SleepTick(1f, false, null)
        if (remaining <= 0) {
            mode = Mode.Off
            return SleepTick(1f, true, 0)
        }
        val volume = if (remaining >= fadeMs) 1f else (remaining.toFloat() / fadeMs).coerceIn(0f, 1f)
        return SleepTick(volume, false, remaining)
    }

    val fadeWindowMs: Long get() = fadeMs
}

/** Where to resume after a pause: back to the start of the sentence, and a little further after a long pause. */
object SmartRewind {
    const val LONG_PAUSE_MS = 30_000L
    const val EXTRA_MS = 2_000L
    private const val IGNORE_BELOW_MS = 1_000L

    /** All values are global plan milliseconds; the result never goes below zero. */
    fun targetMs(currentMs: Long, sentenceStartMs: Long, pausedForMs: Long): Long {
        if (pausedForMs < IGNORE_BELOW_MS) return currentMs
        val extra = if (pausedForMs >= LONG_PAUSE_MS) EXTRA_MS else 0L
        return (minOf(currentMs, sentenceStartMs) - extra).coerceAtLeast(0)
    }
}

/**
 * Tracks sentences that were really played, as completed runs. A sentence counts once playback moves
 * past it naturally; seeks end the current run and start a new one.
 */
class ConsumeTracker {
    private var last = -1
    private var runStart = -1
    private var runEnd = -1
    private val done = ArrayList<IntRange>()

    /** [isNext]: whether `next` is the sentence that naturally follows `prev`. */
    fun onOrdinal(ordinal: Int, isNext: (prev: Int, next: Int) -> Boolean) {
        if (last < 0) { last = ordinal; return }
        if (ordinal == last) return
        if (isNext(last, ordinal)) {
            if (runStart >= 0 && isNext(runEnd, last)) runEnd = last else { closeRun(); runStart = last; runEnd = last }
        } else closeRun()
        last = ordinal
    }

    /** A jump (seek, skip or reload): nothing between the old and new positions was played. */
    fun onSeek(ordinal: Int) {
        closeRun()
        last = ordinal
    }

    /** Playback stopped: sentences already passed are kept, the one in progress is not. */
    fun drain(): List<IntRange> {
        closeRun()
        val out = done.toList()
        done.clear()
        return out
    }

    fun hasPending(): Boolean = done.isNotEmpty() || runStart >= 0

    private fun closeRun() {
        if (runStart >= 0) done += runStart..runEnd
        runStart = -1
        runEnd = -1
    }
}

/** Book time estimated at 1x from the audio generated so far. */
object BookTimeline {
    const val DEFAULT_MS_PER_SENTENCE = app.kepler.core.model.ListenPace.DEFAULT_MS_PER_SENTENCE

    fun msPerSentence(segmentDurationsMs: Long, sentencesCovered: Int): Double =
        app.kepler.core.model.ListenPace.msPerSentence(segmentDurationsMs, sentencesCovered)
}

/** Detects a shake from accelerometer samples (m/s^2): two spikes over [threshold] inside [windowMs]. */
class ShakeDetector(private val threshold: Float = 17f, private val windowMs: Long = 900) {
    private var firstSpikeAt = -1L
    private var lastSpikeAt = -1L

    fun onSample(x: Float, y: Float, z: Float, atMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z)
        if (abs(g) < threshold) return false
        if (lastSpikeAt >= 0 && atMs - lastSpikeAt < 120) return false
        lastSpikeAt = atMs
        if (firstSpikeAt >= 0 && atMs - firstSpikeAt <= windowMs) {
            firstSpikeAt = -1
            return true
        }
        firstSpikeAt = atMs
        return false
    }
}
