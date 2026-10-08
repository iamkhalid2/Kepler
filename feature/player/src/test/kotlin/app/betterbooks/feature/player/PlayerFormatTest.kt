package app.betterbooks.feature.player

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

    @Test fun minutesAheadFollowsSpeed() {
        assertEquals(10, PlayerFormat.minutesAhead(600_000, 1f))
        assertEquals(5, PlayerFormat.minutesAhead(600_000, 2f))
        assertEquals(0, PlayerFormat.minutesAhead(30_000, 1f))
    }

    @Test fun scrubberMapsBetweenOrdinalsAndFractions() {
        assertEquals(0f, PlayerFormat.fractionOf(0, 101), 0f)
        assertEquals(0.5f, PlayerFormat.fractionOf(50, 101), 0.0001f)
        assertEquals(1f, PlayerFormat.fractionOf(100, 101), 0f)
        assertEquals(50, PlayerFormat.ordinalAt(0.5f, 101))
        assertEquals(100, PlayerFormat.ordinalAt(2f, 101))
        assertEquals(0, PlayerFormat.ordinalAt(0.5f, 1))
    }

    @Test fun detentsSnapOnlyWhenClose() {
        val ticks = listOf(0f, 0.25f, 0.6f)
        assertEquals(0.25f, PlayerFormat.nearestTick(0.26f, ticks, 0.02f)!!, 0f)
        assertNull(PlayerFormat.nearestTick(0.4f, ticks, 0.02f))
    }
}
