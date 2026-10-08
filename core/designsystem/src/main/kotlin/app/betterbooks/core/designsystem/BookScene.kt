package app.betterbooks.core.designsystem

import android.graphics.Matrix
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One book in the scene. Cover is BOOK_W x BOOK_H design units; [thickness] is the spine width. */
@Immutable
class BookSpec(
    val title: String,
    val author: String,
    val thickness: Float,
    val material: BookMaterial,
)

/**
 * Where a book is, in a 390-unit-wide design space (scaled to the real width at draw time).
 * [ry] is rotation about the vertical axis: 90 = spine to the viewer, ~25 = cover with the spine
 * visible on the left, 0 = flat cover, negative shows the page block.
 */
@Immutable
data class BookPose(
    val cx: Float, val cy: Float, val scale: Float,
    val ry: Float, val rx: Float = 0f, val alpha: Float = 1f,
)

const val BOOK_W = 150f
const val BOOK_H = 225f
private const val CAMERA = 1300f
private const val SPACE_W = 390f

private class PoseState(
    val cx: State<Float>, val cy: State<Float>, val scale: State<Float>,
    val ry: State<Float>, val rx: State<Float>, val alpha: State<Float>,
)

@Composable
private fun animatePose(t: BookPose): PoseState {
    val spec = spring<Float>(dampingRatio = 0.82f, stiffness = 170f)
    val rot = spring<Float>(dampingRatio = 0.78f, stiffness = 130f)
    return PoseState(
        animateFloatAsState(t.cx, spec, label = "cx"),
        animateFloatAsState(t.cy, spec, label = "cy"),
        animateFloatAsState(t.scale, spec, label = "s"),
        animateFloatAsState(t.ry, rot, label = "ry"),
        animateFloatAsState(t.rx, rot, label = "rx"),
        animateFloatAsState(t.alpha, spring(Spring.DampingRatioNoBouncy, 400f), label = "a"),
    )
}

/**
 * A set of real 3D book objects drawn on a single canvas (no 3D engine): each face is a textured quad
 * mapped through a perspective matrix, lit by a fixed light so shading changes as the book turns.
 * Targets change -> springs carry every book between poses (spine -> cover -> player ...).
 */
@Composable
fun BookScene(
    books: List<BookSpec>,
    targets: List<BookPose>,
    modifier: Modifier = Modifier,
    onTap: (index: Int) -> Unit = {},
) {
    val palette = Bb.palette
    val poses = books.indices.map { animatePose(targets[it]) }
    val measurer = rememberTextMeasurer()
    val matrix = remember { Matrix() }
    val src = remember { FloatArray(8) }
    val dst = remember { FloatArray(8) }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(books, targets) {
                    detectTapGestures { p ->
                        val u = size.width / SPACE_W
                        // top-most book first (drawn last = highest index among opaque ones)
                        for (i in books.indices.reversed()) {
                            val ps = poses[i]
                            if (ps.alpha.value < 0.3f) continue
                            val half = projectedHalfExtent(books[i], ps.ry.value, ps.scale.value)
                            val cx = ps.cx.value * u
                            val cy = ps.cy.value * u
                            if (abs(p.x - cx) <= (half.first + 6f) * u && abs(p.y - cy) <= (half.second + 6f) * u) {
                                onTap(i); return@detectTapGestures
                            }
                        }
                    }
                },
        ) {
            val u = size.width / SPACE_W
            // draw far-to-near by scale so a pulled-down book sits above the shelf
            books.indices.sortedBy { poses[it].scale.value }.forEach { i ->
                val ps = poses[i]
                if (ps.alpha.value > 0.01f) {
                    drawBook(
                        books[i], u, ps.cx.value, ps.cy.value, ps.scale.value,
                        ps.ry.value, ps.rx.value, ps.alpha.value, palette, measurer, matrix, src, dst,
                    )
                }
            }
        }
    }
}

private fun projectedHalfExtent(b: BookSpec, ry: Float, scale: Float): Pair<Float, Float> {
    val a = Math.toRadians(ry.toDouble()).toFloat()
    val w = abs(cos(a)) * BOOK_W + abs(sin(a)) * b.thickness
    return Pair(w / 2f * scale, BOOK_H / 2f * scale)
}

private class V3(val x: Float, val y: Float, val z: Float)

