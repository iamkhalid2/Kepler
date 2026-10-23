package app.kepler.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpMotion
import app.kepler.core.designsystem.KpRadius
import app.kepler.core.designsystem.KpScrubber
import app.kepler.core.designsystem.KpSkipIcon
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.designsystem.rememberReducedMotion
import app.kepler.core.model.PlaybackController
import app.kepler.core.model.bookRemainingWallMs
import app.kepler.core.model.sectionElapsedWallMs
import app.kepler.core.model.sectionFraction
import app.kepler.core.model.sectionRemainingWallMs

// The transport vocabulary shared by the Listen face and the reader's dock: one play key, standard skip
// glyphs, chapter skips, and the small labelled controls around them. 48 dp targets everywhere.

/**
 * Play/pause on a `key` circle. The glyph morphs: the triangle splits into two halves that square off into the
 * pause bars. While [buffering] a thin accent arc runs round the circle.
 */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    buffering: Boolean = false,
    enabled: Boolean = true,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val morph = remember { Animatable(if (playing) 1f else 0f) }
    LaunchedEffect(playing, reduced) {
        if (reduced) morph.snapTo(if (playing) 1f else 0f) else morph.animateTo(if (playing) 1f else 0f, KpMotion.snap())
    }
    val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    val fill = when {
        !enabled -> p.surface2
        pressed -> lerp(p.key, p.bg, 0.12f)
        else -> p.key
    }
    val glyph = if (enabled) p.onKey else p.text2
    Box(
        modifier.size(maxOf(size, 48.dp))
            .clip(CircleShape)
            .clickable(src, null, enabled = enabled, role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(fill, CircleShape))
        Canvas(Modifier.size(size * 0.42f)) { drawPlayPause(morph.value, glyph) }
        if (buffering) BufferingArc(size, p.accent)
    }
}

