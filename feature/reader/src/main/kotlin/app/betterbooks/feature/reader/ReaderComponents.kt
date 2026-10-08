package app.betterbooks.feature.reader

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
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.designsystem.BbFonts
import app.betterbooks.core.designsystem.BbPalette

// Shelf design language pieces used by the reader (docs/design/DESIGN-BRIEF.md §1, §2, §4).
// Kept local until :core:designsystem ships shared components; the design lead may lift them.

/** Springs from the brief §4. */
internal object ReaderMotion {
    fun <T> snap(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) tween(120) else spring(dampingRatio = 1f, stiffness = 1600f)

    fun <T> settle(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) tween(120) else spring(dampingRatio = 0.9f, stiffness = 400f)
}

@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Interface key colours. The palette has no `key` token yet: the key is the strongest neutral. */
internal val BbPalette.key: Color get() = text
internal val BbPalette.onKey: Color get() = bg

internal object ReaderType {
    private val tnum = "tnum"

    fun caps(color: Color, size: Float = 11f, tracking: Float = 0.14f) = TextStyle(
        fontFamily = BbFonts.ui, fontWeight = FontWeight.W600, fontSize = size.sp,
        lineHeight = 14.sp, letterSpacing = tracking.em, color = color,
    )

    fun body(color: Color) = TextStyle(
        fontFamily = BbFonts.ui, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp, color = color,
    )

    fun caption(color: Color) = TextStyle(
        fontFamily = BbFonts.ui, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp, color = color,
    )

    fun rowTitle(color: Color) = TextStyle(
        fontFamily = BbFonts.ui, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 22.sp, color = color,
    )

    fun keyLabel(color: Color, size: Float = 14f) = TextStyle(
        fontFamily = BbFonts.ui, fontWeight = FontWeight.W600, fontSize = size.sp, color = color,
        textAlign = TextAlign.Center,
    )

    fun title(color: Color, size: Float = 16f) = TextStyle(
        fontFamily = BbFonts.displaySmall, fontWeight = FontWeight.W400, fontSize = size.sp,
        lineHeight = (size + 4).sp, color = color,
    )

    fun headline(color: Color) = TextStyle(
        fontFamily = BbFonts.display, fontWeight = FontWeight(360), fontSize = 26.sp, lineHeight = 30.sp, color = color,
    )

    fun numeral(color: Color, size: Float = 13f) = TextStyle(
        fontFamily = BbFonts.displaySmall, fontWeight = FontWeight.W400, fontSize = size.sp,
        fontFeatureSettings = tnum, color = color,
    )

    fun prose(color: Color) = TextStyle(
        fontFamily = BbFonts.reading, fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 26.sp, color = color,
    )
}

/** Primary key: `key` fill with the bevel from the brief; pressed inverts the bevel. */
@Composable
internal fun PrimaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 52.dp) {
    val p = Bb.palette
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val drop = if (p.isDark) 0.9f else 0.25f
    Box(
        modifier
            .heightIn(min = height)
            .widthIn(min = 120.dp)
            .drawBehind {
                val r = CornerRadius(4.dp.toPx())
                val px = 1.dp.toPx()
                if (!pressed) drawRoundRect(Color.Black.copy(alpha = drop), Offset(0f, 2.dp.toPx()), size, r)
                drawRoundRect(p.key, cornerRadius = r)
                val top = if (pressed) Color.Black.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.7f)
                val bottom = if (pressed) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.2f)
                drawRect(top, Offset(r.x, 0f), Size(size.width - 2 * r.x, px))
                drawRect(bottom, Offset(r.x, size.height - px), Size(size.width - 2 * r.x, px))
            }
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = ReaderType.keyLabel(p.onKey, 15f), maxLines = 1)
    }
}

@Composable
internal fun SecondaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Bb.palette
    val shape = RoundedCornerShape(4.dp)
    val lip = if (p.isDark) 0.07f else 0.9f
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 84.dp)
            .clip(shape)
            .background(p.surface1)
            .border(1.dp, p.hairline, shape)
            .drawBehind { drawRect(Color.White.copy(alpha = lip), size = Size(size.width, 1.dp.toPx())) }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = ReaderType.keyLabel(p.text), maxLines = 1)
    }
}

@Composable
internal fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = ReaderType.keyLabel(Bb.palette.accentText))
    }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    val c = Bb.palette.hairline
    Box(modifier.fillMaxWidth().height(1.dp).background(c))
}

/** Segmented control: `surface1` track with a hairline; the active segment is a small primary key. */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = Bb.palette
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(p.surface1)
            .border(1.dp, p.hairline, shape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .weight(if (active) 1.5f else 1f)
                    .fillMaxHeight()
                    .then(
                        if (active) {
                            Modifier.drawBehind {
                                val r = CornerRadius(3.dp.toPx())
                                drawRoundRect(p.key, cornerRadius = r)
                                drawRect(Color.White.copy(alpha = 0.7f), Offset(r.x, 0f), Size(size.width - 2 * r.x, 1.dp.toPx()))
                            }
                        } else {
                            Modifier
                        },
                    )
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label, style = ReaderType.keyLabel(if (active) p.onKey else p.text2, 13f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A 48 dp square target holding a small line glyph drawn in [color]. */
@Composable
internal fun GlyphKey(description: String, onClick: () -> Unit, glyph: Glyph, color: Color) {
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
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
    val p = Bb.palette
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
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)),
                            size = Size(size.width, 1.5.dp.toPx()),
                        )
                    }
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
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight).padding(start = indent, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (separator) Hairline(Modifier.padding(start = indent))
    }
}
