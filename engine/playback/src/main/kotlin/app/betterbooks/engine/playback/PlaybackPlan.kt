package app.betterbooks.engine.playback

import app.betterbooks.core.model.AudioSegment

/** One entry of the internal ExoPlayer playlist. */
sealed interface PlanItem {
    val durationMs: Long

    data class Audio(val segment: AudioSegment) : PlanItem {
        override val durationMs: Long get() = segment.durationMs
    }

    /** Silence after [afterOrdinal]; it belongs to the chapter of that sentence. */
    data class Silence(override val durationMs: Long, val afterOrdinal: Int) : PlanItem
}

enum class Boundary { SAME_PARAGRAPH, PARAGRAPH, SECTION }

/** Pause lengths between consecutive segments. A sentence boundary is already in the audio. */
data class GapPolicy(val sameParagraphMs: Long = 0, val paragraphMs: Long = 450, val sectionMs: Long = 1_100) {
    fun gap(boundary: Boundary): Long = when (boundary) {
        Boundary.SAME_PARAGRAPH -> sameParagraphMs
        Boundary.PARAGRAPH -> paragraphMs
        Boundary.SECTION -> sectionMs
    }
}

/**
 * The ordered list of audio and silence items that the internal player plays, with start offsets.
 * Immutable: growing the plan returns a new one that shares the same prefix.
 */
class PlaybackPlan private constructor(val items: List<PlanItem>, private val offsets: LongArray) {
    val size: Int get() = items.size
    val totalMs: Long get() = if (items.isEmpty()) 0 else offsets.last() + items.last().durationMs
    val lastSegment: AudioSegment? get() = items.lastOrNull { it is PlanItem.Audio }?.let { (it as PlanItem.Audio).segment }
    val lastOrdinal: Int get() = lastSegment?.endOrdinal ?: -1

    fun startMs(index: Int): Long = offsets[index]

    fun globalMs(index: Int, positionMs: Long): Long {
        if (items.isEmpty()) return 0
        val i = index.coerceIn(0, items.lastIndex)
        return offsets[i] + positionMs.coerceIn(0, items[i].durationMs)
    }

    /** The item that contains [globalMs] and the offset inside it. Past the end, the end of the last item. */
    fun locate(globalMs: Long): Pair<Int, Long> {
        if (items.isEmpty()) return 0 to 0L
        if (globalMs <= 0) return 0 to 0L
        if (globalMs >= totalMs) return items.lastIndex to items.last().durationMs
        var lo = 0
        var hi = items.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (offsets[mid] <= globalMs) lo = mid else hi = mid - 1
        }
        return lo to (globalMs - offsets[lo])
    }

    fun indexOfOrdinal(ordinal: Int): Int = items.indexOfFirst {
        it is PlanItem.Audio && ordinal in it.segment.startOrdinal..it.segment.endOrdinal
    }

    /** The ordinal being spoken at ([index], [positionMs]); inside a silence, the sentence that just ended. */
    fun ordinalAt(index: Int, positionMs: Long, lookup: (AudioSegment) -> SyncLookup): Int {
        if (items.isEmpty()) return 0
        return when (val item = items[index.coerceIn(0, items.lastIndex)]) {
            is PlanItem.Audio -> lookup(item.segment).ordinalAt(positionMs)
            is PlanItem.Silence -> item.afterOrdinal
        }
    }

    /**
     * The run of items around [index] that share a chapter (by [chapterOf] of each item's ordinal),
     * as global start and end of audio. The trailing silence is not counted.
     */
    fun chapterSpan(index: Int, chapterOf: (Int) -> Int): LongRange {
        if (items.isEmpty()) return 0L..0L
        val i = index.coerceIn(0, items.lastIndex)
        val key = chapterOf(ordinalOf(items[i]))
        var first = i
        while (first > 0 && chapterOf(ordinalOf(items[first - 1])) == key) first--
        var last = i
        while (last < items.lastIndex && chapterOf(ordinalOf(items[last + 1])) == key) last++
        while (last > first && items[last] is PlanItem.Silence) last--
        return offsets[first]..(offsets[last] + items[last].durationMs)
    }

    operator fun plus(more: List<PlanItem>): PlaybackPlan {
        if (more.isEmpty()) return this
        val all = items + more
        val o = LongArray(all.size)
        for (k in 1 until all.size) o[k] = o[k - 1] + all[k - 1].durationMs
        return PlaybackPlan(all, o)
    }

    companion object {
        val EMPTY = PlaybackPlan(emptyList(), LongArray(0))

        private fun ordinalOf(item: PlanItem): Int = when (item) {
            is PlanItem.Audio -> item.segment.startOrdinal
            is PlanItem.Silence -> item.afterOrdinal
        }

        /**
         * Items for [segments] following [previous] (null for the first). [boundary] classifies the join
         * between the last sentence of one segment and the first of the next.
         */
        fun itemsFor(
            previous: AudioSegment?,
            segments: List<AudioSegment>,
            gaps: GapPolicy,
            boundary: (prevEnd: Int, nextStart: Int) -> Boundary,
        ): List<PlanItem> {
            val out = ArrayList<PlanItem>(segments.size * 2)
            var prev = previous
            for (s in segments) {
                if (prev != null) {
                    val gap = gaps.gap(boundary(prev.endOrdinal, s.startOrdinal))
                    if (gap > 0) out += PlanItem.Silence(gap, prev.endOrdinal)
                }
                out += PlanItem.Audio(s)
                prev = s
            }
            return out
        }
    }
}
