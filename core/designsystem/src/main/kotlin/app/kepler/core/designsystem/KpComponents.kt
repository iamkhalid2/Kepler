package app.kepler.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ================================================================ text

/** Overline label (brief v2 `label` role), uppercased. */
@Composable
fun KpCaps(text: String, modifier: Modifier = Modifier, color: Color = Kp.palette.text2, maxLines: Int = 1) {
    Text(text.uppercase(), modifier, color = color, style = KpType.label, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A tabular numeral run. */
@Composable
fun KpNumeral(text: String, modifier: Modifier = Modifier, color: Color = Kp.palette.text) {
    Text(text, modifier, color = color, style = KpType.numeral)
}

// ================================================================ buttons

enum class KpButtonStyle { Primary, Secondary, Ghost }

/**
 * Pill button (brief v2 section 3). Primary: `key` fill; Secondary: surface2 (pass [onBg] = true for surface1
 * when it sits straight on the screen background); Ghost: accent text only. [small] = 40 high.
 */
@Composable
fun KpButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KpButtonStyle = KpButtonStyle.Primary,
    small: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fullWidth: Boolean = false,
    onBg: Boolean = false,
    haptic: HapticToken = if (style == KpButtonStyle.Primary) HapticToken.Confirm else HapticToken.Tap,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val (fill, content) = when (style) {
        KpButtonStyle.Primary -> p.key to p.onKey
        KpButtonStyle.Secondary -> (if (onBg) p.surface1 else p.surface2) to p.text
        KpButtonStyle.Ghost -> Color.Transparent to p.accentText
    }
    val shownFill = when {
        !enabled && style != KpButtonStyle.Ghost -> p.surface2
        pressed && style == KpButtonStyle.Primary -> mix(fill, p.bg, 0.12f)
        pressed && style == KpButtonStyle.Secondary -> mix(fill, p.text, 0.06f)
        pressed -> p.surface1
        else -> fill
    }
    val shownContent = if (enabled) content else p.text2
    val width = if (fullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = if (style == KpButtonStyle.Ghost) 0.dp else 96.dp)
    Row(
        modifier.then(width)
            .heightIn(min = if (small) KpSize.buttonHeightSmall else KpSize.buttonHeight)
            .clip(KpRadius.pill)
            .background(shownFill)
            .clickable(src, null, enabled = enabled, role = Role.Button) { haptics.play(haptic); onClick() }
            .padding(horizontal = if (small) 16.dp else 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            KpIconView(icon, shownContent, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = shownContent, style = KpType.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** v1 name: now the Primary [KpButton], full width by default. */
@Composable
fun KpPrimaryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) = KpButton(label, onClick, modifier, KpButtonStyle.Primary, enabled = enabled, fullWidth = fullWidth)

/** v1 name: now the Secondary [KpButton]. [topEdge] becomes a status dot before the label (grade keys). */
@Composable
fun KpSecondaryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    topEdge: Color? = null,
    labelColor: Color = Kp.palette.text,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier.widthIn(min = 84.dp).heightIn(min = 48.dp)
            .clip(KpRadius.pill)
            .background(if (pressed) mix(p.surface2, p.text, 0.06f) else p.surface2)
            .clickable(src, null, enabled = enabled, role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (topEdge != null) {
            Box(Modifier.size(6.dp).background(topEdge, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = if (enabled) labelColor else p.text2, style = KpType.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun KpTextLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Kp.palette.accentText) {
    val haptics = rememberHaptics()
    Box(
        modifier.heightIn(min = 48.dp)
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = color, style = KpType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

enum class KpIconButtonStyle { Plain, Tonal, Filled }

/**
 * 48 dp icon button. Plain: glyph only; Tonal: 40 dp surface1 circle (top-bar actions, back); Filled: `key`
 * circle of [filledSize] (play/pause, send).
 */
@Composable
fun KpIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: KpIconButtonStyle = KpIconButtonStyle.Plain,
    tint: Color? = null,
    enabled: Boolean = true,
    filledSize: Dp = 44.dp,
    iconSize: Dp = KpSize.icon,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val outer = if (style == KpIconButtonStyle.Filled) maxOf(filledSize, KpSize.touch) else KpSize.touch
    Box(
        modifier.size(outer)
            .clickable(src, null, enabled = enabled, role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = tint ?: if (style == KpIconButtonStyle.Filled) p.onKey else p.text
        when (style) {
            KpIconButtonStyle.Plain -> Box(
                Modifier.size(40.dp).background(if (pressed) p.surface2 else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { KpIconView(icon, if (enabled) glyph else p.text2, size = iconSize) }
            KpIconButtonStyle.Tonal -> Box(
                Modifier.size(KpSize.iconButtonTonal).background(if (pressed) p.surface2 else p.surface1, CircleShape),
                contentAlignment = Alignment.Center,
            ) { KpIconView(icon, if (enabled) glyph else p.text2, size = 22.dp) }
            KpIconButtonStyle.Filled -> Box(
                Modifier.size(filledSize).background(if (pressed) mix(p.key, p.bg, 0.12f) else p.key, CircleShape),
                contentAlignment = Alignment.Center,
            ) { KpIconView(icon, glyph, size = iconSize) }
        }
    }
}

/** v1 name: a plain [KpIconButton] for a [KpIconKind]. */
@Composable
fun KpIconKey(kind: KpIconKind, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Kp.palette.text) {
    val haptics = rememberHaptics()
    Box(
        modifier.size(48.dp)
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { KpIcon(kind, tint) }
}

// ================================================================ surfaces

/** Card (brief v2 section 3): surface1, r20; light mode adds a hairline. Clickable cards tone to surface2 on press. */
@Composable
fun KpCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    shape: Shape = KpRadius.card,
    color: Color = Kp.palette.surface1,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val click = if (onClick != null) Modifier.combinedClickable(
        interactionSource = src, indication = null, role = Role.Button,
        onLongClick = onLongClick?.let { lc -> { haptics.play(HapticToken.Confirm); lc() } },
        onClick = { haptics.play(HapticToken.Tap); onClick() },
    ) else Modifier
    Column(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(if (pressed) mix(color, p.text, 0.05f) else color)
            .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, shape) else Modifier)
            .then(click)
            .padding(padding),
        content = content,
    )
}

/** Stat tile: value over a caption, surface1 r14, 72 high. */
@Composable
fun KpStatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Column(
        modifier.heightIn(min = 72.dp).clip(KpRadius.tile).background(p.surface1)
            .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, KpRadius.tile) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, color = p.text, style = KpType.stat, maxLines = 1)
        Text(label, color = p.text2, style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun KpHairline(modifier: Modifier = Modifier, inset: Dp = 20.dp) {
    Box(modifier.fillMaxWidth().padding(horizontal = inset).height(1.dp).background(Kp.palette.hairline))
}

@Composable
fun KpAccentRule(modifier: Modifier = Modifier, width: Dp = 24.dp) {
    Box(modifier.width(width).height(2.dp).background(Kp.palette.accent, CircleShape))
}

@Composable
fun KpDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).background(color, CircleShape))
}

/** Generation ring: a track circle with an accent arc. */
@Composable
fun KpRing(progress: Float, modifier: Modifier = Modifier, size: Dp = 24.dp, stroke: Dp = 2.5.dp) {
    val p = Kp.palette
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), KpMotion.settle(), label = "ring")
    Canvas(modifier.size(size)) {
        val s = stroke.toPx()
        val inset = s / 2f
        val sz = Size(this.size.width - s, this.size.height - s)
        drawArc(p.surface2, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(s))
        drawArc(p.accent, -90f, 360f * shown, false, Offset(inset, inset), sz, style = Stroke(s, cap = StrokeCap.Round))
    }
}

// ================================================================ rows and headers

/** List row: 56 for one line, 72 for two; 40 dp leading slot; gutter 20 (pass [inset] 16 inside a card). */
@Composable
fun KpRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Kp.palette.text,
    onLongClick: (() -> Unit)? = null,
    inset: Dp = 20.dp,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val base = if (onClick != null) modifier.combinedClickable(
        role = Role.Button,
        onLongClick = onLongClick?.let { lc -> { haptics.play(HapticToken.Confirm); lc() } },
        onClick = { haptics.play(HapticToken.Tap); onClick() },
    ) else modifier
    Row(
        base.fillMaxWidth().heightIn(min = if (meta == null) KpSize.rowOneLine else KpSize.rowTwoLine).padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.widthIn(min = 40.dp), contentAlignment = Alignment.CenterStart) { leading() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = titleColor, style = KpType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) Text(meta, color = p.text2, style = KpType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

/** Separator that starts at the row text (after the leading slot when there is one). */
@Composable
fun KpRowSeparator(hasLeading: Boolean = false, inset: Dp = 20.dp) {
    Box(
        Modifier.fillMaxWidth().padding(start = if (hasLeading) inset + 52.dp else inset, end = inset)
            .height(1.dp).background(Kp.palette.hairline),
    )
}

/** Trailing chevron for navigational rows. */
@Composable
fun KpChevron(modifier: Modifier = Modifier) = KpIconView(KpIcons.ChevronRight, Kp.palette.text2, modifier, size = 20.dp)

/** Section header: caps label left, optional link right, 40 high. */
@Composable
fun KpSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        KpCaps(title, Modifier.weight(1f))
        if (actionLabel != null && onAction != null) KpTextLink(actionLabel, onAction)
    }
}

// ================================================================ top bars

/** Tab-root header: optional caption line over the large title, up to two actions on the right. */
@Composable
fun KpLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = Kp.palette
    Row(
        modifier.fillMaxWidth().padding(start = KpSpace.gutter, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (caption != null) Text(caption, color = p.text2, style = KpType.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, color = p.text, style = KpType.largeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Detail top bar: 56 high, tonal back, optional centred title + caption, actions on the right. */
@Composable
fun KpTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    caption: String? = null,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = KpIcons.Back,
    center: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = Kp.palette
    Box(modifier.fillMaxWidth().height(KpSize.topBar).padding(horizontal = 8.dp)) {
        if (onBack != null) {
            KpIconButton(backIcon, stringResource(R.string.kp_back), onBack, Modifier.align(Alignment.CenterStart), KpIconButtonStyle.Tonal)
        }
        Box(Modifier.align(Alignment.Center).padding(horizontal = 56.dp), contentAlignment = Alignment.Center) {
            if (center != null) center()
            else if (title != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = p.text, style = KpType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (caption != null) Text(caption, color = p.text2, style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

// ================================================================ controls

/** Switch: 52x32 pill track (accent when on), 24 dp `key` thumb. */
@Composable
fun KpSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val x by animateDpAsState(if (checked) 24.dp else 4.dp, KpMotion.snap(), label = "thumb")
    val on = stringResource(R.string.kp_on)
    val off = stringResource(R.string.kp_off)
    Box(
        modifier.size(56.dp, 48.dp)
            .clickable(role = Role.Switch) { haptics.play(HapticToken.Tap); onCheckedChange(!checked) }
            .semantics { if (label != null) contentDescription = label; stateDescription = if (checked) on else off },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(52.dp, 32.dp).background(if (checked) p.accent else p.surface2, CircleShape)) {
            Box(Modifier.offset(x, 4.dp).size(24.dp).background(if (checked) p.onAccent else p.text2, CircleShape))
        }
    }
}

/** Segmented control: surface1 track, a sliding thumb (surface3 dark, white light) on the snap spring. */
@Composable
fun KpSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    BoxWithConstraints(
        modifier.fillMaxWidth().height(KpSize.segmented).clip(KpRadius.segmentTrack).background(p.surface1)
            .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, KpRadius.segmentTrack) else Modifier)
            .padding(3.dp),
    ) {
        val n = options.size.coerceAtLeast(1)
        val segW = maxWidth / n
        val x by animateDpAsState(segW * selected.coerceIn(0, n - 1), KpMotion.snap(), label = "segment")
        Box(
            Modifier.offset(x = x).width(segW).fillMaxHeight().clip(KpRadius.segmentThumb)
                .background(if (p.isDark) p.surface3 else Color.White)
                .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, KpRadius.segmentThumb) else Modifier),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) {
                            if (!on) { haptics.play(HapticToken.Tap); onSelect(i) }
                        }
                        .semantics { this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (on) p.text else p.text2, style = KpType.button.copy(fontSize = KpType.subhead.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Chip: h32 pill; selected = `key` fill. Only for Library filters, Ask citations and the Hub genre tag. */
@Composable
fun KpChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val fill = if (selected) p.key else p.surface1
    val content = if (selected) p.onKey else p.text
    Row(
        modifier.heightIn(min = KpSize.chip).clip(KpRadius.pill).background(fill)
            .then(if (!p.isDark && !selected) Modifier.border(1.dp, p.hairline, KpRadius.pill) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() } else Modifier)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { KpIconView(icon, content, size = 16.dp); Spacer(Modifier.width(6.dp)) }
        Text(label, color = content, style = KpType.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Slider: 4 dp track, accent fill, 18 dp `key` thumb (24 while dragging). [steps] > 0 snaps to that many
 * intermediate values and ticks at each.
 */
@Composable
fun KpSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    var dragging by remember { mutableStateOf(false) }
    val thumb by animateDpAsState(if (dragging) 24.dp else 18.dp, KpMotion.snap(), label = "thumb")
    val latest by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var lastStep by remember { mutableIntStateOf(-1) }
    fun snap(f: Float): Float = if (steps > 0) ((f * (steps + 1)).roundToInt() / (steps + 1f)) else f
    Box(
        modifier.fillMaxWidth().height(48.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { v -> latest(v.coerceIn(valueRange)); true }
            }
            .pointerInput(valueRange, steps) {
                val pad = 12.dp.toPx()
                fun at(x: Float): Float {
                    val f = snap(((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f))
                    if (steps > 0) {
                        val idx = (f * (steps + 1)).roundToInt()
                        if (idx != lastStep) { if (lastStep >= 0) haptics.play(HapticToken.Tick); lastStep = idx }
                    }
                    return valueRange.start + f * span
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    latest(at(down.position.x))
                    drag(down.id) { change -> latest(at(change.position.x)); change.consume() }
                    dragging = false
                    lastStep = -1
                    finished?.invoke()
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp)) {
            val cy = size.height / 2f
            val h = 4.dp.toPx()
            val r = CornerRadius(h / 2f)
            drawRoundRect(p.surface2, Offset(0f, cy - h / 2f), Size(size.width, h), r)
            drawRoundRect(p.accent, Offset(0f, cy - h / 2f), Size(size.width * fraction, h), r)
            drawCircle(p.key, thumb.toPx() / 2f, Offset(size.width * fraction, cy))
        }
    }
}

/**
 * Chapter scrubber (brief v2 section 3, decision #109). The fraction is within the current chapter. No thumb
 * at rest; pressing shows a 14 dp thumb and previews the position, release calls [onSeek]. Labels below:
 * [elapsed] left, [middle] centre (time left in the book), [remaining] right.
 */
@Composable
fun KpScrubber(
    fraction: Float,
    onSeek: (Float) -> Unit,
    elapsed: String,
    remaining: String,
    modifier: Modifier = Modifier,
    middle: String? = null,
    contentDescription: String? = null,
    onPreview: ((Float?) -> Unit)? = null,
) {
    val p = Kp.palette
    var preview by remember { mutableStateOf<Float?>(null) }
    val track by animateDpAsState(if (preview != null) 6.dp else 4.dp, KpMotion.snap(), label = "track")
    val shown = preview ?: fraction.coerceIn(0f, 1f)
    val seek by rememberUpdatedState(onSeek)
    val previewCb by rememberUpdatedState(onPreview)
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(32.dp)
                .semantics {
                    if (contentDescription != null) this.contentDescription = contentDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                    setProgress { v -> seek(v.coerceIn(0f, 1f)); true }
                }
                .pointerInput(Unit) {
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        preview = at(down.position.x).also { previewCb?.invoke(it) }
                        drag(down.id) { change -> preview = at(change.position.x).also { previewCb?.invoke(it) }; change.consume() }
                        preview?.let { seek(it) }
                        preview = null
                        previewCb?.invoke(null)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                val cy = size.height / 2f
                val h = track.toPx()
                val r = CornerRadius(h / 2f)
                drawRoundRect(p.surface2, Offset(0f, cy - h / 2f), Size(size.width, h), r)
                drawRoundRect(p.text, Offset(0f, cy - h / 2f), Size((size.width * shown).coerceAtLeast(h), h), r)
                if (preview != null) drawCircle(p.key, 7.dp.toPx(), Offset(size.width * shown, cy))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(elapsed, color = p.text2, style = KpType.time, modifier = Modifier.weight(1f))
            if (middle != null) Text(middle, color = p.text2, style = KpType.time, textAlign = TextAlign.Center)
            Text(remaining, color = p.text2, style = KpType.time, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

// ================================================================ inputs

/** Text input: 52 high, r16, surface1 (pass [inCard] for surface2); focus draws a 1.5 dp accent border. */
@Composable
fun KpInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
    inCard: Boolean = false,
) {
    val p = Kp.palette
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    val border = when {
        error != null -> BorderStroke(1.5.dp, p.negative)
        focused -> BorderStroke(1.5.dp, p.accent)
        !p.isDark -> BorderStroke(1.dp, p.hairline)
        else -> null
    }
    Column(modifier) {
        if (label != null) { KpCaps(label); Spacer(Modifier.height(8.dp)) }
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (singleLine) KpSize.input else 112.dp)
                .clip(KpRadius.input)
                .background(if (inCard) p.surface2 else p.surface1)
                .then(if (border != null) Modifier.border(border, KpRadius.input) else Modifier)
                .padding(start = 16.dp, end = 4.dp, top = if (singleLine) 0.dp else 14.dp, bottom = if (singleLine) 0.dp else 14.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(Modifier.weight(1f).padding(end = 12.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value, onValueChange = onValueChange, singleLine = singleLine,
                    interactionSource = src,
                    textStyle = KpType.body.copy(color = p.text),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = keyboardOptions,
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.isEmpty() && placeholder != null) Text(placeholder, color = p.text2, style = KpType.body)
                        inner()
                    },
                )
            }
            if (secret) KpTextLink(stringResource(if (reveal) R.string.kp_hide else R.string.kp_show), { reveal = !reveal })
            trailing?.invoke()
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, color = p.negative, style = KpType.caption)
        }
    }
}

/** Chat input: pill, 52 high, trailing 40 dp `key` send button. */
@Composable
fun KpChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    val p = Kp.palette
    val canSend = enabled && value.isNotBlank()
    Row(
        modifier.fillMaxWidth().heightIn(min = KpSize.input).clip(RoundedCornerShape(26.dp)).background(p.surface1)
            .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, RoundedCornerShape(26.dp)) else Modifier)
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, maxLines = 5,
            textStyle = KpType.body.copy(color = p.text), cursorBrush = SolidColor(p.accent),
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) Text(placeholder, color = p.text2, style = KpType.body)
                inner()
            },
        )
        Box(Modifier.alpha(if (canSend) 1f else 0.4f)) {
            KpIconButton(KpIcons.Send, stringResource(R.string.kp_send), { if (canSend) onSend() }, style = KpIconButtonStyle.Filled, filledSize = 40.dp, iconSize = 20.dp, enabled = canSend)
        }
    }
}

