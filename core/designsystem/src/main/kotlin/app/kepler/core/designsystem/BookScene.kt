package app.kepler.core.designsystem

import android.graphics.Matrix
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** Optional jacket image. When present it fills the front face instead of the typographic cover. */
    val cover: ImageBitmap? = null,
    /**
     * How much of the jacket shows over the typographic cover, read at draw time. A screen that shows the
     * typographic cover instantly and decodes the jacket afterwards ramps this 0 -> 1, so nothing flashes.
     */
    val jacketAlpha: () -> Float = { 1f },
) {
    /** A jacketed book's spine and boards wear the jacket's own colour, not a theme cloth. */
    val spineMaterial: BookMaterial by lazy { cover?.let(::jacketSpineMaterial) ?: material }
}

/** Average of the strip beside the hinge: that is the colour a jacket wraps around the spine. */
private fun jacketSpineMaterial(img: ImageBitmap): BookMaterial? = runCatching {
    val bmp = img.asAndroidBitmap()
    val stripW = (bmp.width / 10).coerceAtLeast(1)
    val stepX = (stripW / 6).coerceAtLeast(1)
    val stepY = (bmp.height / 64).coerceAtLeast(1)
    var r = 0f; var g = 0f; var b = 0f; var n = 0
    var y = 0
    while (y < bmp.height) {
        var x = 0
        while (x < stripW) {
            val c = bmp.getPixel(x, y)
            r += (c shr 16 and 0xFF); g += (c shr 8 and 0xFF); b += (c and 0xFF); n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0) return null
    val avg = Color(r / n / 255f, g / n / 255f, b / n / 255f)
    BookMaterial(avg, inkFor(avg))
}.getOrNull()

/**
 * Where a book is, in a 390-unit-wide design space (scaled to the real width at draw time).
 * [ry] is rotation about the vertical axis: 90 = spine to the viewer, ~25 = cover with the spine
 * visible on the left, 0 = flat cover, negative shows the page block.
 */
@Immutable
data class BookPose(
    val cx: Float, val cy: Float, val scale: Float,
    val ry: Float, val rx: Float = 0f, val alpha: Float = 1f,
    /** 0 = standing on the plank (full contact shadow), 1 = held in the hand (shadow spreads and fades). */
    val lift: Float = 0f,
    /** Lean in the picture plane, degrees; positive tips the top to the left about the bottom-left corner. */
    val rz: Float = 0f,
)

const val BOOK_W = 150f
const val BOOK_H = 225f
private const val CAMERA = 1300f
internal const val SPACE_W = 390f

private class PoseState(start: BookPose) {
    val cx = Animatable(start.cx)
    val cy = Animatable(start.cy)
    val scale = Animatable(start.scale)
    val ry = Animatable(start.ry)
    val rx = Animatable(start.rx)
    val alpha = Animatable(start.alpha)
    val lift = Animatable(start.lift)
    val rz = Animatable(start.rz)
    /** Press feedback: 0 at rest, 1 held (the book lifts a few millimetres toward the viewer). */
    val press = Animatable(0f)
    var entered = false
    /** A stage flight is landing here: skip the entrance and stand at the target. */
    var claimed = false

    suspend fun snap(t: BookPose) {
        cx.snapTo(t.cx); cy.snapTo(t.cy); scale.snapTo(t.scale)
        ry.snapTo(t.ry); rx.snapTo(t.rx); alpha.snapTo(t.alpha); lift.snapTo(t.lift); rz.snapTo(t.rz)
    }
}

/**
 * Each channel is its own spring so a retarget mid-flight keeps its velocity (brief section 4):
 * position and size `move`, yaw `turn` (lands with a small overshoot), pitch `settle`, shadows `lift`.
 * When the scene enters from [from], lift goes first, yaw follows at +60 ms and position at +90 ms.
 */
@Composable
private fun animatePose(t: BookPose, from: BookPose?): PoseState {
    val reduced = rememberReducedMotion()
    val state = remember { PoseState(if (from == null || reduced) t else from) }
    LaunchedEffect(t, reduced) {
        val entering = !state.entered && from != null
        state.entered = true
        if (reduced || state.claimed) {
            state.snap(t)
            return@LaunchedEffect
        }
        launch { state.rz.animateTo(t.rz, KpMotion.turn()) }
        launch { state.lift.animateTo(t.lift, KpMotion.lift()) }
        launch { state.alpha.animateTo(t.alpha, spring(Spring.DampingRatioNoBouncy, 400f)) }
        launch { state.rx.animateTo(t.rx, KpMotion.settle()) }
        launch {
            if (entering) delay(KpMotion.STAGGER_MS)
            state.ry.animateTo(t.ry, KpMotion.turn())
        }
        launch {
            if (entering) delay(90)
            launch { state.cx.animateTo(t.cx, KpMotion.move()) }
            launch { state.cy.animateTo(t.cy, KpMotion.move()) }
            state.scale.animateTo(t.scale, KpMotion.move())
        }
    }
    return state
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
    onLongPress: (index: Int) -> Unit = {},
    /** Extra touch slop around each book, in design units. Thin shelf spines want more. */
    hitPad: Float = 6f,
    /** Poses the books start from when the scene first appears (an entrance); null = start at [targets]. */
    enterFrom: List<BookPose>? = null,
    /** Accessibility labels, one per book; when given each book gets a TalkBack node (click + long-press). */
    labels: List<String>? = null,
    /**
     * Book identities (the book id) for the shared book stage, one per book. When given, each book is published
     * as a landing place so one book object can fly here from another screen; null keeps the scene local.
     */
    stageKeys: List<Any?>? = null,
    /** Names this scene's slots (for example "shelf", "nowbar") so a book returns to the place it left from. */
    stageTag: String = "",
    /** Books lift a few millimetres toward the viewer while pressed. */
    pressLift: Boolean = false,
) {
    val palette = Kp.palette
    val poses = books.indices.map { animatePose(targets[it], enterFrom?.getOrNull(it)) }
    val measurer = rememberTextMeasurer()
    val matrix = remember { Matrix() }
    val src = remember { FloatArray(8) }
    val dst = remember { FloatArray(8) }
    val scope = rememberCoroutineScope()
    val stage = LocalBookStage.current
    val owner = LocalStageOwner.current
    val sceneId = remember { Any() }
    val geometry = remember { FloatArray(3) } // origin x, origin y, width (root px)
    val latestTargets by rememberUpdatedState(targets)
    val latestBooks by rememberUpdatedState(books)
    val latestKeys by rememberUpdatedState(stageKeys)
    val latestPoses by rememberUpdatedState(poses)

    fun publish() {
        val keys = latestKeys
        if (stage == null || owner == null || keys == null || geometry[2] <= 0f) return
        val u = geometry[2] / SPACE_W
        val slots = latestBooks.indices.mapNotNull { i ->
            val key = keys.getOrNull(i) ?: return@mapNotNull null
            val t = latestTargets.getOrNull(i) ?: return@mapNotNull null
            val b = latestBooks[i]
            StageSlot(
                key, owner, StageRole.Book, stageTag,
                StagePose(geometry[0] + t.cx * u, geometry[1] + t.cy * u, u, t.scale, t.ry, t.rx, b.thickness),
                b,
                onClaim = claim@{
                    val ps = latestPoses.getOrNull(i) ?: return@claim
                    ps.claimed = true
                    scope.launch { ps.snap(t) }
                },
            )
        }
        stage.publishScene(sceneId, slots)
    }
    if (stageKeys != null && stage != null) {
        SideEffect { publish() }
        DisposableEffect(stage, sceneId) { onDispose { stage.withdrawScene(sceneId) } }
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                if (stageKeys == null) return@onGloballyPositioned
                val o = it.positionInRoot()
                geometry[0] = o.x; geometry[1] = o.y; geometry[2] = it.size.width.toFloat()
                publish()
            },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(books, targets, hitPad, pressLift) {
                    val u = size.width / SPACE_W
                    // top-most book first (drawn last = highest index among opaque ones)
                    fun hit(p: Offset): Int {
                        for (i in books.indices.reversed()) {
                            val ps = poses[i]
                            if (ps.alpha.value < 0.3f) continue
                            val half = projectedHalfExtent(books[i].thickness, ps.ry.value, ps.scale.value)
                            val cx = ps.cx.value * u
                            val cy = ps.cy.value * u
                            if (abs(p.x - cx) <= (half.first + hitPad) * u && abs(p.y - cy) <= (half.second + hitPad) * u) return i
                        }
                        return -1
                    }
                    detectTapGestures(
                        onPress = { p ->
                            val i = if (pressLift) hit(p) else -1
                            if (i >= 0) {
                                val press = poses[i].press
                                scope.launch { press.animateTo(1f, KpMotion.lift()) }
                                tryAwaitRelease()
                                scope.launch { press.animateTo(0f, KpMotion.settle()) }
                            }
                        },
                        onLongPress = { p -> hit(p).let { if (it >= 0) onLongPress(it) } },
                        onTap = { p ->
                            hit(p).let {
                                if (it >= 0) {
                                    stage?.prefer(stageTag)
                                    onTap(it)
                                }
                            }
                        },
                    )
                },
        ) {
            val u = size.width / SPACE_W
            // draw far-to-near by scale so a pulled-down book sits above the shelf
            books.indices.sortedBy { poses[it].scale.value }.forEach { i ->
                val ps = poses[i]
                if (ps.alpha.value > 0.01f && !(stage != null && stage.hides(stageKeys?.getOrNull(i), owner))) {
                    val pr = ps.press.value
                    val sc = ps.scale.value * (1f + 0.04f * pr)
                    // keep the base on the plank, then rise 2 units: the book comes off the shelf toward you
                    val cy = ps.cy.value - BOOK_H / 2f * ps.scale.value * 0.04f * pr - 2f * pr
                    drawBook(
                        books[i], books[i].thickness, u, ps.cx.value, cy, sc,
                        ps.ry.value, ps.rx.value, ps.rz.value, ps.alpha.value, (ps.lift.value + 0.6f * pr).coerceAtMost(1f),
                        0f, palette.page, palette, measurer, matrix, src, dst,
                    )
                }
            }
        }
        if (labels != null) {
            books.indices.forEach { i ->
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val u = constraints.maxWidth / SPACE_W
                            val ps = poses[i]
                            val half = projectedHalfExtent(books[i].thickness, ps.ry.value, ps.scale.value)
                            val w = (half.first * 2 * u).toInt().coerceAtLeast(1)
                            val h = (half.second * 2 * u).toInt().coerceAtLeast(1)
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place((ps.cx.value * u - w / 2f).toInt(), (ps.cy.value * u - h / 2f).toInt())
                            }
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = labels.getOrElse(i) { "" }
                            role = Role.Button
                            onClick { onTap(i); true }
                            onLongClick { onLongPress(i); true }
                        },
                )
            }
        }
    }
}

