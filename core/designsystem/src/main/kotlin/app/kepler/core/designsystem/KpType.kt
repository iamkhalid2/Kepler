package app.kepler.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Type scale from the design brief section 2. Colours are applied by the caller. */
object KpType {
    val display = TextStyle(
        fontFamily = KpFonts.displayFor(34f), fontWeight = FontWeight(360),
        fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em,
    )
    val headline = TextStyle(
        fontFamily = KpFonts.displayFor(26f), fontWeight = FontWeight(360), fontSize = 26.sp, lineHeight = 30.sp,
    )
    val title = TextStyle(fontFamily = KpFonts.title, fontWeight = FontWeight(450), fontSize = 19.sp, lineHeight = 24.sp)
    val author = TextStyle(fontFamily = KpFonts.readingItalic, fontStyle = FontStyle.Italic, fontSize = 14.sp, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val rowTitle = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val caption = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val caps = TextStyle(
        fontFamily = KpFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp,
        letterSpacing = 0.14.em,
    )
    val keyLabel = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val keyLabelSmall = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    val numeral = TextStyle(
        fontFamily = KpFonts.numeral, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    )
    val bigNumeral = TextStyle(
        fontFamily = KpFonts.displayFor(40f), fontWeight = FontWeight(360), fontSize = 40.sp, lineHeight = 44.sp,
        fontFeatureSettings = "tnum",
    )
    /** Book-derived prose (AI answers, recaps, slips). */
    val prose = TextStyle(fontFamily = KpFonts.reading, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)
}

internal val SingleLine = TextOverflow.Ellipsis