// ================================================================ states

/** Skeleton block for loading states: surface1, alpha pulsing 0.5-1.0 over 1.2 s. */
@Composable
fun KpSkeleton(modifier: Modifier = Modifier, shape: Shape = KpRadius.tile) {
    val p = Kp.palette
    val reduced = rememberReducedMotion()
    val a = if (reduced) 0.75f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        t.animateFloat(0.5f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse").value
    }
    Box(modifier.alpha(a).clip(shape).background(p.surface1))
}

/** Empty state: optional art, Fraunces headline, short body, one primary action. */
@Composable
fun KpEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    art: (@Composable () -> Unit)? = null,
) {
    val p = Kp.palette
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (art != null) { art(); Spacer(Modifier.height(24.dp)) }
        Text(title, color = p.text, style = KpType.displayTitle.copy(fontSize = KpType.title1.fontSize), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = p.text2, style = KpType.body, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            KpButton(actionLabel, onAction)
        }
    }
}

/** Inline error: headline, one plain sentence, small "Try again". */
@Composable
fun KpErrorCard(title: String, message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val p = Kp.palette
    KpCard(modifier) {
        Text(title, color = p.text, style = KpType.rowTitle)
        Spacer(Modifier.height(4.dp))
        Text(message, color = p.text2, style = KpType.body)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            KpButton(stringResource(R.string.kp_try_again), onRetry, style = KpButtonStyle.Secondary, small = true, icon = KpIcons.Retry)
        }
    }
}

