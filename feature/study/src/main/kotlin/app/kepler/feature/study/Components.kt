package app.kepler.feature.study

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpPrimaryKey
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.designsystem.KpFonts
import app.kepler.core.designsystem.KpPalette

// Shelf design language pieces for the study screens (docs/design/DESIGN-BRIEF.md §1, §2, §4).
// Keys, links and the segmented control delegate to :core:designsystem (bevels, haptics).

internal object StudyMotion {
    fun <T> snap(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) tween(120) else spring(dampingRatio = 1f, stiffness = 1600f)

    fun <T> settle(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) tween(120) else spring(dampingRatio = 0.9f, stiffness = 400f)

    fun <T> flip(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) tween(120) else spring(dampingRatio = 0.86f, stiffness = 380f)
}

/** App setting or a system animator scale of 0. */
@Composable
internal fun rememberReducedMotion(): Boolean = app.kepler.core.designsystem.rememberReducedMotion()

internal object StudyType {
    fun caps(color: Color, size: Float = 11f, tracking: Float = 0.14f) = TextStyle(
        fontFamily = KpFonts.ui, fontWeight = FontWeight.W600, fontSize = size.sp,
        lineHeight = 14.sp, letterSpacing = tracking.em, color = color,
    )

    fun body(color: Color) = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp, color = color)
    fun caption(color: Color) = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp, color = color)
    fun rowTitle(color: Color) = TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 22.sp, color = color)
    fun keyLabel(color: Color, size: Float = 14f) =
        TextStyle(fontFamily = KpFonts.ui, fontWeight = FontWeight.W600, fontSize = size.sp, color = color, textAlign = TextAlign.Center)

    fun title(color: Color, size: Float = 19f) = KpType.title.copy(fontSize = size.sp, lineHeight = (size + 5).sp, color = color)

    fun headline(color: Color) = TextStyle(fontFamily = KpFonts.display, fontWeight = FontWeight(360), fontSize = 26.sp, lineHeight = 30.sp, color = color)
    fun display(color: Color, size: Float = 28f) =
        TextStyle(fontFamily = KpFonts.display, fontWeight = FontWeight(360), fontSize = size.sp, lineHeight = (size + 4).sp, color = color)

    fun numeral(color: Color, size: Float = 13f) = KpType.numeral.copy(fontSize = size.sp, lineHeight = (size + 4).sp, color = color)

    fun bigNumeral(color: Color) =
        TextStyle(fontFamily = KpFonts.display, fontWeight = FontWeight(360), fontSize = 40.sp, lineHeight = 44.sp, fontFeatureSettings = "tnum", color = color)

    /** Book-derived prose: AI answers, recaps, slips. */
    fun prose(color: Color) = TextStyle(fontFamily = KpFonts.reading, fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 26.sp, color = color)
    fun quote(color: Color) = prose(color).copy(fontFamily = KpFonts.readingItalic, fontStyle = FontStyle.Italic)
    fun cardProse(color: Color) = TextStyle(fontFamily = KpFonts.reading, fontWeight = FontWeight.W400, fontSize = 17.sp, lineHeight = 26.sp, color = color)
}

@Composable
internal fun Caps(text: String, modifier: Modifier = Modifier, color: Color = Kp.palette.text2) {
    BasicText(text.uppercase(), modifier, style = StudyType.caps(color))
}

@Composable
internal fun PrimaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    KpPrimaryKey(text, onClick, modifier, enabled = enabled, fullWidth = false)

/** Secondary key; with a [caption] (grade intervals) it grows a numeral line under the label. */
@Composable
internal fun SecondaryKey(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    edge: Color? = null, caption: String? = null, enabled: Boolean = true,
) {
    if (caption == null) {
        KpSecondaryKey(text, onClick, modifier, enabled = enabled, topEdge = edge)
        return
    }
    val p = Kp.palette
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(4.dp)
    val lip = if (p.isDark) 0.07f else 0.9f
    Box(
        modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 72.dp)
            .clip(shape)
            .background(if (pressed) p.surface2 else p.surface1)
            .border(1.dp, p.edge, shape)
            .drawBehind {
                val inset = 4.dp.toPx()
                drawRect(Color.White.copy(alpha = lip), Offset(inset, 1.dp.toPx()), Size(size.width - 2 * inset, 1.dp.toPx()))
                if (edge != null) drawRect(edge, size = Size(size.width, 2.dp.toPx()))
            }
            .clickable(source, null, enabled = enabled, role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(text, style = StudyType.keyLabel(if (enabled) p.text else p.text2), maxLines = 1)
            if (caption != null) BasicText(caption, style = StudyType.numeral(p.text2, 12f), maxLines = 1)
        }
    }
}

@Composable
internal fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) = KpTextLink(text, onClick, modifier)

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Kp.palette.hairline))
}

@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    KpSegmented(options, selected, onSelect, modifier)