private fun DrawScope.drawBook(
    b: BookSpec, u: Float, cxD: Float, cyD: Float, scale: Float,
    ryDeg: Float, rxDeg: Float, alpha: Float,
    palette: BbPalette, tm: TextMeasurer, m: Matrix, src: FloatArray, dst: FloatArray,
) {
    val w = BOOK_W / 2f
    val h = BOOK_H / 2f
    val t = b.thickness / 2f
    val ry = Math.toRadians(ryDeg.toDouble()).toFloat()
    val rx = Math.toRadians(rxDeg.toDouble()).toFloat()
    val cYa = cos(ry); val sYa = sin(ry)
    val cXa = cos(rx); val sXa = sin(rx)

    // CSS-style rotation: rotateY then rotateX, z toward the viewer.
    fun rot(x: Float, y: Float, z: Float): V3 {
        val x1 = x * cYa + z * sYa
        val z1 = -x * sYa + z * cYa
        val y2 = y * cXa - z1 * sXa
        val z2 = y * sXa + z1 * cXa
        return V3(x1, y2, z2)
    }
    fun project(p: V3): Offset {
        val k = CAMERA / (CAMERA - p.z * scale)
        return Offset((cxD + p.x * scale * k) * u, (cyD + p.y * scale * k) * u)
    }

    // light from upper-left-front
    val lx = -0.45f; val ly = -0.55f; val lz = 0.70f
    val ll = sqrt(lx * lx + ly * ly + lz * lz)

    class Face(
        val name: String, val c: Array<V3>, val nx: Float, val ny: Float, val nz: Float,
        val fw: Float, val fh: Float,
    )

    val faces = listOf(
        Face("front", arrayOf(V3(-w, -h, t), V3(w, -h, t), V3(w, h, t), V3(-w, h, t)), 0f, 0f, 1f, BOOK_W, BOOK_H),
        Face("back", arrayOf(V3(w, -h, -t), V3(-w, -h, -t), V3(-w, h, -t), V3(w, h, -t)), 0f, 0f, -1f, BOOK_W, BOOK_H),
        Face("spine", arrayOf(V3(-w, -h, -t), V3(-w, -h, t), V3(-w, h, t), V3(-w, h, -t)), -1f, 0f, 0f, b.thickness, BOOK_H),
        Face("pages", arrayOf(V3(w, -h, t), V3(w, -h, -t), V3(w, h, -t), V3(w, h, t)), 1f, 0f, 0f, b.thickness, BOOK_H),
        Face("top", arrayOf(V3(-w, -h, -t), V3(w, -h, -t), V3(w, -h, t), V3(-w, -h, t)), 0f, -1f, 0f, BOOK_W, b.thickness),
        Face("bottom", arrayOf(V3(-w, h, t), V3(w, h, t), V3(w, h, -t), V3(-w, h, -t)), 0f, 1f, 0f, BOOK_W, b.thickness),
    )

    // ground contact shadow (only meaningful when the book stands upright)
    val base = project(rot(0f, h, 0f))
    val shadowW = (abs(cos(ry)) * BOOK_W + abs(sin(ry)) * b.thickness) * scale * u * 0.55f
    drawOval(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.55f * alpha), Color.Transparent),
            center = Offset(base.x, base.y + 4f * u), radius = shadowW.coerceAtLeast(1f),
        ),
        topLeft = Offset(base.x - shadowW, base.y - 2f * u), size = Size(shadowW * 2f, 14f * u * scale),
    )

    for (f in faces) {
        val pts = f.c.map { project(rot(it.x, it.y, it.z)) }
        // winding test: draw only faces turned toward the camera
        var area = 0f
        for (i in 0 until 4) {
            val a = pts[i]; val c = pts[(i + 1) % 4]
            area += a.x * c.y - c.x * a.y
        }
        if (area <= 0f) continue

        // lighting from the rotated normal
        val n = rot(f.nx, f.ny, f.nz)
        val dot = ((n.x * lx + n.y * ly + n.z * lz) / ll).coerceIn(-1f, 1f)
        val bright = 0.30f + 0.70f * dot.coerceAtLeast(0f)
        val shade = ((1f - bright) * 0.78f).coerceIn(0f, 0.8f)

        src[0] = 0f; src[1] = 0f; src[2] = f.fw; src[3] = 0f; src[4] = f.fw; src[5] = f.fh; src[6] = 0f; src[7] = f.fh
        for (i in 0 until 4) { dst[i * 2] = pts[i].x; dst[i * 2 + 1] = pts[i].y }
        m.reset()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) continue

        val nc = drawContext.canvas.nativeCanvas
        drawContext.canvas.save()
        nc.concat(m)
        when (f.name) {
            "front" -> drawFront(b, tm, alpha)
            "back" -> drawRect(b.material.cloth.copy(alpha = alpha), size = Size(f.fw, f.fh))
            "spine" -> drawSpine(b, tm, alpha)
            "pages" -> drawPages(f.fw, f.fh, vertical = true, alpha = alpha)
            "top" -> drawPages(f.fw, f.fh, vertical = false, alpha = alpha)
            else -> drawRect(Color(0xFFB9B09A).copy(alpha = alpha), size = Size(f.fw, f.fh))
        }
        drawRect(Color.Black.copy(alpha = shade * alpha), size = Size(f.fw, f.fh))
        drawContext.canvas.restore()
    }
}

