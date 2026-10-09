package app.kepler.engine.playback

import app.kepler.core.model.AudioSegment
import app.kepler.core.model.ChapterMark
import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import app.kepler.core.model.VoiceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val v = VoiceRef("fish", "ava", "Ava")
private fun seg(id: Long, start: Int, end: Int, ms: Long) = AudioSegment(id, 1, start, end, "/s/$id", ms, v, 0)
private const val GAP = 450L

class ChapterTimelineTest {
    private fun build(
        range: IntRange,
        segments: List<AudioSegment>,
        narrated: List<IntRange> = listOf(range),
        sentences: List<SentenceInfo>? = null,
    ) = ChapterTimeline.build(range.first, range.last, narrated, segments, sentences, msPerSentence = 1_000.0, paragraphGapMs = GAP)

    @Test fun fullyNarratedChapterIsExactAndNotEstimated() {
        val t = build(0..9, listOf(seg(1, 0, 3, 8_000), seg(2, 4, 9, 12_000)))
        assertEquals(8_000 + GAP + 12_000, t.durationMs)
        assertFalse(t.estimated)
        assertEquals(2, t.blocks.size)
        assertEquals(8_000 + GAP, t.blocks[1].startMs)
    }

    @Test fun elapsedFollowsTheSegmentOffset() {
        val t = build(0..9, listOf(seg(1, 0, 3, 8_000), seg(2, 4, 9, 12_000)))
        assertEquals(3_000L, t.elapsedInSegment(1, 3_000))
        assertEquals(8_000 + GAP + 500, t.elapsedInSegment(2, 500))
        assertNull(t.elapsedInSegment(99, 0))
        // the pause after segment one runs from 8000 to 8450 and never beyond
        assertEquals(8_000L, t.elapsedInGapAfter(3, 0))
        assertEquals(8_200L, t.elapsedInGapAfter(3, 200))
        assertEquals(8_000 + GAP, t.elapsedInGapAfter(3, 5_000))
        assertEquals(0L, t.elapsedAtOrdinal(0))
        assertEquals(8_000 + GAP + 2_000, t.elapsedAtOrdinal(5)) // 1 of 6 sentences into a 12 s block
    }

    @Test fun missingNarrationIsEstimatedAndFlagged() {
        val t = build(0..9, listOf(seg(1, 0, 3, 8_000)))
        assertTrue(t.estimated)
        val tail = t.blocks.last()
        assertTrue(tail.estimated)
        assertEquals(4..9, tail.startOrdinal..tail.endOrdinal)
        assertEquals(6_000L, tail.audioMs) // 6 sentences at the 1 s fallback
        assertEquals(8_000 + GAP + 6_000, t.durationMs)
    }

    @Test fun textBasedEstimateUsesWordsAndTheChaptersOwnPace() {
        // 60 known words in 12 s: 200 ms per word. The 30 unknown words are estimated at that pace.
        val text = (0..9).map { SentenceInfo(it, if (it < 6) 10 else 7, 0) } // 60 words known, 28 unknown
        val t = build(0..9, listOf(seg(1, 0, 5, 12_000)), sentences = text)
        assertTrue(t.calibrated)
        assertEquals(200.0, t.msPerWord, 1e-9)
        assertEquals(28 * 200L, t.blocks.last().audioMs)
    }

    @Test fun fewKnownWordsFallBackToTheDefaultPace() {
        val text = (0..9).map { SentenceInfo(it, 2, it / 5) }
        val t = build(0..9, listOf(seg(1, 0, 1, 1_000)), sentences = text)
        assertFalse(t.calibrated)
        assertEquals(SpeechRate.DEFAULT_MS_PER_WORD, t.msPerWord, 0.0)
        // 8 sentences x 2 words x 385 ms plus one paragraph break
        assertEquals((16 * 385.0).toLong() + GAP, t.blocks.last().audioMs)
    }

    @Test fun excludedTextBetweenRunsIsNotCounted() {
        val t = build(0..19, listOf(seg(1, 0, 4, 5_000), seg(2, 15, 19, 5_000)), narrated = listOf(0..4, 15..19))
        assertEquals(2, t.blocks.size)
        assertFalse(t.estimated)
        assertEquals(10_000 + GAP, t.durationMs)
    }

    @Test fun segmentStickingOutOfTheChapterIsProRated() {
        val t = build(5..9, listOf(seg(1, 0, 9, 10_000)))
        assertEquals(5_000L, t.durationMs)
        assertEquals(5_000L, t.blocks.single().skipMs)
        assertEquals(0L, t.elapsedInSegment(1, 5_000))
        assertEquals(1_500L, t.elapsedInSegment(1, 6_500))
        assertEquals(5_000L, t.elapsedInSegment(1, 99_000))
    }

