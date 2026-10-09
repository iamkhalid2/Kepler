package app.kepler.feature.study

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kepler.core.designsystem.Kp
import app.kepler.core.model.Rating
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The geometry of the single-plane flip (brief and `shelf-language.md` §3). Angle runs 0..180. */
object FlipModel {
    private fun rad(a: Float) = a * PI.toFloat() / 180f
    /** Faces swap at 90 degrees. */
    fun showsBack(angle: Float) = angle >= 90f
    /** The back is pre-rotated 180 so it reads the right way round after the swap. */
    fun faceRotation(angle: Float) = if (showsBack(angle)) angle - 180f else angle
    fun scale(angle: Float) = 1f + 0.04f * sin(rad(angle))
    /** Shade peaks edge-on. */
    fun shade(angle: Float) = 0.35f * sin(rad(angle))
    /** The contact shadow narrows as the card turns edge-on. */
    fun contactWidth(angle: Float) = abs(cos(rad(angle)))
    fun target(flipped: Boolean) = if (flipped) 180f else 0f
}

@Composable
fun ReviewRoute(deps: StudyDeps, onClose: () -> Unit) {
    val vm = studyViewModel("review") { ReviewViewModel(deps) }
    val s by vm.state.collectAsState()
    val p = Kp.palette
    Column(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Caps(
                if (s.loading || s.finished) stringResource(R.string.review_caps) else stringResource(R.string.review_caps_left, s.left),
                Modifier.weight(1f),
            )
            TextLink(stringResource(R.string.close), onClose)
        }
        ProgressRule(s.progress, Modifier.padding(horizontal = 24.dp))
        when {
            s.loading -> Box(Modifier.weight(1f).padding(24.dp)) { LoadingBlocks(3) }
            s.finished -> ReviewDone(s, Modifier.weight(1f), onClose)
            else -> ReviewCardStage(s, vm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressRule(progress: Float, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Box(modifier.fillMaxWidth().height(1.dp).background(p.hairline).drawBehind {
        drawRect(p.accent, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
    })
}

@Composable
private fun ReviewCardStage(s: ReviewState, vm: ReviewViewModel, modifier: Modifier) {
    val p = Kp.palette
    val card = s.current ?: return
    Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            // a 3:4 index card resting on the table, not a pad stretched to the screen
            Box(Modifier.fillMaxWidth().heightIn(max = maxWidth * 4f / 3f)) {
            FlipCard(
                flipped = s.flipped, onFlip = vm::flip,
                onSwipe = { r -> if (s.flipped) vm.grade(r) },
                front = {
                    Column(Modifier.fillMaxSize()) {
                        BasicText(card.front, style = StudyType.cardProse(p.readText).copy(fontSize = 21.sp))
                        s.hint?.let {
                            Spacer(Modifier.height(16.dp))
                            BasicText(it, style = StudyType.quote(p.text2))
                        }
                    }
                },
                back = {
                    Column(Modifier.fillMaxSize()) {
                        Caps(card.front.lineSequence().first().take(80), color = p.text2)
                        Spacer(Modifier.height(12.dp))
                        BasicText(card.back, style = StudyType.cardProse(p.readText))
                        if (!card.userAnswer.isNullOrBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Caps(stringResource(R.string.in_your_words), color = p.text2)
                            Spacer(Modifier.height(4.dp))
                            BasicText(card.userAnswer!!, style = StudyType.body(p.text2))
                        }
                    }
                },
            )
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            if (!s.flipped) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!s.hintShown) SecondaryKey(stringResource(R.string.hint), vm::showHint)
                    PrimaryKey(stringResource(R.string.show_answer), vm::flip, Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    GradeKey(Rating.AGAIN, p.negative, R.string.grade_again, s, vm, Modifier.weight(1f))
                    GradeKey(Rating.HARD, p.warning, R.string.grade_hard, s, vm, Modifier.weight(1f))
                    GradeKey(Rating.GOOD, p.positive, R.string.grade_good, s, vm, Modifier.weight(1f))
                    GradeKey(Rating.EASY, p.accent, R.string.grade_easy, s, vm, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GradeKey(r: Rating, edge: Color, label: Int, s: ReviewState, vm: ReviewViewModel, modifier: Modifier) {
    val ms = s.intervals[r]
    SecondaryKey(
        stringResource(label), { vm.grade(r) }, modifier.heightIn(min = 56.dp), edge = edge,
        caption = ms?.let { intervalText(formatInterval(it)) },
    )
}

/**
 * One plane turned with `graphicsLayer`: rotationY driven by the `flip` spring, camera 12 x density,
 * faces swapped at 90 degrees. Reduced motion crossfades instead. Swipe left Again, right Good, up Easy.
 */
@Composable
internal fun FlipCard(
    flipped: Boolean, onFlip: () -> Unit, onSwipe: (Rating) -> Unit,
    front: @Composable BoxScope.() -> Unit, back: @Composable BoxScope.() -> Unit,
) {
    val reduced = rememberReducedMotion()
    val angle = remember { Animatable(FlipModel.target(flipped)) }
    LaunchedEffect(flipped, reduced) {
        if (reduced) angle.snapTo(FlipModel.target(flipped)) else angle.animateTo(FlipModel.target(flipped), StudyMotion.flip(false))
    }
    val haptics = app.kepler.core.designsystem.rememberHaptics()
    // A tick as the card passes edge-on, where the faces swap.
    LaunchedEffect(angle) {
        androidx.compose.runtime.snapshotFlow { FlipModel.showsBack(angle.value) }
            .distinctUntilChanged().drop(1)
            .collect { haptics.play(app.kepler.core.designsystem.HapticToken.Tick) }
    }
    val density = LocalDensity.current.density
    val p = Kp.palette
    var dx = 0f; var dy = 0f
    val swipe = 72f * density
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onFlip() } }
            .pointerInput(flipped) {
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDrag = { _, d -> dx += d.x; dy += d.y },
                    onDragEnd = {
                        when {
                            dy < -swipe && abs(dy) > abs(dx) -> onSwipe(Rating.EASY)
                            dx < -swipe -> onSwipe(Rating.AGAIN)
                            dx > swipe -> onSwipe(Rating.GOOD)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (reduced) {
            Crossfade(flipped, animationSpec = tween(120), label = "flip") { isBack -> IndexCard(Modifier.fillMaxSize()) { if (isBack) back(this) else front(this) } }
        } else {
            val a = angle.value
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    rotationY = FlipModel.faceRotation(a)
                    cameraDistance = 12f * density
                    val sc = FlipModel.scale(a); scaleX = sc; scaleY = sc
                },
            ) {
                IndexCard(Modifier.fillMaxSize()) { if (FlipModel.showsBack(a)) back(this) else front(this) }
                Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = FlipModel.shade(a))) })
            }
        }
    }
}

/** Index card on `page`: r2, an accent rule 24 from the top, ruled hairlines, two paper edges offset 4 and 8. */
@Composable
internal fun IndexCard(modifier: Modifier = Modifier, scroll: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val p = Kp.palette
    val shape = RoundedCornerShape(2.dp)
    Box(modifier.padding(bottom = 8.dp)) {
        Box(
            Modifier.matchParentSize().drawBehind {
                listOf(8.dp, 4.dp).forEach { off ->
                    drawRoundRect(p.hairline, Offset(0f, off.toPx()), size, androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                    drawRoundRect(p.page, Offset(1.dp.toPx(), off.toPx()), Size(size.width - 2.dp.toPx(), size.height - 1.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            },
        )
        Box(
            Modifier.matchParentSize().clip(shape).background(p.page).border(1.dp, p.hairline, shape).drawBehind {
                val top = 24.dp.toPx()
                drawRect(p.accent, Offset(0f, top), Size(size.width, 1.dp.toPx()))
                // rules sit just under each 26 dp line of card prose (text starts 16 below the accent rule)
                var y = top + 38.dp.toPx()
                while (y < size.height - 8.dp.toPx()) { drawRect(p.hairline.copy(alpha = 0.6f), Offset(0f, y), Size(size.width, 1.dp.toPx())); y += 26.dp.toPx() }
            },
        )
        Box(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 20.dp)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
    }
}

@Composable
private fun ReviewDone(s: ReviewState, modifier: Modifier, onClose: () -> Unit) {
    val p = Kp.palette
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
        if (s.nothingDue) {
            BasicText(stringResource(R.string.nothing_due), style = StudyType.headline(p.text))
        } else {
            BasicText(stringResource(R.string.review_done), style = StudyType.prose(p.text))
        }
        s.week?.let { w ->
            Spacer(Modifier.height(32.dp))
            WeekSquares(w)
            Spacer(Modifier.height(12.dp))
            WeekCaption(w)
        }
        Spacer(Modifier.height(32.dp))
        PrimaryKey(stringResource(R.string.done), onClose, Modifier.fillMaxWidth())
    }
}

/** Seven 10 dp squares for the week; today fills with the accent, other reviewed days with `text2`. */
@Composable
internal fun WeekSquares(w: WeekProgress, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        w.days.forEach { d ->
            Box(
                Modifier.size(10.dp).clip(RoundedCornerShape(1.dp))
                    .background(
                        when {
                            d.reviewed && d.isToday -> p.accent
                            d.reviewed -> p.text2
                            else -> Color.Transparent
                        },
                    )
                    .then(if (!d.reviewed) Modifier.border(1.dp, if (d.isFuture) p.hairline else p.text2.copy(alpha = 0.5f), RoundedCornerShape(1.dp)) else Modifier),
            )
        }
    }
}

@Composable
internal fun WeekCaption(w: WeekProgress) {
    val p = Kp.palette
    BasicText(
        // "0 freezes left" reads as a warning; with none left the caption just counts days
        if (w.freezesLeft > 0) stringResource(R.string.week_caption, w.reviewedDays, w.goal, w.freezesLeft)
        else stringResource(R.string.week_caption_plain, w.reviewedDays, w.goal),
        style = StudyType.caption(p.text2),
    )
}
