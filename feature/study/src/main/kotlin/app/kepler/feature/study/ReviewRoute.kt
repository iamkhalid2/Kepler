package app.kepler.feature.study

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpInput
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpStatTile
import app.kepler.core.designsystem.KpTopBar
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.model.Rating
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The geometry of the single-plane flip (design brief v2 section 7). Angle runs 0..180. */
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
        KpTopBar(
            onBack = onClose, backIcon = KpIcons.Close,
            title = stringResource(R.string.review_title),
            caption = if (s.loading || s.finished) null else stringResource(R.string.review_card_of, (s.index + 1).coerceAtMost(s.total), s.total),
        )
        if (!s.loading && s.total > 0) SegmentProgress(s.total, s.index, Modifier.padding(horizontal = KpSpace.gutter, vertical = 8.dp))
        when {
            s.loading -> Box(Modifier.weight(1f).padding(KpSpace.gutter)) { LoadingBlocks(3) }
            s.finished -> ReviewDone(s, Modifier.weight(1f), onClose)
            else -> ReviewCardStage(s, vm, Modifier.weight(1f))
        }
    }
}

/** One 4 dp segment per card (a single bar past 30 cards); accent for the cards already graded. */
@Composable
internal fun SegmentProgress(total: Int, done: Int, modifier: Modifier = Modifier) {
    val p = Kp.palette
    if (total > 30) {
        Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.surface2).drawBehind {
            drawRect(p.accent, size = Size(size.width * (done.toFloat() / total).coerceIn(0f, 1f), size.height))
        })
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(total) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i < done) p.accent else p.surface2))
            }
        }
    }
}

@Composable
private fun ReviewCardStage(s: ReviewState, vm: ReviewViewModel, modifier: Modifier) {
    val p = Kp.palette
    val card = s.current ?: return
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = KpSpace.gutter, vertical = 12.dp), contentAlignment = Alignment.Center) {
            // a 3:4 index card resting on the table, not a pad stretched to the screen
            Box(Modifier.fillMaxWidth().heightIn(max = maxWidth * 4f / 3f)) {
                FlipCard(
                    flipped = s.flipped, onFlip = vm::flip,
                    onSwipe = { r -> if (s.flipped) vm.grade(r) },
                    front = {
                        Column(Modifier.fillMaxSize()) {
                            Text(card.front, color = p.readText, style = KpType.title2)
                            s.hint?.let {
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.hint_label, it), color = p.readText.copy(alpha = 0.6f), style = StudyType.quote(p.readText.copy(alpha = 0.6f)))
                            }
                        }
                    },
                    footer = s.source?.let { src ->
                        {
                            Text(src, color = p.readText.copy(alpha = 0.55f), style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    back = {
                        Column(Modifier.fillMaxSize()) {
                            Text(card.front.lineSequence().first().take(90), color = p.readText.copy(alpha = 0.55f), style = KpType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(12.dp))
                            Text(card.back, color = p.readText, style = KpType.title2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                            val typed = s.typed.ifBlank { card.userAnswer.orEmpty() }
                            if (typed.isNotBlank()) {
                                Spacer(Modifier.height(16.dp))
                                Caps(stringResource(R.string.in_your_words), color = p.readText.copy(alpha = 0.55f))
                                Spacer(Modifier.height(4.dp))
                                Text(typed, color = p.readText.copy(alpha = 0.8f), style = KpType.body)
                            }
                        }
                    },
                )
            }
        }
        Column(Modifier.padding(start = KpSpace.gutter, end = KpSpace.gutter, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!s.flipped) {
                KpInput(
                    s.typed, vm::setTyped, placeholder = stringResource(R.string.review_answer_hint),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!s.hintShown) SecondaryKey(stringResource(R.string.hint), vm::showHint, Modifier.weight(1f))
                    PrimaryKey(stringResource(R.string.show_answer), vm::flip, Modifier.weight(if (s.hintShown) 1f else 1.4f))
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

/** 56 high, r14: label over the next interval, tinted by how it went. */
@Composable
private fun GradeKey(r: Rating, tone: Color, label: Int, s: ReviewState, vm: ReviewViewModel, modifier: Modifier) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val ms = s.intervals[r]
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(14.dp))
            .background(p.surface1)
            .drawBehind { drawRect(tone.copy(alpha = if (p.isDark) 0.16f else 0.12f)) }
            .clickable(role = Role.Button) { haptics.play(HapticToken.Confirm); vm.grade(r) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(label), color = p.text, style = KpType.button, maxLines = 1)
        if (ms != null) Text(intervalText(formatInterval(ms)), color = p.text2, style = KpType.time, maxLines = 1)
    }
}

/**
 * One plane turned with `graphicsLayer`: rotationY driven by the `flip` spring, camera 12 x density,
 * faces swapped at 90 degrees. Reduced motion crossfades instead. Swipe left Again, right Good, up Easy.
 */
@Composable
internal fun FlipCard(
    flipped: Boolean, onFlip: () -> Unit, onSwipe: (Rating) -> Unit,
    front: @Composable BoxScope.() -> Unit, back: @Composable BoxScope.() -> Unit,
    footer: (@Composable BoxScope.() -> Unit)? = null,
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
            Crossfade(flipped, animationSpec = tween(120), label = "flip") { isBack -> IndexCard(Modifier.fillMaxSize(), footer = footer) { if (isBack) back(this) else front(this) } }
        } else {
            val a = angle.value
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    rotationY = FlipModel.faceRotation(a)
                    cameraDistance = 12f * density
                    val sc = FlipModel.scale(a); scaleX = sc; scaleY = sc
                },
            ) {
                IndexCard(Modifier.fillMaxSize(), footer = footer) { if (FlipModel.showsBack(a)) back(this) else front(this) }
                Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = FlipModel.shade(a))) })
            }
        }
    }
}