/** Screen rect (px, scene space) of a facing book's front face, used where the first page leaves the book. */
internal fun projectedFrontRect(cxD: Float, cyD: Float, scale: Float, thickness: Float, u: Float): androidx.compose.ui.geometry.Rect {
    val k = CAMERA / (CAMERA - thickness / 2f * scale)
    val hw = BOOK_W / 2f * scale * k * u
    val hh = BOOK_H / 2f * scale * k * u
    return androidx.compose.ui.geometry.Rect(cxD * u - hw, cyD * u - hh, cxD * u + hw, cyD * u + hh)
}

private fun projectedHalfExtent(thickness: Float, ry: Float, scale: Float): Pair<Float, Float> {
    val a = Math.toRadians(ry.toDouble()).toFloat()
    val w = abs(cos(a)) * BOOK_W + abs(sin(a)) * thickness
    return Pair(w / 2f * scale, BOOK_H / 2f * scale)
}

private class V3(val x: Float, val y: Float, val z: Float)

/** Largest front-board swing when the cover is fully open (brief 4.2: 0 to -165 degrees). */
private const val OPEN_DEG = 165f

/**
 * Draws one book. [thickness] overrides the spec's spine width (a flight interpolates it), [rzDeg] leans the
 * book in the picture plane about its bottom-left corner, and [open] (0..1) swings the front board about the
 * hinge, showing the paper liner and a first page of [pageColor].
 */
