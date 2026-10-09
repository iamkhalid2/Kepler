package app.kepler.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfLayoutTest {
    private fun books(n: Int) = List(n) { ShelfBookFacts(it.toLong() + 1, 30f) }

    @Test fun oneBookStandsFaceOutWithTheGhostBesideIt() {
        val plan = ShelfLayout.plan(books(1), 342f)
        assertEquals(1, plan.size)
        assertTrue(plan[0].books.single().faceOut)
        val ghost = plan[0].ghost!!
        assertTrue(ghost.x >= plan[0].books.single().width)
    }

    @Test fun onlyTheFirstBookIsFaceOutAndNothingOverflows() {
        val plan = ShelfLayout.plan(books(80), 342f)
        val placed = plan.flatMap { it.books }
        assertEquals(80, placed.size)
        assertEquals(listOf(0), placed.filter { it.faceOut }.map { it.index })
        plan.forEach { row -> row.books.forEach { assertTrue(it.x + it.width <= 342f + 0.01f) } }
        assertEquals((0 until 80).toList(), placed.map { it.index })
    }

    @Test fun exactlyOneSpineLeansAndItRestsOnItsNeighbour() {
        val plan = ShelfLayout.plan(books(5), 342f)
        val leaning = plan.flatMap { it.books }.filter { it.lean != 0f }
        assertEquals(1, leaning.size)
        val row = plan.first { it.books.contains(leaning[0]) }.books
        val n = row[row.size - 2]
        val gap = leaning[0].x - (n.x + n.width)
        assertEquals(ShelfLayout.leanGap(leaning[0].height, n.height), gap, 0.01f)
    }

    @Test fun ghostMovesToANewPlankWhenTheLastIsFull() {
        val plan = ShelfLayout.plan(books(1), 105f)
        assertTrue(plan.last().books.isEmpty())
        assertNotNull(plan.last().ghost)
    }

    @Test fun heightsAreStableAndNatural() {
        val h = (1L..50L).map(ShelfLayout::spineHeight)
        assertEquals(h, (1L..50L).map(ShelfLayout::spineHeight))
        assertTrue(h.all { it in 130f..150f })
        assertTrue(h.toSet().size > 2)
    }

    @Test fun planksFillTheViewport() {
        assertEquals(4, ShelfLayout.plankCount(1, 700f))
        assertEquals(7, ShelfLayout.plankCount(7, 700f))
    }
}
