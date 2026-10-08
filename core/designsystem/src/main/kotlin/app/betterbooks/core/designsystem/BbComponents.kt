package app.betterbooks.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val KeyShape = RoundedCornerShape(4.dp)

// ---------------------------------------------------------------- text

@Composable
fun BbCaps(text: String, modifier: Modifier = Modifier, color: Color = Bb.palette.text2, maxLines: Int = 1) {
    Text(text.uppercase(), modifier, color = color, style = BbType.caps, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A Fraunces numeral run. */
@Composable
fun BbNumeral(text: String, modifier: Modifier = Modifier, color: Color = Bb.palette.text) {
    Text(text, modifier, color = color, style = BbType.numeral)
}

// ---------------------------------------------------------------- keys

/** Key bevel: inset top/bottom lines and a hard 2 dp drop. Pressed inverts the insets and drops the shadow. */
private fun Modifier.bevel(fill: Color, pressed: Boolean, topInset: Color, bottomInset: Color, drop: Color?, radius: Dp = 4.dp): Modifier =
    drawBehind {
        val r = CornerRadius(radius.toPx())
        if (drop != null && !pressed) drawRoundRect(drop, Offset(0f, 2.dp.toPx()), size, r)
        drawRoundRect(fill, Offset.Zero, size, r)
        val inset = 4.dp.toPx()
        val line = 1.dp.toPx()
        val (a, b) = if (pressed) bottomInset to topInset else topInset to bottomInset
        drawRect(a, Offset(inset, 0f), Size(size.width - 2 * inset, line))
        drawRect(b, Offset(inset, size.height - line), Size(size.width - 2 * inset, line))
    }

/** The one primary key per screen: `key` fill with `onKey` label, height 52. Never an accent fill. */
@Composable
fun BbPrimaryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val size = if (fullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 120.dp)
    Box(
        modifier.then(size).height(52.dp)
            .then(
                if (enabled) Modifier.bevel(
                    p.key, pressed, Color.White.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.2f),
                    Color.Black.copy(alpha = if (p.isDark) 0.9f else 0.25f),
                ) else Modifier.background(p.surface2, KeyShape),
            )
            .clickable(src, null, enabled = enabled, role = Role.Button) {
                haptics.play(HapticToken.Confirm); onClick()
            }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) p.onKey else p.text2, style = BbType.keyLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Secondary key: `surface1` with an `edge` border. [topEdge] adds the 2 dp status edge used by grade keys. */
@Composable
fun BbSecondaryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    topEdge: Color? = null,
    labelColor: Color = Bb.palette.text,
) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        modifier.widthIn(min = 84.dp).height(48.dp)
            .background(if (pressed) p.surface2 else p.surface1, KeyShape)
            .border(1.dp, p.edge, KeyShape)
            .drawBehind {
                val inset = 4.dp.toPx()
                drawRect(Color.White.copy(alpha = if (p.isDark) 0.07f else 0.9f), Offset(inset, 1.dp.toPx()), Size(size.width - 2 * inset, 1.dp.toPx()))
                if (topEdge != null) drawRect(topEdge, Offset(0f, 0f), Size(size.width, 2.dp.toPx()))
            }
            .clickable(src, null, enabled = enabled, role = Role.Button) {
                haptics.play(HapticToken.Tap); onClick()
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) labelColor else p.text2, style = BbType.keyLabelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun BbTextLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Bb.palette.accentText) {
    val haptics = rememberHaptics()
    Box(
        modifier.heightIn(min = 48.dp)
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = color, style = BbType.keyLabelSmall)
    }
}

/** A 48 dp icon key (Search, Shelf/List, Add, Back). */
@Composable
fun BbIconKey(kind: BbIconKind, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Bb.palette.text) {
    val haptics = rememberHaptics()
    Box(
        modifier.size(48.dp)
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BbIcon(kind, tint) }
}

// ---------------------------------------------------------------- structure

@Composable
fun BbHairline(modifier: Modifier = Modifier, inset: Dp = 24.dp) {
    Box(modifier.fillMaxWidth().padding(horizontal = inset).height(1.dp).background(Bb.palette.hairline))
}

/** The one accent rule a screen may carry. */
@Composable
fun BbAccentRule(modifier: Modifier = Modifier, width: Dp = 24.dp) {
    Box(modifier.width(width).height(1.dp).background(Bb.palette.accent))
}

@Composable
fun BbDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).background(color, CircleShape))
}

/** Generation ring: a hairline circle with an accent arc. */
@Composable
fun BbRing(progress: Float, modifier: Modifier = Modifier, size: Dp = 24.dp, stroke: Dp = 2.dp) {
    val p = Bb.palette
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), BbMotion.settle(), label = "ring")
    Canvas(modifier.size(size)) {
        val s = stroke.toPx()
        val inset = s / 2f
        val sz = Size(this.size.width - s, this.size.height - s)
        drawArc(p.hairline, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(s))
        drawArc(p.accent, -90f, 360f * shown, false, Offset(inset, inset), sz, style = Stroke(s, cap = StrokeCap.Butt))
    }
}

