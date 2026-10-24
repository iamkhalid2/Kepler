package app.kepler.feature.reader

import app.kepler.feature.player.faceSwitchWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpButton
import app.kepler.core.designsystem.KpButtonStyle
import app.kepler.core.designsystem.KpIconButton
import app.kepler.core.designsystem.KpIconButtonStyle
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpScrubber
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpSize
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.feature.player.ChapterChip
import kotlin.math.roundToInt

// The Read face's chrome (brief v2, #112). It is drawn inside the page's paper palette, so on a sepia page the
// bars are sepia too and the text never sits next to a foreign surface.

/**
 * Top bar: back, the Listen | Read segmented control centred exactly where the Listen face has it (so switching
 * faces never moves it), then Aa and Learn. The chapter title lives in the page header and the dock's chip; search
 * lives at the top of the contents sheet.
 */
@Composable
internal fun ReaderTopBar(
    onBack: () -> Unit,
    onListen: () -> Unit,
    onTypography: () -> Unit,
    onLearn: () -> Unit,
) {
    val p = Kp.palette
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().background(p.bg).statusBarsPadding().height(KpSize.topBar).padding(horizontal = 8.dp)) {
            KpIconButton(
                KpIcons.Back, stringResource(R.string.reader_back), onBack,
                Modifier.align(Alignment.CenterStart), KpIconButtonStyle.Tonal,
            )
            KpSegmented(
                options = listOf(stringResource(R.string.reader_face_listen), stringResource(R.string.reader_face_read)),
                selected = 1,
                onSelect = { if (it == 0) onListen() },
                modifier = Modifier.align(Alignment.Center).width(faceSwitchWidth(maxWidth)),
            )
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                TypographyButton(onTypography)
                KpIconButton(KpIcons.Sparkle, stringResource(R.string.reader_learn), onLearn, style = KpIconButtonStyle.Tonal)
            }
        }
        // A short fade out of the bar instead of a rule, so a line of text passing under it never shows cut in half.
        Box(Modifier.fillMaxWidth().height(20.dp).background(Brush.verticalGradient(listOf(p.bg, p.bg.copy(alpha = 0f)))))
    }
}

/** "Aa" on a tonal circle: text and theme. */
@Composable
private fun TypographyButton(onClick: () -> Unit) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val label = stringResource(R.string.reader_typography)
    Box(
        Modifier.size(48.dp).clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(KpSize.iconButtonTonal).background(p.surface1, CircleShape), contentAlignment = Alignment.Center) {
            Text("Aa", color = p.text, style = KpType.button)
        }
    }
}

/**
 * Bottom bar while reading without narration (shown with the chrome): the chapter chip (contents), Listen, and the
 * chapter scrubber with the share of the book and the minutes left in the chapter.
 */
@Composable
internal fun ReaderIdleBar(
    chapter: String?,
    progress: SectionProgress?,
    bookFraction: Float,
    onSeek: (Float) -> Unit,
    onContents: () -> Unit,
    onListen: () -> Unit,
) {
    val p = Kp.palette
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(20.dp).background(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg))))
        Column(Modifier.fillMaxWidth().background(p.bg).padding(start = 16.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ChapterChip(chapter ?: stringResource(R.string.reader_contents), onContents, Modifier.weight(1f, fill = false).padding(end = 12.dp))
                KpButton(
                    stringResource(R.string.reader_listen), onListen, small = true, icon = KpIcons.Headphones,
                )
            }
            val minutes = progress?.minutesLeft ?: 0.0
            KpScrubber(
                fraction = progress?.fraction ?: 0f,
                onSeek = onSeek,
                elapsed = stringResource(R.string.reader_book_percent, (bookFraction * 100).roundToInt()),
                remaining = if (minutes < 1.0) stringResource(R.string.reader_under_min_left)
                else stringResource(R.string.reader_min_left, minutes.roundToInt()),
                contentDescription = stringResource(R.string.reader_chapter_track),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.fillMaxWidth().background(p.bg).navigationBarsPadding())
    }
}

/** The dock's surface: a 32 dp fade out of the page, then the page colour, with 16 dp clear above the navigation bar. */
@Composable
internal fun DockSurface(page: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(32.dp).background(Brush.verticalGradient(listOf(page.copy(alpha = 0f), page))))
        Column(Modifier.fillMaxWidth().background(page).navigationBarsPadding().padding(bottom = 16.dp), content = content)
    }
}

/** "Back to narration": a key-filled pill floating over the page when the reader moved away from the voice. */
@Composable
internal fun BackToNarrationPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    KpButton(
        stringResource(R.string.reader_back_to_narration), onClick, modifier,
        style = KpButtonStyle.Primary, small = true, icon = KpIcons.Headphones,
        haptic = HapticToken.Tap,
    )
}
