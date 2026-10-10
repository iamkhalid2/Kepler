package app.kepler.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.AiBudget
import app.kepler.core.model.AskAnswer
import app.kepler.core.model.AskEvent
import app.kepler.core.model.Book
import app.kepler.core.model.BookId
import app.kepler.core.model.Citation
import app.kepler.core.model.ConceptMap
import app.kepler.core.model.ConceptNode
import app.kepler.core.model.Section
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One entry in the Ask transcript. Questions are quiet, answers are book prose. */
sealed interface AskItem {
    data class Question(val text: String) : AskItem
    data class Answer(val answer: AskAnswer, val question: String = "") : AskItem
    data class Failure(val question: String, val error: UiError) : AskItem
}

/** The answer being written right now. [text] grows token by token; [citations] fill in as they resolve. */
data class LiveAnswer(
    val question: String,
    val text: String = "",
    val citations: List<Citation> = emptyList(),
    /** `System.nanoTime()` when the question was sent, for the time-to-first-words measurement. */
    val startedNs: Long = 0L,
) {
    val started: Boolean get() = text.isNotEmpty()
}

data class LearnState(
    val book: Book? = null,
    val sections: List<Section> = emptyList(),
    val frontier: Int = -1,
    val fence: FenceInfo = FenceInfo(false, null, null),
    val budget: AiBudget? = null,
    val transcript: List<AskItem> = emptyList(),
    val live: LiveAnswer? = null,
    /** Recap text so far; [Load.Loading] until the first words, then [Load.Ready] while it keeps streaming. */
    val recap: Load<String> = Load.Idle,
    val recapStreaming: Boolean = false,
    val concepts: Load<ConceptMap> = Load.Idle,
    val positions: Map<String, MapPt> = emptyMap(),
    val pinned: Set<String> = emptySet(),
    val selectedNode: String? = null,
    val takeaways: Map<Long, Load<List<String>>> = emptyMap(),
) {
    val asking: Boolean get() = live != null
    val reached: List<Section> get() = reachedSections(sections, frontier)
    val studyMode: Boolean get() = Study.isStudyMode(book?.contentType)
    val node: ConceptNode? get() =
        (concepts as? Load.Ready)?.value?.nodes?.firstOrNull { it.id == selectedNode }
}

class LearnViewModel(private val bookId: BookId, private val deps: StudyDeps) : ViewModel() {
    private val _state = MutableStateFlow(LearnState())
    val state: StateFlow<LearnState> = _state.asStateFlow()
    private var askJob: Job? = null
    private var recapJob: Job? = null

    init { viewModelScope.launch { refreshContext() } }

    private suspend fun refreshContext() {
        val book = deps.library.book(bookId).first()
        val sections = deps.spine.sections(bookId)
        val frontier = deps.positions.frontier(bookId)
        _state.update { it.copy(book = book, sections = sections, frontier = frontier, fence = fenceInfo(book?.contentType, sections, frontier)) }
        // Concepts, Map and the recap read packs built in the background; start building them as soon as Learn opens.
        if (frontier >= 0) deps.prefetch?.prefetch(bookId, frontier)
        refreshBudget()
    }

    fun refreshBudget() {
        viewModelScope.launch {
            val b = try { deps.ai.budget() } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
            _state.update { it.copy(budget = b ?: it.budget) }
        }
    }

