package app.betterbooks.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.designsystem.HapticToken
import app.betterbooks.core.designsystem.rememberHaptics

private const val DETENT_DP = 8f

/**
 * The book scrubber: 48 dp touch height, a 2 dp track with an accent fill, 1x6 chapter ticks and a
 * 4x20 `key` thumb. Dragging snaps to a chapter tick when within 8 dp and ticks the haptic motor as
 * it crosses one. The seek happens on release.
 */
@Composable
internal fun BookScrubber(
    fraction: Float,
    ticks: List<Float>,
    description: String,
    stateText: String,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: fraction
    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                contentDescription = description
                stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { target -> onSeek(target.coerceIn(0f, 1f)); true }
            }
            .pointerInput(ticks) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val window = DETENT_DP.dp.toPx() / width
                    var lastTick: Float? = null
                    fun at(x: Float): Float {
                        val raw = (x / width).coerceIn(0f, 1f)
                        val snapped = PlayerFormat.nearestTick(raw, ticks, window)
                        if (snapped != null && snapped != lastTick) haptics.play(HapticToken.Tick)
                        lastTick = snapped
                        return snapped ?: raw
                    }
                    dragging = at(down.position.x)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEventChange() ?: break
                        if (!change.pressed) break
                        dragging = at(change.position.x)
                        change.consume()
                    }
                    dragging?.let(onSeek)
                    dragging = null
                }
            },
    ) {
        val cy = size.height / 2f
        val track = 2.dp.toPx()
        drawRect(p.hairline, Offset(0f, cy - track / 2f), Size(size.width, track))
        val x = shown * size.width
        drawRect(p.accent, Offset(0f, cy - track / 2f), Size(x, track))
        val tickColor = p.text2
        for (t in ticks) {
            val tx = (t * size.width).coerceIn(0.5.dp.toPx(), size.width - 0.5.dp.toPx())
            drawRect(tickColor.copy(alpha = 0.7f), Offset(tx - 0.5.dp.toPx(), cy - 3.dp.toPx()), Size(1.dp.toPx(), 6.dp.toPx()))
        }
        // thumb: 4x20 key with the primary-key bevel
        val tw = 4.dp.toPx()
        val th = 20.dp.toPx()
        val left = (x - tw / 2f).coerceIn(0f, size.width - tw)
        val r = CornerRadius(1.dp.toPx())
        drawRoundRect(Color.Black.copy(alpha = if (p.isDark) 0.9f else 0.25f), Offset(left, cy - th / 2f + 1.dp.toPx()), Size(tw, th), r)
        drawRoundRect(p.key, Offset(left, cy - th / 2f), Size(tw, th), r)
        drawRect(Color.White.copy(alpha = 0.7f), Offset(left, cy - th / 2f), Size(tw, 1.dp.toPx()))
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPointerEventChange() =
    awaitPointerEvent().changes.firstOrNull()
