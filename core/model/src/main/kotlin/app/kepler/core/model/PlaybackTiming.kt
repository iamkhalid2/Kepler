package app.kepler.core.model

/** What the player scrubber covers. CHAPTER is the default (decision #109). */
enum class ScrubberScope { CHAPTER, BOOK }

/** 0..1 progress through the current chapter. */
val PlaybackState.sectionFraction: Float
    get() = if (sectionDurationMs <= 0) 0f else (sectionElapsedMs.toFloat() / sectionDurationMs).coerceIn(0f, 1f)

/** Chapter time left, in wall-clock milliseconds at the current speed. */
val PlaybackState.sectionRemainingWallMs: Long
    get() = ((sectionDurationMs - sectionElapsedMs).coerceAtLeast(0) / speed.coerceAtLeast(0.1f)).toLong()

/** Chapter time elapsed, in wall-clock milliseconds at the current speed. */
val PlaybackState.sectionElapsedWallMs: Long
    get() = (sectionElapsedMs / speed.coerceAtLeast(0.1f)).toLong()

/** Narrated time left in the book, in wall-clock milliseconds at the current speed. */
val PlaybackState.bookRemainingWallMs: Long
    get() = (bookRemainingMs / speed.coerceAtLeast(0.1f)).toLong()

/**
 * The one narration pace used wherever a time-left figure has no measured audio behind it yet (the player, Home and
 * the book hub). The player replaces it with the calibrated pace of the voice once it has audio to measure.
 */
object ListenPace {
    /** About 2.6 words per second at 1x, matching the narration planner. */
    const val DEFAULT_MS_PER_WORD = 385.0

    /** A typical sentence of running prose. */
    const val WORDS_PER_SENTENCE = 20

    /** Narrated time at 1x of a typical sentence nobody has measured yet. */
    const val DEFAULT_MS_PER_SENTENCE = WORDS_PER_SENTENCE * DEFAULT_MS_PER_WORD

    /** Narrated time at 1x for [sentences] sentences nobody has measured yet. */
    fun estimateMs(sentences: Int): Long = (sentences.coerceAtLeast(0) * DEFAULT_MS_PER_SENTENCE).toLong()

    /** Pace per sentence measured from the audio generated so far, or the default when there is none. */
    fun msPerSentence(segmentDurationsMs: Long, sentencesCovered: Int): Double =
        if (sentencesCovered <= 0 || segmentDurationsMs <= 0) DEFAULT_MS_PER_SENTENCE
        else segmentDurationsMs.toDouble() / sentencesCovered

    /** [msPerSentence] over a book's audio segments. */
    fun msPerSentence(segments: List<AudioSegment>): Double =
        msPerSentence(segments.sumOf { it.durationMs }, segments.sumOf { it.endOrdinal - it.startOrdinal + 1 })
}
