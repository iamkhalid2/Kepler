package app.betterbooks.core.designsystem

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Bundled OFL variable fonts: Fraunces (display), Literata (reading), Albert Sans (interface). */
@OptIn(ExperimentalTextApi::class)
object BbFonts {
    private fun fraunces(w: Int, opsz: Float, soft: Float = 100f, italic: Boolean = false) = Font(
        R.font.fraunces, FontWeight(w),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(w), FontVariation.Setting("SOFT", soft), FontVariation.Setting("opsz", opsz),
        ),
    )

    private fun literata(w: Int, opsz: Float = 14f) = Font(
        R.font.literata, FontWeight(w),
        variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", opsz)),
    )

    private fun albert(w: Int) = Font(
        R.font.albert_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)),
    )

    /** Small sizes (book covers, spines): low optical size keeps the serifs sturdy. */
    val displaySmall = FontFamily(fraunces(400, 14f), fraunces(600, 14f))

    /** Headlines. */
    val display = FontFamily(fraunces(360, 72f), fraunces(420, 72f), fraunces(600, 72f))

    val reading = FontFamily(literata(400), literata(500), literata(600))

    val ui = FontFamily(albert(400), albert(500), albert(600), albert(700))
}