internal fun DrawScope.drawBook(
    b: BookSpec, thickness: Float, u: Float, cxD: Float, cyD: Float, scale: Float,
    ryDeg: Float, rxDeg: Float, rzDeg: Float, alpha: Float, lift: Float, open: Float, pageColor: Color,
    palette: KpPalette, tm: TextMeasurer, m: Matrix, src: FloatArray, dst: FloatArray,
) {
    val w = BOOK_W / 2f
    val h = BOOK_H / 2f
    val t = thickness / 2f
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
    val lx = -0.35f; val ly = -0.55f; val lz = 0.76f
    val ll = sqrt(lx * lx + ly * ly + lz * lz)
    fun shadeFor(nx: Float, ny: Float, nz: Float): Float {
        val n = rot(nx, ny, nz)
        val dot = ((n.x * lx + n.y * ly + n.z * lz) / ll).coerceIn(-1f, 1f)
        return (1f - (0.63f + 0.49f * dot.coerceAtLeast(0f))).coerceIn(0f, 0.6f)
    }

    class Face(
        val name: String, val c: Array<V3>, val nx: Float, val ny: Float, val nz: Float,
        val fw: Float, val fh: Float,
    )

    val opening = open > 0.001f
    val faces = listOf(
        Face("front", arrayOf(V3(-w, -h, t), V3(w, -h, t), V3(w, h, t), V3(-w, h, t)), 0f, 0f, 1f, BOOK_W, BOOK_H),
        Face("back", arrayOf(V3(w, -h, -t), V3(-w, -h, -t), V3(-w, h, -t), V3(w, h, -t)), 0f, 0f, -1f, BOOK_W, BOOK_H),
        Face("spine", arrayOf(V3(-w, -h, -t), V3(-w, -h, t), V3(-w, h, t), V3(-w, h, -t)), -1f, 0f, 0f, thickness, BOOK_H),
        Face("pages", arrayOf(V3(w, -h, t), V3(w, -h, -t), V3(w, h, -t), V3(w, h, t)), 1f, 0f, 0f, thickness, BOOK_H),
        Face("top", arrayOf(V3(-w, -h, -t), V3(w, -h, -t), V3(w, -h, t), V3(-w, -h, t)), 0f, -1f, 0f, BOOK_W, thickness),
        Face("bottom", arrayOf(V3(-w, h, t), V3(w, h, t), V3(w, h, -t), V3(-w, h, -t)), 0f, 1f, 0f, BOOK_W, thickness),
    )

    // soft contact: a cloth bounce on the plank, then a tight ellipse on the plank line (no blur)
    val base = project(rot(0f, h, 0f))
    val coverW = (abs(cos(ry)) * BOOK_W + abs(sin(ry)) * thickness) * scale * u
    val env = palette.materials.env
    val grounded = alpha * (1f - 0.6f * lift) * (1f - 0.5f * abs(rzDeg) / 90f)
    val spread = 1f + 0.3f * lift
    fun ellipse(rx: Float, ryy: Float, center: Offset, c: Color) {
        if (rx < 0.5f) return
        withTransform({ scale(1f, ryy / rx, pivot = center) }) {
            drawCircle(
                Brush.radialGradient(0f to c, 0.55f to c.copy(alpha = c.alpha * 0.5f), 1f to Color.Transparent, center = center, radius = rx),
                radius = rx, center = center,
            )
        }
    }
    val bounce = b.material.cloth
    ellipse(coverW * 0.7f * spread, 5f * u * scale, Offset(base.x, base.y + 1f * u), bounce.copy(alpha = env.bounceAlpha * grounded))
    ellipse(coverW * 0.64f * spread, 3f * u * scale, base, Color.Black.copy(alpha = env.contactAlpha * grounded))

    val nc = drawContext.canvas.nativeCanvas
    fun mapped(pts: List<Offset>, fw: Float, fh: Float, body: DrawScope.() -> Unit) {
        src[0] = 0f; src[1] = 0f; src[2] = fw; src[3] = 0f; src[4] = fw; src[5] = fh; src[6] = 0f; src[7] = fh
        for (i in 0 until 4) { dst[i * 2] = pts[i].x; dst[i * 2 + 1] = pts[i].y }
        m.reset()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return
        drawContext.canvas.save()
        nc.concat(m)
        body()
        drawContext.canvas.restore()
    }
    fun facing(pts: List<Offset>): Boolean {
        var area = 0f
        for (i in 0 until 4) {
            val a = pts[i]; val c = pts[(i + 1) % 4]
            area += a.x * c.y - c.x * a.y
        }
        return area > 0f
    }

    // lean: rotate about the bottom-left corner of what is visible, after the contact shadow is laid down
    val leaning = abs(rzDeg) > 0.01f
    if (leaning) {
        drawContext.canvas.save()
        drawContext.transform.rotate(-rzDeg, Offset(base.x - coverW / 2f, base.y))
    }
    for (f in faces) {
        if (opening && f.name == "front") continue
        val pts = f.c.map { project(rot(it.x, it.y, it.z)) }
        // winding test: draw only faces turned toward the camera
        if (!facing(pts)) continue
        val shade = shadeFor(f.nx, f.ny, f.nz)
        mapped(pts, f.fw, f.fh) {
            when (f.name) {
                "front" -> drawFront(b, tm, alpha)
                "back" -> drawRect(b.spineMaterial.cloth.copy(alpha = alpha), size = Size(f.fw, f.fh))
                "spine" -> drawSpine(b, thickness, tm, alpha)
                "pages" -> drawPages(f.fw, f.fh, vertical = true, alpha = alpha, paper = palette.materials.paper)
                "top" -> drawPages(f.fw, f.fh, vertical = false, alpha = alpha, paper = palette.materials.paper)
                else -> drawRect(palette.materials.paper.fore.copy(alpha = alpha), size = Size(f.fw, f.fh))
            }
            drawRect(Color.Black.copy(alpha = shade * alpha), size = Size(f.fw, f.fh))
        }
    }
    if (opening) {
        // the first page, just inside the board
        val inset = 1.5f
        val page = arrayOf(V3(-w + inset, -h + inset, t), V3(w - inset, -h + inset, t), V3(w - inset, h - inset, t), V3(-w + inset, h - inset, t))
        val pagePts = page.map { project(rot(it.x, it.y, it.z)) }
        if (facing(pagePts)) {
            val shade = shadeFor(0f, 0f, 1f)
            mapped(pagePts, BOOK_W, BOOK_H) {
                drawRect(pageColor.copy(alpha = alpha), size = Size(BOOK_W, BOOK_H))
                drawRect(Color.Black.copy(alpha = shade * 0.5f * alpha), size = Size(BOOK_W, BOOK_H))
            }
        }
        // the board swings about the hinge (x = -w, z = t) toward the viewer and over to the left
        val phi = Math.toRadians((OPEN_DEG * open).toDouble()).toFloat()
        val cp = cos(phi); val sp = sin(phi)
        fun board(x: Float, y: Float): Offset {
            val dx = x + w
            return project(rot(-w + dx * cp, y, t + dx * sp))
        }
        val boardPts = listOf(board(-w, -h), board(w, -h), board(w, h), board(-w, h))
        if (facing(boardPts)) {
            val shade = shadeFor(-sp, 0f, cp)
            mapped(boardPts, BOOK_W, BOOK_H) {
                drawFront(b, tm, alpha)
                drawRect(Color.Black.copy(alpha = shade * alpha), size = Size(BOOK_W, BOOK_H))
            }
        } else {
            // inside of the board: the paper liner, lit as the board turns toward the light
            val shade = shadeFor(sp, 0f, -cp)
            val liner = listOf(boardPts[1], boardPts[0], boardPts[3], boardPts[2])
            mapped(liner, BOOK_W, BOOK_H) {
                drawRect(palette.materials.paper.lit.copy(alpha = alpha), size = Size(BOOK_W, BOOK_H))
                drawRect(b.spineMaterial.cloth.copy(alpha = alpha), Offset(BOOK_W - 3f, 0f), Size(3f, BOOK_H))
                drawRect(Color.Black.copy(alpha = shade * alpha), size = Size(BOOK_W, BOOK_H))
            }
        }
    }
    if (leaning) drawContext.canvas.restore()
}