/** A list row: 56 for one line, 72 for two; 40 dp leading slot; separator is drawn by the caller with [BbRowSeparator]. */
@Composable
fun BbRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = Bb.palette.text,
    onLongClick: (() -> Unit)? = null,
) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    val base = if (onClick != null) modifier.combinedClickable(
        role = Role.Button,
        onLongClick = onLongClick?.let { lc -> { haptics.play(HapticToken.Confirm); lc() } },
        onClick = { haptics.play(HapticToken.Tap); onClick() },
    ) else modifier
    Row(
        base.fillMaxWidth().heightIn(min = if (meta == null) 56.dp else 72.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) { leading() }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = titleColor, style = BbType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) Text(meta, color = p.text2, style = BbType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

/** Separator that starts at the text (after the 40 dp leading slot). */
@Composable
fun BbRowSeparator(hasLeading: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().padding(start = if (hasLeading) 64.dp else 24.dp, end = 24.dp)
            .height(1.dp).background(Bb.palette.hairline),
    )
}

// ---------------------------------------------------------------- controls

@Composable
fun BbSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, BbMotion.snap(), label = "thumb")
    Box(
        modifier.size(48.dp, 48.dp)
            .clickable(role = Role.Switch) { haptics.play(HapticToken.Tap); onCheckedChange(!checked) }
            .semantics { if (label != null) contentDescription = label; stateDescription = if (checked) "On" else "Off" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp, 24.dp).background(if (checked) p.accent else p.surface2, KeyShape)) {
            Box(
                Modifier.offset(x, 2.dp).size(20.dp)
                    .bevel(p.key, false, Color.White.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.2f), null)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

/** Segmented control: `surface1` track, the active segment is a small primary key. */
@Composable
fun BbSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    Row(
        modifier.fillMaxWidth().height(48.dp).background(p.surface1, KeyShape).border(1.dp, p.hairline, KeyShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(if (on) 1.5f else 1f).fillMaxHeight()
                    .then(
                        if (on) Modifier.bevel(p.key, false, Color.White.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.2f), null, radius = 3.dp) else Modifier,
                    )
                    .clickable(role = Role.Tab) { if (!on) { haptics.play(HapticToken.Tap); onSelect(i) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) p.onKey else p.text2, style = BbType.keyLabelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Text input: 52 high, r4, `surface1`, hairline; focus turns the border accent. Caps label above, error below. */
@Composable
fun BbInput(
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
) {
    val p = Bb.palette
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label != null) { BbCaps(label); Spacer(Modifier.height(8.dp)) }
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(p.surface1, KeyShape)
                .border(BorderStroke(1.dp, if (focused) p.accent else p.hairline), KeyShape)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = value, onValueChange = onValueChange, singleLine = singleLine,
                    interactionSource = src,
                    textStyle = BbType.body.copy(color = p.text),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = keyboardOptions,
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.isEmpty() && placeholder != null) Text(placeholder, color = p.text2, style = BbType.body)
                        inner()
                    },
                )
            }
            if (secret) BbTextLink(if (reveal) "Hide" else "Show", { reveal = !reveal })
            trailing?.invoke()
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, color = p.negative, style = BbType.caption)
        }
    }
}

// ---------------------------------------------------------------- toast and sheet

/** Toast: `surface2`, r4, min 48; shows [message] for 4 s, with an optional link. Place it 16 dp above the bottom chrome. */
@Composable
fun BbToastHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val p = Bb.palette
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
                Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.surface2, KeyShape)
                    .border(1.dp, p.hairline, KeyShape).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message.orEmpty(), color = p.text, style = BbType.keyLabelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), modifier = Modifier.weight(1f))
                if (actionLabel != null && onAction != null) BbTextLink(actionLabel, { onAction(); onDismiss() })
            }
        }
    }
}

/** Bottom sheet: `surface1`, 8 dp top radius, plank lip on top, 32x3 handle, scrim black .5 (light .25). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BbSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = Bb.palette
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = p.surface1,
        contentColor = p.text,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        scrimColor = Color.Black.copy(alpha = if (p.isDark) 0.5f else 0.25f),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(24.dp).drawBehind {
                // Light mode: the lip sits over a 0.5 dp seam, otherwise white on linen vanishes.
                if (!p.isDark) drawRect(p.hairline, size = Size(size.width, 0.5.dp.toPx()))
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = if (p.isDark) 0.10f else 0.6f), Color.Transparent), startY = 0f, endY = 6.dp.toPx()),
                    size = Size(size.width, 6.dp.toPx()),
                )
            }, contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(top = 12.dp).size(32.dp, 3.dp).background(p.hairline, RoundedCornerShape(1.5.dp)))
            }
        },
    ) {
        Column(modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), content = content)
    }
}
