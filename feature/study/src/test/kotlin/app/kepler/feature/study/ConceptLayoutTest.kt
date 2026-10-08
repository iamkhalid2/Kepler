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
}