private val serif = KpFonts.displaySmall

private val grainShader: androidx.compose.ui.graphics.Shader by lazy {
    val n = 256
    val rnd = java.util.Random(7)
    val raw = FloatArray(n * n) { rnd.nextFloat() }
    val px = IntArray(n * n)
    for (y in 0 until n) for (x in 0 until n) {
        // woven look: blur across x so the grain is anisotropic (about 3:1)
        var a = 0f
        for (d in -1..1) a += raw[y * n + (x + d + n) % n]
        a /= 3f
        val alpha = (1.1f * a - 0.5f).coerceIn(0f, 1f)
        px[y * n + x] = (((alpha * 255).toInt()) shl 24) or 0x00FFFFFF
    }
    val bmp = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val shader = androidx.compose.ui.graphics.ImageShader(
        bmp.asImageBitmap(), androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated,
    )
    (shader as android.graphics.Shader).setLocalMatrix(Matrix().apply { setScale(0.42f, 0.42f) })
    shader
}

private fun DrawScope.drawGrain(w: Float, h: Float, alpha: Float) {
    drawRect(
        brush = androidx.compose.ui.graphics.ShaderBrush(grainShader), size = Size(w, h),
        alpha = 0.5f * alpha, blendMode = androidx.compose.ui.graphics.BlendMode.Overlay,
    )
}

