package app.kepler.feature.player

import app.kepler.core.model.ChapterMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerFormatTest {
    @Test fun clockUsesMinutesThenHours() {
        assertEquals("0:00", PlayerFormat.clock(0))
        assertEquals("0:09", PlayerFormat.clock(9_400))
        assertEquals("12:05", PlayerFormat.clock(725_000))
        assertEquals("1:02:03", PlayerFormat.clock(3_723_000))
        assertEquals("0:00", PlayerFormat.clock(-5))
    }

    @Test fun speedLabelDropsNeedlessDecimals() {
        assertEquals("1×", PlayerFormat.speed(1f))
        assertEquals("1.4×", PlayerFormat.speed(1.4f))
        assertEquals("1.25×", PlayerFormat.speed(1.25f))
        assertEquals("3.5×", PlayerFormat.speed(3.5f))
    }

    @Test fun stepSpeedStaysInRange() {
        assertEquals(1.1f, PlayerFormat.stepSpeed(1f, 0.1f), 0.001f)
        assertEquals(0.5f, PlayerFormat.stepSpeed(0.55f, -0.1f), 0.001f)
        assertEquals(3.5f, PlayerFormat.stepSpeed(3.45f, 0.1f), 0.001f)
    }

    @Test fun durationRoundsMinutesUp() {
        assertEquals(0 to 0, PlayerFormat.durationParts(0))
        assertEquals(0 to 1, PlayerFormat.durationParts(5_000))
        assertEquals(0 to 18, PlayerFormat.durationParts(17 * 60_000L + 1))
        assertEquals(2 to 10, PlayerFormat.durationParts(130 * 60_000L))
    }

    private val marks = listOf(
        ChapterMark(1, "One", 0, 99),
        ChapterMark(2, "Two", 100, 199),
        ChapterMark(3, "Three", 200, 299),
    )

    @Test fun chapterIndexFindsTheHoldingChapter() {
        assertEquals(0, PlayerFormat.chapterIndex(marks, 0))
        assertEquals(1, PlayerFormat.chapterIndex(marks, 150))
        assertEquals(2, PlayerFormat.chapterIndex(marks, 299))
        assertEquals(-1, PlayerFormat.chapterIndex(emptyList(), 5))
    }

    @Test fun previousChapterRestartsOnceTheChapterIsUnderway() {
        assertEquals(100, PlayerFormat.previousChapterTarget(marks, 150, sectionElapsedMs = 60_000))
        assertEquals(0, PlayerFormat.previousChapterTarget(marks, 101, sectionElapsedMs = 1_000))
        assertEquals(0, PlayerFormat.previousChapterTarget(marks, 3, sectionElapsedMs = 500))
    }

    @Test fun nextChapterStopsAtTheLast() {
        assertEquals(100, PlayerFormat.nextChapterTarget(marks, 0))
        assertEquals(200, PlayerFormat.nextChapterTarget(marks, 150))
        assertNull(PlayerFormat.nextChapterTarget(marks, 250))
    }
}
