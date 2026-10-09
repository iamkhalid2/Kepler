package app.kepler.core.designsystem

import android.graphics.Matrix
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One place a book can stand on the current screens, published by a BookScene or the reader page. */
class StageSlot(
    override val key: Any,
    override val owner: String,
    override val role: StageRole,
    override val tag: String,
    override val pose: StagePose,
    /** How the book looks here; null for a [StageRole.Page] slot. */
    val spec: BookSpec?,
    /** Page rect in root px and its paper colour ([StageRole.Page] only). */
    val page: Rect = Rect.Zero,
    val pageColor: Color = Color.Unspecified,
    /** The page has laid out its first screen of text ([StageRole.Page] only). */
    val ready: Boolean = false,
    /** Called when a flight claims this slot as its landing place, so the screen drops its own entrance. */
    val onClaim: () -> Unit = {},
) : SlotFacts

/** Identifies the back-stack entry a slot belongs to. The navigation host provides one per entry. */
val LocalStageOwner = staticCompositionLocalOf<String?> { null }

/** The single book layer above navigation; null where there is none (previews, onboarding tests). */
val LocalBookStage = staticCompositionLocalOf<BookStageState?> { null }

/**
 * The shared book layer (brief section 4). Screens publish slots; when the top back-stack entry changes and
 * the same book has a slot on both the old and the new screen, one book object flies between them on its own
 * springs. While it flies, both screens hide their copy of that book ([hides]); when it lands, the target
 * screen's own BookScene takes over at exactly the same pose. A flight to a [StageRole.Page] opens the cover
 * and grows the first page to the page rect, then fades once the page is ready. Retargets keep velocity, so a
 * system back mid-flight simply sends the same book home.
 */
@Stable
class BookStageState internal constructor(private val scope: CoroutineScope) {
    private val scenes = HashMap<Any, List<StageSlot>>()
    private val known = HashMap<Any, BookSpec>()
    private val lastTag = HashMap<Any, String>()
    private var top: String? = null
    private var pending: Pending? = null
    private var preferredTag: String? = null
    private var preferredAt = 0L

    /** App or system reduced motion: no flights, screens simply crossfade. */
    var reduced = false

    internal var flight by mutableStateOf<Flight?>(null)
        private set

    /** Overlay origin and width in root px; the stage's design unit follows its width. */
    internal var origin = Offset.Zero
    internal var widthPx = 0f

    /** The best look seen so far for a book (largest jacket), so a screen can show it while it decodes its own. */
    fun knownSpec(key: Any): BookSpec? = known[key]

    /** Marks the slot tag the person just touched (a shelf spine, the now-bar) as the source of the next flight. */
    fun prefer(tag: String) {
        preferredTag = tag
        preferredAt = SystemClock.uptimeMillis()
    }

    /** Whether a screen should skip drawing its own copy of [key]: true while that book is in flight from or to [owner]. */
    fun hides(key: Any?, owner: String?): Boolean {
        val f = flight ?: return false
        return key != null && f.key == key && (owner == f.fromOwner || owner == f.toOwner)
    }

    /** Replaces every slot a scene publishes. */
    fun publishScene(sceneId: Any, slots: List<StageSlot>) {
        scenes[sceneId] = slots
        slots.forEach { s ->
            val spec = s.spec ?: return@forEach
            val old = known[s.key]
            if (old == null || (spec.cover?.width ?: 0) >= (old.cover?.width ?: 0)) known[s.key] = spec
        }
        slots.forEach(::consider)
    }

    fun withdrawScene(sceneId: Any) {
        val gone = scenes.remove(sceneId) ?: return
        val f = flight ?: return
        // The landing place left composition mid-flight (its screen was popped): let the book go.
        if (gone.any { it === f.target }) end(f)
    }