private fun DrawScope.drawFront(b: BookSpec, tm: TextMeasurer, alpha: Float) {
    val ink = b.material.ink
    val img = b.cover
    drawRect(b.material.cloth.copy(alpha = alpha), size = Size(BOOK_W, BOOK_H))
    val jacket = if (img != null) b.jacketAlpha().coerceIn(0f, 1f) else 0f
    if (img != null && jacket > 0f) {
        // centre-crop the jacket to 2:3, flat on the face
        val wantW = minOf(img.width, (img.height * 2f / 3f).toInt())
        val wantH = minOf(img.height, (img.width * 3f / 2f).toInt())
        drawImage(
            img, srcOffset = androidx.compose.ui.unit.IntOffset((img.width - wantW) / 2, (img.height - wantH) / 2),
            srcSize = androidx.compose.ui.unit.IntSize(wantW, wantH),
            dstSize = androidx.compose.ui.unit.IntSize(BOOK_W.toInt(), BOOK_H.toInt()), alpha = alpha * jacket,
        )
    }
    drawGrain(BOOK_W, BOOK_H, alpha)
    // directional light across the cloth
    drawRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.18f * alpha),
            0.42f to Color.Transparent,
            1f to Color.Black.copy(alpha = (if (b.material.cloth.luminance() > 0.7f) 0.24f else 0.38f) * alpha),
            start = Offset(0f, 0f), end = Offset(BOOK_W, BOOK_H),
        ),
        size = Size(BOOK_W, BOOK_H),
    )
    // hinge groove
    drawRect(Color.Black.copy(alpha = 0.5f * alpha), Offset(11f, 0f), Size(1.5f, BOOK_H))
    drawRect(Color.White.copy(alpha = 0.14f * alpha), Offset(12.5f, 0f), Size(1f, BOOK_H))
    // lit top edge
    drawRect(Color.White.copy(alpha = 0.22f * alpha), Offset(0f, 0f), Size(BOOK_W, 0.8f))
    if (jacket < 1f) drawTypographicCover(b, tm, alpha * (1f - jacket))
}

