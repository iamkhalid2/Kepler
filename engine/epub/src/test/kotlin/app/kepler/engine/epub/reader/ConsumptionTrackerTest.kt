package app.kepler.engine.epub.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumptionTrackerTest {
    // 10 words per sentence: a 10-sentence page needs 100 * 85 ms = 8.5 s.
    private val tracker = ConsumptionTracker(words = { from, to -> (to - from + 1) * 10 })

    @Test fun dwellScalesWithWords() {
        assertEquals(1_500, tracker.requiredDwellMs(0, 0))
        assertEquals(8_500, tracker.requiredDwellMs(0, 9))
        assertEquals(45_000, tracker.requiredDwellMs(0, 999))
    }

    @Test fun readingForwardMarksEachPage() {
        assertEquals(emptyList<IntRange>(), tracker.onPage(0, 0))
        assertEquals(listOf(0..9), tracker.onPage(10, 9_000))
        assertEquals(listOf(10..19), tracker.onPage(20, 18_000))
    }

    @Test fun skimmedPageIsNotCounted() {
        tracker.onPage(0, 0)
        assertEquals(emptyList<IntRange>(), tracker.onPage(10, 1_000))
    }

    @Test fun jumpNeedsTwoContiguousPagesThenCommitsBoth() {
        tracker.onPage(0, 0)
        assertEquals(emptyList<IntRange>(), tracker.onPage(500, 1_000, jump = true))
        assertEquals(emptyList<IntRange>(), tracker.onPage(510, 10_000))
        assertEquals(listOf(500..509, 510..519), tracker.onPage(520, 19_000))
        assertEquals(listOf(520..529), tracker.onPage(530, 28_000))
    }

    @Test fun secondJumpDiscardsHeldPages() {
        tracker.onPage(0, 0, jump = true)
        tracker.onPage(10, 9_000)
        tracker.onPage(300, 9_500, jump = true)
        assertEquals(emptyList<IntRange>(), tracker.onPage(310, 19_000))
        assertEquals(listOf(300..309, 310..319), tracker.onPage(320, 28_000))
    }

    @Test fun largeForwardMoveIsTreatedAsJump() {
        tracker.onPage(0, 0)
        assertEquals(emptyList<IntRange>(), tracker.onPage(1_000, 60_000))
        assertEquals(emptyList<IntRange>(), tracker.onPage(1_010, 70_000))
    }

    @Test fun goingBackDoesNotMarkOrBreakContiguity() {
        tracker.onPage(0, 0)
        tracker.onPage(10, 9_000)
        assertEquals(emptyList<IntRange>(), tracker.onPage(0, 10_000))
        assertEquals(listOf(0..9), tracker.onPage(10, 19_000))
    }

    @Test fun pausedTimeDoesNotCount() {
        tracker.onPage(0, 0)
        tracker.pause()
        tracker.resume(100_000)
        assertEquals(emptyList<IntRange>(), tracker.onPage(10, 101_000))
    }
}
