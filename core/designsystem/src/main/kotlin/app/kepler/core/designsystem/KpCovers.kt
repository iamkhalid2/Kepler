package app.kepler.core.designsystem

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File

/** What a cover is drawn from. Build it from the book row; equal requests share one cache entry. */
@Immutable
data class CoverRequest(val bookId: Long, val title: String, val author: String, val coverPath: String?)

/**
 * What generated covers depend on: the palette (book cloth), fonts and density. Obtain with [rememberCoverStyle].
 */
@Stable
class CoverStyle internal constructor(
    internal val palette: KpPalette,
    internal val fonts: FontFamily.Resolver,
    internal val density: Float,
) {
    internal val id: String = "${palette.seed.toArgb()}-${palette.isDark}"
    internal val materials: List<BookMaterial> = bookMaterials(palette)
    fun materialFor(bookId: Long): BookMaterial = materials[bookMaterialIndex(bookId, materials.size)]
}

@Composable
fun rememberCoverStyle(): CoverStyle {
    val palette = Kp.palette
    val fonts = LocalFontFamilyResolver.current
    val density = LocalDensity.current.density
    return remember(palette, fonts, density) { CoverStyle(palette, fonts, density) }
}

/**
 * The process-wide cover cache (brief v2 section 6). Every cover in the app is drawn from here:
 * jackets are decoded once per size bucket on IO with `inSampleSize`; books without a usable jacket get a
 * typographic cover rendered once per book and palette. [peek] is synchronous, so a screen whose covers are
 * cached draws them on its first frame and never shows a placeholder; [preload] warms the cache as soon as a
 * list of books is known. A book whose jacket file exists never shows the generated cover first.
 */
