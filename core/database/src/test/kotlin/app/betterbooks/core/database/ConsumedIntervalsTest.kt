package app.betterbooks.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumedIntervalsTest {
    private fun r(a: Int, b: Int) = OrdinalRange(a, b)

    @Test fun mergesOverlappingAndAdjacent() {
        val out = ConsumedIntervals.normalize(listOf(r(10, 12), r(0, 4), r(5, 9), r(20, 25), r(22, 30)))
        assertEquals(listOf(r(0, 12), r(20, 30)), out)
    }

    @Test fun addBridgesGap() {
        val out = ConsumedIntervals.add(listOf(r(0, 4), r(8, 12)), r(5, 7))
        assertEquals(listOf(r(0, 12)), out)
    }

    @Test fun addInsideExistingChangesNothing() {
        assertEquals(listOf(r(0, 10)), ConsumedIntervals.add(listOf(r(0, 10)), r(3, 4)))
    }

    @Test fun handlesIntMaxWithoutOverflow() {
        val out = ConsumedIntervals.normalize(listOf(r(0, Int.MAX_VALUE), r(5, 6)))
        assertEquals(listOf(r(0, Int.MAX_VALUE)), out)
    }

    @Test fun frontierIsEndOfRunContainingStart() {
        val consumed = listOf(r(2, 9), r(11, 20))
        assertEquals(9, ConsumedIntervals.frontier(consumed, 2))
        assertEquals(9, ConsumedIntervals.frontier(consumed, 5))
    }

    @Test fun frontierIsMinusOneWhenStartNotConsumed() {
        assertEquals(-1, ConsumedIntervals.frontier(listOf(r(5, 9)), 2))
        assertEquals(-1, ConsumedIntervals.frontier(emptyList(), 0))
    }

    @Test fun frontierIgnoresJumpsAhead() {
        assertEquals(4, ConsumedIntervals.frontier(listOf(r(0, 4), r(100, 200)), 0))
    }

    @Test fun frontierGrowsWhenGapClosed() {
        val c = ConsumedIntervals.add(listOf(r(0, 4), r(100, 200)), r(5, 99))
        assertEquals(200, ConsumedIntervals.frontier(c, 0))
    }

    @Test fun fractionIntersectsWithScope() {
        val scope = listOf(r(10, 19), r(30, 39)) // 20 sentences
        val consumed = listOf(r(0, 14), r(35, 100)) // 5 + 5 inside scope
        assertEquals(0.5f, ConsumedIntervals.fraction(consumed, scope), 1e-6f)
    }

    @Test fun fractionEmptyScopeIsZero() {
        assertEquals(0f, ConsumedIntervals.fraction(listOf(r(0, 5)), emptyList()), 0f)
    }

    @Test fun fractionFullIsOne() {
        assertEquals(1f, ConsumedIntervals.fraction(listOf(r(0, 99)), listOf(r(0, 49))), 0f)
    }
}