    /** Called by the navigation host whenever the top back-stack entry changes. */
    fun setTop(owner: String) {
        if (owner == top) return
        val prev = top
        top = owner
        pending = null
        if (reduced) { flight?.let(::end); return }
        val f = flight
        if (f != null) {
            val t = StagePlan.pickTarget(f.key, owner, allSlots(), lastTag[f.key])
            if (t != null) retarget(f, t)
            return
        }
        if (prev == null) return
        val sources = allSlots().filter { it.owner == prev }
        if (sources.isEmpty()) return
        val tag = preferredTag?.takeIf { SystemClock.uptimeMillis() - preferredAt < StagePlan.PREFER_MS }
        pending = Pending(owner, sources, tag, SystemClock.uptimeMillis())
        allSlots().filter { it.owner == owner }.forEach(::consider)
    }

    private fun allSlots(): List<StageSlot> = scenes.values.flatten()

    private fun consider(slot: StageSlot) {
        if (slot.owner != top) return
        val f = flight
        if (f != null) {
            if (f.key != slot.key) return
            if (f.target.owner != slot.owner) {
                // Back was pressed mid-flight and the screen it returns to has just composed again.
                StagePlan.pickTarget(f.key, slot.owner, allSlots(), lastTag[f.key])?.let { retarget(f, it) }
                return
            }
            if (slot.role == StageRole.Page && f.target.role == StageRole.Page) {
                // The reader settles its theme and insets after it first composes: follow its page.
                f.target = slot
                f.targetReady = slot.ready
                f.page = slot.page
                f.pageColor = slot.pageColor
                return
            }
            if (f.target.owner == slot.owner && f.target.role == slot.role && f.target.tag == slot.tag) {
                if (StagePlan.moved(f.target.pose, slot.pose)) retarget(f, slot) else f.target = slot
            }
            return
        }
        val p = pending ?: return
        if (SystemClock.uptimeMillis() - p.since > StagePlan.PENDING_MS) { pending = null; return }
        val src = StagePlan.pickSource(slot.key, p.sources, p.tag) ?: return
        val dst = StagePlan.pickTarget(slot.key, slot.owner, allSlots(), src.tag) ?: return
        pending = null
        start(src, dst)
    }

    private fun stageU(): Float = if (widthPx > 0f) widthPx / SPACE_W else 1f

    private fun poseOf(slot: StageSlot, thickness: Float): StagePose =
        if (slot.role == StageRole.Page) {
            StagePlan.facing(slot.page.left, slot.page.top, slot.page.right, slot.page.bottom, stageU(), thickness)
        } else slot.pose

    private fun start(src: StageSlot, dst: StageSlot) {
        val spec = dst.spec ?: src.spec ?: return
        val thickness = (if (src.role == StageRole.Book) src.pose.thickness else spec.thickness)
        val from = poseOf(src, thickness)
        val fromPage = src.role == StageRole.Page
        val f = Flight(src.key, spec, from, open = if (fromPage) 1f else 0f, reveal = if (fromPage) 1f else 0f)
        f.fromOwner = src.owner
        f.toOwner = dst.owner
        f.target = dst
        f.targetReady = dst.ready
        if (fromPage) { f.page = src.page; f.pageColor = src.pageColor }
        if (dst.role == StageRole.Page) { f.page = dst.page; f.pageColor = dst.pageColor }
        if (src.role == StageRole.Book) lastTag[src.key] = src.tag
        flight = f
        dst.onClaim()
        run(f, fresh = true, fromPage = fromPage)
    }

    private fun retarget(f: Flight, dst: StageSlot) {
        if (dst.owner != f.toOwner) {
            f.fromOwner = f.toOwner
            f.toOwner = dst.owner
        }
        if (dst.role == StageRole.Page) { f.page = dst.page; f.pageColor = dst.pageColor }
        if (dst.spec != null) f.spec = dst.spec
        f.target = dst
        f.targetReady = dst.ready
        dst.onClaim()
        run(f, fresh = false, fromPage = false)
    }

    private fun end(f: Flight) {
        f.job?.cancel()
        if (flight === f) flight = null
    }