/** Title, debossed medallion and author on bare cloth: the cover every book has before (or without) a jacket. */
private fun DrawScope.drawTypographicCover(b: BookSpec, tm: TextMeasurer, alpha: Float) {
    val ink = b.material.ink
    val titleLayout = tm.measure(
        b.title.uppercase(),
        TextStyle(color = ink.copy(alpha = alpha), fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = (11f / fontScale).sp, letterSpacing = 1.6.sp, lineHeight = (14f / fontScale).sp),
        overflow = TextOverflow.Clip,
        constraints = Constraints(maxWidth = (112 * density).toInt()),
    )
    drawScaledText(titleLayout, Offset(24f, 22f), 1f / density)
    // the seed mark
    val ring = Offset(BOOK_W / 2f + 6f, 122f)
    // debossed medallion: groove stroke, a light edge one unit below, and the centre dot
    drawCircle(Color.Black.copy(alpha = 0.6f * alpha), 20f, ring, style = Stroke(5f))
    drawCircle(Color.White.copy(alpha = 0.16f * alpha), 20f, ring.copy(y = ring.y + 0.8f), style = Stroke(0.8f))
    drawCircle(ink.copy(alpha = alpha), 4.4f, ring)
    val authorLayout = tm.measure(
        b.author.uppercase(),
        TextStyle(color = ink.copy(alpha = 0.78f * alpha), fontFamily = serif, fontSize = (8f / fontScale).sp, letterSpacing = 1.2.sp),
        overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = (112 * density).toInt()),
    )
    drawScaledText(authorLayout, Offset(24f, BOOK_H - 24f), 1f / density)
}

