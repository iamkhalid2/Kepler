package app.kepler.engine.tts

import android.content.SharedPreferences
import app.kepler.core.model.BookId
import app.kepler.core.model.NarrationBlockedException
import app.kepler.core.model.NarrationLimits
import app.kepler.core.model.PauseReason
import app.kepler.core.model.PaidSwitch
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * Paid-narration bookkeeping: what was spent (month, book), what is promised to requests that are on their
 * way, and whether the user agreed to paid generation.
 *
 * A request first [reserve]s its price under one lock together with the cap checks, so parallel chunks can
 * never overshoot the monthly or the per-book cap. The reservation becomes spend when the service accepts
 * the request ([Reservation.commit]) and is dropped when the request fails before that ([Reservation.release]).
 * Amounts are kept in micro-cents (1/1000 of a cent).
 */
internal class SpendLedger(
    private val prefs: SharedPreferences,
    private val clock: Clock,
    private val monthlyCapCents: suspend () -> Int,
) {
    private val lock = Any()
    private var reservedMicro = 0L
    private val reservedByBook = HashMap<BookId, Long>()

    fun spentThisMonthCents(): Double = prefs.getLong(monthKey(), 0) / MICRO
    fun spentOnBookCents(bookId: BookId): Double = prefs.getLong(bookKey(bookId), 0) / MICRO
    fun requestsThisMonth(): Int = prefs.getInt(requestsKey(), 0)

    /** True once [confirm] was called after the free period ended. A confirmation given earlier does not count. */
    fun paidConfirmed(): Boolean = prefs.getLong(CONFIRMED_AT, 0) > PaidSwitch.FREE_UNTIL.toEpochMilli()

    fun confirm() {
        prefs.edit().putLong(CONFIRMED_AT, clock.millis()).apply()
    }

    fun forgetBook(bookId: BookId) {
        synchronized(lock) {
            reservedMicro -= reservedByBook.remove(bookId) ?: 0
            prefs.edit().remove(bookKey(bookId)).apply()
        }
    }

    /** Why paid synthesis of [nextCents] must not start now, or null. A free provider ([paid] false) is never blocked. */
    suspend fun blockReason(bookId: BookId?, nextCents: Double, paid: Boolean): PauseReason? =
        check(bookId, (nextCents * MICRO).roundToLong(), monthlyCap(), paid)

    /** A free provider (price 0) is not checked. Throws [NarrationBlockedException] when the request must not start. */
    suspend fun reserve(bookId: BookId?, priceCents: Double): Reservation {
        if (priceCents <= 0) return Reservation(bookId, 0)
        val micro = (priceCents * MICRO).roundToLong()
        val cap = monthlyCap()
        synchronized(lock) {
            check(bookId, micro, cap, paid = true)?.let { throw NarrationBlockedException(it) }
            reservedMicro += micro
            if (bookId != null) reservedByBook[bookId] = (reservedByBook[bookId] ?: 0) + micro
        }
        return Reservation(bookId, micro)
    }

    private suspend fun monthlyCap(): Int = monthlyCapCents().takeIf { it > 0 } ?: NarrationLimits.DEFAULT_MONTHLY_CAP_CENTS

    private fun check(bookId: BookId?, micro: Long, capCents: Int, paid: Boolean): PauseReason? = synchronized(lock) {
        if (!paid) return@synchronized null
        if (!paidConfirmed()) return@synchronized PauseReason.PAID_CONFIRM
        if (prefs.getLong(monthKey(), 0) + reservedMicro + micro > capCents * MICRO.toLong()) return@synchronized PauseReason.MONTHLY_CAP
        if (bookId != null) {
            val booked = prefs.getLong(bookKey(bookId), 0) + (reservedByBook[bookId] ?: 0) + micro
            if (booked > NarrationLimits.PER_BOOK_CAP_CENTS * MICRO.toLong()) return@synchronized PauseReason.BOOK_CAP
        }
        null
    }

    /** One promise of spend. [commit] and [release] are idempotent and mutually exclusive. */
    inner class Reservation internal constructor(private val bookId: BookId?, private val micro: Long) {
        private var done = false

        /** The service accepted the request: its price is spent, and the request counts. */
        fun commit() = settle(spend = true)

        /** The request failed before the service accepted it: nothing was charged. */
        fun release() = settle(spend = false)

        private fun settle(spend: Boolean) = synchronized(lock) {
            if (done) return@synchronized
            done = true
            reservedMicro -= micro
            if (bookId != null && micro > 0) {
                val left = (reservedByBook[bookId] ?: 0) - micro
                if (left > 0) reservedByBook[bookId] = left else reservedByBook.remove(bookId)
            }
            if (spend) {
                val e = prefs.edit().putInt(requestsKey(), prefs.getInt(requestsKey(), 0) + 1)
                if (micro > 0) {
                    e.putLong(monthKey(), prefs.getLong(monthKey(), 0) + micro)
                    if (bookId != null) e.putLong(bookKey(bookId), prefs.getLong(bookKey(bookId), 0) + micro)
                }
                e.apply()
            }
        }
    }

    private fun month() = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC).format(clock.instant())
    private fun monthKey() = SPEND_PREFIX + month()
    private fun requestsKey() = REQUESTS_PREFIX + month()
    private fun bookKey(bookId: BookId) = BOOK_SPEND_PREFIX + bookId

    private companion object {
        const val MICRO = 1000.0
        const val SPEND_PREFIX = "spend_"
        const val REQUESTS_PREFIX = "requests_"
        const val BOOK_SPEND_PREFIX = "bookspend_"
        const val CONFIRMED_AT = "paid_confirmed_at"
    }
}
