package app.kepler.engine.epub.reader

/**
 * Decides which sentences were really read, from the stream of "page starts at ordinal X" events.
 *
 * A page counts once the reader moves forward past it after a dwell scaled to its word count.
 * After a jump (TOC, search, link, scrub) or a skimmed page, pages are held back until
 * [Config.pagesToConfirm] pages in a row were read, and then committed together.
 */
class ConsumptionTracker(
    /** Word count of sentences [from, to] inclusive. */
    private val words: (from: Int, to: Int) -> Int,
    private val config: Config = Config(),
) {
    data class Config(
        /** ~700 wpm: faster than this is skimming. */
        val msPerWord: Long = 85,
        val minDwellMs: Long = 1_500,
        val maxDwellMs: Long = 45_000,
        val pagesToConfirm: Int = 2,
        /** A forward move spanning more sentences than this is a jump, not a page turn. */
        val maxPageSentences: Int = 80,
    )

    private var current: Int? = null
    private var since = 0L
    private var paused = false
    private var probation = false
    private val held = ArrayList<IntRange>()

    fun requiredDwellMs(from: Int, to: Int): Long =
        (words(from, to) * config.msPerWord).coerceIn(config.minDwellMs, config.maxDwellMs)

    /**
     * The visible page now starts at [startOrdinal]. [jump] marks navigation that is not reading
     * (TOC, search, links, scrubbing). Returns the ranges to record as consumed, in order.
     */
    fun onPage(startOrdinal: Int, nowMs: Long, jump: Boolean = false): List<IntRange> {
        val prev = current
        val dwell = nowMs - since
        current = startOrdinal
        since = nowMs
        if (prev == null) {
            if (jump) enterProbation()
            return emptyList()
        }
        if (startOrdinal == prev) return emptyList()
        if (jump || startOrdinal - prev > config.maxPageSentences) {
            enterProbation()
            return emptyList()
        }
        // Going back to re-read is neither progress nor a break in contiguity.
        if (startOrdinal < prev) return emptyList()

        val page = prev until startOrdinal
        if (paused || dwell < requiredDwellMs(page.first, page.last)) {
            enterProbation()
            return emptyList()
        }
        if (!probation) return listOf(page)
        held += page
        if (held.size < config.pagesToConfirm) return emptyList()
        val out = held.toList()
        held.clear()
        probation = false
        return out
    }

    /** The reader is not visible (app in background, sheet covering the page). */
    fun pause() {
        paused = true
    }

    fun resume(nowMs: Long) {
        paused = false
        since = nowMs
    }

    private fun enterProbation() {
        probation = true
        held.clear()
    }
}
