package app.betterbooks.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.designsystem.BbMotion
import app.betterbooks.core.designsystem.BbType
import app.betterbooks.core.designsystem.HapticToken
import app.betterbooks.core.designsystem.rememberHaptics
import app.betterbooks.core.designsystem.rememberReducedMotion

/**
 * The round play key: `key` fill with the primary-key bevel (inset top 1 white .7, inset bottom 1 black .2,
 * drop 0/2). It is a circle while paused and an r8 square while playing, on the `snap` spring.
 */
@Composable
internal fun PlayKey(
    playing: Boolean,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val radius by animateDpAsState(
        if (playing) 8.dp else size / 2, if (reduced) snap() else BbMotion.snap(), label = "playRadius",
    )
    val glyph by animateFloatAsState(if (playing) 1f else 0f, if (reduced) snap() else BbMotion.snap(), label = "playGlyph")
    val fill = if (enabled) p.key else p.surface2
    val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    Box(
        modifier
            .size(size)
            .drawWithCache {
                val r = radius.toPx()
                val rr = RoundRect(0f, 0f, this.size.width, this.size.height, CornerRadius(r))
                val body = Path().apply { addRoundRect(rr) }
                val lift = 1.dp.toPx()
                fun shifted(dy: Float) = Path().apply { addRoundRect(rr.translate(Offset(0f, dy))) }
                val topLine = Path.combine(PathOperation.Difference, body, shifted(lift))
                val bottomLine = Path.combine(PathOperation.Difference, body, shifted(-lift))
                val drop = 2.dp.toPx()
                val dropColor = Color.Black.copy(alpha = if (p.isDark) 0.9f else 0.25f)
                onDrawBehind {
                    if (enabled) {
                        if (!pressed) drawRoundRect(dropColor, Offset(0f, drop), this.size, CornerRadius(r))
                        drawRoundRect(fill, Offset.Zero, this.size, CornerRadius(r))
                        val a = Color.White.copy(alpha = 0.7f)
                        val b = Color.Black.copy(alpha = 0.2f)
                        drawPath(topLine, if (pressed) b else a)
                        drawPath(bottomLine, if (pressed) a else b)
                    } else {
                        drawRoundRect(fill, Offset.Zero, this.size, CornerRadius(r))
                    }
                }
            }
            .clickable(src, null, enabled = enabled, role = Role.Button) {
                haptics.play(HapticToken.Confirm)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.38f)) {
            val tint = if (enabled) p.onKey else p.text2
            drawPlayPause(tint, glyph)
        }
    }
}

/** Triangle cross-fading into two bars; [t] 0 = play, 1 = pause. */
private fun DrawScope.drawPlayPause(tint: Color, t: Float) {
    val w = size.width
    val h = size.height
    if (t < 1f) {
        val tri = Path().apply {
            moveTo(w * 0.12f, 0f); lineTo(w * 0.12f, h); lineTo(w, h / 2f); close()
        }
        drawPath(tri, tint.copy(alpha = tint.alpha * (1f - t)))
        drawPath(tri, tint.copy(alpha = tint.alpha * (1f - t)), style = Stroke(w * 0.1f, join = StrokeJoin.Round))
    }
    if (t > 0f) {
        val bar = w * 0.30f
        val c = CornerRadius(w * 0.06f)
        val col = tint.copy(alpha = tint.alpha * t)
        drawRoundRect(col, Offset(w * 0.08f, 0f), Size(bar, h), c)
        drawRoundRect(col, Offset(w - bar - w * 0.08f, 0f), Size(bar, h), c)
    }
}

internal enum class SkipDirection { Back, Forward }

/** A 48 dp key-less skip control: an open circular arrow around the number of seconds. */
@Composable
internal fun SkipKey(
    seconds: Int,
    direction: SkipDirection,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    Box(
        modifier
            .size(56.dp)
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            val u = size.width / 24f
            val st = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val mirror = if (direction == SkipDirection.Back) -1f else 1f
            scale(mirror, 1f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                val inset = 3f * u
                drawArc(
                    p.text, startAngle = -50f, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset), style = st,
                )
                // arrowhead at the end of the arc (upper left of the circle), pointing along the stroke
                val head = Path().apply {
                    moveTo(5.2f * u, 1.8f * u); lineTo(9.2f * u, 3.1f * u); lineTo(6.4f * u, 6.3f * u)
                }
                drawPath(head, p.text, style = st)
            }
        }
        Text(seconds.toString(), color = p.text, style = BbType.numeral.copy(fontSize = 11.sp, lineHeight = 12.sp))
    }
}

internal enum class SentenceDirection { Previous, Next }

/** The previous/next sentence glyph from the reader transport: a bar and a chevron. */
@Composable
internal fun SentenceKey(
    direction: SentenceDirection,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    Box(
        modifier
            .size(48.dp)
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.width / 24f
            val st = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (direction == SentenceDirection.Previous) {
                drawLine(p.text, Offset(6f * u, 5.5f * u), Offset(6f * u, 18.5f * u), st.width, StrokeCap.Round)
                val chev = Path().apply { moveTo(18f * u, 5.5f * u); lineTo(10f * u, 12f * u); lineTo(18f * u, 18.5f * u) }
                drawPath(chev, p.text, style = st)
            } else {
                drawLine(p.text, Offset(18f * u, 5.5f * u), Offset(18f * u, 18.5f * u), st.width, StrokeCap.Round)
                val chev = Path().apply { moveTo(6f * u, 5.5f * u); lineTo(14f * u, 12f * u); lineTo(6f * u, 18.5f * u) }
                drawPath(chev, p.text, style = st)
            }
        }
    }
}

internal val SmallKeyShape = RoundedCornerShape(4.dp)
