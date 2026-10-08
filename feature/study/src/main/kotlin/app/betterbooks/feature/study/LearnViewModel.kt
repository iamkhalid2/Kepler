package app.betterbooks.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.betterbooks.core.model.AiBudget
import app.betterbooks.core.model.AskAnswer
import app.betterbooks.core.model.Book
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.ConceptMap
import app.betterbooks.core.model.ConceptNode
import app.betterbooks.core.model.Section
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One entry in the Ask transcript. Questions are quiet, answers are book prose. */
sealed interface AskItem {
    data class Question(val text: String) : AskItem
    data class Answer(val answer: AskAnswer) : AskItem
    data class Failure(val question: String, val error: UiError) : AskItem
}

data class LearnState(
    val book: Book? = null,
    val sections: List<Section> = emptyList(),
    val frontier: Int = -1,
    val fence: FenceInfo = FenceInfo(false, null, null),
    val budget: AiBudget? = null,
    val transcript: List<AskItem> = emptyList(),
    val asking: Boolean = false,
    val recap: Load<String> = Load.Idle,
    val concepts: Load<ConceptMap> = Load.Idle,
    val positions: Map<String, MapPt> = emptyMap(),
    val pinned: Set<String> = emptySet(),
    val selectedNode: String? = null,
    val takeaways: Map<Long, Load<List<String>>> = emptyMap(),
) {
    val reached: List<Section> get() = reachedSections(sections, frontier)
    val studyMode: Boolean get() = Study.isStudyMode(book?.contentType)
    val node: ConceptNode? get() =
        (concepts as? Load.Ready)?.value?.nodes?.firstOrNull { it.id == selectedNode }
}

class LearnViewModel(private val bookId: BookId, private val deps: StudyDeps) : ViewModel() {
    private val _state = MutableStateFlow(LearnState())
    val state: StateFlow<LearnState> = _state.asStateFlow()

    init { viewModelScope.launch { refreshContext() } }

    private suspend fun refreshContext() {
        val book = deps.library.book(bookId).first()
        val sections = deps.spine.sections(bookId)
        val frontier = deps.positions.frontier(bookId)
        _state.update { it.copy(book = book, sections = sections, frontier = frontier, fence = fenceInfo(book?.contentType, sections, frontier)) }
        refreshBudget()
    }

    fun refreshBudget() {
        viewModelScope.launch {
            val b = try { deps.ai.budget() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
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
        _state.update { it.copy(asking = true, transcript = it.transcript + AskItem.Question(q)) }
        viewModelScope.launch {
            val r = loading { deps.ai.ask(bookId, q) }
            _state.update {
                it.copy(
                    asking = false,
                    transcript = it.transcript + when (r) {
                        is Load.Ready -> AskItem.Answer(r.value)
                        is Load.Failed -> AskItem.Failure(q, r.error)
                        else -> AskItem.Failure(q, UiError(UiErrorKind.OTHER, true))
                    },
                )
            }
            refreshBudget()
        }
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
        if (_state.value.recap is Load.Loading) return
        _state.update { it.copy(recap = Load.Loading) }
        viewModelScope.launch {
            val r = loading { deps.ai.recap(bookId) }
            _state.update { it.copy(recap = r) }
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
