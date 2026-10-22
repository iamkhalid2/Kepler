package app.kepler.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.CoverImage
import app.kepler.core.designsystem.CoverRequest
import app.kepler.core.designsystem.KpProgressBar
import app.kepler.core.designsystem.KpRadius
import app.kepler.core.model.PlaybackController
import app.kepler.core.model.sectionFraction

/**
 * The transport docked under the text while this book is narrated (#112): chapter chip and a way back to the
 * Listen face, the chapter scrubber, then speed, replay 15, play/pause (56), forward 30 and sleep. [compact]
 * (short windows, landscape) keeps only the transport over a 2 dp chapter progress line.
 * The caller draws the background so the dock can sit on the page's own paper.
 */
@Composable
fun PlaybackDock(
    controller: PlaybackController,
    cover: CoverRequest?,
    onShowListen: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var sheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    val section by controller.selectInfo { it.sectionTitle }
    val speed by controller.select { it.speed }
    val sleepRemaining by controller.select { it.sleepRemainingMs?.let { ms -> ms / 1000 * 1000 } }
    val sleepAtSectionEnd by controller.selectInfo { it.sleepAtSectionEnd }

    Column(modifier.fillMaxWidth()) {
        if (compact) {
            val fraction by controller.select { it.sectionFraction }
            KpProgressBar(fraction, Modifier.fillMaxWidth().padding(horizontal = 20.dp), height = 2.dp)
            TransportRow(
                controller, Modifier.padding(horizontal = 8.dp), playSize = 48.dp,
                leading = { CoverButton(cover, onShowListen) },
                trailing = { SpeedButton(speed, { sheet = PlayerSheet.Speed }) },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChapterChip(
                    section ?: stringResource(R.string.player_chapters), { sheet = PlayerSheet.Chapters },
                    Modifier.weight(1f, fill = false).padding(end = 12.dp),
                )
                CoverButton(cover, onShowListen)
            }
            PlaybackScrubber(controller, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(2.dp))
            TransportRow(
                controller, Modifier.padding(horizontal = 8.dp), playSize = 56.dp,
                leading = { SpeedButton(speed, { sheet = PlayerSheet.Speed }) },
                trailing = { SleepButton(sleepRemaining, sleepAtSectionEnd, { sheet = PlayerSheet.Sleep }) },
            )
        }
    }

    PlayerSheetHost(controller, sheet) { sheet = null }
}

/** The cover as a 24x36 thumbnail in a 48 dp target: back to the Listen face. */
@Composable
private fun CoverButton(cover: CoverRequest?, onClick: () -> Unit) {
    PressCircle(onClick, stringResource(R.string.player_show_listen), size = 48.dp) {
        if (cover != null) {
            CoverImage(cover, Modifier.size(24.dp, 36.dp), shape = KpRadius.coverSmall)
        } else {
            Box(Modifier.size(24.dp, 36.dp))
        }
    }
}
