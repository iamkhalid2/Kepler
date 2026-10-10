package app.kepler.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.Card
import app.kepler.core.model.Rating
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewState(
    val loading: Boolean = true,
    val queue: List<Card> = emptyList(),
    val index: Int = 0,
    val flipped: Boolean = false,
    val hintShown: Boolean = false,
    val intervals: Map<Rating, Long> = emptyMap(),
    val reviewedThisSession: Int = 0,
    val againThisSession: Int = 0,
    val week: WeekProgress? = null,
    /** What the person typed before turning the card, shown beside the answer. Never saved. */
    val typed: String = "",
    val titles: Map<Long, String> = emptyMap(),
) {
    val current: Card? get() = queue.getOrNull(index)
    val left: Int get() = (queue.size - index).coerceAtLeast(0)
    val finished: Boolean get() = !loading && current == null
    /** Nothing was due when the screen opened. */
    val nothingDue: Boolean get() = finished && reviewedThisSession == 0
    val progress: Float get() = if (queue.isEmpty()) 0f else index.toFloat() / queue.size
    val hint: String? get() = current?.takeIf { hintShown && !flipped }?.let { Study.hintFor(it.back) }
    /** Cards in this sitting, including ones that came back after Again. */
    val total: Int get() = queue.size
    val source: String? get() = current?.let { titles[it.bookId] }
}

class ReviewViewModel(private val deps: StudyDeps) : ViewModel() {
    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The queue is a snapshot: grading a card must not reshuffle the cards still waiting.
            val due = deps.cards.dueCards(deps.clock(), 50).first()
            val titles = runCatching { deps.library.books.first().associate { it.id to it.title } }.getOrDefault(emptyMap())
            _state.update { it.copy(loading = false, queue = due, titles = titles) }
            refreshIntervals()
            refreshWeek()
        }
    }

    private fun refreshIntervals() = _state.update { s ->
        s.copy(intervals = s.current?.let { deps.scheduler.preview(it, deps.clock()) } ?: emptyMap())
    }

    private suspend fun refreshWeek() {
        val days = deps.reviewStats.reviewDays.first()
        val goal = deps.reviewStats.weeklyGoalDays.first()
        val today = java.time.Instant.ofEpochMilli(deps.clock()).atZone(deps.zone).toLocalDate()
        _state.update { it.copy(week = weekProgress(today, days, goal)) }
    }

    fun setTyped(v: String) = _state.update { it.copy(typed = v) }
    fun flip() = _state.update { if (it.current == null) it else it.copy(flipped = !it.flipped) }
    fun showHint() = _state.update { if (it.flipped) it else it.copy(hintShown = true) }

    fun grade(rating: Rating) {
        val s = _state.value
        val card = s.current ?: return
        if (!s.flipped) return // answer first: grades only exist on the back
        viewModelScope.launch {
            val now = deps.clock()
            val updated = deps.scheduler.review(card, rating, now)
            deps.cards.update(updated)
            deps.reviewStats.recordReviewDay(Study.dayKey(now, deps.zone))
            _state.update {
                val again = rating == Rating.AGAIN && Study.repeatsThisSession(updated.dueAt, now)
                it.copy(
                    queue = if (again) it.queue + updated else it.queue,
                    index = it.index + 1, flipped = false, hintShown = false, typed = "",
                    reviewedThisSession = it.reviewedThisSession + 1,
                    againThisSession = it.againThisSession + if (rating == Rating.AGAIN) 1 else 0,
                )
            }
            refreshIntervals()
            refreshWeek()
        }
    }
}