/**
 * Index card (design brief v2 section 7): paper, one red rule 36 dp from the top, faint blue lines, r6, and the soft
 * shadow every real object carries. Colours come from the palette (`page`, `negative`, `accent`).
 */
@Composable
internal fun IndexCard(
    modifier: Modifier = Modifier, scroll: Boolean = true, footer: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val p = Kp.palette
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape).background(p.page)
            .drawBehind {
                val rule = 40.dp.toPx()
                var y = rule + 30.dp.toPx()
                while (y < size.height - 8.dp.toPx()) {
                    drawRect(p.accent.copy(alpha = 0.16f), Offset(0f, y), Size(size.width, 1.dp.toPx())); y += 30.dp.toPx()
                }
                drawRect(p.negative.copy(alpha = 0.75f), Offset(0f, rule), Size(size.width, 1.5.dp.toPx()))
            },
    ) {
        Box(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = if (footer != null) 44.dp else 20.dp)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
        if (footer != null) Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 14.dp), content = footer)
    }
}

@Composable
private fun ReviewDone(s: ReviewState, modifier: Modifier, onClose: () -> Unit) {
    val p = Kp.palette
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = KpSpace.gutter, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        KpIconView(KpIcons.Check, p.accentText, size = 32.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (s.nothingDue) R.string.nothing_due else R.string.review_done_title),
            color = p.text, style = KpType.displayTitle, textAlign = TextAlign.Center,
        )
        if (!s.nothingDue) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.review_done), color = p.text2, style = KpType.body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KpSpace.cardGap)) {
                KpStatTile("${s.reviewedThisSession}", stringResource(R.string.reviewed_label), Modifier.weight(1f))
                KpStatTile("${s.againThisSession}", stringResource(R.string.to_see_again), Modifier.weight(1f))
            }
        }
        s.week?.let { w ->
            Spacer(Modifier.height(KpSpace.cardGap))
            KpCard(padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                Caps(stringResource(R.string.this_week))
                Spacer(Modifier.height(12.dp))
                WeekDots(w, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                WeekCaption(w)
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryKey(stringResource(R.string.done), onClose, Modifier.fillMaxWidth())
    }
}

/** Seven day dots with the weekday initial under each; reviewed days fill with the accent, today is ringed. */
@Composable
internal fun WeekDots(w: WeekProgress, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        w.days.forEach { d ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (d.reviewed) p.accent else p.surface2)
                        .then(if (d.isToday && !d.reviewed) Modifier.border(1.5.dp, p.accent, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (d.reviewed) KpIconView(KpIcons.Check, p.onAccent, size = 16.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    d.date.dayOfWeek.getDisplayName(DayStyle.NARROW, Locale.getDefault()),
                    color = if (d.isToday) p.text else p.text2, style = KpType.time,
                )
            }
        }
    }
}

@Composable
internal fun WeekCaption(w: WeekProgress) {
    val p = Kp.palette
    Text(
        // "0 freezes left" reads as a warning; with none left the caption just counts days
        if (w.freezesLeft > 0) stringResource(R.string.week_caption, w.reviewedDays, w.goal, w.freezesLeft)
        else stringResource(R.string.week_caption_plain, w.reviewedDays, w.goal),
        color = p.text2, style = KpType.caption,
    )
}
