package app.kepler.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpType
import app.kepler.core.model.PlaybackController

/**
 * The reader's transport slot in Read + Listen: narrator and how far ahead the audio is, previous
 * sentence, play/pause (56), next sentence, and the speed key. Height 72, gutters 24.
 */
@Composable
fun ReadAlongTransport(controller: PlaybackController, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val loaded by controller.select { it.bookId != null }
    val playing by controller.select { it.playing }
    val buffering by controller.select { it.buffering }
    val speed by controller.select { it.speed }
    val voice by controller.selectInfo { it.voiceName }
    val ahead by controller.selectInfo { it.aheadMs }
    val generating by controller.selectInfo { it.generating }
    val error by controller.select { it.error }
    var speedSheet by remember { mutableStateOf(false) }
    val minutes = PlayerFormat.minutesAhead(ahead, speed)

    Row(
        modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(72.dp), verticalArrangement = Arrangement.Center) {
            Text(
                voice ?: stringResource(R.string.player_narrator_fallback),
                color = if (playing) p.accentText else p.text2,
                style = KpType.caption.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                error != null && !playing -> stringResource(R.string.player_error_short)
                buffering -> stringResource(R.string.player_buffering_short)
                minutes > 0 -> pluralStringResource(R.plurals.player_ahead_minutes, minutes, minutes)
                generating -> stringResource(R.string.player_binding_short)
                else -> null
            }
            if (sub != null) {
                Text(sub, color = p.text2, style = KpType.caption.copy(fontSize = 11.sp, lineHeight = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SentenceKey(SentenceDirection.Previous, stringResource(R.string.player_prev_sentence), { controller.skipSentences(-1) })
        PlayKey(playing = playing, size = 56.dp, enabled = loaded, onClick = { if (playing) controller.pause() else controller.resume() })
        SentenceKey(SentenceDirection.Next, stringResource(R.string.player_next_sentence), { controller.skipSentences(1) })
        KpSecondaryKey(PlayerFormat.speed(speed), { speedSheet = true }, Modifier.width(64.dp))
    }

    if (speedSheet) SpeedSheet(speed, controller::setSpeed) { speedSheet = false }
}
