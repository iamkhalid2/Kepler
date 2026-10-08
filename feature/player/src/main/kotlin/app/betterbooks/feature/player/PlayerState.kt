package app.betterbooks.feature.player

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.betterbooks.core.designsystem.BbPalette
import app.betterbooks.core.designsystem.BookMaterial
import app.betterbooks.core.designsystem.BookSpec
import app.betterbooks.core.designsystem.bookMaterials
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.PlaybackController
import app.betterbooks.core.model.PlaybackInfo
import app.betterbooks.core.model.PlaybackInfoSource
import app.betterbooks.core.model.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Observes one derived field of the playback state. Playback state updates about ten times a second;
 * selecting a field keeps a composable from recomposing unless that field changed.
 */
@Composable
internal fun <T> PlaybackController.select(selector: (PlaybackState) -> T): State<T> {
    val raw = state.collectAsStateWithLifecycle()
    val sel = rememberUpdatedState(selector)
    return remember(this) { derivedStateOf { sel.value(raw.value) } }
}

@Composable
internal fun <T> PlaybackController.selectInfo(selector: (PlaybackInfo) -> T): State<T> {
    val flow: StateFlow<PlaybackInfo> = remember(this) { (this as? PlaybackInfoSource)?.info ?: MutableStateFlow(PlaybackInfo()) }
    val raw = flow.collectAsStateWithLifecycle()
    val sel = rememberUpdatedState(selector)
    return remember(this) { derivedStateOf { sel.value(raw.value) } }
}

/** Loads a jacket image off the main thread, small enough for a 220 dp cover. */
@Composable
internal fun rememberCover(path: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 720 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

/** The same material every time for a given book; the shared source of truth for this is the library. */
internal fun materialFor(bookId: BookId?, palette: BbPalette): BookMaterial {
    val all = bookMaterials(palette)
    return all[Math.floorMod((bookId ?: 0L).hashCode(), all.size)]
}

internal fun bookSpec(
    bookId: BookId?, title: String?, author: String?, palette: BbPalette, cover: ImageBitmap?, thickness: Float = 24f,
): BookSpec = BookSpec(title.orEmpty(), author.orEmpty(), thickness, materialFor(bookId, palette), cover)
