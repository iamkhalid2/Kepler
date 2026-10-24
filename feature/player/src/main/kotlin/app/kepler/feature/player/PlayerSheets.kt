package app.kepler.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpIconButton
import app.kepler.core.designsystem.KpIconButtonStyle
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpRadius
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSheet
import app.kepler.core.designsystem.KpSlider
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.model.ChapterMark
import kotlin.math.abs

/** Lets full-width rows (which carry their own 20 dp gutter) sit inside the sheet's padded column. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val wide = constraints.copy(
        minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra),
        maxWidth = constraints.maxWidth + extra,
    )
    val placeable = measurable.measure(wide)
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

@Composable
private fun SheetTitle(title: String, caption: String? = null) {
    val p = Kp.palette
    Text(title, color = p.text, style = KpType.title2)
    if (caption != null) Text(caption, color = p.text2, style = KpType.caption, modifier = Modifier.padding(top = 2.dp))
}

/** Speed: the value large, a 0.1-detent slider between slower/faster steps, and the common presets. */
@Composable
internal fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    val p = Kp.palette
    KpSheet(onDismiss) {
        SheetTitle(stringResource(R.string.speed_sheet_title))
        Spacer(Modifier.height(16.dp))
        Text(
            PlayerFormat.speed(speed), Modifier.fillMaxWidth(), color = p.text, style = KpType.bigStat,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            KpIconButton(
                PlayerIcons.Minus, stringResource(R.string.speed_slower),
                { onSpeed(PlayerFormat.stepSpeed(speed, -0.05f)) }, style = KpIconButtonStyle.Tonal,
            )
            KpSlider(
                value = speed,
                onValueChange = { onSpeed(PlayerFormat.stepSpeed(it, 0f)) },
                valueRange = PlayerFormat.MIN_SPEED..PlayerFormat.MAX_SPEED,
                steps = ((PlayerFormat.MAX_SPEED - PlayerFormat.MIN_SPEED) / 0.1f).toInt() - 1,
                contentDescription = stringResource(R.string.player_speed),
                modifier = Modifier.weight(1f),
            )
            KpIconButton(
                KpIcons.Add, stringResource(R.string.speed_faster),
                { onSpeed(PlayerFormat.stepSpeed(speed, 0.05f)) }, style = KpIconButtonStyle.Tonal,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerFormat.SPEED_PRESETS.forEach { preset ->
                PresetPill(PlayerFormat.speed(preset), abs(preset - speed) < 0.01f, { onSpeed(preset) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    Box(
        modifier.heightIn(min = 48.dp)
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(KpRadius.pill)
                .background(if (selected) p.key else p.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (selected) p.onKey else p.text, style = KpType.numeral, maxLines = 1)
        }
    }
}

@Composable
internal fun SleepSheet(
    remainingMs: Long?,
    atSectionEnd: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = Kp.palette
    val active = remainingMs != null || atSectionEnd
    KpSheet(onDismiss) {
        SheetTitle(
            stringResource(R.string.sleep_sheet_title),
            when {
                atSectionEnd -> stringResource(R.string.player_sleep_section_end)
                remainingMs != null -> stringResource(R.string.player_sleep_remaining, PlayerFormat.clock(remainingMs))
                else -> null
            },
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.bleed(20.dp)) {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item { SleepRow(stringResource(R.string.sleep_off), selected = !active) { onSelect(0) }; KpRowSeparator() }
                PlayerFormat.SLEEP_MINUTES.forEach { m ->
                    item(m) { SleepRow(pluralStringResource(R.plurals.sleep_minutes, m, m), selected = false) { onSelect(m) }; KpRowSeparator() }
                }
                item {
                    SleepRow(stringResource(R.string.player_sleep_section_end), selected = atSectionEnd) {
                        onSelect(PlayerFormat.SLEEP_END_OF_SECTION)
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = Kp.palette
    KpRow(
        title = label, onClick = onClick,
        titleColor = if (selected) p.accentText else p.text,
        trailing = if (selected) ({ KpIconView(KpIcons.Check, p.accentText, size = 22.dp) }) else null,
    )
}

/** The chapter list: numbered rows, the playing one marked and scrolled into view. */
@Composable
internal fun ChaptersSheet(
    marks: List<ChapterMark>,
    currentIndex: Int,
    onSelect: (ChapterMark) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = Kp.palette
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    KpSheet(onDismiss) {
        SheetTitle(stringResource(R.string.chapters_sheet_title), pluralStringResource(R.plurals.chapters_count, marks.size, marks.size))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.bleed(20.dp)) {
            LazyColumn(Modifier.heightIn(max = 520.dp), state = list) {
                itemsIndexed(marks, key = { _, m -> m.sectionId to m.startOrdinal }) { i, mark ->
                    val current = i == currentIndex
                    KpRow(
                        title = mark.title,
                        meta = if (current) stringResource(R.string.chapters_current) else null,
                        titleColor = if (current) p.accentText else p.text,
                        onClick = { onSelect(mark) },
                        leading = {
                            Text(
                                (i + 1).toString(), Modifier.widthIn(min = 28.dp), color = if (current) p.accentText else p.text2,
                                style = KpType.numeral,
                            )
                        },
                        trailing = if (current) ({ KpIconView(KpIcons.Headphones, p.accentText, size = 20.dp) }) else null,
                    )
                    if (i < marks.lastIndex) KpRowSeparator(hasLeading = true)
                }
            }
        }
    }
}
