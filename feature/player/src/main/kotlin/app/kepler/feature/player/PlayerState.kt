package app.kepler.feature.player

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
import app.kepler.core.designsystem.KpPalette
import app.kepler.core.designsystem.BookMaterial
import app.kepler.core.designsystem.BookSpec
import app.kepler.core.designsystem.bookMaterials
import app.kepler.core.model.BookId
import app.kepler.core.model.PlaybackController
import app.kepler.core.model.PlaybackInfo
import app.kepler.core.model.PlaybackInfoSource
import app.kepler.core.model.PlaybackState
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
internal fun rememberCover(path: String?): ImageBitmap? = rememberCoverLoad(path).second

/** (decoded for the current path, image): lets the hero wait for its jacket instead of swapping it in. */
@Composable
internal fun rememberCoverLoad(path: String?): Pair<Boolean, ImageBitmap?> {
    val loaded by produceState<Pair<String?, ImageBitmap?>?>(null, path) {
        value = path to if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 720 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val v = loaded
    return if (v != null && v.first == path) true to v.second else false to null
}

/** The same cloth the library shelf gives this book (rule lives in :core:designsystem). */
internal fun materialFor(bookId: BookId?, palette: KpPalette): BookMaterial {
    val all = bookMaterials(palette)
    return all[app.kepler.core.designsystem.bookMaterialIndex(bookId ?: 0L, all.size)]
}

internal fun bookSpec(
    bookId: BookId?, title: String?, author: String?, palette: KpPalette, cover: ImageBitmap?, thickness: Float = 24f,
    jacketAlpha: () -> Float = { 1f },
): BookSpec = BookSpec(title.orEmpty(), author.orEmpty(), thickness, materialFor(bookId, palette), cover, jacketAlpha)
