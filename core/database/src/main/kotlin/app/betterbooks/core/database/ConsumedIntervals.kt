package app.betterbooks.core.database

/** Inclusive range of sentence ordinals. */
data class OrdinalRange(val start: Int, val end: Int) {
    init { require(start <= end) { "start > end: $start > $end" } }
    val size: Int get() = end - start + 1
}

/** Pure interval arithmetic behind the spoiler fence and the read/listen fractions. */
object ConsumedIntervals {
    /** Sorts and merges overlapping or directly adjacent ranges ([0,4] + [5,9] -> [0,9]). */
    fun normalize(ranges: Collection<OrdinalRange>): List<OrdinalRange> {
        val sorted = ranges.sortedWith(compareBy({ it.start }, { it.end }))
        val out = ArrayList<OrdinalRange>(sorted.size)
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end.toLong() + 1) {
                if (r.end > last.end) out[out.lastIndex] = OrdinalRange(last.start, r.end)
            } else {
                out += r
            }
        }
        return out
    }

    fun add(existing: Collection<OrdinalRange>, added: OrdinalRange): List<OrdinalRange> = normalize(existing + added)

    /**
     * End of the contiguous consumed run that contains [startOrdinal]; -1 when [startOrdinal] itself has not been
     * consumed. [startOrdinal] is the first sentence of the first included BODY section.
     */
    fun frontier(consumed: Collection<OrdinalRange>, startOrdinal: Int): Int {
        val run = normalize(consumed).firstOrNull { startOrdinal in it.start..it.end } ?: return -1
        return run.end
    }

    /** Share of the sentences in [scope] that are consumed, 0..1. An empty scope yields 0. */
    fun fraction(consumed: Collection<OrdinalRange>, scope: Collection<OrdinalRange>): Float {
        val s = normalize(scope)
        val total = s.sumOf { it.size.toLong() }
        if (total == 0L) return 0f
        val c = normalize(consumed)
        var covered = 0L
        var i = 0
        var j = 0
        while (i < s.size && j < c.size) {
            val lo = maxOf(s[i].start, c[j].start)
            val hi = minOf(s[i].end, c[j].end)
            if (lo <= hi) covered += hi - lo + 1
            if (s[i].end < c[j].end) i++ else j++
        }
        return (covered.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }
}
