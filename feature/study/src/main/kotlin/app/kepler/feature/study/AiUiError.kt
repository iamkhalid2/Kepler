package app.kepler.feature.study

import app.kepler.engine.ai.AiError
import kotlinx.coroutines.CancellationException

/** What the AI failure means for the person, independent of wording. */
enum class UiErrorKind { NO_KEY, BAD_KEY, BUDGET, CREDITS, RATE_LIMITED, OFFLINE, UNAVAILABLE, NOTHING_READ, NOT_REACHED, OTHER }

data class UiError(val kind: UiErrorKind, val retryable: Boolean)

fun Throwable.toUiError(): UiError = when (this) {
    is AiError.MissingKey -> UiError(UiErrorKind.NO_KEY, false)
    is AiError.Unauthorized -> UiError(UiErrorKind.BAD_KEY, false)
    is AiError.BudgetExhausted -> UiError(UiErrorKind.BUDGET, false)
    is AiError.InsufficientCredits -> UiError(UiErrorKind.CREDITS, false)
    is AiError.RateLimited -> UiError(UiErrorKind.RATE_LIMITED, true)
    is AiError.Network -> UiError(UiErrorKind.OFFLINE, true)
    is AiError.ModelUnavailable, is AiError.InvalidResponse -> UiError(UiErrorKind.UNAVAILABLE, true)
    is AiError.NothingRead -> UiError(UiErrorKind.NOTHING_READ, false)
    is AiError.SectionNotReached, is AiError.SectionUnavailable -> UiError(UiErrorKind.NOT_REACHED, false)
    else -> UiError(UiErrorKind.OTHER, true)
}

/** Loading, empty and error states every AI-backed surface shares. */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: UiError) : Load<Nothing>
}

/** Runs [block], mapping typed failures to [Load.Failed]; cancellation passes through. */
suspend fun <T> loading(block: suspend () -> T): Load<T> = try {
    Load.Ready(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Load.Failed(e.toUiError())
}
