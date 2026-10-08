package app.betterbooks.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class BbIconKind { Search, Add, Shelf, List, Back, Close, Check, Chevron }

/** Line icons drawn on a 24-unit grid with a 1.6 stroke, so no icon library is needed. */
@Composable
fun BbIcon(kind: BbIconKind, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val st = Stroke(1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        when (kind) {
            BbIconKind.Search -> {
                drawCircle(tint, 6f * u, o(10.5f, 10.5f), style = st)
                drawLine(tint, o(15f, 15f), o(20f, 20f), st.width, StrokeCap.Round)
            }
            BbIconKind.Add -> {
                drawLine(tint, o(12f, 5f), o(12f, 19f), st.width, StrokeCap.Round)
                drawLine(tint, o(5f, 12f), o(19f, 12f), st.width, StrokeCap.Round)
            }
            BbIconKind.Shelf -> {
                // three spines of different heights on a plank
                drawRect(tint, o(5f, 6f), Size(3.2f * u, 11f * u), style = st)
                drawRect(tint, o(10.4f, 4f), Size(3.2f * u, 13f * u), style = st)
                drawRect(tint, o(15.8f, 7f), Size(3.2f * u, 10f * u), style = st)
                drawLine(tint, o(3f, 20f), o(21f, 20f), st.width, StrokeCap.Round)
            }
            BbIconKind.List -> {
                for (y in listOf(7f, 12f, 17f)) drawLine(tint, o(6f, y), o(20f, y), st.width, StrokeCap.Round)
                for (y in listOf(7f, 12f, 17f)) drawCircle(tint, 0.6f * u, o(3.5f, y))
            }
            BbIconKind.Back -> {
                val p = Path().apply { moveTo(14f * u, 5f * u); lineTo(7f * u, 12f * u); lineTo(14f * u, 19f * u) }
                drawPath(p, tint, style = st)
            }
            BbIconKind.Chevron -> {
                val p = Path().apply { moveTo(10f * u, 5f * u); lineTo(17f * u, 12f * u); lineTo(10f * u, 19f * u) }
                drawPath(p, tint, style = st)
            }
            BbIconKind.Close -> {
                drawLine(tint, o(6f, 6f), o(18f, 18f), st.width, StrokeCap.Round)
                drawLine(tint, o(18f, 6f), o(6f, 18f), st.width, StrokeCap.Round)
            }
            BbIconKind.Check -> {
                val p = Path().apply { moveTo(5f * u, 12.5f * u); lineTo(10f * u, 17.5f * u); lineTo(19f * u, 7f * u) }
                drawPath(p, tint, style = st)
            }
        }
    }
}
