package app.betterbooks.core.model

import kotlinx.coroutines.flow.Flow

/** A predicted-vs-actual chapter recall, kept so the stats can show how well the user knows what they know. */
data class RecallResult(
    val bookId: BookId,
    val sectionId: Long,
    /** 0..100, what the user guessed before recalling. */
    val predicted: Int,
    /** 0..100, share of rubric items the user's recall covered. */
    val actual: Int,
    val at: Long,
)

/**
 * Review-habit state for the study UI. The integrator backs it with DataStore or Room.
 * Days are `yyyy-MM-dd` in device time. A week is Monday to Sunday; the weekly goal is a number of
 * review days, and up to two missed days per week are forgiven (computed, not stored).
 */
interface ReviewStatsStore {
    val reviewDays: Flow<Set<String>>
    /** Days per week the user aims to review; default 5. */
    val weeklyGoalDays: Flow<Int>
    suspend fun recordReviewDay(day: String)
    suspend fun setWeeklyGoalDays(days: Int)
    val recalls: Flow<List<RecallResult>>
    suspend fun addRecall(result: RecallResult)
}
