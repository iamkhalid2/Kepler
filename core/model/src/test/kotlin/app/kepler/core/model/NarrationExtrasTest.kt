package app.kepler.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class NarrationExtrasTest {
    private fun at(s: String) = paidSwitchStatus(Instant.parse(s))

    @Test fun freeUntilThePreviousWeek() {
        assertEquals(PaidSwitchStatus.FREE_ACTIVE, at("2026-10-09T00:00:00Z"))
        assertEquals(PaidSwitchStatus.FREE_ACTIVE, at("2026-11-23T12:00:00Z"))
    }

    @Test fun switchingSoonCountsWholeDaysRoundedUp() {
        // 7 days before 2026-11-30T23:59:59Z
        assertEquals(PaidSwitchStatus.SWITCHING_SOON(7), at("2026-11-23T23:59:59Z"))
        assertEquals(PaidSwitchStatus.SWITCHING_SOON(7), at("2026-11-24T00:00:00Z"))
        assertEquals(PaidSwitchStatus.SWITCHING_SOON(2), at("2026-11-29T00:00:00Z"))
        assertEquals(PaidSwitchStatus.SWITCHING_SOON(1), at("2026-11-30T12:00:00Z"))
        assertEquals(PaidSwitchStatus.SWITCHING_SOON(1), at("2026-11-30T23:59:59Z"))
    }

    @Test fun paidAfterTheLastFreeSecond() {
        assertEquals(PaidSwitchStatus.PAID, at("2026-12-01T00:00:00Z"))
    }

    @Test fun pauseReasonCodesRoundTrip() {
        PauseReason.entries.forEach { assertEquals(it, PauseReason.fromCode(it.code)) }
        assertNull(PauseReason.fromCode("unknown"))
        assertEquals(PauseReason.BOOK_CAP, NarrationState.Paused(1, "book_cap").pauseReason)
    }

    @Test fun aiCacheKeysDoNotCollideAcrossBooks() {
        assertEquals("b12:x", AiCacheKeys.scope(12, "x"))
        assert(!AiCacheKeys.scope(120, "x").startsWith(AiCacheKeys.prefix(12)))
    }
}