private val serif = FontFamily.Serif

private fun DrawScope.drawFront(b: BookSpec, tm: TextMeasurer, alpha: Float) {
    val ink = b.material.ink
    drawRect(b.material.cloth.copy(alpha = alpha), size = Size(BOOK_W, BOOK_H))
    // directional light across the cloth
    drawRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.20f * alpha),
            0.42f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.34f * alpha),
            start = Offset(0f, 0f), end = Offset(BOOK_W, BOOK_H),
        ),
        size = Size(BOOK_W, BOOK_H),
    )
    // hinge groove
    drawRect(Color.Black.copy(alpha = 0.5f * alpha), Offset(11f, 0f), Size(1.5f, BOOK_H))
    drawRect(Color.White.copy(alpha = 0.14f * alpha), Offset(12.5f, 0f), Size(1f, BOOK_H))
    // lit top edge
    drawRect(Color.White.copy(alpha = 0.22f * alpha), Offset(0f, 0f), Size(BOOK_W, 0.8f))

    val titleLayout = tm.measure(
        b.title.uppercase(),
        TextStyle(color = ink.copy(alpha = alpha), fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.6.sp, lineHeight = 14.sp),
        overflow = TextOverflow.Clip,
        constraints = Constraints(maxWidth = (112 * density).toInt()),
    )
    drawScaledText(titleLayout, Offset(24f, 22f), 1f / density)
    // the seed mark
    val ring = Offset(BOOK_W / 2f + 6f, 122f)
    drawCircle(ink.copy(alpha = 0.9f * alpha), 20f, ring, style = Stroke(5f))
    drawCircle(ink.copy(alpha = alpha), 4.4f, ring)
    val authorLayout = tm.measure(
        b.author.uppercase(),
        TextStyle(color = ink.copy(alpha = 0.78f * alpha), fontFamily = serif, fontSize = 8.sp, letterSpacing = 1.2.sp),
        overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = (112 * density).toInt()),
    )
    drawScaledText(authorLayout, Offset(24f, BOOK_H - 24f), 1f / density)
}

private fun DrawScope.drawSpine(b: BookSpec, tm: TextMeasurer, alpha: Float) {
    val ink = b.material.ink
    val tw = b.thickness
    drawRect(b.material.cloth.copy(alpha = alpha), size = Size(tw, BOOK_H))
    drawRect(
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = 0.18f * alpha), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f * alpha),
            startX = 0f, endX = tw,
        ),
        size = Size(tw, BOOK_H),
    )
    drawCircle(ink.copy(alpha = 0.92f * alpha), 3.6f, Offset(tw / 2f, 18f))
    val layout = tm.measure(
        b.title,
        TextStyle(color = ink.copy(alpha = alpha), fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.6.sp),
        overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = ((BOOK_H - 70f) * density).toInt()),
    )
    val s = 1f / density
    withTransform({
        translate(tw / 2f - layout.size.height * s / 2f, BOOK_H - 30f)
        rotate(-90f, Offset.Zero)
    }) { drawScaledText(layout, Offset.Zero, s) }
    val bb = tm.measure("BB", TextStyle(color = ink.copy(alpha = 0.8f * alpha), fontFamily = serif, fontSize = 8.sp))
    drawScaledText(bb, Offset(tw / 2f - bb.size.width * s / 2f, BOOK_H - 24f), s)
}

private fun DrawScope.drawPages(w: Float, h: Float, vertical: Boolean, alpha: Float) {
    drawRect(Color(0xFFE6DECD).copy(alpha = alpha), size = Size(w, h))
    val dark = Color(0xFFB9B09A).copy(alpha = 0.55f * alpha)
    var p = 1.2f
    if (vertical) { while (p < w) { drawRect(dark, Offset(p, 0f), Size(0.45f, h)); p += 1.5f } }
    else { while (p < h) { drawRect(dark, Offset(0f, p), Size(w, 0.45f)); p += 1.5f } }
}

private fun DrawScope.drawScaledText(layout: TextLayoutResult, at: Offset, s: Float) {
    withTransform({ translate(at.x, at.y); scale(s, s, Offset.Zero) }) {
        drawText(layout, topLeft = Offset.Zero)
    }
}
