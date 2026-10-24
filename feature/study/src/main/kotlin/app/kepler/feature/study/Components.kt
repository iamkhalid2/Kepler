package app.kepler.feature.study

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpButton
import app.kepler.core.designsystem.KpButtonStyle
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpErrorCard
import app.kepler.core.designsystem.KpFonts
import app.kepler.core.designsystem.KpInput
import app.kepler.core.designsystem.KpRadius
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpSheet
import app.kepler.core.designsystem.KpSkeleton
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberHaptics

// Thin v2 wrappers for the study screens (docs/design/DESIGN-BRIEF.md v2 sections 1-3 and 9).
// Every control delegates to :core:designsystem; nothing here draws its own chrome.

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
    fun caps(color: Color) = KpType.label.copy(color = color)
    fun body(color: Color) = KpType.body.copy(color = color)
    fun subhead(color: Color) = KpType.subhead.copy(color = color)
    fun caption(color: Color) = KpType.caption.copy(color = color)
    fun rowTitle(color: Color) = KpType.rowTitle.copy(color = color)
    fun title(color: Color) = KpType.title2.copy(color = color)
    fun title1(color: Color) = KpType.title1.copy(color = color)
    fun headline(color: Color) = KpType.displayTitle.copy(color = color)
    fun display(color: Color, size: Float = 26f) = KpType.displayTitle.copy(color = color, fontSize = size.sp, lineHeight = (size + 4).sp)
    fun numeral(color: Color, size: Float = 13f) = KpType.numeral.copy(fontSize = size.sp, lineHeight = (size + 4).sp, color = color)
    fun bigNumeral(color: Color) = KpType.bigStat.copy(color = color)
    fun stat(color: Color) = KpType.stat.copy(color = color)
    fun keyLabel(color: Color, size: Float = 14f) = KpType.button.copy(fontSize = size.sp, color = color, textAlign = TextAlign.Center)

    /** Book-derived prose: AI answers, recaps. */
    fun prose(color: Color) = KpType.prose.copy(color = color)
    fun quote(color: Color) = prose(color).copy(fontFamily = KpFonts.readingItalic, fontStyle = FontStyle.Italic)

    /** 15/24 prose for highlight cards. */
    fun highlight(color: Color) = KpType.prose.copy(color = color, fontSize = 15.sp, lineHeight = 24.sp)
}

@Composable
internal fun Caps(text: String, modifier: Modifier = Modifier, color: Color = Kp.palette.text2) = KpCaps(text, modifier, color)

@Composable
internal fun PrimaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false) =
    KpButton(text, onClick, modifier, KpButtonStyle.Primary, small = small, enabled = enabled)

@Composable
internal fun SecondaryKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false) =
    KpButton(text, onClick, modifier, KpButtonStyle.Secondary, small = small, enabled = enabled)

@Composable
internal fun GhostKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = true) =
    KpButton(text, onClick, modifier, KpButtonStyle.Ghost, small = small)

@Composable
internal fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) = KpTextLink(text, onClick, modifier)

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Kp.palette.hairline))
}

@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    KpSegmented(options, selected, onSelect, modifier)

/** Input on the design system's field: r16, focus ring, caps label above. */
@Composable
internal fun StudyInput(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: String? = null, placeholder: String? = null, minLines: Int = 1, maxLines: Int = 6,
    error: String? = null, inCard: Boolean = false,
) {
    KpInput(
        value, onChange, modifier, label = label, placeholder = placeholder, error = error,
        singleLine = minLines <= 1 && maxLines <= 1, inCard = inCard,
    )
}

/** Brief sheet (surface3, r28, handle) around a scrolling body; [tall] allows most of the screen. */
@Composable
internal fun StudySheet(
    onDismiss: () -> Unit,
    tall: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val screen = LocalConfiguration.current.screenHeightDp
    val max = (screen * (if (tall) 0.8f else 0.65f)).dp
    KpSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = max).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
internal fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Kp.palette.text, style = KpType.title2)
}

/** A simple tappable row used inside cards and sheets: min 56, hairline below unless [separator] is false. */
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

/** Skeleton lines at final size (no shimmer spinner, no full-screen spinner). */
@Composable
internal fun LoadingBlocks(lines: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(lines) { i ->
            KpSkeleton(Modifier.fillMaxWidth(if (i == lines - 1) 0.6f else 1f).height(14.dp), KpRadius.coverSmall)
        }
    }
}

@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(vertical = 24.dp), color = Kp.palette.text2, style = KpType.body)
}

/** A typed AI failure in plain words: what happened and what still works. [onRetry] only appears when retrying can help. */
@Composable
internal fun ErrorNote(
    error: UiError,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    LaunchedEffect(error) { haptics.play(HapticToken.Reject) }
    KpErrorCard(
        title = stringResource(errorTitle(error.kind)),
        message = stringResource(errorText(error.kind)),
        modifier = modifier,
        onRetry = if (error.retryable) onRetry else null,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

internal fun errorTitle(kind: UiErrorKind): Int = when (kind) {
    UiErrorKind.NO_KEY -> R.string.errt_no_key
    UiErrorKind.BAD_KEY -> R.string.errt_bad_key
    UiErrorKind.BUDGET -> R.string.errt_budget
    UiErrorKind.CREDITS -> R.string.errt_credits
    UiErrorKind.RATE_LIMITED, UiErrorKind.UNAVAILABLE -> R.string.errt_busy
    UiErrorKind.OFFLINE -> R.string.errt_offline
    UiErrorKind.NOTHING_READ -> R.string.errt_nothing_read
    UiErrorKind.NOT_REACHED -> R.string.errt_not_reached
    UiErrorKind.OTHER -> R.string.errt_other
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

/** True when the failure is fixed in Settings (a missing or rejected key). */
internal fun UiError.needsSettings(): Boolean = kind == UiErrorKind.NO_KEY || kind == UiErrorKind.BAD_KEY

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

internal fun Modifier.gutter() = padding(horizontal = 20.dp)
internal val SpacerW8 = Modifier.width(8.dp)

@Composable
internal fun Gap(h: Dp) = Spacer(Modifier.height(h))

internal fun TextStyle.withColor(c: Color) = copy(color = c)