private fun DrawScope.drawSpine(b: BookSpec, tw: Float, tm: TextMeasurer, alpha: Float) {
    val mat = b.spineMaterial
    val ink = mat.ink
    drawRect(mat.cloth.copy(alpha = alpha), size = Size(tw, BOOK_H))
    drawGrain(tw, BOOK_H, alpha)
    drawRect(
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = 0.18f * alpha), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f * alpha),
            startX = 0f, endX = tw,
        ),
        size = Size(tw, BOOK_H),
    )
    // type follows the spine width so a shelf spine stays legible and a slim one never overflows
    val fs = (tw * 0.42f).coerceIn(5f, 14f)
    drawCircle(ink.copy(alpha = 0.92f * alpha), (tw * 0.2f).coerceIn(1.6f, 3.6f), Offset(tw / 2f, 18f))
    val layout = tm.measure(
        b.title,
        TextStyle(color = ink.copy(alpha = alpha), fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = (fs / fontScale).sp, letterSpacing = 0.5.sp),
        overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = ((BOOK_H - 70f) * density).toInt()),
    )
    val s = 1f / density
    withTransform({
        translate(tw / 2f - layout.size.height * s / 2f, BOOK_H - 30f)
        rotate(-90f, Offset.Zero)
    }) { drawScaledText(layout, Offset.Zero, s) }
    if (tw >= 14f) {
        val bb = tm.measure("K", TextStyle(color = ink.copy(alpha = 0.8f * alpha), fontFamily = serif, fontSize = (8f / fontScale).sp))
        drawScaledText(bb, Offset(tw / 2f - bb.size.width * s / 2f, BOOK_H - 24f), s)
    }
}

private fun DrawScope.drawPages(w: Float, h: Float, vertical: Boolean, alpha: Float, paper: Paper) {
    drawRect(paper.head.copy(alpha = alpha), size = Size(w, h))
    val dark = paper.fore.copy(alpha = 0.55f * alpha)
    var p = 1.2f
    if (vertical) { while (p < w) { drawRect(dark, Offset(p, 0f), Size(0.45f, h)); p += 1.5f } }
    else { while (p < h) { drawRect(dark, Offset(0f, p), Size(w, 0.45f)); p += 1.5f } }
}

private fun DrawScope.drawScaledText(layout: TextLayoutResult, at: Offset, s: Float) {
    withTransform({ translate(at.x, at.y); scale(s, s, Offset.Zero) }) {
        drawText(layout, topLeft = Offset.Zero)
    }
}
