package app.kepler.engine.srs

import app.kepler.core.model.Card
import app.kepler.core.model.CardType
import app.kepler.core.model.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsSchedulerTest {
    private val s = FsrsScheduler()
    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun newCard() = Card(
        1, 1, CardType.QA, 0, 0, "q", "a", null,
        dueAt = t0, stability = 0.0, difficulty = 0.0, reps = 0, lapses = 0, state = 0,
        lastReviewAt = null, createdAt = t0,
    )

    @Test fun newCardEasyGraduatesWithInitialStabilityOfEightDays() {
        // Default w3 = 8.2956 -> round -> 8 days; D0(easy) = w4 - e^(w5*3) + 1 = 6.4133 - 12.2 + 1, clamped to 1.
        val c = s.review(newCard(), Rating.EASY, t0)
        assertEquals(2, c.state)
        assertEquals(8 * day, c.dueAt - t0)
        assertEquals(8.2956, c.stability, 1e-9)
        assertEquals(1.0, c.difficulty, 1e-9)
    }

    @Test fun newCardGoodEntersLearningWithTenMinuteStep() {
        val c = s.review(newCard(), Rating.GOOD, t0)
        assertEquals(1, c.state)
        assertEquals(10 * 60_000L, c.dueAt - t0)
        assertEquals(2.3065, c.stability, 1e-9) // w2
        assertEquals(2.1184, c.difficulty, 1e-3) // w4 - e^(w5*2) + 1
        assertEquals(1, c.reps)
    }

    @Test fun newCardPreviewIntervals() {
        val p = s.preview(newCard(), t0)
        assertEquals(60_000L, p.getValue(Rating.AGAIN))
        assertEquals(5 * 60_000L, p.getValue(Rating.HARD))
        assertEquals(10 * 60_000L, p.getValue(Rating.GOOD))
        assertEquals(8 * day, p.getValue(Rating.EASY))
    }

    @Test fun learningGoodGraduatesToReview() {
        val learning = s.review(newCard(), Rating.GOOD, t0)
        val grad = s.review(learning, Rating.GOOD, learning.dueAt)
        assertEquals(2, grad.state)
        assertTrue(grad.dueAt - grad.lastReviewAt!! >= day)
    }

    @Test fun retrievabilityAtIntervalEqualsDesiredRetention() {
        // At retention 0.9 the interval in days equals stability (FSRS definition).
        val c = s.review(newCard(), Rating.EASY, t0)
        assertEquals(c.stability, (c.dueAt - t0).toDouble() / day, 0.5)
    }

    @Test fun reviewIntervalsAreStrictlyMonotonicAcrossRatings() {
        var card = s.review(s.review(newCard(), Rating.EASY, t0), Rating.GOOD, t0 + 8 * day)
        var now = card.dueAt
        repeat(6) {
            val p = s.preview(card, now)
            assertTrue(p.getValue(Rating.AGAIN) < p.getValue(Rating.HARD))
            assertTrue(p.getValue(Rating.HARD) < p.getValue(Rating.GOOD))
            assertTrue(p.getValue(Rating.GOOD) < p.getValue(Rating.EASY))
            card = s.review(card, Rating.GOOD, now)
            now = card.dueAt
        }
    }

    @Test fun repeatedGoodGrowsIntervals() {
        var card = s.review(s.review(newCard(), Rating.EASY, t0), Rating.GOOD, t0 + 8 * day)
        var prev = card.dueAt - card.lastReviewAt!!
        repeat(5) {
            val now = card.dueAt
            card = s.review(card, Rating.GOOD, now)
            val interval = card.dueAt - now
            assertTrue("interval $interval should exceed $prev", interval > prev)
            prev = interval
        }
    }

    @Test fun lapseMovesToRelearningAndShrinksStability() {
        val c1 = s.review(newCard(), Rating.EASY, t0)
        val c2 = s.review(c1, Rating.AGAIN, t0 + 8 * day)
        assertEquals(3, c2.state)
        assertEquals(1, c2.lapses)
        assertEquals(10 * 60_000L, c2.dueAt - (t0 + 8 * day))
        assertTrue(c2.stability < c1.stability)
        assertTrue(c2.difficulty > c1.difficulty || c2.difficulty == 10.0)
        val back = s.review(c2, Rating.GOOD, c2.dueAt)
        assertEquals(2, back.state)
    }

    @Test fun lateReviewEarnsLongerIntervalThanOnTime() {
        val c = s.review(s.review(newCard(), Rating.EASY, t0), Rating.GOOD, t0 + 8 * day)
        val onTime = s.review(c, Rating.GOOD, c.dueAt)
        val late = s.review(c, Rating.GOOD, c.dueAt + 10 * day)
        assertTrue(late.stability > onTime.stability)
    }

    @Test fun reviewMatchesPreview() {
        val c = s.review(newCard(), Rating.EASY, t0)
        val now = c.dueAt
        val p = s.preview(c, now)
        for (r in Rating.entries) assertEquals(p.getValue(r), s.review(c, r, now).dueAt - now)
    }
}
