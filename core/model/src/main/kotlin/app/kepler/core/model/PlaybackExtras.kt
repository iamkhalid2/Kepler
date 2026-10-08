package app.kepler.core.model

import kotlinx.coroutines.flow.StateFlow

/** A chapter tick for the player scrubber. Ordinals are sentence ordinals. */
data class ChapterMark(val sectionId: Long, val title: String, val startOrdinal: Int, val endOrdinal: Int)

/** Everything the player UI needs beyond [PlaybackState]. */
data class PlaybackInfo(
    val bookId: BookId? = null,
    val bookTitle: String? = null,
    val author: String? = null,
    val coverPath: String? = null,
    val sectionTitle: String? = null,
    /** Display name of the narrating voice. */
    val voiceName: String? = null,
    val sentenceCount: Int = 0,
    /** Chapter ticks in book order, thinned so the scrubber stays readable. */
    val marks: List<ChapterMark> = emptyList(),
    /** Generated audio after the playhead, in media milliseconds (divide by speed for wall time). */
    val aheadMs: Long = 0,
    /** True while narration for this book is being generated. */
    val generating: Boolean = false,
    /** Estimated listening time at 1x: where the playhead is, and the whole book. */
    val bookElapsedMs: Long = 0,
    val bookTotalMs: Long = 0,
    /** True when the sleep timer is set to stop at the end of the current section. */
    val sleepAtSectionEnd: Boolean = false,
)

/** Implemented by the playback client next to [PlaybackController]. */
interface PlaybackInfoSource {
    val info: StateFlow<PlaybackInfo>
}
