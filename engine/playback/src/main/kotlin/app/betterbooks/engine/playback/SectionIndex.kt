package app.betterbooks.engine.playback

import app.betterbooks.core.model.ChapterMark
import app.betterbooks.core.model.Section

/**
 * What is narrated, and where the chapters are. Narrated text is the union of the included
 * sections, so excluded front and back matter is stepped over. Chapters are the innermost included
 * sections (the ones with no included child), thinned so that the scrubber stays readable.
 */
class SectionIndex(sections: List<Section>, private val sentenceCount: Int, bookTitle: String) {
    private val runStarts: IntArray
    private val runEnds: IntArray
    val marks: List<ChapterMark>
    private val markStarts: IntArray

    init {
        val included = sections.filter { it.included && it.startOrdinal <= it.endOrdinal }
        val clipped = included.map { it to (it.startOrdinal.coerceAtLeast(0)..it.endOrdinal.coerceAtMost(sentenceCount - 1)) }
            .filter { !it.second.isEmpty() }
        val merged = ArrayList<IntRange>()
        for ((_, r) in clipped.sortedBy { it.second.first }) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else merged += r
        }
        if (merged.isEmpty() && sentenceCount > 0) merged += 0..sentenceCount - 1
        runStarts = IntArray(merged.size) { merged[it].first }
        runEnds = IntArray(merged.size) { merged[it].last }

        val leaves = clipped.filter { (s, r) ->
            clipped.none { (o, or) ->
                o.id != s.id && or.first >= r.first && or.last <= r.last && (or != r || o.level > s.level)
            }
        }.sortedBy { it.second.first }
        val minGap = (sentenceCount / 100).coerceAtLeast(1)
        val thinned = ArrayList<ChapterMark>()
        for ((s, r) in leaves) {
            val prev = thinned.lastOrNull()
            if (prev == null || r.first - prev.startOrdinal >= minGap) {
                thinned += ChapterMark(s.id, s.title, r.first, r.last)
            }
        }
        if (thinned.isEmpty() && sentenceCount > 0) thinned += ChapterMark(-1, bookTitle, 0, sentenceCount - 1)
        // Chapter ends run to the next mark; the last one runs to the end of the narrated text.
        marks = thinned.mapIndexed { i, m ->
            val end = if (i + 1 < thinned.size) thinned[i + 1].startOrdinal - 1 else lastNarrated
            m.copy(endOrdinal = end.coerceAtLeast(m.startOrdinal))
        }
        markStarts = IntArray(marks.size) { marks[it].startOrdinal }
    }

    val firstNarrated: Int get() = if (runStarts.isEmpty()) 0 else runStarts.first()
    val lastNarrated: Int get() = if (runEnds.isEmpty()) -1 else runEnds.last()

    fun isNarrated(ordinal: Int): Boolean = runIndexFor(ordinal) >= 0

    /** [ordinal] itself if narrated, otherwise the start of the next narrated run, or null at the end. */
    fun firstNarratedFrom(ordinal: Int): Int? {
        if (runStarts.isEmpty()) return null
        if (ordinal < runStarts.first()) return runStarts.first()
        val i = runIndexFor(ordinal)
        if (i >= 0) return ordinal
        val next = runStarts.indexOfFirst { it > ordinal }
        return if (next >= 0) runStarts[next] else null
    }

    /** Moves [delta] narrated sentences from [ordinal], clamped to the narrated text. Null if nothing is narrated. */
    fun step(ordinal: Int, delta: Int): Int? {
        if (runStarts.isEmpty()) return null
        var cur = firstNarratedFrom(ordinal) ?: runEnds.last()
        var left = delta
        while (left != 0) {
            val i = runIndexFor(cur)
            if (left > 0) {
                val room = runEnds[i] - cur
                if (left <= room) { cur += left; left = 0 }
                else if (i == runStarts.size - 1) { cur = runEnds[i]; left = 0 }
                else { left -= room + 1; cur = runStarts[i + 1] }
            } else {
                val room = cur - runStarts[i]
                if (-left <= room) { cur += left; left = 0 }
                else if (i == 0) { cur = runStarts[0]; left = 0 }
                else { left += room + 1; cur = runEnds[i - 1] }
            }
        }
        return cur
    }

    fun chapterAt(ordinal: Int): ChapterMark? {
        if (marks.isEmpty()) return null
        val i = markStarts.binarySearch(ordinal)
        val idx = if (i >= 0) i else (-i - 2).coerceAtLeast(0)
        return marks[idx]
    }

    private fun runIndexFor(ordinal: Int): Int {
        val i = runStarts.binarySearch(ordinal)
        val idx = if (i >= 0) i else -i - 2
        return if (idx >= 0 && ordinal <= runEnds[idx]) idx else -1
    }
}
