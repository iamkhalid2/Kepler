package app.kepler.feature.study

import app.kepler.core.model.ConceptEdge
import app.kepler.core.model.ConceptMap
import app.kepler.core.model.ConceptNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConceptLayoutTest {
    private val map = ConceptMap(
        (0..7).map { ConceptNode("n$it", "N$it", "concept", "", it) },
        (0..6).map { ConceptEdge("n$it", "n${it + 1}", "next", it) },
    )

    @Test fun staysInsideTheMargins() {
        ConceptLayout.layout(map).values.forEach {
            assertTrue(it.x in ConceptLayout.MARGIN..1 - ConceptLayout.MARGIN)
            assertTrue(it.y in ConceptLayout.MARGIN..1 - ConceptLayout.MARGIN)
        }
    }

    @Test fun isDeterministic() = assertEquals(ConceptLayout.layout(map), ConceptLayout.layout(map))

    @Test fun pinnedNodesDoNotMove() {
        val first = ConceptLayout.layout(map)
        val pinnedAt = MapPt(0.2f, 0.8f)
        val again = ConceptLayout.layout(map, first + ("n3" to pinnedAt), setOf("n3"))
        assertEquals(pinnedAt, again["n3"])
    }

    @Test fun separatesNodes() {
        val pos = ConceptLayout.layout(map).values.toList()
        for (i in pos.indices) for (j in i + 1 until pos.size) {
            assertTrue(kotlin.math.hypot(pos[i].x - pos[j].x, pos[i].y - pos[j].y) > 0.02f)
        }
    }

    @Test fun emptyMapIsEmpty() = assertTrue(ConceptLayout.layout(ConceptMap(emptyList(), emptyList())).isEmpty())

    @Test fun separatedLabelsNeitherOverlapNorLeaveTheMap() {
        val sizes = List(4) { 200 to 60 }
        val origins = listOf(floatArrayOf(-50f, 900f), floatArrayOf(10f, 905f), floatArrayOf(60f, 910f), floatArrayOf(700f, 0f))
        separate(origins, sizes, width = 800, height = 1000, inset = 16, gap = 8f)
        origins.forEachIndexed { i, o ->
            assertTrue(o[0] >= 16f && o[0] + 200 <= 784f && o[1] >= 16f && o[1] + 60 <= 984f)
            for (j in i + 1 until origins.size) {
                val q = origins[j]
                val overlapX = minOf(o[0], q[0]) + 200 > maxOf(o[0], q[0])
                val overlapY = minOf(o[1], q[1]) + 60 > maxOf(o[1], q[1])
                assertTrue("boxes $i and $j overlap", !(overlapX && overlapY))
            }
        }
    }
}
