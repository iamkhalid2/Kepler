package app.kepler.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Swing angle in degrees for a ribbon. It swings once whenever [trigger] changes (an event: appearance,
 * progress change, sentence change), then rests. The spring is under-damped (zeta .2), so a velocity of
 * about 17 deg/s gives a peak near 4 degrees and a period near 1.9 s. Nothing loops.
 */
@Composable
fun rememberRibbonSwing(
    trigger: Any?,
    velocity: Float = 17f,
    delayMs: Long = 0L,
    stiffness: Float = 12f,
): State<Float> {
    val angle = remember { Animatable(0f) }
    val reduced = rememberReducedMotion()
    LaunchedEffect(trigger) {
        if (reduced) {
            angle.snapTo(0f)
            return@LaunchedEffect
        }
        if (delayMs > 0) delay(delayMs)
        angle.snapTo(0f)
        angle.animateTo(0f, spring(0.2f, stiffness), initialVelocity = velocity)
    }
    return angle.asState()
}

/**
 * One notched silk ribbon hanging from its top centre. Reads [swing] only while drawing, so a swing
 * never recomposes. [foldAt] is where the ribbon crosses the plank lip (a 1.6 dp highlight).
 */
@Composable
fun SilkRibbon(
    silk: Silk,
    swing: State<Float>,
    width: Dp,
    length: Dp,
    modifier: Modifier = Modifier,
    foldAt: Dp = 9.dp,
) {
    Canvas(modifier.size(width, length)) {
        val w = size.width
        val l = size.height
        val notch = 0.7f * w
        val path = Path().apply {
            moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, l); lineTo(w / 2f, l - notch); lineTo(0f, l); close()
        }
        rotate(swing.value, pivot = Offset(w / 2f, 0f)) {
            drawPath(
                path,
                Brush.verticalGradient(0f to silk.edgeA, 0.45f to silk.sheen, 1f to silk.edgeB, startY = 0f, endY = l),
            )
            // head shade: .45 black fading out over the first 20%
            drawRect(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent, startY = 0f, endY = l * 0.2f),
                size = Size(w, l * 0.2f),
            )
            drawRect(Color.White.copy(alpha = 0.55f), Offset(0f, foldAt.toPx()), Size(w, 1.6.dp.toPx()))
        }
    }
}

/** Ribbon length for a 0..1 share. */
fun ribbonLength(share: Float, scale: Float = 1f): Dp = ((24f + 80f * share.coerceIn(0f, 1f)) * scale).dp

/**
 * The two progress ribbons: bone = read share, accent silk = listened share. Both swing when [swingKey]
 * changes; the second starts 120 ms later at .85 of the velocity.
 */
@Composable
fun BookRibbons(
    readShare: Float,
    listenShare: Float,
    swingKey: Any?,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    val m = Kp.palette.materials
    val a = rememberRibbonSwing(swingKey, velocity = 17f)
    val b = rememberRibbonSwing(swingKey, velocity = 17f * 0.85f, delayMs = 120L)
    Row(modifier, verticalAlignment = Alignment.Top) {
        SilkRibbon(m.boneRibbon, b, 8.dp * scale, ribbonLength(readShare, scale))
        Spacer(Modifier.width(5.dp * scale))
        SilkRibbon(m.silk, a, 8.dp * scale, ribbonLength(listenShare, scale))
    }
}
