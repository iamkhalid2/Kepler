package app.kepler.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The persistent mini player (brief v2 section 3), shown above the tab bar on every top-level screen while a
 * book is loaded. Stateless: the shell feeds it from the playback controller. [progress] is the share of the
 * current chapter. Tap or swipe up calls [onOpen]; swipe down calls [onDismiss] (the shell pauses and hides it).
 */
@Composable
fun KpMiniPlayer(
    cover: CoverRequest?,
    title: String,
    caption: String,
    playing: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val open by rememberUpdatedState(onOpen)
    val dismiss by rememberUpdatedState(onDismiss)
    val openLabel = stringResource(R.string.kp_open_player)
    Box(
        modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .shadow(12.dp, KpRadius.miniPlayer, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(KpRadius.miniPlayer)
            .background(p.surface3)
            .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, KpRadius.miniPlayer) else Modifier)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total < -32.dp.toPx() -> open()
                            total > 32.dp.toPx() -> { haptics.play(HapticToken.Tap); dismiss() }
                        }
                    },
                    onVerticalDrag = { change, delta -> total += delta; change.consume() },
                )
            }
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { haptics.play(HapticToken.Tap); open() }
            .semantics(mergeDescendants = false) { contentDescription = openLabel },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = KpSize.miniPlayer).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cover != null) {
                CoverImage(cover, Modifier.size(32.dp, 48.dp), shape = KpRadius.coverSmall)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, color = p.text, style = KpType.rowTitle.copy(fontSize = KpType.button.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(caption, color = p.text2, style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            KpIconButton(
                if (playing) KpIcons.Pause else KpIcons.Play,
                stringResource(if (playing) R.string.kp_pause else R.string.kp_play),
                onPlayPause,
                style = KpIconButtonStyle.Filled,
                filledSize = 44.dp,
                iconSize = 22.dp,
            )
        }
        Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)) {
            drawRect(p.surface2)
            drawRect(p.accent, size = size.copy(width = size.width * progress.coerceIn(0f, 1f)))
        }
    }
}
