package app.kepler.engine.epub.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A mark painted over the text. [tint] is ARGB. Highlights and underlines use Readium's own opacity;
 * a [band] (the read-along sentence) uses the tint's alpha as given.
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
}
