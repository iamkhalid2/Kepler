package app.kepler.engine.tts

import app.kepler.core.model.NarrationBlockedException
import app.kepler.core.model.PauseReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpendLedgerTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("ledger_test", 0)
    private var now = Instant.parse("2026-12-05T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    @After fun cleanUp() {
        prefs.edit().clear().commit()
    }

    private fun ledger(cap: Int, confirmed: Boolean = true) =
        SpendLedger(prefs, clock) { cap }.also { if (confirmed) it.confirm() }

    private suspend fun tryReserve(l: SpendLedger, book: Long?, cents: Double): SpendLedger.Reservation? =
        try { l.reserve(book, cents) } catch (e: NarrationBlockedException) { null }

    @Test fun parallelReservationsNeverOvershootTheMonthlyCap() = runBlocking {
        val l = ledger(cap = 100)
        val granted = (1..40).map { i -> async(Dispatchers.Default) { tryReserve(l, i.toLong(), 30.0) } }.awaitAll().filterNotNull()
        assertEquals(3, granted.size)
        granted.forEach { it.commit() }
        assertEquals(90.0, l.spentThisMonthCents(), 1e-9)
        assertEquals(3, l.requestsThisMonth())
    }

    @Test fun parallelReservationsNeverOvershootThePerBookCap() = runBlocking {
        val l = ledger(cap = 10_000)
        val granted = (1..40).map { async(Dispatchers.Default) { tryReserve(l, 7, 100.0) } }.awaitAll().filterNotNull()
        assertEquals(3, granted.size)
        assertEquals(PauseReason.BOOK_CAP, runCatching { l.reserve(7, 100.0) }.exceptionOrNull().let { (it as NarrationBlockedException).reason })
        // another book is unaffected
        assertTrue(tryReserve(l, 8, 100.0) != null)
    }

    @Test fun releasedReservationsFreeTheirBudgetAndCommitIsFinal() = runBlocking {
        val l = ledger(cap = 100)
        val a = l.reserve(1, 60.0)
        assertEquals(null, tryReserve(l, 1, 60.0))
        a.release()
        val b = l.reserve(1, 60.0)
        b.commit()
        b.release() // too late to undo a spend
        assertEquals(60.0, l.spentThisMonthCents(), 1e-9)
        assertEquals(60.0, l.spentOnBookCents(1), 1e-9)
    }

    @Test fun freeRequestsAreCountedButNeverBlocked() = runBlocking {
        val l = ledger(cap = 1, confirmed = false)
        l.reserve(1, 0.0).commit()
        assertEquals(1, l.requestsThisMonth())
        assertEquals(0.0, l.spentThisMonthCents(), 0.0)
    }

    @Test fun monthRollsOverAtUtcMidnight() = runBlocking {
        val l = ledger(cap = 100)
        now = Instant.parse("2026-12-31T23:59:59Z")
        l.reserve(1, 40.0).commit()
        now = Instant.parse("2027-01-01T00:00:01Z")
        assertEquals(0.0, l.spentThisMonthCents(), 0.0)
        assertEquals(40.0, l.spentOnBookCents(1), 1e-9)
    }
}
