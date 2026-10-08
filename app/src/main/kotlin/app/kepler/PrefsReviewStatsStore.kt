package app.kepler

import android.content.Context
import app.kepler.core.model.RecallResult
import app.kepler.core.model.ReviewStatsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Review-habit state kept in a small private preferences file. The data is tiny and append-mostly. */
class PrefsReviewStatsStore(context: Context) : ReviewStatsStore {
    private val prefs = context.getSharedPreferences("review_stats", Context.MODE_PRIVATE)

    private val days = MutableStateFlow(prefs.getStringSet(KEY_DAYS, emptySet()).orEmpty().toSet())
    private val goal = MutableStateFlow(prefs.getInt(KEY_GOAL, 5))
    private val recallList = MutableStateFlow(decode(prefs.getString(KEY_RECALLS, null)))

    override val reviewDays: Flow<Set<String>> = days
    override val weeklyGoalDays: Flow<Int> = goal
    override val recalls: Flow<List<RecallResult>> = recallList

    override suspend fun recordReviewDay(day: String) = withContext(Dispatchers.IO) {
        days.update { it + day }
        prefs.edit().putStringSet(KEY_DAYS, days.value).apply()
    }

    override suspend fun setWeeklyGoalDays(days: Int) = withContext(Dispatchers.IO) {
        goal.value = days.coerceIn(1, 7)
        prefs.edit().putInt(KEY_GOAL, goal.value).apply()
    }

    override suspend fun addRecall(result: RecallResult) = withContext(Dispatchers.IO) {
        recallList.update { (it + result).takeLast(MAX_RECALLS) }
        prefs.edit().putString(KEY_RECALLS, encode(recallList.value)).apply()
    }

    private fun encode(list: List<RecallResult>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("b", it.bookId).put("s", it.sectionId).put("p", it.predicted)
                    .put("a", it.actual).put("t", it.at),
            )
        }
    }.toString()

    private fun decode(raw: String?): List<RecallResult> = runCatching {
        val a = JSONArray(raw ?: return emptyList())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            RecallResult(o.getLong("b"), o.getLong("s"), o.getInt("p"), o.getInt("a"), o.getLong("t"))
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_DAYS = "days"
        const val KEY_GOAL = "goal"
        const val KEY_RECALLS = "recalls"
        const val MAX_RECALLS = 500
    }
}
