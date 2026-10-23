package app.kepler.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import app.kepler.core.designsystem.BOOK_W
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.BookMaterial
import app.kepler.core.designsystem.BookSpec
import app.kepler.core.designsystem.bookMaterials
import app.kepler.core.model.Book

/**
 * Spine thickness buckets as a share of the cover width. Design lead: the brief's 6/9/12 percent
 * gave 9 dp spines on a 150 dp shelf, too thin to read a title; 16/20/25 percent reads as real
 * cloth spines (16 to 25 dp) without turning into bricks.
 */
enum class SpineBucket(val fraction: Float) { Slim(0.16f), Regular(0.20f), Thick(0.25f) }

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

fun bookSpecFor(book: Book, materials: List<BookMaterial>, cover: ImageBitmap? = null, thickness: Float = spineThicknessUnits(book.sentenceCount)) =
    BookSpec(book.title, book.authors, thickness, materialFor(book, materials), cover)