@Composable
private fun BufferingArc(size: Dp, color: Color) {
    val turn = rememberInfiniteTransition(label = "buffering")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(Modifier.size(size)) {
        val w = 2.5.dp.toPx()
        drawArc(color, angle, 90f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha,
)

// Play (t = 0) and pause (t = 1) as two quads each on a 24 grid, vertices in matching order.
private val PLAY_LEFT = floatArrayOf(7.5f, 4.5f, 13f, 8f, 13f, 16f, 7.5f, 19.5f)
private val PLAY_RIGHT = floatArrayOf(13f, 8f, 19.5f, 12f, 19.5f, 12f, 13f, 16f)
private val PAUSE_LEFT = floatArrayOf(6f, 4.5f, 10f, 4.5f, 10f, 19.5f, 6f, 19.5f)
private val PAUSE_RIGHT = floatArrayOf(14f, 4.5f, 18f, 4.5f, 18f, 19.5f, 14f, 19.5f)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayPause(t: Float, color: Color) {
    val u = size.minDimension / 24f
    fun quad(a: FloatArray, b: FloatArray) = Path().apply {
        for (i in 0 until 4) {
            val x = (a[i * 2] + (b[i * 2] - a[i * 2]) * t) * u
            val y = (a[i * 2 + 1] + (b[i * 2 + 1] - a[i * 2 + 1]) * t) * u
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    val round = Stroke(1.6f * u, join = StrokeJoin.Round)
    for (path in listOf(quad(PLAY_LEFT, PAUSE_LEFT), quad(PLAY_RIGHT, PAUSE_RIGHT))) {
        drawPath(path, color, style = Fill)
        drawPath(path, color, style = round)
    }
}

/** Replay 15 / forward 30: the standard arrow ring with the seconds in it, in a 56 dp target. */
@Composable
fun SkipButton(seconds: Int, forward: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: Dp = 34.dp) {
    val label = stringResource(if (forward) R.string.player_forward_30 else R.string.player_back_15)
    PressCircle(onClick, label, modifier, 56.dp) { KpSkipIcon(seconds, forward, Kp.palette.text, size = glyph) }
}

/** Previous / next chapter. */
@Composable
fun ChapterSkipButton(next: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = Kp.palette
    val label = stringResource(if (next) R.string.player_next_chapter else R.string.player_prev_chapter)
    PressCircle(onClick, label, modifier, 48.dp, enabled) {
        KpIconView(if (next) KpIcons.SkipNext else KpIcons.SkipPrevious, if (enabled) p.text else p.text2.copy(alpha = 0.5f), size = 26.dp)
    }
}

/** A round touch target that shows a surface2 disc while pressed. */
@Composable
internal fun PressCircle(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    enabled: Boolean = true,
    state: String? = null,
    content: @Composable () -> Unit,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier.size(size)
            .clip(CircleShape)
            .background(if (pressed) p.surface2 else Color.Transparent)
            .clickable(src, null, enabled = enabled, role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Speed as a label ("1.25×") in a 48 dp target. */
@Composable
fun SpeedButton(speed: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val text = PlayerFormat.speed(speed)
    LabelledControl(onClick, stringResource(R.string.player_speed_value, text), modifier, active = speed != 1f) {
        Text(text, color = if (speed != 1f) p.accentText else p.text, style = KpType.numeral, maxLines = 1)
    }
}

/** Sleep timer: the moon, plus the time left (or "Chapter end") while a timer runs. */
@Composable
fun SleepButton(remainingMs: Long?, atSectionEnd: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val active = remainingMs != null || atSectionEnd
    val label = when {
        atSectionEnd -> stringResource(R.string.player_sleep_chapter_short)
        remainingMs != null -> PlayerFormat.clock(remainingMs)
        else -> null
    }
    val description = if (label != null) stringResource(R.string.player_sleep_on, label) else stringResource(R.string.player_sleep)
    LabelledControl(onClick, description, modifier, active = active) {
        KpIconView(KpIcons.Sleep, if (active) p.accentText else p.text, size = 22.dp)
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = p.accentText, style = KpType.time.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** Bookmark the sentence being spoken; solid while one is there. */
@Composable
fun BookmarkButton(marked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = Kp.palette
    val label = stringResource(if (marked) R.string.player_bookmark_remove else R.string.player_bookmark)
    PressCircle(onClick, label, modifier, enabled = enabled) {
        KpIconView(if (marked) KpIcons.BookmarkFilled else KpIcons.Bookmark, if (marked) p.accentText else p.text, size = 22.dp)
    }
}

/** A 48 high pill-shaped target for a glyph and/or a short label. */
@Composable
private fun LabelledControl(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.heightIn(min = 36.dp)
                .clip(KpRadius.pill)
                .background(if (pressed) p.surface2 else Color.Transparent)
                .clickable(src, null, role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
                .semantics { contentDescription = description }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/** The narrating voice: a 40 high pill with an initial avatar; opens narration setup. */
@Composable
fun VoicePill(name: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val shown = name ?: stringResource(R.string.player_narrator_fallback)
    val description = stringResource(R.string.player_voice, shown)
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(40.dp).widthIn(max = 180.dp)
                .clip(KpRadius.pill)
                .background(p.surface1)
                .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, KpRadius.pill) else Modifier)
                .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
                .semantics { contentDescription = description }
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).background(p.accent.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                Text(shown.take(1).uppercase(), color = p.accentText, style = KpType.button)
            }
            Spacer(Modifier.width(8.dp))
            Text(shown, color = p.text, style = KpType.subhead.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp))
            KpIconView(KpIcons.ChevronDown, p.text2, size = 16.dp)
        }
    }
}

/** The chapter chip: h36 pill, list glyph, chapter title, chevron. Opens the chapter list. */
@Composable
fun ChapterChip(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val chapters = stringResource(R.string.player_chapters)
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(36.dp)
                .clip(KpRadius.pill)
                .background(p.surface1)
                .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, KpRadius.pill) else Modifier)
                .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
                .semantics { contentDescription = "$chapters, $title" }
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KpIconView(KpIcons.Chapters, p.text2, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(title, color = p.text, style = KpType.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            KpIconView(KpIcons.ChevronDown, p.text2, size = 16.dp)
        }
    }
}

/**
 * The chapter scrubber (#109): elapsed and -remaining in the chapter at the current speed, time left in the book
 * in the middle ([showBook]). Estimated lengths carry "~".
 */
@Composable
fun PlaybackScrubber(controller: PlaybackController, modifier: Modifier = Modifier, showBook: Boolean = true) {
    val fraction by controller.select { it.sectionFraction }
    val elapsedSec by controller.select { it.sectionElapsedWallMs / 1000 }
    val remainingSec by controller.select { it.sectionRemainingWallMs / 1000 }
    val estimatedChapter by controller.select { it.sectionDurationEstimated }
    val bookMin by controller.select { it.bookRemainingWallMs / 60_000 }
    val estimatedBook by controller.select { it.bookRemainingEstimated }
    val hasChapter by controller.select { it.sectionDurationMs > 0 }
    val middle = if (showBook && bookMin > 0) {
        stringResource(R.string.player_left_in_book, estimated(durationLabel(bookMin * 60_000), estimatedBook))
    } else null
    KpScrubber(
        fraction = fraction,
        onSeek = { controller.seekWithinSection(it) },
        elapsed = PlayerFormat.clock(elapsedSec * 1000),
        // One "~" per row: when the book time in the middle is already marked as estimated, "~−12:40" beside it only
        // adds noise, so the chapter time drops its own mark.
        remaining = if (hasChapter) {
            estimated("−" + PlayerFormat.clock(remainingSec * 1000), estimatedChapter && !(middle != null && estimatedBook))
        } else "−0:00",
        middle = middle,
        contentDescription = stringResource(R.string.player_scrubber_description),
        modifier = modifier,
    )
}

/**
 * The transport row: [leading], replay 15, play/pause, forward 30, [trailing]. On the Listen face the outer slots
 * hold the chapter skips; in the reader's dock, speed and sleep.
 */
@Composable
fun TransportRow(
    controller: PlaybackController,
    modifier: Modifier = Modifier,
    playSize: Dp = 72.dp,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val loaded by controller.select { it.bookId != null }
    val playing by controller.select { it.playing }
    val buffering by controller.select { it.buffering && it.playing }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) { leading() }
        SkipButton(15, forward = false, onClick = { controller.seekByMs(-15_000) })
        PlayPauseButton(
            playing = playing, size = playSize, buffering = buffering, enabled = loaded,
            onClick = { if (playing) controller.pause() else controller.resume() },
        )
        SkipButton(30, forward = true, onClick = { controller.seekByMs(30_000) })
        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) { trailing() }
    }
}