    private fun run(f: Flight, fresh: Boolean, fromPage: Boolean) {
        f.job?.cancel()
        f.job = scope.launch {
            val dst = f.target
            val to = poseOf(dst, f.thickness.value)
            if (dst.role == StageRole.Page) toPage(f, to, fresh) else toBook(f, to, fresh, fromPage)
            if (flight === f) flight = null
        }
    }

    private suspend fun toBook(f: Flight, to: StagePose, fresh: Boolean, fromPage: Boolean) = coroutineScope {
        // Leaving the page: the page shrinks back into the book, the board closes, then the book goes home.
        val d0 = if (fresh && fromPage) 200L else 0L
        launch { f.alpha.animateTo(1f, KpMotion.snap()) }
        launch { f.reveal.animateTo(0f, KpMotion.reveal()) }
        launch { if (fresh && fromPage) delay(120); f.open.animateTo(0f, KpMotion.open()) }
        launch { f.lift.animateTo(1f, KpMotion.lift()) }
        launch { delay(d0); f.rx.animateTo(to.rx, KpMotion.settle()) }
        launch { delay(if (fresh) d0 + KpMotion.STAGGER_MS else 0L); f.ry.animateTo(to.ry, KpMotion.turn()) }
        launch {
            delay(if (fresh) d0 + 90L else 0L)
            moveTo(f, to)
            f.lift.animateTo(0f, KpMotion.lift())
        }
    }

    private suspend fun toPage(f: Flight, to: StagePose, fresh: Boolean) = coroutineScope {
        val started = SystemClock.uptimeMillis()
        val motion = launch {
            launch { f.lift.animateTo(1f, KpMotion.lift()) }
            launch { f.rx.animateTo(0f, KpMotion.settle()) }
            launch { if (fresh) delay(KpMotion.STAGGER_MS); f.ry.animateTo(0f, KpMotion.turn()) }
            launch { if (fresh) delay(90L); moveTo(f, to) }
            launch { if (fresh) delay(180L); f.open.animateTo(1f, KpMotion.open()) }
            launch {
                snapshotFlow { f.open.value }.first { it > 0.55f }
                f.reveal.animateTo(1f, KpMotion.reveal())
            }
        }
        launch { f.alpha.animateTo(1f, KpMotion.snap()) }
        // Fade the snapshot once the page is ready: let the reveal finish if it is close, never longer than the grace.
        withTimeoutOrNull(StagePlan.PAGE_CEILING_MS) { snapshotFlow { f.targetReady }.first { it } }
        val left = StagePlan.PAGE_CEILING_MS - (SystemClock.uptimeMillis() - started)
        withTimeoutOrNull(minOf(StagePlan.PAGE_GRACE_MS, left.coerceAtLeast(0L))) {
            snapshotFlow { f.reveal.value }.first { it > 0.92f }
        }
        f.alpha.animateTo(0f, KpMotion.snap())
        motion.cancel()
    }

    private suspend fun moveTo(f: Flight, to: StagePose) = coroutineScope {
        launch { f.cx.animateTo(to.cx, KpMotion.move()) }
        launch { f.cy.animateTo(to.cy, KpMotion.move()) }
        launch { f.u.animateTo(to.u, KpMotion.move()) }
        launch { f.thickness.animateTo(to.thickness, KpMotion.move()) }
        f.scale.animateTo(to.scale, KpMotion.move())
    }

    private class Pending(val owner: String, val sources: List<StageSlot>, val tag: String?, val since: Long)
}

