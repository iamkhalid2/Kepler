package app.kepler.core.designsystem

import android.graphics.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The cover of [request] at roughly [widthPx], from [KpCovers]. Returns the cached cover on the first
 * composition when there is one (generated covers are rendered on the spot, once per book), then upgrades to
 * the exact size bucket in the background. Null only while a jacket is decoded for the very first time.
 */
@Composable
fun rememberCoverImage(request: CoverRequest, widthPx: Int): ImageBitmap? {
    val style = rememberCoverStyle()
    val bucket = CoverKeys.bucketFor(widthPx)
    var image by remember(request, style, bucket) {
        mutableStateOf(KpCovers.peek(request, style, bucket) ?: KpCovers.generatedNow(request, style, bucket))
    }
    LaunchedEffect(request, style, bucket) {
        if (!KpCovers.hasExact(request, style, bucket)) KpCovers.load(request, style, bucket)?.let { image = it }
    }
    return image
}

/**
 * A flat book cover (2:3 unless [modifier] says otherwise), cropped to fill, with a hairline edge. Use it for
 * every 2D cover: grid tiles, rows, the mini player. With [sharedElement] it is the book's shared cover for
 * navigation transitions; set it on one cover per book per screen.
 */
@Composable
fun CoverImage(
    request: CoverRequest,
    modifier: Modifier = Modifier,
    shape: Shape = KpRadius.cover,
    sharedElement: Boolean = false,
    contentDescription: String? = null,
) {
    val p = Kp.palette
    val shared = if (sharedElement) Modifier.kpSharedCover(request.bookId) else Modifier
    val label = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    BoxWithConstraints(modifier.then(shared).then(label).clip(shape).background(p.surface2)) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val image = rememberCoverImage(request, widthPx)
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().border(0.75.dp, if (p.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f), shape))
    }
}

/**
 * Library grid tile (brief v2 section 8): cover, 3 dp position bar, title and author. [badge] draws in the
 * cover's top-right corner (headphones when narrated, a ring while generating).
 */
@Composable
fun KpCoverTile(
    request: CoverRequest,
    position: Float,
    modifier: Modifier = Modifier,
    sharedElement: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
) {
    val p = Kp.palette
    Column(modifier) {
        Box {
            CoverImage(request, Modifier.fillMaxWidth().aspectRatio(2f / 3f), sharedElement = sharedElement)
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                        .background(p.surface3.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { badge() }
            }
        }
        Spacer(Modifier.height(8.dp))
        KpProgressBar(position, Modifier.fillMaxWidth(), height = 3.dp)
        Spacer(Modifier.height(6.dp))
        Text(request.title, color = p.text, style = KpType.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(request.author, color = p.text2, style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The 3D book object (brief v2 section 7): a single book that fills its bounds (2:3), drawn by the BookScene
 * renderer with the cached cover on its front. [yaw] turns it to show the spine; with [turnIn] it arrives flat
 * and turns to [yaw] once per screen entry (never again on recomposition, tab return or rotation).
 * [ribbon] (0..1, the position share) hangs a silk ribbon below the book.
 */
@Composable
fun BookObject(
    request: CoverRequest,
    modifier: Modifier = Modifier,
    yaw: Float = 18f,
    turnIn: Boolean = false,
    thickness: Float = 30f,
    ribbon: Float? = null,
    sharedElement: Boolean = false,
    contentDescription: String? = null,
) {
    val p = Kp.palette
    val reduced = rememberReducedMotion()
    var turned by rememberSaveable(request.bookId) { mutableStateOf(!turnIn) }
    val ry = remember(request.bookId) { Animatable(if (turned || reduced) yaw else 0f) }
    LaunchedEffect(yaw, reduced) {
        if (turned || reduced) ry.snapTo(yaw) else {
            turned = true
            ry.animateTo(yaw, KpMotion.turn())
        }
    }
    val measurer = rememberTextMeasurer()
    val matrix = remember { Matrix() }
    val src = remember { FloatArray(8) }
    val dst = remember { FloatArray(8) }
    val style = rememberCoverStyle()
    val shared = if (sharedElement) Modifier.kpSharedCover(request.bookId) else Modifier
    val label = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    BoxWithConstraints(modifier.aspectRatio(BOOK_W / BOOK_H).then(shared).then(label)) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val image = rememberCoverImage(request, widthPx)
        val spec = remember(request, image, thickness, style) {
            BookSpec(request.title, request.author, thickness, style.materialFor(request.bookId), image)
        }
        if (ribbon != null) {
            val swing = rememberRibbonSwing(request.bookId)
            val scale = maxWidth / 150.dp
            SilkRibbon(
                p.materials.silk, swing, 8.dp * scale, ribbonLength(ribbon, scale) + 6.dp,
                Modifier.align(Alignment.BottomStart).offset(x = maxWidth * 0.72f, y = ribbonLength(ribbon, scale)),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / BOOK_W
            drawBook(
                spec, thickness, u, BOOK_W / 2f, BOOK_H / 2f, 1f, ry.value, 0f, 0f,
                1f, 0f, 0f, p.page, p, measurer, matrix, src, dst,
            )
        }
    }
}

/** Thin progress bar: surface2 track, accent fill, fully rounded. */
@Composable
fun KpProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, color: Color = Kp.palette.accent) {
    val p = Kp.palette
    Canvas(modifier.height(height)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(p.surface2, cornerRadius = r)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) drawRoundRect(color, size = size.copy(width = (size.width * f).coerceAtLeast(size.height)), cornerRadius = r)
    }
}
