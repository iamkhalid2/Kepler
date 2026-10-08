package app.betterbooks.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Top face 16 + lip 1 + front 9. Books stand [PlankBaseline] below the top edge, on the top face. */
val PlankHeight: Dp = 26.dp
val PlankBaseline: Dp = 8.dp

/**
 * The lit shelf plank: a top face, a 0.9 dp lip that fades away from the key light, and a dark front.
 * No elevation, no blur: depth is the lip and the contact ellipse of whatever stands on it.
 */
@Composable
fun ShelfPlank(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val env = Bb.palette.materials.env
    Canvas(modifier.fillMaxWidth().height(PlankHeight)) {
        drawShelfPlank(env, 0f, size.width, alpha)
    }
}

/** Draws a plank whose top edge is at [top] (px). Used by shelves that share one canvas. */
fun DrawScope.drawShelfPlank(env: Env, top: Float, width: Float, alpha: Float = 1f) {
    val d = density
    val topH = 16f * d
    val lipH = 0.9f * d
    val frontH = 9f * d
    drawRect(
        Brush.verticalGradient(listOf(env.plankTopFrom.copy(alpha = alpha), env.plankTopTo.copy(alpha = alpha)), startY = top, endY = top + topH),
        Offset(0f, top), Size(width, topH),
    )
    val lipY = top + topH
    if (env.seam.alpha > 0f) drawRect(env.seam.copy(alpha = env.seam.alpha * alpha), Offset(0f, lipY - 0.25f * d), Size(width, 0.5f * d))
    drawRect(
        Brush.horizontalGradient(
            listOf(
                env.lipBase.copy(alpha = env.lipA * alpha),
                env.lipBase.copy(alpha = env.lipB * alpha),
                env.lipBase.copy(alpha = env.lipC * alpha),
            ),
            startX = 0f, endX = width,
        ),
        Offset(0f, lipY), Size(width, lipH),
    )
    drawRect(env.plankFront.copy(alpha = alpha), Offset(0f, lipY + lipH), Size(width, frontH))
}
