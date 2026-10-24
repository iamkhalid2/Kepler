package app.kepler.engine.playback

import app.kepler.core.model.AudioSegment
import app.kepler.core.model.ChapterMark

/** What the estimator needs to know about one sentence's text. */
class SentenceInfo(val ordinal: Int, val words: Int, val paragraph: Int)

object SpeechRate {
    /** About 2.6 words per second at 1x, matching the narration planner (shared with Home and the hub). */
    const val DEFAULT_MS_PER_WORD = app.kepler.core.model.ListenPace.DEFAULT_MS_PER_WORD

    /** Fewer known words than this say nothing reliable about the pace of a voice. */
    const val MIN_CALIBRATION_WORDS = 40
}

/**
 * Time layout of one chapter in media milliseconds at 1x, from the narration that exists and an estimate
 * for the narrated sentences that have no audio yet. Pure and immutable; the engine rebuilds it when
 * segments or the chapter change. Segments are one paragraph each and never cross a chapter, but a
 * segment that does overlap the edge is pro-rated by its sentences.
 *
 * Gaps (the pauses the player inserts between segments) are counted at the paragraph pause, so the
 * layout is close to, not identical with, the player's own playlist.
 */
class ChapterTimeline private constructor(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val blocks: List<Block>,
    /** The pace used for estimated blocks: calibrated from this chapter's own audio when possible. */
    val msPerWord: Double,
    /** True when [msPerWord] was measured from this chapter's own audio rather than assumed. */
    val calibrated: Boolean = false,
) {
    /**
     * A run of consecutive narrated sentences. [segmentId] is set when [audioMs] is real audio and null
     * when it is estimated. [skipMs] is the part of the segment's audio before this block (0 unless a
     * segment sticks out of the chapter). [gapMs] is the pause that follows before the next block.
     */
    data class Block(
        val startOrdinal: Int,
        val endOrdinal: Int,
        val startMs: Long,
        val audioMs: Long,
        val gapMs: Long,
        val segmentId: Long?,
        val skipMs: Long = 0,
    ) {
        val endMs: Long get() = startMs + audioMs + gapMs
        val sentences: Int get() = endOrdinal - startOrdinal + 1
        val estimated: Boolean get() = segmentId == null
    }

    /** Where a chapter time points to: the segment and offset in it, or just an ordinal for estimated parts. */
    data class Target(val ordinal: Int, val segmentId: Long?, val offsetInSegmentMs: Long, val estimated: Boolean)

    val durationMs: Long = blocks.lastOrNull()?.let { it.startMs + it.audioMs + it.gapMs } ?: 0L
    val estimated: Boolean = blocks.any { it.estimated }
    private val bySegment: Map<Long, Block> = blocks.filter { it.segmentId != null }.associateBy { it.segmentId!! }
    val isEmpty: Boolean get() = blocks.isEmpty()

    /** Chapter time of the start of [ordinal] (spread evenly inside its block). */
    fun elapsedAtOrdinal(ordinal: Int): Long {
        if (blocks.isEmpty()) return 0
        val i = blockIndexOf(ordinal)
        val b = blocks[i]
        if (ordinal < b.startOrdinal) return b.startMs
        if (ordinal > b.endOrdinal) return b.startMs + b.audioMs
        return b.startMs + b.audioMs * (ordinal - b.startOrdinal) / b.sentences
    }

    /** Chapter time at [offsetMs] into the audio of [segmentId]; null when that segment is not part of the chapter. */
    fun elapsedInSegment(segmentId: Long, offsetMs: Long): Long? {
        val b = bySegment[segmentId] ?: return null
        return b.startMs + (offsetMs - b.skipMs).coerceIn(0, b.audioMs)
    }

    /** Chapter time [intoGapMs] after the audio of the block that holds [ordinal] ended (the pause that follows it). */
    fun elapsedInGapAfter(ordinal: Int, intoGapMs: Long): Long {
        if (blocks.isEmpty()) return 0
        val b = blocks[blockIndexOf(ordinal)]
        return b.startMs + b.audioMs + intoGapMs.coerceIn(0, b.gapMs)
    }

    /** The segment/offset or ordinal at chapter time [ms]. Times inside a pause land on the next block. */
    fun locate(ms: Long): Target? {
        if (blocks.isEmpty()) return null
        val t = ms.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
        var lo = 0
        var hi = blocks.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid].startMs <= t) lo = mid else hi = mid - 1
        }
        var b = blocks[lo]
        var into = t - b.startMs
        if (into >= b.audioMs && lo < blocks.lastIndex) {
            b = blocks[lo + 1]
            into = 0
        }
        into = into.coerceIn(0, (b.audioMs - 1).coerceAtLeast(0))
        val share = if (b.audioMs <= 0) 0.0 else into.toDouble() / b.audioMs
        val ordinal = (b.startOrdinal + (share * b.sentences).toInt()).coerceIn(b.startOrdinal, b.endOrdinal)
        return Target(ordinal, b.segmentId, if (b.segmentId != null) b.skipMs + into else 0, b.estimated)
    }

    private fun blockIndexOf(ordinal: Int): Int {
        var lo = 0
        var hi = blocks.lastIndex
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (blocks[mid].startOrdinal <= ordinal) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        val EMPTY = ChapterTimeline(0, -1, emptyList(), SpeechRate.DEFAULT_MS_PER_WORD)

        /**
         * @param narrated narrated runs (sorted, any extent; they are clipped to the chapter).
         * @param segments the active segments of the book, sorted by start ordinal.
         * @param sentences text facts for this chapter's sentences, or null when not loaded yet.
         * @param msPerSentence fallback for estimating a sentence without text.
         */
        fun build(
            startOrdinal: Int,
            endOrdinal: Int,
            narrated: List<IntRange>,
            segments: List<AudioSegment>,
            sentences: List<SentenceInfo>?,
            fallbackMsPerWord: Double = SpeechRate.DEFAULT_MS_PER_WORD,
            msPerSentence: Double = BookTimeline.DEFAULT_MS_PER_SENTENCE,
            paragraphGapMs: Long = GapPolicy().paragraphMs,
        ): ChapterTimeline {
            val runs = narrated.mapNotNull { r ->
                val a = maxOf(r.first, startOrdinal)
                val b = minOf(r.last, endOrdinal)
                if (a <= b) a..b else null
            }
            if (runs.isEmpty()) return EMPTY
            val relevant = segments.filter { it.endOrdinal >= runs.first().first && it.startOrdinal <= runs.last().last }

            class Piece(val range: IntRange, val segment: AudioSegment?)
            val pieces = ArrayList<Piece>()
            for (run in runs) {
                var cursor = run.first
                for (s in relevant) {
                    if (cursor > run.last) break
                    if (s.endOrdinal < cursor || s.startOrdinal > run.last) continue
                    if (s.startOrdinal > cursor) pieces += Piece(cursor..minOf(s.startOrdinal - 1, run.last), null)
                    val from = maxOf(s.startOrdinal, cursor)
                    val to = minOf(s.endOrdinal, run.last)
                    pieces += Piece(from..to, s)
                    cursor = to + 1
                }
                if (cursor <= run.last) pieces += Piece(cursor..run.last, null)
            }

            val base = sentences?.minOfOrNull { it.ordinal }
            val info = if (sentences == null || base == null) null else {
                val arr = arrayOfNulls<SentenceInfo>((sentences.maxOf { it.ordinal } - base + 1))
                for (s in sentences) arr[s.ordinal - base] = s
                arr
            }
            fun infoAt(o: Int): SentenceInfo? {
                val b = base ?: return null
                return if (o < b) null else info?.getOrNull(o - b)
            }

            fun knownAudio(p: Piece): Long {
                val s = p.segment!!
                val size = s.endOrdinal - s.startOrdinal + 1
                return s.durationMs * p.range.count() / size
            }

            var knownMs = 0L
            var knownWords = 0
            for (p in pieces) if (p.segment != null) {
                knownMs += knownAudio(p)
                for (o in p.range) knownWords += infoAt(o)?.words ?: 0
            }
            val calibrated = knownWords >= SpeechRate.MIN_CALIBRATION_WORDS && knownMs > 0
            val msPerWord = if (calibrated) knownMs.toDouble() / knownWords else fallbackMsPerWord

            fun estimate(range: IntRange): Long {
                var total = 0.0
                var prevParagraph = Int.MIN_VALUE
                for (o in range) {
                    val i = infoAt(o)
                    total += if (i != null) i.words * msPerWord else msPerSentence
                    if (i != null) {
                        if (prevParagraph != Int.MIN_VALUE && i.paragraph != prevParagraph) total += paragraphGapMs
                        prevParagraph = i.paragraph
                    }
                }
                return total.toLong()
            }

            val blocks = ArrayList<Block>(pieces.size)
            var t = 0L
            for ((k, p) in pieces.withIndex()) {
                val gap = if (k == pieces.lastIndex) 0L else paragraphGapMs
                val s = p.segment
                val block = if (s != null) {
                    val size = s.endOrdinal - s.startOrdinal + 1
                    Block(p.range.first, p.range.last, t, knownAudio(p), gap, s.id, s.durationMs * (p.range.first - s.startOrdinal) / size)
                } else Block(p.range.first, p.range.last, t, estimate(p.range), gap, null)
                blocks += block
                t = block.endMs
            }
            return ChapterTimeline(startOrdinal, endOrdinal, blocks, msPerWord, calibrated)
        }
    }
}