/** Input: 52, r4, `surface1`, 1 hairline; focus 1 `accent` with an accent caret; caps label above. */
@Composable
internal fun StudyInput(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: String? = null, placeholder: String? = null, minLines: Int = 1, maxLines: Int = 6,
    error: String? = null, enabled: Boolean = true, style: TextStyle? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = Kp.palette
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)
    Column(modifier) {
        if (label != null) { Caps(label); Spacer(Modifier.height(8.dp)) }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(p.surface1)
                .border(1.dp, if (focused) p.accent else if (error != null) p.negative else p.hairline, shape)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) BasicText(placeholder, style = (style ?: StudyType.body(p.text2)).copy(color = p.text2))
                BasicTextField(
                    value, onChange, Modifier.fillMaxWidth(), enabled = enabled,
                    textStyle = (style ?: StudyType.body(p.text)).copy(color = p.text),
                    cursorBrush = SolidColor(p.accent),
                    minLines = minLines, maxLines = maxLines, interactionSource = source,
                )
            }
            trailing?.invoke()
        }
        if (error != null) { Spacer(Modifier.height(4.dp)); BasicText(error, style = StudyType.caption(p.negative)) }
    }
}

/**
 * Sheet from the brief: `surface1`, top plank lip, 32x3 handle, padding 24, scrim black .5 (light .25),
 * `settle` spring. Fills the host and calls [onDismiss] once it has left.
 */
@Composable
internal fun StudySheet(
    onDismiss: () -> Unit,
    tall: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = Kp.palette
    val reduced = rememberReducedMotion()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(visible.currentState, visible.targetState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onDismiss()
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(StudyMotion.settle(reduced)), exit = fadeOut(StudyMotion.settle(reduced))) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = if (p.isDark) 0.5f else 0.25f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { visible.targetState = false },
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(StudyMotion.settle(reduced)) { it },
            exit = slideOutVertically(StudyMotion.settle(reduced)) { it },
        ) {
            val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (tall) Modifier.fillMaxHeight(0.92f) else Modifier.heightIn(max = 680.dp))
                    .clip(shape)
                    .background(p.surface1)
                    .drawBehind {
                        if (!p.isDark) drawRect(p.hairline, size = Size(size.width, 0.5.dp.toPx()))
                        drawRect(
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = if (p.isDark) 0.10f else 0.6f), Color.Transparent), endY = 6.dp.toPx()),
                            size = Size(size.width, 6.dp.toPx()),
                        )
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.size(32.dp, 3.dp).clip(RoundedCornerShape(1.5.dp)).background(p.hairline))
                }
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
                    content = content,
                )
            }
        }
    }
}

/** A paper slip (`page`, r2, hairline) with the pencil sideline option; the study screens' card of record. */
@Composable
internal fun Slip(modifier: Modifier = Modifier, sideline: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = Kp.palette
    val shape = RoundedCornerShape(2.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(p.page).border(1.dp, p.hairline, shape)
            .drawBehind { if (sideline) drawRect(p.pencil, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = if (sideline) 18.dp else 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        content = content,
    )
}

@Composable
internal fun ListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 56.dp,
    separator: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }) {
        Row(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        if (separator) Hairline()
    }
}

/** Loading is a static `surface2` block, never a shimmer. */
@Composable
internal fun LoadingBlocks(lines: Int = 3, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        repeat(lines) { i ->
            Box(Modifier.fillMaxWidth(if (i == lines - 1) 0.6f else 1f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(p.surface2))
        }
    }
}

@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(vertical = 24.dp), style = StudyType.prose(Kp.palette.text2))
}

/** A typed AI failure: what happened and what still works. [onRetry] only appears when retrying can help. */
@Composable
internal fun ErrorNote(error: UiError, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val haptics = rememberHaptics()
        LaunchedEffect(error) { haptics.play(HapticToken.Reject) }
        BasicText(stringResource(errorText(error.kind)), style = StudyType.body(p.text))
        if (error.retryable && onRetry != null) TextLink(stringResource(R.string.try_again), onRetry, Modifier.padding(start = 0.dp))
    }
}

internal fun errorText(kind: UiErrorKind): Int = when (kind) {
    UiErrorKind.NO_KEY -> R.string.err_no_key
    UiErrorKind.BAD_KEY -> R.string.err_bad_key
    UiErrorKind.BUDGET -> R.string.err_budget
    UiErrorKind.CREDITS -> R.string.err_credits
    UiErrorKind.RATE_LIMITED -> R.string.err_rate
    UiErrorKind.OFFLINE -> R.string.err_offline
    UiErrorKind.UNAVAILABLE -> R.string.err_unavailable
    UiErrorKind.NOTHING_READ -> R.string.err_nothing_read
    UiErrorKind.NOT_REACHED -> R.string.err_not_reached
    UiErrorKind.OTHER -> R.string.err_other
}

/** Renders any [Load] with the shared loading and error states. */
@Composable
internal fun <T> LoadView(
    load: Load<T>,
    onRetry: (() -> Unit)?,
    lines: Int = 3,
    stateModifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (load) {
        Load.Idle, Load.Loading -> LoadingBlocks(lines, stateModifier.padding(vertical = 8.dp))
        is Load.Failed -> ErrorNote(load.error, onRetry, stateModifier)
        is Load.Ready -> content(load.value)
    }
}

@Composable
internal fun intervalText(i: Interval): String = when (i.unit) {
    IntervalUnit.MINUTES -> stringResource(R.string.interval_min, i.value)
    IntervalUnit.HOURS -> stringResource(R.string.interval_hour, i.value)
    IntervalUnit.DAYS -> stringResource(R.string.interval_day, i.value)
    IntervalUnit.MONTHS -> stringResource(R.string.interval_month, i.value)
    IntervalUnit.YEARS -> stringResource(R.string.interval_year, i.value)
}

internal fun Modifier.gutter() = padding(horizontal = 24.dp)
internal val SpacerW8 = Modifier.width(8.dp)
