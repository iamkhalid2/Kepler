package app.kepler.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Kepler mark: an elliptical orbit, the sector swept from its focus (equal areas in equal
 * times), and the body at the end of the sweep in the accent. Same geometry as the launcher icon
 * and docs/design/brand/kepler-mark.svg. [size] maps to the 66-unit safe zone of the 108dp icon, so
 * the drawing carries a small built-in margin; the clear-space rule in the brand README still applies.
 *
 * @param ink colour of orbit, sector and focus; defaults to `text` (bone in dark, ink in light).
 * @param accent colour of the body; defaults to `accent`. Pass [ink] for a one-colour mark.
 */
@Composable
fun KpMark(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    ink: Color = Kp.palette.text,
    accent: Color = Kp.palette.accent,
    contentDescription: String? = null,
) {
    val geometry = remember { KpMarkGeometry() }
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier.size(size).then(semantics)) {
        val s = this.size.minDimension / KpMarkGeometry.BOX
        withTransform({
            translate(left = (KpMarkGeometry.BOX / 2f + KpMarkGeometry.NUDGE_X) * s, top = KpMarkGeometry.BOX / 2f * s)
            rotate(KpMarkGeometry.ROTATION, pivot = Offset.Zero)
            scale(s, s, pivot = Offset.Zero)
        }) {
            clipPath(geometry.halo, ClipOp.Difference) {
                drawOval(
                    color = ink,
                    topLeft = Offset(-KpMarkGeometry.A, -KpMarkGeometry.B),
                    size = Size(2 * KpMarkGeometry.A, 2 * KpMarkGeometry.B),
                    style = Stroke(width = KpMarkGeometry.STROKE),
                )
                drawPath(geometry.sector, ink)
                drawCircle(ink, KpMarkGeometry.SUN, Offset(geometry.focus, 0f))
            }
            drawCircle(accent, KpMarkGeometry.BODY, geometry.body)
        }
    }
}

/** Mark geometry in mark units, origin at the orbit centre, before rotation. */
internal class KpMarkGeometry {
    val focus = sqrt(A * A - B * B)
    private val start = point(SWEEP_START)
    val body = point(SWEEP_START + SWEEP)
    val sector = Path().apply {
        moveTo(focus, 0f)
        lineTo(start.x, start.y)
        arcTo(Rect(-A, -B, A, B), SWEEP_START, SWEEP, forceMoveTo = false)
        close()
    }
    val halo = Path().apply { addOval(Rect(body, HALO)) }

    private fun point(deg: Float): Offset {
        val r = Math.toRadians(deg.toDouble())
        return Offset(A * cos(r).toFloat(), B * sin(r).toFloat())
    }

    companion object {
        const val BOX = 66f
        const val NUDGE_X = 1f
        const val ROTATION = -18f
        const val A = 27f
        const val B = 18.5f
        const val STROKE = 3.5f
        const val SWEEP_START = 145f
        const val SWEEP = 70f
        const val SUN = 3.2f
        const val BODY = 4.5f
        const val HALO = 6.5f
    }
}