/**
 * Time layout of the whole narrated book, one number per chapter, cheap enough to rebuild whenever the
 * set of segments changes. Estimated parts use the book's average sentence length, not the text.
 */
class BookTiming private constructor(
    val marks: List<ChapterMark>,
    private val chapterMs: LongArray,
    private val chapterEstimated: BooleanArray,
    private val prefix: LongArray,
) {
    val totalMs: Long get() = prefix.last()
    val isEmpty: Boolean get() = marks.isEmpty()

    fun chapterDurationMs(index: Int): Long = chapterMs[index]

    /** Narrated time of all chapters before [index]. */
    fun beforeMs(index: Int): Long = prefix[index]

    /** Narrated time of all chapters after [index]. */
    fun afterMs(index: Int): Long = prefix.last() - prefix[index + 1]

    /** True when anything after [index] is estimated. */
    fun estimatedAfter(index: Int): Boolean {
        for (i in index + 1 until chapterEstimated.size) if (chapterEstimated[i]) return true
        return false
    }

    /** The chapter index and the time inside it at book time [ms]. */
    fun locate(ms: Long): Pair<Int, Long>? {
        if (marks.isEmpty()) return null
        val t = ms.coerceIn(0, (totalMs - 1).coerceAtLeast(0))
        var lo = 0
        var hi = marks.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (prefix[mid] <= t) lo = mid else hi = mid - 1
        }
        return lo to (t - prefix[lo])
    }

    companion object {
        val EMPTY = BookTiming(emptyList(), LongArray(0), BooleanArray(0), longArrayOf(0))

        fun build(
            marks: List<ChapterMark>,
            narratedCount: (start: Int, end: Int) -> Int,
            segments: List<AudioSegment>,
            msPerSentence: Double = BookTimeline.DEFAULT_MS_PER_SENTENCE,
            paragraphGapMs: Long = GapPolicy().paragraphMs,
        ): BookTiming {
            if (marks.isEmpty()) return EMPTY
            val starts = IntArray(marks.size) { marks[it].startOrdinal }
            val knownMs = LongArray(marks.size)
            val knownSentences = IntArray(marks.size)
            val segmentCount = IntArray(marks.size)
            for (s in segments) {
                val i = starts.binarySearch(s.startOrdinal).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
                knownMs[i] += s.durationMs
                knownSentences[i] += s.endOrdinal - s.startOrdinal + 1
                segmentCount[i]++
            }
            val ms = LongArray(marks.size)
            val est = BooleanArray(marks.size)
            val prefix = LongArray(marks.size + 1)
            for (i in marks.indices) {
                val narrated = narratedCount(marks[i].startOrdinal, marks[i].endOrdinal)
                val unknown = (narrated - knownSentences[i]).coerceAtLeast(0)
                val gaps = (segmentCount[i] - 1).coerceAtLeast(0) * paragraphGapMs
                ms[i] = knownMs[i] + gaps + (unknown * msPerSentence).toLong()
                est[i] = unknown > 0
                prefix[i + 1] = prefix[i] + ms[i]
            }
            return BookTiming(marks, ms, est, prefix)
        }
    }
}

/**
 * Keeps the book's "time left" from wobbling. The raw figure is an estimate that is rebuilt whenever a segment lands or
 * the calibrated pace changes, so it jumps both ways. While playback moves forward, the shown value falls with the audio
 * that has played and takes a lower raw figure at once, but never rises. A seek or a new book calls [reset].
 */
class RemainingClock(private val maxStepMs: Long = 5_000) {
    private var shown = -1L
    private var sectionId: Long? = null
    private var elapsedMs = 0L

    fun reset() { shown = -1 }

    fun next(rawRemainingMs: Long, sectionId: Long?, sectionElapsedMs: Long): Long {
        val step = if (sectionId == this.sectionId) sectionElapsedMs - elapsedMs else 0L
        this.sectionId = sectionId
        elapsedMs = sectionElapsedMs
        if (shown < 0 || step < 0 || step > maxStepMs) {
            // Nothing shown yet, or the playhead jumped (a seek): trust the new figure.
            shown = rawRemainingMs
            return shown
        }
        shown = minOf(rawRemainingMs, (shown - step).coerceAtLeast(0))
        return shown
    }
}
