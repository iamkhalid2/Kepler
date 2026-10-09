package app.kepler.feature.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.bookMaterials
import app.kepler.core.model.ConceptMap
import app.kepler.core.model.ConceptNode

private val personKinds = setOf("person", "people", "character", "figure", "author")
private val otherKinds = setOf("place", "location", "organization", "organisation", "event", "work", "book")

private enum class NodeStyle { SLIP, CLOTH, OTHER }

private fun styleOf(kind: String) = when (kind.lowercase().trim()) {
    in personKinds -> NodeStyle.CLOTH
    in otherKinds -> NodeStyle.OTHER
    else -> NodeStyle.SLIP
}

/**
 * The concept map: edges are hairlines on a Canvas, nodes are small composables placed at their
 * normalised positions. Drag a node to move and pin it; tap to open its detail. Concepts are paper
 * slips, people cloth tabs, the third kind carries a `secondary` edge.
 */
@Composable
fun ConceptMapView(
    map: ConceptMap,
    positions: Map<String, MapPt>,
    pinned: Set<String>,
    selected: String?,
    onMove: (id: String, x: Float, y: Float) -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Kp.palette
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    val currentPositions by rememberUpdatedState(positions)
    val selectedEdges = remember(map, selected) {
        if (selected == null) emptySet() else map.edges.filter { it.from == selected || it.to == selected }.map { it.from to it.to }.toSet()
    }
    val mapLabel = androidx.compose.ui.res.stringResource(R.string.concept_map)
    Box(modifier.onSizeChanged { size = it }.semantics { contentDescription = mapLabel }) {
        Canvas(Modifier.matchParentSize()) {
            map.edges.forEach { e ->
                val a = positions[e.from] ?: return@forEach
                val b = positions[e.to] ?: return@forEach
                val hot = (e.from to e.to) in selectedEdges
                drawLine(
                    if (hot) p.accent else p.hairline,
                    Offset(a.x * this.size.width, a.y * this.size.height), Offset(b.x * this.size.width, b.y * this.size.height),
                    strokeWidth = (if (hot) 1.5f else 1f).dp.toPx(),
                )
            }
        }
        Layout(
            content = {
                map.nodes.forEach { n ->
                    MapNode(
                        n, n.id in pinned, n.id == selected,
                        onTap = { onSelect(n.id) },
                        onDrag = { dx, dy ->
                            val cur = currentPositions[n.id] ?: MapPt(0.5f, 0.5f)
                            onMove(n.id, cur.x + dx / size.width, cur.y + dy / size.height)
                        },
                    )
                }
            },
            modifier = Modifier.matchParentSize(),
        ) { measurables, c ->
            val loose = Constraints(maxWidth = (c.maxWidth * 0.42f).toInt().coerceAtLeast(1))
            val placeables = measurables.map { it.measure(loose) }
            val inset = 16.dp.roundToPx()
            val origins = placeables.mapIndexed { i, pl ->
                val pt = positions[map.nodes[i].id] ?: MapPt(0.5f, 0.5f)
                floatArrayOf(pt.x * c.maxWidth - pl.width / 2f, pt.y * c.maxHeight - pl.height / 2f)
            }
            separate(origins, placeables.map { it.width to it.height }, c.maxWidth, c.maxHeight, inset, gap = 8.dp.toPx())
            layout(c.maxWidth, c.maxHeight) {
                placeables.forEachIndexed { i, pl -> pl.place(IntOffset(origins[i][0].toInt(), origins[i][1].toInt())) }
            }
        }
    }
}

@Composable
private fun MapNode(node: ConceptNode, pinned: Boolean, selected: Boolean, onTap: () -> Unit, onDrag: (Float, Float) -> Unit) {
    val p = Kp.palette
    val style = styleOf(node.kind)
    val cloth = remember(p) { bookMaterials(p).firstOrNull() }
    val shape = RoundedCornerShape(2.dp)
    val (fill, ink) = when (style) {
        NodeStyle.CLOTH -> (cloth?.cloth ?: p.surface2) to (cloth?.ink ?: p.text)
        else -> p.page to p.text
    }
    val border = when {
        selected -> p.accent
        style == NodeStyle.OTHER -> p.secondary
        else -> p.hairline
    }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .pointerInput(node.id) { detectTapGestures { onTap() } }
            .pointerInput(node.id) { detectDragGestures { change, d -> change.consume(); onDrag(d.x, d.y) } }
            .padding(vertical = 8.dp),
    ) {
        Box(
            Modifier.background(fill, shape).border(if (selected || style == NodeStyle.OTHER) 2.dp else 1.dp, border, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    node.label, Modifier.weight(1f, fill = false),
                    style = StudyType.keyLabel(ink, 13f).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W500),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (pinned) Box(Modifier.padding(start = 6.dp).size(6.dp).background(ink.copy(alpha = 0.6f), CircleShape))
            }
        }
    }
}

/**
 * The force layout works in normalised units and knows nothing of label sizes, so labels can
 * overlap or run off the edge. Nudge the placed boxes apart along their shallower overlap and keep
 * them [inset] inside the map.
 */
internal fun separate(origins: List<FloatArray>, sizes: List<Pair<Int, Int>>, width: Int, height: Int, inset: Int, gap: Float) {
    fun clamp(i: Int) {
        val (w, h) = sizes[i]
        origins[i][0] = origins[i][0].coerceIn(inset.toFloat(), (width - w - inset).toFloat().coerceAtLeast(inset.toFloat()))
        origins[i][1] = origins[i][1].coerceIn(inset.toFloat(), (height - h - inset).toFloat().coerceAtLeast(inset.toFloat()))
    }
    origins.indices.forEach(::clamp)
    repeat(40) {
        var moved = false
        for (i in origins.indices) for (j in i + 1 until origins.size) {
            val a = origins[i]; val b = origins[j]
            val (aw, ah) = sizes[i]; val (bw, bh) = sizes[j]
            val ox = minOf(a[0] + aw, b[0] + bw) - maxOf(a[0], b[0]) + gap
            val oy = minOf(a[1] + ah, b[1] + bh) - maxOf(a[1], b[1]) + gap
            if (ox <= 0f || oy <= 0f) continue
            moved = true
            if (oy <= ox) {
                val d = oy / 2f * if (a[1] <= b[1]) 1f else -1f
                a[1] -= d; b[1] += d
            } else {
                val d = ox / 2f * if (a[0] <= b[0]) 1f else -1f
                a[0] -= d; b[0] += d
            }
            clamp(i); clamp(j)
        }
        if (!moved) return
    }
}