    @Test fun locateMapsTimeBackToSegmentOffsets() {
        val t = build(0..9, listOf(seg(1, 0, 3, 8_000), seg(2, 4, 9, 12_000)))
        val a = t.locate(3_000)!!
        assertEquals(1L, a.segmentId)
        assertEquals(3_000L, a.offsetInSegmentMs)
        assertEquals(1, a.ordinal)
        assertFalse(a.estimated)
        // inside the pause: the next block's start
        val gap = t.locate(8_200)!!
        assertEquals(2L, gap.segmentId)
        assertEquals(0L, gap.offsetInSegmentMs)
        assertEquals(4, gap.ordinal)
        val b = t.locate(8_000 + GAP + 6_000)!!
        assertEquals(2L, b.segmentId)
        assertEquals(6_000L, b.offsetInSegmentMs)
        assertEquals(7, b.ordinal)
        // out of range clamps inside the chapter
        assertEquals(2L, t.locate(999_999)!!.segmentId)
        assertEquals(1L, t.locate(-5)!!.segmentId)
    }

    @Test fun locateInsideEstimatedPartGivesAnOrdinalOnly() {
        val t = build(0..9, listOf(seg(1, 0, 3, 8_000)))
        val target = t.locate(8_000 + GAP + 3_000)!!
        assertNull(target.segmentId)
        assertTrue(target.estimated)
        assertEquals(7, target.ordinal) // halfway through 4..9
    }

    @Test fun emptyChapterIsHarmless() {
        val t = build(0..9, emptyList(), narrated = emptyList())
        assertTrue(t.isEmpty)
        assertEquals(0L, t.durationMs)
        assertNull(t.locate(0))
        assertEquals(0L, t.elapsedAtOrdinal(3))
    }
}

class BookTimingTest {
    private val marks = listOf(
        ChapterMark(1, "One", 0, 9),
        ChapterMark(2, "Two", 10, 19),
        ChapterMark(3, "Three", 20, 29),
    )

    private fun build(segments: List<AudioSegment>) =
        BookTiming.build(marks, { a, b -> b - a + 1 }, segments, msPerSentence = 1_000.0, paragraphGapMs = GAP)

    @Test fun sumsKnownAudioAndEstimatesTheRest() {
        val t = build(listOf(seg(1, 0, 4, 5_000), seg(2, 5, 9, 5_000), seg(3, 10, 19, 20_000)))
        assertEquals(10_000 + GAP, t.chapterDurationMs(0))
        assertEquals(20_000L, t.chapterDurationMs(1))
        assertEquals(10_000L, t.chapterDurationMs(2)) // 10 unknown sentences
        assertEquals(10_000 + GAP + 20_000 + 10_000, t.totalMs)
        assertEquals(10_000 + GAP, t.beforeMs(1))
        assertEquals(10_000L, t.afterMs(1))
        assertTrue(t.estimatedAfter(1))
        assertFalse(t.estimatedAfter(2))
    }

    @Test fun locateFindsTheChapterAndTheTimeInside() {
        val t = build(listOf(seg(1, 0, 9, 10_000), seg(2, 10, 19, 20_000), seg(3, 20, 29, 30_000)))
        assertEquals(0 to 4_000L, t.locate(4_000))
        assertEquals(1 to 5_000L, t.locate(15_000))
        assertEquals(2 to 29_999L, t.locate(999_999))
        assertEquals(60_000L, t.totalMs)
    }

    @Test fun noChaptersMeansNoTiming() {
        val t = BookTiming.build(emptyList(), { _, _ -> 0 }, emptyList())
        assertTrue(t.isEmpty)
        assertNull(t.locate(0))
        assertEquals(0L, t.totalMs)
    }
}

class SectionIndexTimingTest {
    private fun sec(id: Long, from: Int, to: Int, included: Boolean = true) =
        Section(id, 1, null, id.toInt(), 0, "S$id", "h$id", SectionKind.BODY, from, to, included)

    private val index = SectionIndex(listOf(sec(1, 0, 9), sec(2, 10, 19, included = false), sec(3, 20, 29)), 30, "Book")

    @Test fun narratedRunsAndCountsAreClippedToTheRange() {
        assertEquals(listOf(0..9, 20..29), index.narratedRuns(0, 29))
        assertEquals(listOf(5..9, 20..22), index.narratedRuns(5, 22))
        assertEquals(20, index.narratedCount(0, 29))
        assertEquals(0, index.narratedCount(10, 19))
        assertEquals(10, index.narratedCount(2, 21))
    }

    @Test fun markIndexAtFindsTheChapter() {
        assertEquals(0, index.markIndexAt(3))
        assertEquals(1, index.markIndexAt(25))
        assertNotNull(index.marks.getOrNull(1))
    }
}
