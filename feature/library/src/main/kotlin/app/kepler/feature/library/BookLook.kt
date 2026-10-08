package app.kepler.feature.library

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.kepler.core.designsystem.BOOK_W
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.BookMaterial
import app.kepler.core.designsystem.BookSpec
import app.kepler.core.designsystem.bookMaterials
import app.kepler.core.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Spine thickness buckets: 6, 9 or 12 percent of the cover width. */
enum class SpineBucket(val fraction: Float) { Slim(0.06f), Regular(0.09f), Thick(0.12f) }

/** Page count is not stored, so the sentence count stands in for it. */
fun spineBucketFor(sentenceCount: Int): SpineBucket = when {
    sentenceCount < 4_000 -> SpineBucket.Slim
    sentenceCount < 12_000 -> SpineBucket.Regular
    else -> SpineBucket.Thick
}

/** Thickness in BookScene design units (the cover is [BOOK_W] wide). */
fun spineThicknessUnits(sentenceCount: Int): Float = BOOK_W * spineBucketFor(sentenceCount).fraction

/** A stable material index for a book id: the same book always gets the same cloth. */
fun materialIndexFor(id: Long, count: Int): Int = app.kepler.core.designsystem.bookMaterialIndex(id, count)

fun materialFor(book: Book, materials: List<BookMaterial>): BookMaterial =
    materials[materialIndexFor(book.id, materials.size)]

@Composable
fun rememberBookMaterials(): List<BookMaterial> {
    val p = Kp.palette
    return remember(p) { bookMaterials(p) }
}

/** Decodes a cover thumbnail off the main thread, or null when the book has none. */
@Composable
fun rememberCover(path: String?, maxWidthPx: Int = 360): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, path, maxWidthPx) {
        value = if (path == null) null else withContext(Dispatchers.IO) { decodeCover(path, maxWidthPx) }
    }
    return state.value
}

internal fun decodeCover(path: String, maxWidthPx: Int): ImageBitmap? = runCatching {
    if (!File(path).exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

fun bookSpecFor(book: Book, materials: List<BookMaterial>, cover: ImageBitmap? = null, thickness: Float = spineThicknessUnits(book.sentenceCount)) =
    BookSpec(book.title, book.authors, thickness, materialFor(book, materials), cover)