    /** Suggested questions: generic prompts that work on any book, shaped by Study or Light mode. */
    fun suggestions(): List<SuggestedQuestion> =
        if (state.value.studyMode) listOf(SuggestedQuestion.MainArgument, SuggestedQuestion.KeyTerms, SuggestedQuestion.LastChapter)
        else listOf(SuggestedQuestion.WhoIsWho, SuggestedQuestion.WhatHappened, SuggestedQuestion.LastChapter)

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.asking) return
        _state.update { it.copy(live = LiveAnswer(q, startedNs = System.nanoTime()), transcript = it.transcript + AskItem.Question(q)) }
        askJob = viewModelScope.launch {
            var final: AskAnswer? = null
            var failure: UiError? = null
            deps.streaming.askStream(bookId, q).catch { failure = it.toUiError() }.collect { e ->
                when (e) {
                    is AskEvent.Delta -> _state.update { s -> s.live?.let { s.copy(live = it.copy(text = it.text + e.text)) } ?: s }
                    is AskEvent.Citations -> _state.update { s -> s.live?.let { s.copy(live = it.copy(citations = e.citations)) } ?: s }
                    is AskEvent.Done -> final = e.answer
                    is AskEvent.Failed -> failure = e.error.toUiError()
                }
            }
            finishAsk(q, final, failure)
        }
    }

    private fun finishAsk(q: String, final: AskAnswer?, failure: UiError?) {
        _state.update { s ->
            val live = s.live
            val partial = live?.takeIf { it.text.isNotBlank() }?.let { AskAnswer(it.text.trim(), it.citations) }
            val answer = final ?: partial
            val items = buildList {
                if (answer != null) add(AskItem.Answer(answer, q))
                if (final == null && failure != null) add(AskItem.Failure(q, failure))
            }
            s.copy(live = null, transcript = s.transcript + items)
        }
        refreshBudget()
    }

    /** Stops the answer where it is; what was written stays. */
    fun stop() {
        val job = askJob ?: return
        val live = _state.value.live ?: return
        job.cancel()
        askJob = null
        finishAsk(live.question, null, null)
    }

    /** Drops the failed attempt (and its question line) and asks again. */
    fun retry(failure: AskItem.Failure) {
        _state.update { s ->
            val i = s.transcript.indexOfFirst { it === failure }
            if (i < 0) s else s.copy(transcript = s.transcript.filterIndexed { j, _ -> j != i && !(j == i - 1 && s.transcript[j] is AskItem.Question) })
        }
        ask(failure.question)
    }

    fun loadRecap(force: Boolean = false) {
        if (!force && _state.value.recap is Load.Ready) return
        if (recapJob?.isActive == true) return
        _state.update { it.copy(recap = Load.Loading, recapStreaming = true) }
        recapJob = viewModelScope.launch {
            var text = ""
            var failure: UiError? = null
            deps.streaming.recapStream(bookId).catch { failure = it.toUiError() }.collect { d ->
                text += d
                _state.update { it.copy(recap = Load.Ready(text)) }
            }
            _state.update {
                it.copy(
                    recapStreaming = false,
                    recap = if (text.isBlank() && failure != null) Load.Failed(failure!!) else Load.Ready(text),
                )
            }
            refreshBudget()
        }
    }

    fun loadConcepts(force: Boolean = false) {
        if (!force && _state.value.concepts is Load.Ready) return
        if (_state.value.concepts is Load.Loading) return
        _state.update { it.copy(concepts = Load.Loading) }
        viewModelScope.launch {
            val r = loading { deps.ai.concepts(bookId) }
            _state.update { s ->
                val pos = (r as? Load.Ready)?.value?.let { ConceptLayout.layout(it, s.positions, s.pinned) } ?: s.positions
                s.copy(concepts = r, positions = pos)
            }
            refreshBudget()
        }
    }

    fun loadTakeaways(sectionId: Long, force: Boolean = false) {
        val cur = _state.value.takeaways[sectionId]
        if (cur is Load.Loading || (!force && cur is Load.Ready)) return
        _state.update { it.copy(takeaways = it.takeaways + (sectionId to Load.Loading)) }
        viewModelScope.launch {
            val r = loading { deps.ai.takeaways(bookId, sectionId) }
            _state.update { it.copy(takeaways = it.takeaways + (sectionId to r)) }
            refreshBudget()
        }
    }

    // ---- map ----
    fun selectNode(id: String?) = _state.update { it.copy(selectedNode = id) }

    fun moveNode(id: String, x: Float, y: Float) = _state.update {
        it.copy(
            positions = it.positions + (id to MapPt(x.coerceIn(ConceptLayout.MARGIN, 1 - ConceptLayout.MARGIN), y.coerceIn(ConceptLayout.MARGIN, 1 - ConceptLayout.MARGIN))),
            pinned = it.pinned + id,
        )
    }

    fun togglePin(id: String) = _state.update { it.copy(pinned = if (id in it.pinned) it.pinned - id else it.pinned + id) }

    /** Relaxes every unpinned node again. */
    fun relayout() = _state.update { s ->
        val map = (s.concepts as? Load.Ready)?.value ?: return@update s
        s.copy(positions = ConceptLayout.layout(map, s.positions, s.pinned))
    }
}

enum class SuggestedQuestion { MainArgument, KeyTerms, LastChapter, WhoIsWho, WhatHappened }
