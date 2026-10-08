package app.kepler.feature.study

import app.kepler.core.model.ConceptMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Normalised position inside the map (0..1 on both axes). */
data class MapPt(val x: Float, val y: Float)

/**
 * A small deterministic force layout for the concept map. Nodes the user has pinned never move;
 * everything else relaxes around them. Runs on the VM thread: a few hundred iterations for a few dozen nodes.
 */
object ConceptLayout {
    const val MARGIN = 0.1f

    fun layout(
        map: ConceptMap,
        existing: Map<String, MapPt> = emptyMap(),
        pinned: Set<String> = emptySet(),
        iterations: Int = 260,
    ): Map<String, MapPt> {
        val ids = map.nodes.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        val n = ids.size
        val px = FloatArray(n); val py = FloatArray(n)
        ids.forEachIndexed { i, id ->
            val e = existing[id]
            if (e != null) { px[i] = e.x; py[i] = e.y } else {
                // Start on a ring ordered by first mention so the result is stable between runs.
                val a = 2.0 * PI * i / n
                px[i] = (0.5 + 0.32 * cos(a)).toFloat(); py[i] = (0.5 + 0.32 * sin(a)).toFloat()
            }
        }
        val index = ids.withIndex().associate { it.value to it.index }
        val edges = map.edges.mapNotNull { e ->
            val a = index[e.from]; val b = index[e.to]
            if (a != null && b != null && a != b) a to b else null
        }
        val fixed = BooleanArray(n) { ids[it] in pinned }
        val k = 0.9f / kotlin.math.sqrt(n.toFloat().coerceAtLeast(1f)) // ideal spacing
        repeat(iterations) { step ->
            val temp = 0.05f * (1f - step / iterations.toFloat()) + 0.002f
            val dx = FloatArray(n); val dy = FloatArray(n)
            for (i in 0 until n) for (j in i + 1 until n) {
                var vx = px[i] - px[j]; var vy = py[i] - py[j]
                var d = hypot(vx, vy)
                if (d < 1e-4f) { vx = 0.001f * (i - j); vy = 0.001f; d = hypot(vx, vy) }
                val f = k * k / d
                val fx = vx / d * f; val fy = vy / d * f
                dx[i] += fx; dy[i] += fy; dx[j] -= fx; dy[j] -= fy
            }
            for ((a, b) in edges) {
                val vx = px[a] - px[b]; val vy = py[a] - py[b]
                val d = hypot(vx, vy).coerceAtLeast(1e-4f)
                val f = d * d / k
                val fx = vx / d * f; val fy = vy / d * f
                dx[a] -= fx; dy[a] -= fy; dx[b] += fx; dy[b] += fy
            }
            for (i in 0 until n) {
                dx[i] += (0.5f - px[i]) * 0.4f; dy[i] += (0.5f - py[i]) * 0.4f // gentle pull to the centre
                if (fixed[i]) continue
                val d = hypot(dx[i], dy[i]).coerceAtLeast(1e-4f)
                val m = minOf(d, temp)
                px[i] = (px[i] + dx[i] / d * m).coerceIn(MARGIN, 1f - MARGIN)
                py[i] = (py[i] + dy[i] / d * m).coerceIn(MARGIN, 1f - MARGIN)
            }
        }
        return ids.indices.associate { ids[it] to MapPt(px[it], py[it]) }
    }
}
