package app.kepler.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.CoverRequest
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.rememberCoverImage
import app.kepler.core.model.PlaybackController
import app.kepler.core.model.PlaybackInfo
import app.kepler.core.model.PlaybackInfoSource
import app.kepler.core.model.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Observes one derived field of the playback state. Playback state updates about four times a second;
 * selecting a field keeps a composable from recomposing unless that field changed.
 */
@Composable
fun <T> PlaybackController.select(selector: (PlaybackState) -> T): State<T> {
    val raw = state.collectAsStateWithLifecycle()
    val sel = rememberUpdatedState(selector)
    return remember(this) { derivedStateOf { sel.value(raw.value) } }
}

@Composable
fun <T> PlaybackController.selectInfo(selector: (PlaybackInfo) -> T): State<T> {
    val flow: StateFlow<PlaybackInfo> = remember(this) { (this as? PlaybackInfoSource)?.info ?: MutableStateFlow(PlaybackInfo()) }
    val raw = flow.collectAsStateWithLifecycle()
    val sel = rememberUpdatedState(selector)
    return remember(this) { derivedStateOf { sel.value(raw.value) } }
}

/** "2h 10m" or "18 min". */
@Composable
internal fun durationLabel(ms: Long): String {
    val (h, m) = PlayerFormat.durationParts(ms)
    return if (h > 0) stringResource(R.string.player_duration_hm, h, m) else stringResource(R.string.player_duration_m, m)
}

@Composable
internal fun estimated(text: String, estimated: Boolean): String =
    if (estimated) stringResource(R.string.player_estimated, text) else text

/** The cover's average colour (for the ambient light behind the Listen face), or the accent while it is unknown. */
@Composable
internal fun rememberCoverTint(request: CoverRequest?): Color {
    val fallback = Kp.palette.accent
    if (request == null) return fallback
    val image = rememberCoverImage(request, COVER_TINT_PX)
    val tint by produceState<Color?>(null, image) { value = image?.let { withContext(Dispatchers.Default) { averageColor(it) } } }
    return tint ?: fallback
}

private const val COVER_TINT_PX = 192

private fun averageColor(image: ImageBitmap): Color? = runCatching {
    val small = android.graphics.Bitmap.createScaledBitmap(image.asAndroidBitmap(), 6, 9, true)
    var r = 0L
    var g = 0L
    var b = 0L
    val n = small.width * small.height
    for (y in 0 until small.height) for (x in 0 until small.width) {
        val c = small.getPixel(x, y)
        r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c)
    }
    Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}.getOrNull()
