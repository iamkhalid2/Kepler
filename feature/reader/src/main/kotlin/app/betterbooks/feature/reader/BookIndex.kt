package app.betterbooks.feature.reader

import app.betterbooks.core.model.Section
import app.betterbooks.core.model.Sentence

/** Word counts and section lookups over the Text Spine, for progress, ticks and dwell times. */
internal class BookIndex(sentences: List<Sentence>, sections: List<Section>) {
    private val ordinals: IntArray
    /** prefix[i] = words in the first i sentences (by ordinal order). */
    private val prefix: LongArray

    init {
        val sorted = sentences.sortedBy { it.ordinal }
        ordinals = IntArray(sorted.size) { sorted[it].ordinal }
        prefix = LongArray(sorted.size + 1)
        sorted.forEachIndexed { i, s -> prefix[i + 1] = prefix[i] + countWords(s.text) }
    }

    private val included = sections.filter { it.included }.sortedBy { it.startOrdinal }
    private val topLevel = included.minOfOrNull { it.level }

    val sentenceCount: Int get() = ordinals.size
    val firstOrdinal: Int get() = ordinals.firstOrNull() ?: 0
    val lastOrdinal: Int get() = ordinals.lastOrNull() ?: 0

    /** Top-level sections, used for the chapter ticks on the progress bar. */
    val chapters: List<Section> = included.filter { it.level == topLevel }

    /** The deepest included section that contains [ordinal]. */
    fun sectionAt(ordinal: Int): Section? =
        included.filter { ordinal in it.startOrdinal..it.endOrdinal }.maxByOrNull { it.level }

    fun chapterAt(ordinal: Int): Section? = chapters.lastOrNull { it.startOrdinal <= ordinal }

    /** Words in sentences [from, to] inclusive. */
    fun words(from: Int, to: Int): Int {
        if (ordinals.isEmpty() || to < from) return 0
        val a = indexOf(from)
        val b = indexOf(to, upper = true)
        return if (b < a) 0 else (prefix[b + 1] - prefix[a]).toInt()
    }

    fun fraction(ordinal: Int): Float {
        if (ordinals.size <= 1) return 0f
        return (indexOf(ordinal).toFloat() / (ordinals.size - 1)).coerceIn(0f, 1f)
    }

    fun fractionOf(ordinal: Int): Float = fraction(ordinal)

    /** Minutes left until the end of the current chapter at [wpm]. */
    fun minutesLeftInChapter(ordinal: Int, wpm: Int = DEFAULT_WPM): Double {
        val end = chapterAt(ordinal)?.let { ch -> chapters.firstOrNull { it.startOrdinal > ch.startOrdinal }?.startOrdinal?.minus(1) }
            ?: lastOrdinal
        return words(ordinal, end).toDouble() / wpm
    }

    /** Index of the sentence at [ordinal], or of its nearest neighbour when absent. */
    private fun indexOf(ordinal: Int, upper: Boolean = false): Int {
        var lo = 0
        var hi = ordinals.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                ordinals[mid] < ordinal -> lo = mid + 1
                ordinals[mid] > ordinal -> hi = mid - 1
                else -> return mid
            }
        }
        return (if (upper) hi else lo).coerceIn(0, ordinals.size - 1)
    }

    companion object {
        const val DEFAULT_WPM = 230

        fun countWords(text: String): Int {
            var n = 0
            var inWord = false
            for (c in text) {
                if (c.isLetterOrDigit()) {
                    if (!inWord) n++
                    inWord = true
                } else if (c.isWhitespace()) {
                    inWord = false
                }
            }
            return n
        }
    }
}
