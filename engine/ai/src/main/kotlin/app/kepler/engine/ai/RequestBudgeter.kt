package app.kepler.engine.ai

import app.kepler.core.model.AiBudget
import app.kepler.core.model.AiStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Priority { INTERACTIVE, PREFETCH, BACKGROUND }

/**
 * Spends OpenRouter requests. Per minute (sliding window, a few slots always left for INTERACTIVE) and per day
 * (persisted in [AiStore], day = local yyyy-MM-dd). BACKGROUND never touches the last 10% of the day and runs
 * at most one request per [backgroundSpacingMs]; PREFETCH stops 5% short so the user can always ask something.
 */
class RequestBudgeter(
    private val store: AiStore,
    private val provider: String = "openrouter",
    private val perMinute: Int = 15,
    dailyLimit: Int = FREE_DAILY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val interactiveReservePerMinute: Int = 3,
    private val backgroundSpacingMs: Long = 4_000,
    private val maxInteractiveWaitMs: Long = 20_000,
) {
    private val mutex = Mutex()
    private val window = ArrayDeque<Long>()
    private var loadedDay: String? = null
    private var usedToday = 0
    private var lastBackgroundAt = 0L

    @Volatile var dailyLimit: Int = dailyLimit
        private set

    /** Free tier = no credits ever bought (50/day); otherwise 1000/day. */
    fun setTier(freeTier: Boolean) { dailyLimit = if (freeTier) FREE_DAILY else PAID_DAILY }

    suspend fun remainingToday(): Int = mutex.withLock {
        syncDay(clock())
        (dailyLimit - usedToday).coerceAtLeast(0)
    }

    /** Waits for a slot (or throws [AiError]) and records the request. Call once per HTTP completion attempt. */
    suspend fun acquire(priority: Priority) {
        while (true) {
            val wait = mutex.withLock {
                val now = clock()
                syncDay(now)
                val share = when (priority) {
                    Priority.INTERACTIVE -> 1.0
                    Priority.PREFETCH -> 0.95
                    Priority.BACKGROUND -> 0.90
                }
                val allowed = if (priority == Priority.INTERACTIVE) dailyLimit else (dailyLimit * share).toInt()
                if (usedToday >= allowed) throw AiError.BudgetExhausted(dailyLimit, backgroundOnly = priority != Priority.INTERACTIVE && usedToday < dailyLimit)
                while (window.isNotEmpty() && now - window.first() >= 60_000) window.removeFirst()
                val cap = if (priority == Priority.INTERACTIVE) perMinute else (perMinute - interactiveReservePerMinute).coerceAtLeast(1)
                var w = if (window.size >= cap) window[window.size - cap] + 60_000 - now else 0L
                if (priority == Priority.BACKGROUND) w = maxOf(w, lastBackgroundAt + backgroundSpacingMs - now)
                if (w <= 0) {
                    window.addLast(now)
                    usedToday++
                    if (priority == Priority.BACKGROUND) lastBackgroundAt = now
                    store.addRequests(loadedDay!!, provider, 1)
                    return
                }
                w
            }
            if (priority == Priority.INTERACTIVE && wait > maxInteractiveWaitMs) {
                throw AiError.RateLimited(((wait + 999) / 1000).toInt())
            }
            sleeper(wait)
        }
    }

    /** The provider says today's quota is gone (e.g. a 429 naming the daily limit): stop spending. */
    suspend fun markExhausted() = mutex.withLock {
        syncDay(clock())
        if (usedToday < dailyLimit) {
            store.addRequests(loadedDay!!, provider, dailyLimit - usedToday)
            usedToday = dailyLimit
        }
    }

    suspend fun budget(model: String?): AiBudget = AiBudget(remainingToday(), dailyLimit, dailyLimit <= FREE_DAILY, model)

    private suspend fun syncDay(now: Long) {
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).toString()
        if (day != loadedDay) {
            loadedDay = day
            usedToday = store.requestsOn(day, provider)
        }
    }

    companion object {
        const val FREE_DAILY = 50
        const val PAID_DAILY = 1000
    }
}
