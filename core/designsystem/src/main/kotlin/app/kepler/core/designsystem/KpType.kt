package app.kepler.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type roles from the design brief v2 section 2. Colours are applied by the caller.
 * v2 roles come first; the v1 names below them still compile and are being phased out by the screen rebuilds.
 */
object KpType {
    private val ui = KpFonts.ui
    private const val TNUM = "tnum"

    val largeTitle = TextStyle(fontFamily = ui, fontWeight = FontWeight(650), fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.01).em)
    val title1 = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    val title2 = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp)
    /** Hero book titles (Hub, Listen face): one of the few Fraunces moments. */
    val displayTitle = TextStyle(
        fontFamily = KpFonts.displayFor(26f, weight = 400), fontWeight = FontWeight(400), fontSize = 26.sp, lineHeight = 30.sp,
        letterSpacing = (-0.01).em,
    )
    val subhead = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = ui, fontWeight = FontWeight(650), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em)
    val button = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val tabLabel = TextStyle(fontFamily = ui, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp)
    val stat = TextStyle(fontFamily = ui, fontWeight = FontWeight(650), fontSize = 22.sp, lineHeight = 26.sp, fontFeatureSettings = TNUM)
    val bigStat = TextStyle(fontFamily = ui, fontWeight = FontWeight(650), fontSize = 40.sp, lineHeight = 44.sp, fontFeatureSettings = TNUM)
    val time = TextStyle(fontFamily = ui, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM)
    /** Display headline for onboarding and empty states. */
    val displayHeadline = TextStyle(
        fontFamily = KpFonts.displayFor(30f, weight = 400), fontWeight = FontWeight(400), fontSize = 30.sp, lineHeight = 36.sp,
        letterSpacing = (-0.015).em,
    )

    // ---- v1 names ----

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
    val rowTitle = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val caption = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val caps = TextStyle(
        fontFamily = KpFonts.ui, fontWeight = FontWeight(650), fontSize = 11.sp, lineHeight = 14.sp,
        letterSpacing = 0.08.em,
    )
    val keyLabel = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val keyLabelSmall = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    /** v2: numerals are Albert Sans with tabular figures. */
    val numeral = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum")
    val bigNumeral = bigStat
    /** Book-derived prose (AI answers, recaps, slips). */
    val prose = TextStyle(fontFamily = KpFonts.reading, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)
}

internal val SingleLine = TextOverflow.Ellipsis
