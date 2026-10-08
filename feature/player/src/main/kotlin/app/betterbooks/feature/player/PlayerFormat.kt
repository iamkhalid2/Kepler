package app.betterbooks.feature.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure helpers for the player screens: time, speed and scrubber maths. */
object PlayerFormat {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.5f
    val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)
    const val SLEEP_END_OF_SECTION = -1

    /** `m:ss`, or `h:mm:ss` from an hour up. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** `1.4×`; whole numbers lose the decimal (`2×`). */
    fun speed(speed: Float): String {
        val r = (speed * 100).roundToInt() / 100f
        val text = if (r == r.toInt().toFloat()) r.toInt().toString() else "%.2f".format(r).trimEnd('0')
        return "$text×"
    }

    /** Moves the speed by [delta] and keeps it inside the supported range on a 0.05 grid. */
    fun stepSpeed(current: Float, delta: Float): Float {
        val stepped = ((current + delta) * 20).roundToInt() / 20f
        return stepped.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /** Whole minutes of listening that [aheadMs] of audio gives at [speed]. */
    fun minutesAhead(aheadMs: Long, speed: Float): Int =
        if (speed <= 0f) 0 else ((aheadMs / speed) / 60_000f).toInt()

    fun fractionOf(ordinal: Int, count: Int): Float =
        if (count <= 1) 0f else (ordinal.toFloat() / (count - 1)).coerceIn(0f, 1f)

    fun ordinalAt(fraction: Float, count: Int): Int =
        if (count <= 1) 0 else (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()

    fun tickFractions(starts: List<Int>, count: Int): List<Float> = starts.map { fractionOf(it, count) }

    /** The tick within [window] of [fraction], or null. Gives the scrubber its chapter detents. */
    fun nearestTick(fraction: Float, ticks: List<Float>, window: Float): Float? =
        ticks.minByOrNull { abs(it - fraction) }?.takeIf { abs(it - fraction) <= window }

    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()

    fun sleepRemainingLabel(ms: Long): String = clock(ms)
}
