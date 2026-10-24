package app.kepler.feature.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/** Glyphs the shared set does not have yet, drawn on the same 24 grid with round caps. */
internal object PlayerIcons {
    private fun icon(name: String, width: Float = 2f, body: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = PathBuilder().apply(body).nodes,
                stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Minus = icon("minus") { moveTo(5f, 12f); lineTo(19f, 12f) }
}
