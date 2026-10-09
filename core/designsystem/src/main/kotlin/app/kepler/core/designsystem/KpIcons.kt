package app.kepler.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Kepler's own glyph set (brief v2 section 3): a 24-unit grid, 1.8 stroke with round caps and joins, solid
 * variants only for play, pause and a set bookmark. Drawn here so no icon library is needed; tint at use.
 */
object KpIcons {
    private const val STROKE = 1.8f

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(width: Float = STROKE, body: PathBuilder.() -> Unit) {
        addPath(
            pathData = PathBuilder().apply(body).nodes,
            stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }

    /** Filled shape; a same-colour round-joined outline softens its corners. */
    private fun ImageVector.Builder.solid(soften: Float = 1.6f, body: PathBuilder.() -> Unit) {
        val nodes = PathBuilder().apply(body).nodes
        addPath(
            pathData = nodes, fill = SolidColor(Color.Black),
            stroke = if (soften > 0f) SolidColor(Color.Black) else null, strokeLineWidth = soften,
            strokeLineJoin = StrokeJoin.Round, strokeLineCap = StrokeCap.Round,
        )
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        close()
    }

    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcTo(r, r, 0f, false, true, x + w, y + r)
        lineTo(x + w, y + h - r)
        arcTo(r, r, 0f, false, true, x + w - r, y + h)
        lineTo(x + r, y + h)
        arcTo(r, r, 0f, false, true, x, y + h - r)
        lineTo(x, y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    val Home = icon("home") {
        line {
            moveTo(4f, 10.5f); lineTo(12f, 4f); lineTo(20f, 10.5f); lineTo(20f, 19f)
            arcTo(1.5f, 1.5f, 0f, false, true, 18.5f, 20.5f); lineTo(5.5f, 20.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 4f, 19f); close()
        }
        line { moveTo(10f, 20.5f); lineTo(10f, 15f); lineTo(14f, 15f); lineTo(14f, 20.5f) }
    }

    val Library = icon("library") {
        line { roundRect(4f, 4.5f, 3.6f, 15.5f, 0.8f) }
        line { roundRect(9.6f, 4.5f, 3.6f, 15.5f, 0.8f) }
        line { moveTo(15.4f, 5.4f); lineTo(18.6f, 4.6f); lineTo(21.4f, 19.2f); lineTo(18.2f, 20f); close() }
    }

    val Notebook = icon("notebook") {
        line {
            moveTo(6f, 3.5f); lineTo(17f, 3.5f); arcTo(2f, 2f, 0f, false, true, 19f, 5.5f); lineTo(19f, 18.5f)
            arcTo(2f, 2f, 0f, false, true, 17f, 20.5f); lineTo(6f, 20.5f); close()
        }
        line { moveTo(9f, 3.5f); lineTo(9f, 20.5f) }
        line { moveTo(12f, 8.5f); lineTo(16f, 8.5f) }
        line { moveTo(12f, 12f); lineTo(16f, 12f) }
    }

    val Search = icon("search") {
        line { circle(10.75f, 10.75f, 6.25f) }
        line { moveTo(15.5f, 15.5f); lineTo(20f, 20f) }
    }

    val Add = icon("add") {
        line(2f) { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    }

    val Back = icon("back") { line(2f) { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }
    val ChevronRight = icon("chevron_right") { line(2f) { moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) } }
    val ChevronDown = icon("chevron_down") { line(2f) { moveTo(5.5f, 9f); lineTo(12f, 15.5f); lineTo(18.5f, 9f) } }
    val Close = icon("close") { line(2f) { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val Check = icon("check") { line(2f) { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }

    val MoreVert = icon("more_vert") {
        solid(0f) { circle(12f, 5.5f, 1.7f); circle(12f, 12f, 1.7f); circle(12f, 18.5f, 1.7f) }
    }
    val MoreHoriz = icon("more_horiz") {
        solid(0f) { circle(5.5f, 12f, 1.7f); circle(12f, 12f, 1.7f); circle(18.5f, 12f, 1.7f) }
    }

    val Play = icon("play") { solid(2f) { moveTo(8.5f, 5.5f); lineTo(18.5f, 12f); lineTo(8.5f, 18.5f); close() } }
    val Pause = icon("pause") {
        solid(1.2f) { roundRect(6.5f, 5f, 3.6f, 14f, 1f) }
        solid(1.2f) { roundRect(13.9f, 5f, 3.6f, 14f, 1f) }
    }
    val SkipPrevious = icon("skip_previous") {
        line(2.2f) { moveTo(6.5f, 6f); lineTo(6.5f, 18f) }
        solid(1.6f) { moveTo(18f, 6.5f); lineTo(10f, 12f); lineTo(18f, 17.5f); close() }
    }
    val SkipNext = icon("skip_next") {
        line(2.2f) { moveTo(17.5f, 6f); lineTo(17.5f, 18f) }
        solid(1.6f) { moveTo(6f, 6.5f); lineTo(14f, 12f); lineTo(6f, 17.5f); close() }
    }

    /** Arrow circle for "back N seconds"; the numeral is drawn by [KpSkipIcon]. */
    val ReplayRing = icon("replay_ring") {
        line { moveTo(12f, 5f); arcTo(8f, 8f, 0f, true, true, 5.07f, 9f) }
        line { moveTo(14.6f, 2.6f); lineTo(12f, 5f); lineTo(14.6f, 7.4f) }
    }

    /** Arrow circle for "forward N seconds". */
    val ForwardRing = icon("forward_ring") {
        line { moveTo(12f, 5f); arcTo(8f, 8f, 0f, true, false, 18.93f, 9f) }
        line { moveTo(9.4f, 2.6f); lineTo(12f, 5f); lineTo(9.4f, 7.4f) }
    }

    val Sleep = icon("sleep") {
        line {
            moveTo(19.5f, 14.5f); arcTo(8f, 8f, 0f, true, true, 9.5f, 4.5f)
            arcTo(6.5f, 6.5f, 0f, false, false, 19.5f, 14.5f); close()
        }
    }

    val Speed = icon("speed") {
        line { moveTo(4.6f, 17.5f); arcTo(8.5f, 8.5f, 0f, true, true, 19.4f, 17.5f) }
        line { moveTo(12f, 14f); lineTo(15.8f, 9.6f) }
        solid(0f) { circle(12f, 14f, 1.6f) }
    }

    val Bookmark = icon("bookmark") {
        line {
            moveTo(7f, 4f); lineTo(17f, 4f); arcTo(1f, 1f, 0f, false, true, 18f, 5f); lineTo(18f, 20f)
            lineTo(12f, 16f); lineTo(6f, 20f); lineTo(6f, 5f); arcTo(1f, 1f, 0f, false, true, 7f, 4f); close()
        }
    }
    val BookmarkFilled = icon("bookmark_filled") {
        solid(STROKE) {
            moveTo(7f, 4f); lineTo(17f, 4f); arcTo(1f, 1f, 0f, false, true, 18f, 5f); lineTo(18f, 20f)
            lineTo(12f, 16f); lineTo(6f, 20f); lineTo(6f, 5f); arcTo(1f, 1f, 0f, false, true, 7f, 4f); close()
        }
    }

    val Headphones = icon("headphones") {
        line { moveTo(4f, 16f); lineTo(4f, 13f); arcTo(8f, 8f, 0f, false, true, 20f, 13f); lineTo(20f, 16f) }
        line { roundRect(4f, 14.5f, 3.6f, 6f, 1.2f) }
        line { roundRect(16.4f, 14.5f, 3.6f, 6f, 1.2f) }
    }

    val Book = icon("book") {
        line {
            moveTo(12f, 6.5f); curveTo(10f, 5f, 7f, 4.5f, 3.5f, 5f); lineTo(3.5f, 18f)
            curveTo(7f, 17.5f, 10f, 18f, 12f, 19.5f); curveTo(14f, 18f, 17f, 17.5f, 20.5f, 18f)
            lineTo(20.5f, 5f); curveTo(17f, 4.5f, 14f, 5f, 12f, 6.5f); close()
        }
        line { moveTo(12f, 6.5f); lineTo(12f, 19.5f) }
    }

    val Sparkle = icon("sparkle") {
        line {
            moveTo(11f, 3.5f); curveTo(11.6f, 8.2f, 13f, 9.6f, 17.5f, 10.2f)
            curveTo(13f, 10.8f, 11.6f, 12.2f, 11f, 17f); curveTo(10.4f, 12.2f, 9f, 10.8f, 4.5f, 10.2f)
            curveTo(9f, 9.6f, 10.4f, 8.2f, 11f, 3.5f); close()
        }
        solid(0.8f) {
            moveTo(18f, 14.5f); curveTo(18.25f, 16f, 18.9f, 16.65f, 20.4f, 16.9f)
            curveTo(18.9f, 17.15f, 18.25f, 17.8f, 18f, 19.3f); curveTo(17.75f, 17.8f, 17.1f, 17.15f, 15.6f, 16.9f)
            curveTo(17.1f, 16.65f, 17.75f, 16f, 18f, 14.5f); close()
        }
    }

    val Chapters = icon("chapters") {
        line { moveTo(9.5f, 7f); lineTo(20f, 7f); moveTo(9.5f, 12f); lineTo(20f, 12f); moveTo(9.5f, 17f); lineTo(20f, 17f) }
        solid(0f) { circle(5f, 7f, 1.3f); circle(5f, 12f, 1.3f); circle(5f, 17f, 1.3f) }
    }

    val Grid = icon("grid") {
        line { roundRect(4f, 4f, 6.5f, 6.5f, 1.5f) }
        line { roundRect(13.5f, 4f, 6.5f, 6.5f, 1.5f) }
        line { roundRect(4f, 13.5f, 6.5f, 6.5f, 1.5f) }
        line { roundRect(13.5f, 13.5f, 6.5f, 6.5f, 1.5f) }
    }

    val Bookcase = icon("bookcase") {
        line { roundRect(5f, 6f, 3.2f, 11f, 0.6f) }
        line { roundRect(10.4f, 4f, 3.2f, 13f, 0.6f) }
        line { moveTo(15.6f, 7.6f); lineTo(18.4f, 6.8f); lineTo(20.6f, 16.2f); lineTo(17.8f, 17f); close() }
        line { moveTo(3f, 20f); lineTo(21f, 20f) }
    }

    val Tune = icon("tune") {
        line { moveTo(4f, 7f); lineTo(13.5f, 7f); moveTo(18.5f, 7f); lineTo(20f, 7f) }
        line { circle(16f, 7f, 2.3f) }
        line { moveTo(4f, 17f); lineTo(5.5f, 17f); moveTo(10.5f, 17f); lineTo(20f, 17f) }
        line { circle(8f, 17f, 2.3f) }
    }

    val Mic = icon("mic") {
        line { roundRect(9f, 3.5f, 6f, 11f, 3f) }
        line { moveTo(5.5f, 11f); arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f) }
        line { moveTo(12f, 17.5f); lineTo(12f, 20.5f) }
    }

    val Send = icon("send") { line(2f) { moveTo(12f, 19f); lineTo(12f, 5.5f); moveTo(6.5f, 11f); lineTo(12f, 5.5f); lineTo(17.5f, 11f) } }
    val ArrowForward = icon("arrow_forward") { line(2f) { moveTo(5f, 12f); lineTo(18.5f, 12f); moveTo(13f, 6.5f); lineTo(18.5f, 12f); lineTo(13f, 17.5f) } }

    val Voice = icon("voice") {
        line { circle(9f, 8f, 3.5f) }
        line { moveTo(3f, 20f); curveTo(3f, 16.5f, 5.7f, 14f, 9f, 14f); curveTo(12.3f, 14f, 15f, 16.5f, 15f, 20f) }
        line { moveTo(17f, 6f); curveTo(18.3f, 7.4f, 18.3f, 10.6f, 17f, 12f) }
        line { moveTo(19.6f, 4f); curveTo(22f, 6.6f, 22f, 11.4f, 19.6f, 14f) }
    }

    val Edit = icon("edit") {
        line { moveTo(4f, 20f); lineTo(8f, 20f); lineTo(19f, 9f); lineTo(15f, 5f); lineTo(4f, 16f); close() }
        line { moveTo(13f, 7f); lineTo(17f, 11f) }
    }

    val Highlight = icon("highlight") {
        line { moveTo(14.5f, 4.5f); lineTo(19.5f, 9.5f); lineTo(11f, 18f); lineTo(6f, 13f); close() }
        line { moveTo(6f, 13f); lineTo(4f, 17.5f); lineTo(6.5f, 20f); lineTo(11f, 18f) }
    }

    val Cards = icon("cards") {
        line { roundRect(4f, 8.5f, 12.5f, 11f, 1.8f) }
        line { moveTo(7.5f, 5f); lineTo(18.2f, 5f); arcTo(1.8f, 1.8f, 0f, false, true, 20f, 6.8f); lineTo(20f, 15.5f) }
    }

    val Download = icon("download") {
        line { moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f); moveTo(5f, 20f); lineTo(19f, 20f) }
    }

    val Trash = icon("trash") {
        line { moveTo(4.5f, 7f); lineTo(19.5f, 7f); moveTo(10f, 4f); lineTo(14f, 4f) }
        line { moveTo(6.5f, 7f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 7f) }
    }

    val Retry = icon("retry") {
        line { moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 16.95f, 7.05f) }
        line { moveTo(17.5f, 3.5f); lineTo(17.5f, 7.5f); lineTo(13.5f, 7.5f) }
    }

    val Info = icon("info") {
        line { circle(12f, 12f, 8.5f) }
        line(2f) { moveTo(12f, 11f); lineTo(12f, 16.5f) }
        solid(0f) { circle(12f, 7.8f, 1.2f) }
    }

    val Settings = Tune
}

/** Draws one [KpIcons] glyph. Pass [contentDescription] only when the icon stands alone without a label. */
@Composable
fun KpIconView(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = KpSize.icon,
    contentDescription: String? = null,
) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}

/**
 * Skip back / forward glyph: an arrow circle with the seconds numeral centred inside. The numeral follows the
 * glyph size, not the font scale, so it always fits the ring.
 */
@Composable
fun KpSkipIcon(
    seconds: Int,
    forward: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    contentDescription: String? = null,
) {
    val density = LocalDensity.current
    val fontSize = with(density) { (size.toPx() * 0.36f / (density.density * density.fontScale)).sp }
    val semantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else Modifier.clearAndSetSemantics {}
    Box(modifier.size(size).then(semantics), contentAlignment = Alignment.Center) {
        Icon(if (forward) KpIcons.ForwardRing else KpIcons.ReplayRing, null, Modifier.size(size), tint = tint)
        BasicText(
            seconds.toString(),
            Modifier.offset(y = size * 0.04f),
            style = TextStyle(
                color = tint, fontFamily = KpFonts.ui, fontWeight = FontWeight.Bold, fontSize = fontSize,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

enum class KpIconKind { Search, Add, Shelf, List, Back, Close, Check, Chevron }

/** v1 entry point, kept for existing callers: maps onto [KpIcons]. */
@Composable
fun KpIcon(kind: KpIconKind, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val icon = when (kind) {
        KpIconKind.Search -> KpIcons.Search
        KpIconKind.Add -> KpIcons.Add
        KpIconKind.Shelf -> KpIcons.Bookcase
        KpIconKind.List -> KpIcons.Chapters
        KpIconKind.Back -> KpIcons.Back
        KpIconKind.Close -> KpIcons.Close
        KpIconKind.Check -> KpIcons.Check
        KpIconKind.Chevron -> KpIcons.ChevronRight
    }
    KpIconView(icon, tint, modifier, size)
}
