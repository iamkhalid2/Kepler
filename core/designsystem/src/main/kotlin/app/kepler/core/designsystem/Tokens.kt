package app.kepler.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Spacing scale from the design brief v2 section 1. */
object KpSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val x3 = 32.dp
    val x4 = 40.dp
    val x5 = 48.dp
    val x6 = 64.dp

    /** Horizontal screen gutter. */
    val gutter = 20.dp
    /** Gap between stacked cards. */
    val cardGap = 12.dp
    /** Gap between sections. */
    val section = 28.dp
}

object KpRadius {
    val card = RoundedCornerShape(20.dp)
    val tile = RoundedCornerShape(14.dp)
    val input = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val miniPlayer = RoundedCornerShape(18.dp)
    val toast = RoundedCornerShape(14.dp)
    val cover = RoundedCornerShape(6.dp)
    val coverSmall = RoundedCornerShape(4.dp)
    val pill = CircleShape
    val segmentTrack = RoundedCornerShape(14.dp)
    val segmentThumb = RoundedCornerShape(11.dp)
}

object KpSize {
    val touch = 48.dp
    val buttonHeight = 52.dp
    val buttonHeightSmall = 40.dp
    val iconButtonTonal = 40.dp
    val icon = 24.dp
    val topBar = 56.dp
    val tabBar = 64.dp
    val miniPlayer = 64.dp
    val chip = 32.dp
    val segmented = 40.dp
    val input = 52.dp
    val rowOneLine = 56.dp
    val rowTwoLine = 72.dp
}