internal class Flight(val key: Any, spec: BookSpec, from: StagePose, open: Float, reveal: Float) {
    var spec by mutableStateOf(spec)
    var fromOwner: String = ""
    var toOwner: String = ""
    lateinit var target: StageSlot
    var targetReady by mutableStateOf(false)
    var page by mutableStateOf(Rect.Zero)
    var pageColor by mutableStateOf(Color.Unspecified)
    var job: Job? = null
    val cx = Animatable(from.cx)
    val cy = Animatable(from.cy)
    val u = Animatable(from.u)
    val scale = Animatable(from.scale)
    val ry = Animatable(from.ry)
    val rx = Animatable(from.rx)
    val thickness = Animatable(from.thickness)
    val lift = Animatable(if (open > 0f) 1f else 0f)
    val open = Animatable(open)
    val reveal = Animatable(reveal)
    val alpha = Animatable(1f)
}

@Composable
fun rememberBookStage(): BookStageState {
    val scope = rememberCoroutineScope()
    val stage = remember(scope) { BookStageState(scope) }
    stage.reduced = rememberReducedMotion()
    return stage
}

/**
 * Draws the book in flight above the navigation host. It never takes touches and is invisible to
 * accessibility: the screens' own anchors own the labels and targets.
 */
@Composable
fun BookStageOverlay(stage: BookStageState, modifier: Modifier = Modifier) {
    val palette = Kp.palette
    val measurer = rememberTextMeasurer()
    val matrix = remember { Matrix() }
    val src = remember { FloatArray(8) }
    val dst = remember { FloatArray(8) }
    Canvas(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .onGloballyPositioned {
                stage.origin = it.positionInRoot()
                stage.widthPx = it.size.width.toFloat()
            }
            .graphicsLayer { alpha = stage.flight?.alpha?.value ?: 1f },
    ) {
        val f = stage.flight ?: return@Canvas
        val u = f.u.value
        val cxD = (f.cx.value - stage.origin.x) / u
        val cyD = (f.cy.value - stage.origin.y) / u
        val pageColor = f.pageColor.takeIf { it != Color.Unspecified } ?: palette.page
        drawBook(
            f.spec, f.thickness.value, u, cxD, cyD, f.scale.value, f.ry.value, f.rx.value, 0f,
            1f, f.lift.value, f.open.value, pageColor, palette, measurer, matrix, src, dst,
        )
        val reveal = f.reveal.value
        if (reveal > 0.001f && f.page != Rect.Zero) {
            // The first page leaves the book and grows into the reader's page (a 2D snapshot, never the WebView).
            val quad = projectedFrontRect(cxD, cyD, f.scale.value, f.thickness.value, u)
            val pg = f.page.translate(-stage.origin)
            drawRect(
                pageColor,
                topLeft = Offset(lerp(quad.left, pg.left, reveal), lerp(quad.top, pg.top, reveal)),
                size = androidx.compose.ui.geometry.Size(
                    lerp(quad.width, pg.width, reveal), lerp(quad.height, pg.height, reveal),
                ),
            )
        }
    }
}

/**
 * Publishes the reader's page rect as a landing place for [key]. [ready] turns true once the first screen of
 * text is laid out; the opening cover fades no later than [StagePlan.PAGE_GRACE_MS] after that.
 */
fun Modifier.bookPageSlot(key: Any, pageColor: Color, ready: Boolean): Modifier = composed {
    val stage = LocalBookStage.current
    val owner = LocalStageOwner.current
    if (stage == null || owner == null) return@composed this
    val sceneId = remember { Any() }
    val bounds = remember { arrayOf(Rect.Zero) }
    val latestReady by rememberUpdatedState(ready)
    val latestColor by rememberUpdatedState(pageColor)
    fun publish() {
        val r = bounds[0]
        if (r == Rect.Zero) return
        val pose = StagePose(r.center.x, r.center.y, 1f, 1f, 0f, 0f, 0f)
        stage.publishScene(sceneId, listOf(StageSlot(key, owner, StageRole.Page, "page", pose, null, r, latestColor, latestReady)))
    }
    SideEffect { publish() }
    DisposableEffect(stage, sceneId) { onDispose { stage.withdrawScene(sceneId) } }
    onGloballyPositioned {
        bounds[0] = it.boundsInRoot()
        publish()
    }
}
