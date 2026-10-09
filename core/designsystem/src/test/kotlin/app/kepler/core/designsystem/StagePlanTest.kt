package app.kepler.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePlanTest {
    private data class S(
        override val key: Any, override val owner: String, override val role: StageRole,
        override val tag: String, override val pose: StagePose,
    ) : SlotFacts

    private fun pose(h: Float) = StagePose(0f, 0f, 1f, h / BOOK_H, 0f, 0f, 20f)

    @Test fun facingFitsWidthOnAPhonePage() {
        val p = StagePlan.facing(0f, 0f, 1080f, 2400f, stageU = 1080f / 390f, thickness = 30f)
        assertEquals(540f, p.cx, 0.01f)
        assertEquals(1200f, p.cy, 0.01f)
        assertEquals(1080f * StagePlan.FACING_WIDTH, BOOK_W * p.scale * p.u, 0.5f)
        assertEquals(0f, p.ry, 0f)
    }

    @Test fun facingFitsHeightOnAWidePage() {
        val p = StagePlan.facing(0f, 0f, 2400f, 1080f, stageU = 1f, thickness = 30f)
        assertEquals(1080f * StagePlan.FACING_HEIGHT, p.heightPx, 0.5f)
    }

    @Test fun sourcePrefersTouchedTagThenLargest() {
        val small = S(1L, "a", StageRole.Book, "nowbar", pose(48f))
        val big = S(1L, "a", StageRole.Book, "home", pose(150f))
        val other = S(2L, "a", StageRole.Book, "home", pose(300f))
        assertEquals(small, StagePlan.pickSource(1L, listOf(small, big, other), "nowbar"))
        assertEquals(big, StagePlan.pickSource(1L, listOf(small, big, other), null))
        assertNull(StagePlan.pickSource(3L, listOf(small, big), null))
    }

    @Test fun targetReturnsToOriginTagThenPageThenLargest() {
        val bar = S(1L, "tabs", StageRole.Book, "nowbar", pose(48f))
        val home = S(1L, "tabs", StageRole.Book, "home", pose(150f))
        val page = S(1L, "reader", StageRole.Page, "page", pose(10f))
        val all = listOf(bar, home, page)
        assertEquals(bar, StagePlan.pickTarget(1L, "tabs", all, "nowbar"))
        assertEquals(home, StagePlan.pickTarget(1L, "tabs", all, "shelf"))
        assertEquals(page, StagePlan.pickTarget(1L, "reader", all, "hub"))
        assertNull(StagePlan.pickTarget(1L, "learn", all, null))
    }

    @Test fun movedIgnoresSubPixelJitter() {
        val a = pose(150f)
        assertFalse(StagePlan.moved(a, a.copy(cx = 0.3f)))
        assertTrue(StagePlan.moved(a, a.copy(cy = 2f)))
        assertTrue(StagePlan.moved(a, a.copy(ry = 1f)))
    }
}
