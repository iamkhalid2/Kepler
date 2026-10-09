package app.kepler.core.model

import java.time.Duration
import java.time.Instant

/**
 * Why a narration job is paused ([NarrationState.Paused.reason] carries the [code]).
 * Unknown codes (written by a newer build) map to null in [fromCode].
 */
enum class PauseReason(val code: String) {
    /** The monthly spend cap would be exceeded by the next request. */
    MONTHLY_CAP("monthly_cap"),

    /** The per-book spend cap would be exceeded by the next request. */
    BOOK_CAP("book_cap"),

    /** The free Fish model has ended; the user has not yet agreed to paid generation. */
    PAID_CONFIRM("paid_confirm"),

    /** Android stopped the background job (time limit, OEM kill); it resumes by itself. */
    SYSTEM_STOP("system_stop"),

    /** No network; resumes when the connection is back. */
    OFFLINE("offline"),
    ;

    companion object {
        fun fromCode(code: String): PauseReason? = entries.firstOrNull { it.code == code }
    }
}

/** Typed reason of a paused job, or null when unknown. */
val NarrationState.Paused.pauseReason: PauseReason? get() = PauseReason.fromCode(reason)

/** Spend limits that apply to paid narration (US cents). */
object NarrationLimits {
    /** Used when the user has not set a monthly cap (stored value 0). Paid narration is never uncapped. */
    const val DEFAULT_MONTHLY_CAP_CENTS = 500

    /** Hard ceiling for one book, independent of the monthly cap. */
    const val PER_BOOK_CAP_CENTS = 300
}

/** The Fish free model ends here; afterwards the paid model bills the user's own Fish account. */
object PaidSwitch {
    val FREE_UNTIL: Instant = Instant.parse("2026-11-30T23:59:59Z")

    /** The UI starts warning this long before [FREE_UNTIL]. */
    val WARN_BEFORE: Duration = Duration.ofDays(7)
}

/** Where the free-to-paid switch stands. Names mirror the product copy keys. */
sealed interface PaidSwitchStatus {
    /** Free model, nothing to say. */
    data object FREE_ACTIVE : PaidSwitchStatus

    /** Free model, but it ends within 7 days. [daysLeft] is whole days remaining, rounded up (1..7). */
    data class SWITCHING_SOON(val daysLeft: Int) : PaidSwitchStatus

    /** The paid model is in effect. */
    data object PAID : PaidSwitchStatus
}

fun paidSwitchStatus(now: Instant): PaidSwitchStatus = when {
    now.isAfter(PaidSwitch.FREE_UNTIL) -> PaidSwitchStatus.PAID
    now.isBefore(PaidSwitch.FREE_UNTIL.minus(PaidSwitch.WARN_BEFORE)) -> PaidSwitchStatus.FREE_ACTIVE
    else -> {
        val ms = Duration.between(now, PaidSwitch.FREE_UNTIL).toMillis()
        PaidSwitchStatus.SWITCHING_SOON(((ms + DAY_MS - 1) / DAY_MS).toInt().coerceIn(1, 7))
    }
}

private const val DAY_MS = 86_400_000L

/**
 * The narration controls the UI needs beyond [NarrationService]; implemented by the narration engine.
 * Spend figures are US cents.
 */
interface NarrationControls {
    /** Free / switching soon / paid, from the engine's clock. */
    fun paidSwitchStatus(): PaidSwitchStatus

    /** True once the user accepted paid generation after the switch date. Until then paid narration stays paused. */
    fun paidGenerationConfirmed(): Boolean

    /** Call after the user accepts the paid-model prompt; paused jobs continue on their next start. */
    fun confirmPaidGeneration()

    fun spentThisMonthCents(): Double
    fun spentOnBookCents(bookId: BookId): Double

    /** Monthly cap in force: the user's cap, or [NarrationLimits.DEFAULT_MONTHLY_CAP_CENTS] when unset. */
    suspend fun effectiveMonthlyCapCents(): Int

    /** [NarrationLimits.PER_BOOK_CAP_CENTS]. */
    fun perBookCapCents(): Int

    /**
     * Stops everything running for the book (whole-book job and just-in-time renders) and forgets its
     * in-memory and spend bookkeeping. Call before deleting the book; late results for it are discarded quietly.
     */
    fun cancelBook(bookId: BookId)
}

/**
 * Thrown by [NarrationService.ensureAhead] and [NarrationService.preview] when paid narration must not start
 * (see [PauseReason]). Nothing was spent. [message] is a neutral sentence that is safe to show.
 */
class NarrationBlockedException(val reason: PauseReason) : Exception(messageFor(reason)) {
    private companion object {
        fun messageFor(reason: PauseReason) = when (reason) {
            PauseReason.PAID_CONFIRM -> "The free narration voice has ended. Confirm paid narration to continue."
            PauseReason.MONTHLY_CAP -> "The monthly narration limit is reached."
            PauseReason.BOOK_CAP -> "This book reached its narration limit."
            PauseReason.SYSTEM_STOP -> "Narration was stopped by the system."
            PauseReason.OFFLINE -> "No connection."
        }
    }
}
