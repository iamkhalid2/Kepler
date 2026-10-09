package app.kepler.core.model

/** What the player scrubber covers. CHAPTER is the default (decision #109). */
enum class ScrubberScope { CHAPTER, BOOK }

/** 0..1 progress through the current chapter. */
val PlaybackState.sectionFraction: Float
    get() = if (sectionDurationMs <= 0) 0f else (sectionElapsedMs.toFloat() / sectionDurationMs).coerceIn(0f, 1f)

/** Chapter time left, in wall-clock milliseconds at the current speed. */
val PlaybackState.sectionRemainingWallMs: Long
    get() = ((sectionDurationMs - sectionElapsedMs).coerceAtLeast(0) / speed.coerceAtLeast(0.1f)).toLong()

/** Chapter time elapsed, in wall-clock milliseconds at the current speed. */
val PlaybackState.sectionElapsedWallMs: Long
    get() = (sectionElapsedMs / speed.coerceAtLeast(0.1f)).toLong()

/** Narrated time left in the book, in wall-clock milliseconds at the current speed. */
val PlaybackState.bookRemainingWallMs: Long
    get() = (bookRemainingMs / speed.coerceAtLeast(0.1f)).toLong()
