package app.kepler.feature.library

import app.kepler.core.model.NarrationControls
import app.kepler.core.model.NarrationLimits
import app.kepler.core.model.PaidSwitchStatus
import app.kepler.core.model.PauseReason
import java.math.BigDecimal
import java.math.RoundingMode

/** Spend, caps and the free-to-paid switch as the narration screens show them. All cents are US cents. */
data class PaidUi(
    val switch: PaidSwitchStatus = PaidSwitchStatus.FREE_ACTIVE,
    val confirmed: Boolean = false,
    val spentMonthCents: Int = 0,
    val spentBookCents: Int = 0,
    val monthlyCapCents: Int = NarrationLimits.DEFAULT_MONTHLY_CAP_CENTS,
    val bookCapCents: Int = NarrationLimits.PER_BOOK_CAP_CENTS,
    val centsPerHour: Double = DEFAULT_PAID_CENTS_PER_HOUR,
) {
    /** True while the notice about the free voice ending should show. */
    val showsNotice: Boolean
        get() = switch is PaidSwitchStatus.SWITCHING_SOON || (switch == PaidSwitchStatus.PAID && !confirmed)
}

/** Reads the current figures from the engine. [bookId] = null leaves the per-book spend at 0. */
internal suspend fun readPaidUi(deps: LibraryDeps, bookId: Long?): PaidUi {
    val c: NarrationControls = deps.controls
    return PaidUi(
        switch = c.paidSwitchStatus(),
        confirmed = c.paidGenerationConfirmed(),
        spentMonthCents = c.spentThisMonthCents().toInt(),
        spentBookCents = bookId?.let { c.spentOnBookCents(it).toInt() } ?: 0,
        monthlyCapCents = c.effectiveMonthlyCapCents(),
        bookCapCents = c.perBookCapCents(),
        centsPerHour = deps.paidCentsPerHour(),
    )
}

/** What the user can do about a paused job. */
enum class PausedKind { PaidConfirm, BookCap, MonthlyCap, Offline, System }

internal fun pausedKind(reason: String): PausedKind = when (PauseReason.fromCode(reason)) {
    PauseReason.PAID_CONFIRM -> PausedKind.PaidConfirm
    PauseReason.BOOK_CAP -> PausedKind.BookCap
    PauseReason.MONTHLY_CAP -> PausedKind.MonthlyCap
    PauseReason.OFFLINE -> PausedKind.Offline
    PauseReason.SYSTEM_STOP, null -> PausedKind.System
}

/** Plain copy for a NarrationState.Paused reason code from the narration engine. */
internal fun pausedReasonRes(reason: String): Int = when (pausedKind(reason)) {
    PausedKind.PaidConfirm -> R.string.narration_paused_paid
    PausedKind.BookCap -> R.string.narration_paused_book_cap
    PausedKind.MonthlyCap -> R.string.narration_paused_cap
    PausedKind.Offline -> R.string.narration_paused_offline
    PausedKind.System -> R.string.narration_paused_system
}

/** "12", "12.5" or "$12.50" typed in the cap field to cents; null when it is not an amount. Blank = 0 (default). */
fun parseCents(text: String): Int? {
    val t = text.trim().removePrefix("$").trim()
    if (t.isEmpty()) return 0
    val d = t.replace(',', '.').toBigDecimalOrNull() ?: return null
    if (d.signum() < 0 || d > BigDecimal(10_000)) return null
    return d.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toInt()
}

fun moneyText(cents: Int): String = "$" + "%.2f".format(java.util.Locale.US, cents / 100.0)
