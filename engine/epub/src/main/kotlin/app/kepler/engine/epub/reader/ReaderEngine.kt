package app.kepler.engine.epub.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A mark painted over the text. [tint] is ARGB. Highlights and underlines use Readium's own opacity;
 * a [band] (the read-along sentence, r4 boxes around the words) uses the tint's alpha as given.
 */
data class TextMark(
    val id: String,
    val anchor: TextAnchor,
    val tint: Int,
    val underline: Boolean = false,
    val band: Boolean = false,
)

/** The current text selection. [top]/[bottom] are in px relative to the navigator view, when known. */
data class TextSelection(val anchor: TextAnchor, val top: Float?, val bottom: Float?)

data class SearchHit(val anchor: TextAnchor, val title: String?)

sealed interface ReaderEvent {
    /** Tap outside the page-turn zones. */
    data object CenterTap : ReaderEvent
    /** Two taps close together while read-along is on; [x]/[y] in px relative to the navigator view. */
    data class DoubleTap(val x: Float, val y: Float) : ReaderEvent
    data class MarkTapped(val group: String, val id: String) : ReaderEvent
    /** An internal link was followed (counts as a jump for consumption). */
    data object LinkJump : ReaderEvent
    data class ExternalLink(val url: String) : ReaderEvent
}

/**
 * The reader's view of a rendering engine. The Readium implementation is [ReadiumReaderEngine];
 * the UI never touches Readium types except to host the navigator fragment.
 */
interface ReaderEngine {
    /** The location of the top of the visible page, refined with the first visible element's text. */
    val location: StateFlow<TextAnchor?>
    val events: Flow<ReaderEvent>

    fun go(anchor: TextAnchor, animated: Boolean = false): Boolean
    fun goForward(animated: Boolean = true): Boolean
    fun goBackward(animated: Boolean = true): Boolean
    fun submit(style: ReaderStyle)

    /** Replaces every mark of [group]. Remembered and re-applied when the view is recreated. */
    suspend fun applyMarks(group: String, marks: List<TextMark>)
    suspend fun selection(): TextSelection?
    fun clearSelection()
    suspend fun search(query: String, limit: Int = 100): List<SearchHit>

    /**
     * Read-along on: a single tap waits briefly for a second one ([ReaderEvent.DoubleTap]) and edge taps no
     * longer turn pages, so a paragraph can be double-tapped anywhere. Swipes still turn pages.
     */
    fun setReadAlong(on: Boolean)

    /**
     * Lays [tint] (ARGB, or null to clear) behind the paragraph that holds the read-along sentence mark. Call after
     * the read-along group was applied.
     */
    suspend fun markReadAlongParagraph(tint: Int?)

    /** Text of the block element (paragraph, list item, heading) under [x]/[y] in navigator px, or null. */
    suspend fun blockTextAt(x: Float, y: Float): String?
}

/**
 * Tells a double tap from a single one. [onTap] returns true when the tap completes a double tap (the pending
 * single tap must then be dropped).
 */
class DoubleTapDetector(private val windowMs: Long = 300, private val slopPx: Float = 96f) {
    private var lastAt = Long.MIN_VALUE / 2
    private var lastX = 0f
    private var lastY = 0f

    fun onTap(x: Float, y: Float, atMs: Long): Boolean {
        val double = atMs - lastAt <= windowMs && kotlin.math.abs(x - lastX) <= slopPx && kotlin.math.abs(y - lastY) <= slopPx
        if (double) {
            lastAt = Long.MIN_VALUE / 2
        } else {
            lastAt = atMs; lastX = x; lastY = y
        }
        return double
    }
}
