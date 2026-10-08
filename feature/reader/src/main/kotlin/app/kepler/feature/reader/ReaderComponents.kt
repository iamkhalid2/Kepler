package app.kepler.feature.reader

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpFonts
import app.kepler.core.designsystem.KpPalette
import app.kepler.core.designsystem.KpPrimaryKey
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.rememberHaptics

// Shelf design language pieces used by the reader (docs/design/DESIGN-BRIEF.md §1, §2, §4).
// Keys, links and the segmented control delegate to :core:designsystem so the reader inherits bevels and haptics.

/** Springs from the brief §4. */
internal object ReaderMotion {
    fun <T> snap(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) tween(120) else spring(dampingRatio = 1f, stiffness = 1600f)

    fun <T> settle(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) tween(120) else spring(dampingRatio = 0.9f, stiffness = 400f)
}

/** App setting or a system animator scale of 0. */
@Composable
internal fun rememberReducedMotion(): Boolean = app.kepler.core.designsystem.rememberReducedMotion()

internal object ReaderType {
    fun caps(color: Color, size: Float = 11f, tracking: Float = 0.14f) = TextStyle(
        fontFamily = KpFonts.ui, fontWeight = FontWeight.W600, fontSize = size.sp,
        lineHeight = 14.sp, letterSpacing = tracking.em, color = color,
    )

    fun body(color: Color) = TextStyle(
        fontFamily = KpFonts.ui, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp, color = color,
    )

    fun caption(color: Color) = TextStyle(
        fontFamily = KpFonts.ui, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp, color = color,
    )

    fun rowTitle(color: Color) = TextStyle(
        fontFamily = KpFonts.ui, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 22.sp, color = color,
    )

    fun keyLabel(color: Color, size: Float = 14f) = TextStyle(
        fontFamily = KpFonts.ui, fontWeight = FontWeight.W600, fontSize = size.sp, color = color,
        textAlign = TextAlign.Center,
    )

    fun title(color: Color, size: Float = 16f) = KpType.title.copy(fontSize = size.sp, lineHeight = (size + 4).sp, color = color)

    fun headline(color: Color) = TextStyle(
        fontFamily = KpFonts.display, fontWeight = FontWeight(360), fontSize = 26.sp, lineHeight = 30.sp, color = color,
    )

    fun numeral(color: Color, size: Float = 13f) = KpType.numeral.copy(fontSize = size.sp, lineHeight = (size + 4).sp, color = color)

    fun prose(color: Color) = TextStyle(
        fontFamily = KpFonts.reading, fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 26.sp, color = color,
    )
}

/** Primary key from :core:designsystem (bevel, haptics, disabled state). */
@Composable
internal fun PrimaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    KpPrimaryKey(text, onClick, modifier, enabled = enabled, fullWidth = false)

@Composable
internal fun SecondaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    KpSecondaryKey(text, onClick, modifier)

@Composable
internal fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    KpTextLink(text, onClick, modifier.padding(horizontal = 8.dp))

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    val c = Kp.palette.hairline
    Box(modifier.fillMaxWidth().height(1.dp).background(c))
}

@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    KpSegmented(options, selected, onSelect, modifier)

/** A 48 dp square target holding a small line glyph drawn in [color]. */
@Composable
internal fun GlyphKey(description: String, onClick: () -> Unit, glyph: Glyph, color: Color) {
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (glyph == Glyph.Typography) {
            BasicText("Aa", style = ReaderType.title(color, 17f))
        } else {
            Canvas(Modifier.size(20.dp)) {
                val w = 1.6.dp.toPx()
                val s = size.minDimension
                when (glyph) {
                    Glyph.Back -> {
                        drawLine(color, Offset(s * .62f, s * .18f), Offset(s * .3f, s * .5f), w, StrokeCap.Round)
                        drawLine(color, Offset(s * .3f, s * .5f), Offset(s * .62f, s * .82f), w, StrokeCap.Round)
                    }
                    Glyph.Search -> {
                        drawCircle(color, s * .3f, Offset(s * .42f, s * .42f), style = Stroke(w))
                        drawLine(color, Offset(s * .64f, s * .64f), Offset(s * .88f, s * .88f), w, StrokeCap.Round)
                    }
                    Glyph.Minus -> drawLine(color, Offset(s * .2f, s * .5f), Offset(s * .8f, s * .5f), w, StrokeCap.Round)
                    Glyph.Plus -> {
                        drawLine(color, Offset(s * .2f, s * .5f), Offset(s * .8f, s * .5f), w, StrokeCap.Round)
                        drawLine(color, Offset(s * .5f, s * .2f), Offset(s * .5f, s * .8f), w, StrokeCap.Round)
                    }
                    Glyph.Typography -> Unit
                }
            }
        }
    }
}

internal enum class Glyph { Back, Search, Typography, Minus, Plus }

/**
 * Sheet from the brief: `surface1`, top plank lip, 32×3 handle at 12, padding 24, scrim black .5
 * (light .25). Rises with the `settle` spring.
 */
@Composable
internal fun ReaderSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    reduced: Boolean,
    tall: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = Kp.palette
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(ReaderMotion.settle(reduced)), exit = fadeOut(ReaderMotion.settle(reduced))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (p.isDark) 0.5f else 0.25f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(ReaderMotion.settle(reduced)) { it },
            exit = slideOutVertically(ReaderMotion.settle(reduced)) { it },
        ) {
            val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (tall) Modifier.fillMaxHeight(0.92f) else Modifier.heightIn(max = 640.dp))
                    .clip(shape)
                    .background(p.surface1)
                    .drawBehind { sheetLip(p.isDark, p.hairline) }
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding(),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.size(32.dp, 3.dp).clip(RoundedCornerShape(1.5.dp)).background(p.hairline))
                }
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp), content = content)
            }
        }
    }
}

/** A tappable list row of at least [minHeight] with a separator starting at the text. */
@Composable
internal fun ReaderRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    indent: Dp = 0.dp,
    separator: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight).padding(start = indent, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (separator) Hairline(Modifier.padding(start = indent))
    }
}

/** Plank lip on a sheet's top edge; in light mode it sits over a 0.5 dp seam so it still reads. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.sheetLip(dark: Boolean, seam: Color) {
    if (!dark) drawRect(seam, size = Size(size.width, 0.5.dp.toPx()))
    drawRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.6f), Color.Transparent), endY = 6.dp.toPx()),
        topLeft = Offset(0f, if (dark) 0f else 0.5.dp.toPx()),
        size = Size(size.width, 6.dp.toPx()),
    )
}
