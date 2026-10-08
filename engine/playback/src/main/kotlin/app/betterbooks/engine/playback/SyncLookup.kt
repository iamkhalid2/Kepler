package app.betterbooks.engine.playback

import app.betterbooks.core.model.AudioSegment
import app.betterbooks.core.model.SyncRow

/**
 * Sentence timing inside one segment file. Rows are sorted by start time, and a position that
 * falls in a pause between sentences belongs to the sentence that just ended.
 */
class SyncLookup private constructor(
    private val ordinals: IntArray,
    private val starts: LongArray,
    private val ends: LongArray,
) {
    val size: Int get() = ordinals.size
    val firstOrdinal: Int get() = ordinals.first()
    val lastOrdinal: Int get() = ordinals.last()

    fun contains(ordinal: Int): Boolean = indexOfOrdinal(ordinal) >= 0

    fun ordinalAt(positionMs: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ordinals[ans]
    }

    fun startMs(ordinal: Int): Long? = indexOfOrdinal(ordinal).takeIf { it >= 0 }?.let { starts[it] }
    fun endMs(ordinal: Int): Long? = indexOfOrdinal(ordinal).takeIf { it >= 0 }?.let { ends[it] }

    private fun indexOfOrdinal(ordinal: Int): Int {
        val i = ordinals.binarySearch(ordinal)
        return if (i >= 0) i else -1
    }

    companion object {
        fun of(rows: List<SyncRow>): SyncLookup {
            require(rows.isNotEmpty()) { "no sync rows" }
            val sorted = rows.sortedBy { it.ordinal }
            return SyncLookup(
                IntArray(sorted.size) { sorted[it].ordinal },
                LongArray(sorted.size) { sorted[it].startMs },
                LongArray(sorted.size) { sorted[it].endMs },
            )
        }

        /** Fallback when a segment has no rows: spread its duration evenly over its sentences. */
        fun uniform(segment: AudioSegment): SyncLookup {
            val n = segment.endOrdinal - segment.startOrdinal + 1
            val rows = (0 until n).map { i ->
                SyncRow(
                    segment.bookId, segment.startOrdinal + i, segment.id,
                    segment.durationMs * i / n, segment.durationMs * (i + 1) / n,
                )
            }
            return of(rows)
        }
    }
}
