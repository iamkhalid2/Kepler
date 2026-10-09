package app.kepler.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpPrimaryKey
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpSheet
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpType
import app.kepler.core.model.PaidSwitchStatus

/**
 * API keys: the password keyboard type keeps the IME from learning or suggesting the text, and
 * autocorrect and capitalisation are off so a pasted key is never altered.
 */
internal val SecretKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
    imeAction = ImeAction.Done,
)

internal fun hourlyText(centsPerHour: Double): String = moneyText(Math.round(centsPerHour).toInt())

/** The quiet free-to-paid notice. Draws nothing outside the 7 days before the switch and until the user agreed. */
@Composable
internal fun PaidNotice(paid: PaidUi, modifier: Modifier = Modifier) {
    val hourly = hourlyText(paid.centsPerHour)
    val text = when (val s = paid.switch) {
        is PaidSwitchStatus.SWITCHING_SOON -> pluralStringResource(R.plurals.paid_notice_soon, s.daysLeft, s.daysLeft, hourly)
        PaidSwitchStatus.PAID -> if (paid.confirmed) return else stringResource(R.string.paid_notice_ended, hourly)
        PaidSwitchStatus.FREE_ACTIVE -> return
    }
    Text(text, color = Kp.palette.text2, style = KpType.caption, modifier = modifier)
}

/**
 * What a paused job needs, in plain words, with the action that really resumes it.
 * [bookEstimateCents] is the estimate for this book, shown on the paid-voice confirmation.
 */
@Composable
internal fun PausedPanel(
    kind: PausedKind,
    paid: PaidUi,
    bookEstimateCents: Double?,
    fishVoice: Boolean,
    onConfirmPaid: () -> Unit,
    onResume: () -> Unit,
    onUseSystem: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val p = Kp.palette
    Column {
        Text(
            stringResource(
                if (kind == PausedKind.PaidConfirm) R.string.paid_confirm_body else pausedReasonRes(kindReason(kind)),
                *(if (kind == PausedKind.PaidConfirm) arrayOf(hourlyText(paid.centsPerHour)) else emptyArray()),
            ),
            color = p.text, style = KpType.body,
        )
        val detail = when (kind) {
            PausedKind.PaidConfirm -> buildList {
                bookEstimateCents?.let { add(stringResource(R.string.paid_this_book, moneyText(Math.round(it).toInt()))) }
                add(stringResource(R.string.paid_limits, moneyText(paid.bookCapCents), moneyText(paid.monthlyCapCents), moneyText(paid.spentMonthCents)))
            }.joinToString(" ")
            PausedKind.BookCap -> stringResource(R.string.paid_spent_book, moneyText(paid.spentBookCents), moneyText(paid.bookCapCents))
            PausedKind.MonthlyCap -> stringResource(R.string.paid_spent_month, moneyText(paid.spentMonthCents), moneyText(paid.monthlyCapCents))
            else -> null
        }
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, color = p.text2, style = KpType.caption)
        }
        Spacer(Modifier.height(12.dp))
        when (kind) {
            PausedKind.PaidConfirm -> KpPrimaryKey(stringResource(R.string.paid_continue), onConfirmPaid)
            PausedKind.Offline, PausedKind.System -> KpSecondaryKey(stringResource(R.string.resume), onResume, Modifier.fillMaxWidth())
            PausedKind.MonthlyCap -> if (onOpenSettings != null) KpTextLink(stringResource(R.string.change_limit), onOpenSettings)
            PausedKind.BookCap -> Unit
        }
        if (fishVoice && kind != PausedKind.Offline && kind != PausedKind.System) {
            Spacer(Modifier.height(8.dp))
            KpSecondaryKey(stringResource(R.string.use_system_voice), onUseSystem, Modifier.fillMaxWidth())
        }
    }
}

private fun kindReason(kind: PausedKind): String = when (kind) {
    PausedKind.PaidConfirm -> "paid_confirm"
    PausedKind.BookCap -> "book_cap"
    PausedKind.MonthlyCap -> "monthly_cap"
    PausedKind.Offline -> "offline"
    PausedKind.System -> "system_stop"
}

/** Confirmation before a book (and its narration, notes and cards) is removed. Used by the shelf and the hub. */
@Composable
internal fun RemoveBookSheet(title: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val p = Kp.palette
    KpSheet(onDismiss = onCancel) {
        Text(stringResource(R.string.remove_title, title), color = p.text, style = KpType.title)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.remove_body), color = p.text2, style = KpType.body)
        Spacer(Modifier.height(24.dp))
        KpSecondaryKey(stringResource(R.string.remove), onConfirm, Modifier.fillMaxWidth(), topEdge = p.negative)
        Spacer(Modifier.height(8.dp))
        KpTextLink(stringResource(R.string.keep), onCancel)
    }
}
