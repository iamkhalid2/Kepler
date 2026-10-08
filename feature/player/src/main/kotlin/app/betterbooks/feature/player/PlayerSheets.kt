package app.betterbooks.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.designsystem.BbCaps
import app.betterbooks.core.designsystem.BbIcon
import app.betterbooks.core.designsystem.BbIconKind
import app.betterbooks.core.designsystem.BbRow
import app.betterbooks.core.designsystem.BbRowSeparator
import app.betterbooks.core.designsystem.BbSecondaryKey
import app.betterbooks.core.designsystem.BbSheet
import app.betterbooks.core.designsystem.BbType
import app.betterbooks.core.model.ChapterMark

/** Lets full-width rows (which carry their own 24 dp gutter) sit inside the sheet's padded column. */
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
internal fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    val p = Bb.palette
    BbSheet(onDismiss) {
        BbCaps(stringResource(R.string.speed_sheet_title))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BbSecondaryKey("−0.1", { onSpeed(PlayerFormat.stepSpeed(speed, -0.1f)) })
            Text(
                PlayerFormat.speed(speed), Modifier.weight(1f), color = p.text, style = BbType.bigNumeral,
                textAlign = TextAlign.Center,
            )
            BbSecondaryKey("+0.1", { onSpeed(PlayerFormat.stepSpeed(speed, 0.1f)) })
        }
        Spacer(Modifier.height(24.dp))
        PlayerFormat.SPEED_PRESETS.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    val selected = kotlin.math.abs(preset - speed) < 0.01f
                    BbSecondaryKey(
                        PlayerFormat.speed(preset), { onSpeed(preset) }, Modifier.weight(1f),
                        labelColor = if (selected) p.accentText else p.text,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
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
    val p = Bb.palette
    BbSheet(onDismiss) {
        BbCaps(stringResource(R.string.sleep_sheet_title))
        if (remainingMs != null && !atSectionEnd) {
            Text(
                stringResource(R.string.player_sleep_remaining, PlayerFormat.sleepRemainingLabel(remainingMs)),
                color = p.text2, style = BbType.caption, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        val active = remainingMs != null || atSectionEnd
        Column(Modifier.bleed(24.dp)) {
            SleepRow(stringResource(R.string.sleep_off), selected = !active) { onSelect(0) }
            BbRowSeparator()
            PlayerFormat.SLEEP_MINUTES.forEach { m ->
                SleepRow(pluralStringResource(R.plurals.sleep_minutes, m, m), selected = false) { onSelect(m) }
                BbRowSeparator()
            }
            SleepRow(stringResource(R.string.player_sleep_section_end), selected = atSectionEnd) {
                onSelect(PlayerFormat.SLEEP_END_OF_SECTION)
            }
        }
    }
}

@Composable
private fun SleepRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = Bb.palette
    BbRow(
        title = label, onClick = onClick,
        titleColor = if (selected) p.accentText else p.text,
        trailing = if (selected) ({ BbIcon(BbIconKind.Check, p.accentText) }) else null,
    )
}

@Composable
internal fun ChaptersSheet(
    marks: List<ChapterMark>,
    currentSectionId: Long?,
    onSelect: (ChapterMark) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = Bb.palette
    BbSheet(onDismiss) {
        BbCaps(stringResource(R.string.chapters_sheet_title))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.bleed(24.dp)) {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(marks, key = { it.sectionId to it.startOrdinal }) { mark ->
                    val current = mark.sectionId == currentSectionId
                    BbRow(
                        title = mark.title,
                        meta = if (current) stringResource(R.string.chapters_current) else null,
                        titleColor = if (current) p.accentText else p.text,
                        onClick = { onSelect(mark) },
                    )
                    BbRowSeparator()
                }
            }
        }
    }
}