/** Streaming caret: a 2x16 accent bar blinking at 1 Hz while an answer streams. */
@Composable
fun KpStreamingCaret(modifier: Modifier = Modifier) {
    val p = Kp.palette
    val reduced = rememberReducedMotion()
    val a = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "caret")
        t.animateFloat(1f, 0f, infiniteRepeatable(tween(500, delayMillis = 0), RepeatMode.Reverse), label = "blink").value
    }
    Box(modifier.size(2.dp, 16.dp).alpha(if (a > 0.5f) 1f else 0f).background(p.accent))
}

// ================================================================ toast and sheet

/** Toast: surface3, r14, min 48; shows [message] for 4 s, with an optional action. Place it 16 above the bottom chrome. */
@Composable
fun KpToastHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val p = Kp.palette
    LaunchedEffect(message) {
        if (message != null) { delay(4000); onDismiss() }
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(KpRadius.toast).background(p.surface3)
                    .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, KpRadius.toast) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message.orEmpty(), color = p.text, style = KpType.subhead, modifier = Modifier.weight(1f))
                if (actionLabel != null && onAction != null) KpTextLink(actionLabel, { onAction(); onDismiss() })
            }
        }
    }
}

/** Bottom sheet: surface3, top r28, 36x4 handle, scrim black .5 (light .25), padding 20 / bottom 24. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KpSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = Kp.palette
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = p.surface3,
        contentColor = p.text,
        shape = KpRadius.sheet,
        scrimColor = Color.Black.copy(alpha = if (p.isDark) 0.5f else 0.25f),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(top = 10.dp).size(36.dp, 4.dp).background(p.text2.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
            }
        },
    ) {
        Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), content = content)
    }
}