object KpCovers {
    private val lock = Any()
    private val maxBytes: Long = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024)
    private val cache = ByteLru<String, ImageBitmap>(maxBytes) { it.width.toLong() * it.height * 4 }
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()
    /** Jacket paths that could not be decoded: those books use the generated cover. */
    private val broken = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The best cover already cached for [bookId] (any source, the largest bucket), or null. */
    fun peek(bookId: Long): ImageBitmap? = synchronized(lock) {
        cache.keys().filter { CoverKeys.bookOf(it) == bookId }.mapNotNull { cache[it] }.maxByOrNull { it.width }
    }

    /** The cover for [request] from the cache, trying other buckets when the exact one is missing. */
    fun peek(request: CoverRequest, style: CoverStyle, widthPx: Int): ImageBitmap? {
        val bucket = CoverKeys.bucketFor(widthPx)
        return synchronized(lock) {
            CoverKeys.lookupOrder(bucket).firstNotNullOfOrNull { cache[keyFor(request, style, it)] }
        }
    }

    /** True when the exact bucket for [request] is cached (no upgrade needed). */
    fun hasExact(request: CoverRequest, style: CoverStyle, widthPx: Int): Boolean = synchronized(lock) {
        cache[keyFor(request, style, CoverKeys.bucketFor(widthPx))] != null
    }

    /** True when [request] will be drawn from a generated cover (no jacket, or the jacket failed to decode). */
    fun usesGenerated(request: CoverRequest): Boolean {
        val path = request.coverPath ?: return true
        return synchronized(lock) { path in broken }
    }

    /**
     * Renders the generated cover now if [request] uses one and it is not cached yet. Cheap (one small bitmap)
     * and done once per book, so a screen can call it while composing and still draw a cover on frame one.
     */
    fun generatedNow(request: CoverRequest, style: CoverStyle, widthPx: Int): ImageBitmap? {
        if (!usesGenerated(request)) return null
        val bucket = CoverKeys.bucketFor(widthPx)
        val key = CoverKeys.generated(request.bookId, style.id, bucket)
        synchronized(lock) { cache[key]?.let { return it } }
        val img = runCatching { renderGenerated(request, style, bucket) }.getOrNull() ?: return null
        synchronized(lock) { cache.put(key, img) }
        return img
    }

    /** Loads (or renders) the cover for [request] at [widthPx]. Never returns a generic cover for a book with a jacket. */
    suspend fun load(request: CoverRequest, style: CoverStyle, widthPx: Int): ImageBitmap? {
        val bucket = CoverKeys.bucketFor(widthPx)
        val path = request.coverPath
        if (path != null && !usesGenerated(request)) {
            val key = CoverKeys.jacket(request.bookId, path, bucket)
            val job = synchronized(lock) {
                cache[key]?.let { return it }
                inFlight.getOrPut(key) { scope.async { decode(key, path, bucket) } }
            }
            val img = try {
                job.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (img != null) return img
        }
        return scope.async { generatedNow(request, style, widthPx) }.await()
    }

    /** Warms the cache for a list of books, off the main thread. Safe to call on every list emission. */
    fun preload(requests: List<CoverRequest>, style: CoverStyle, widthPx: Int) {
        if (requests.isEmpty()) return
        scope.launch {
            for (r in requests) {
                if (!hasExact(r, style, widthPx)) runCatching { load(r, style, widthPx) }
            }
        }
    }

    /** Forgets every cached cover of [bookId] (cover replaced, book deleted). */
    fun invalidate(bookId: Long) = synchronized(lock) {
        // A replaced or re-imported jacket gets another decode attempt.
        broken.clear()
        cache.keys().filter { CoverKeys.bookOf(it) == bookId }.forEach { cache.remove(it) }
    }

    /** Call from `onTrimMemory`. */
    fun trim(level: Int) = synchronized(lock) {
        // Keep the most recent half in the background so returning to the app still draws covers on frame one.
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> cache.trimTo(0)
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> cache.trimTo(cache.size / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimTo(cache.size / 2)
        }
    }

    private fun keyFor(r: CoverRequest, style: CoverStyle, bucket: Int): String {
        val path = r.coverPath
        return if (path != null && path !in broken) CoverKeys.jacket(r.bookId, path, bucket)
        else CoverKeys.generated(r.bookId, style.id, bucket)
    }

    private fun decode(key: String, path: String, bucket: Int): ImageBitmap? {
        val img = try {
            decodeFile(path, bucket)
        } finally {
            synchronized(lock) { inFlight.remove(key) }
        }
        synchronized(lock) {
            if (img == null) broken += path else cache.put(key, img)
        }
        return img
    }

    private fun decodeFile(path: String, bucket: Int): ImageBitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = CoverKeys.sampleSize(bounds.outWidth, bucket) }
        var bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        if (bmp.width > bucket * 3 / 2) {
            val scaled = Bitmap.createScaledBitmap(bmp, bucket, (bmp.height * bucket.toFloat() / bmp.width).toInt().coerceAtLeast(1), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        bmp.prepareToDraw()
        return bmp.asImageBitmap()
    }

    /**
     * The typographic cover: the book's cloth with light falling across it, the author in small caps at the
     * top, a disc mark and the title in Fraunces at the foot. 2:3, [bucket] px wide.
     */
    private fun renderGenerated(r: CoverRequest, style: CoverStyle, bucket: Int): ImageBitmap {
        val w = bucket
        val h = bucket * 3 / 2
        val img = ImageBitmap(w, h)
        val density = Density(style.density, 1f)
        val measurer = TextMeasurer(style.fonts, density, LayoutDirection.Ltr, cacheSize = 0)
        val m = style.materialFor(r.bookId)
        val ink = m.ink
        val light = m.cloth.luminance() > 0.5f
        val disc = withLightness(m.cloth, (m.cloth.toLch().l + if (light) -0.16f else 0.16f).coerceIn(0.1f, 0.95f), 1.25f)
        fun px(v: Float) = (v / style.density).sp
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawRect(m.cloth)
            drawCircle(disc, radius = w * 0.30f, center = Offset(w * 0.70f, h * 0.40f))
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.10f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.22f),
                    start = Offset.Zero, end = Offset(w.toFloat(), h.toFloat()),
                ),
            )
            val pad = w * 0.09f
            val textW = (w - 2 * pad).toInt().coerceAtLeast(1)
            val author = measurer.measure(
                r.author.uppercase(),
                TextStyle(color = ink.copy(alpha = 0.8f), fontFamily = KpFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = px(w * 0.05f), letterSpacing = px(w * 0.006f)),
                overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = textW),
            )
            drawText(author, topLeft = Offset(pad, h * 0.07f))
            val title = measurer.measure(
                r.title,
                TextStyle(color = ink, fontFamily = KpFonts.displaySmall, fontWeight = FontWeight.SemiBold, fontSize = px(w * 0.11f), lineHeight = px(w * 0.125f)),
                overflow = TextOverflow.Ellipsis, maxLines = 3, constraints = Constraints(maxWidth = textW),
            )
            drawText(title, topLeft = Offset(pad, h - h * 0.08f - title.size.height))
        }
        return img
    }
}
