package app.kepler.engine.srs

import app.kepler.core.model.Card
import app.kepler.core.model.Rating
import app.kepler.core.model.Scheduler
import one.felsen.fsrskt.fsrs6.Fsrs
import one.felsen.fsrskt.fsrs6.FsrsCalculator
import one.felsen.fsrskt.fsrs6.FsrsParameters
import one.felsen.fsrskt.fsrs6.FsrsRating
import one.felsen.fsrskt.fsrs6.FsrsState
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FSRS-6 scheduler with default parameters. FsrsKt supplies the memory model (stability/difficulty);
 * learning steps and interval derivation live here, so the grade buttons can show real next intervals.
 *
 * Card.state: 0 = new, 1 = learning, 2 = review, 3 = relearning.
 * Intervals are deterministic (no fuzz) so previews always match what [review] schedules.
 */
class FsrsScheduler(
    private val desiredRetention: Double = 0.9,
    parameters: List<Double> = FsrsParameters.defaultParameters,
    private val maxIntervalDays: Long = 36_500,
) : Scheduler {
    private val calculator = FsrsCalculator(Fsrs(parameters))
    private val decay = -parameters[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    override fun review(card: Card, rating: Rating, nowMs: Long): Card = outcomes(card, nowMs).getValue(rating)

    override fun preview(card: Card, nowMs: Long): Map<Rating, Long> =
        outcomes(card, nowMs).mapValues { (_, c) -> c.dueAt - nowMs }

    private fun outcomes(card: Card, nowMs: Long): Map<Rating, Card> {
        val isNew = card.state == STATE_NEW || card.lastReviewAt == null
        val prior = if (isNew) null else FsrsState(card.difficulty, card.stability)
        val elapsedDays = if (isNew) 0.0 else max(0.0, (nowMs - card.lastReviewAt!!).toDouble() / DAY_MS)
        val sameDay = elapsedDays < 1.0

        val memory = Rating.entries.associateWith { r ->
            calculator.review(prior, r.toFsrs(), elapsedDays, sameDay)
        }
        // Whole-day interval each rating would earn if the card is (or graduates to) review.
        val days = memory.mapValues { (_, m) -> intervalDays(m.stability) }.toMutableMap()
        if (card.state == STATE_REVIEW || card.state == STATE_RELEARNING || card.state == STATE_LEARNING) {
            // Keep the buttons strictly ordered: hard < good < easy.
            val hard = days.getValue(Rating.HARD)
            val good = max(days.getValue(Rating.GOOD), hard + 1).coerceAtMost(maxIntervalDays)
            val easy = max(days.getValue(Rating.EASY), good + 1).coerceAtMost(maxIntervalDays)
            days[Rating.GOOD] = good
            days[Rating.EASY] = easy
        }

        return Rating.entries.associateWith { r ->
            val m = memory.getValue(r)
            val (state, dueDelta) = next(card.state, r, days.getValue(r))
            card.copy(
                stability = m.stability, difficulty = m.difficulty,
                reps = card.reps + 1,
                lapses = card.lapses + if (card.state == STATE_REVIEW && r == Rating.AGAIN) 1 else 0,
                state = state, lastReviewAt = nowMs, dueAt = nowMs + dueDelta,
            )
        }
    }

    private fun next(state: Int, rating: Rating, graduatedDays: Long): Pair<Int, Long> = when (state) {
        STATE_REVIEW -> when (rating) {
            Rating.AGAIN -> STATE_RELEARNING to RELEARN_STEP_MS
            else -> STATE_REVIEW to graduatedDays * DAY_MS
        }
        STATE_RELEARNING -> when (rating) {
            Rating.AGAIN, Rating.HARD -> STATE_RELEARNING to RELEARN_STEP_MS
            else -> STATE_REVIEW to graduatedDays * DAY_MS
        }
        STATE_LEARNING -> when (rating) {
            Rating.AGAIN -> STATE_LEARNING to STEP_AGAIN_MS
            Rating.HARD -> STATE_LEARNING to STEP_HARD_MS
            else -> STATE_REVIEW to graduatedDays * DAY_MS
        }
        else -> when (rating) { // new
            Rating.AGAIN -> STATE_LEARNING to STEP_AGAIN_MS
            Rating.HARD -> STATE_LEARNING to STEP_HARD_MS
            Rating.GOOD -> STATE_LEARNING to STEP_GOOD_MS
            Rating.EASY -> STATE_REVIEW to graduatedDays * DAY_MS
        }
    }

    /** Days until retrievability falls to [desiredRetention]; equals stability at 0.9. */
    private fun intervalDays(stability: Double): Long {
        val days = stability / factor * (desiredRetention.pow(1.0 / decay) - 1.0)
        return days.roundToLong().coerceIn(1L, maxIntervalDays)
    }

    private fun Rating.toFsrs() = when (this) {
        Rating.AGAIN -> FsrsRating.AGAIN
        Rating.HARD -> FsrsRating.HARD
        Rating.GOOD -> FsrsRating.GOOD
        Rating.EASY -> FsrsRating.EASY
    }

    companion object {
        const val STATE_NEW = 0
        const val STATE_LEARNING = 1
        const val STATE_REVIEW = 2
        const val STATE_RELEARNING = 3
        private const val MIN_MS = 60_000L
        private const val DAY_MS = 86_400_000L
        private const val STEP_AGAIN_MS = 1 * MIN_MS
        private const val STEP_HARD_MS = 5 * MIN_MS
        private const val STEP_GOOD_MS = 10 * MIN_MS
        private const val RELEARN_STEP_MS = 10 * MIN_MS
    }
}
