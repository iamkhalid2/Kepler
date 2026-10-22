package app.kepler.feature.player

import app.kepler.core.model.ChapterMark
import kotlin.math.roundToInt

/** Pure helpers for the player: time, speed and chapter maths. */
object PlayerFormat {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.5f
    val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)
    const val SLEEP_END_OF_SECTION = -1

    /** "Previous chapter" restarts the current one when more than this much of it has played. */
    const val RESTART_CHAPTER_MS = 3_000L

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

    /** (hours, minutes) for a "2h 10m" / "18 min" label; minutes round up so a running chapter never shows 0. */
    fun durationParts(ms: Long): Pair<Int, Int> {
        val totalMin = ((ms.coerceAtLeast(0) + 59_999) / 60_000).toInt()
        return (totalMin / 60) to (totalMin % 60)
    }

    /** Index of the chapter holding [ordinal] in [marks] (book order), or -1. */
    fun chapterIndex(marks: List<ChapterMark>, ordinal: Int): Int = marks.indexOfLast { it.startOrdinal <= ordinal }

    /**
     * Where "previous chapter" goes: the start of the current chapter once more than [RESTART_CHAPTER_MS] of it
     * has played (or when it is the first), otherwise the start of the one before.
     */
    fun previousChapterTarget(marks: List<ChapterMark>, ordinal: Int, sectionElapsedMs: Long): Int? {
        val i = chapterIndex(marks, ordinal)
        if (i < 0) return marks.firstOrNull()?.startOrdinal
        return if (sectionElapsedMs > RESTART_CHAPTER_MS || i == 0) marks[i].startOrdinal else marks[i - 1].startOrdinal
    }

    /** Start of the chapter after the one holding [ordinal], or null on the last chapter. */
    fun nextChapterTarget(marks: List<ChapterMark>, ordinal: Int): Int? =
        marks.getOrNull(chapterIndex(marks, ordinal) + 1)?.startOrdinal
}
