package app.kepler.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.AnswerCheck
import app.kepler.core.model.AskEvent
import app.kepler.core.model.BookId
import app.kepler.core.model.CardSuggestion
import app.kepler.core.model.CardType
import app.kepler.core.model.CheckIn
import app.kepler.core.model.RecallResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Explain

enum class ExplainPhase { LOADING_MODE, PREDICT, REVEALED }

data class ExplainState(
    val phase: ExplainPhase = ExplainPhase.LOADING_MODE,
    val studyMode: Boolean = false,
    val prediction: String = "",
    /** Loading until the first words, then Ready with the text so far while [streaming] stays true. */
    val explanation: Load<String> = Load.Idle,
    val streaming: Boolean = false,
)

/** Streams text deltas into a [Load]; the terminal answer replaces what was streamed. */
internal suspend fun collectProse(
    flow: kotlinx.coroutines.flow.Flow<AskEvent>,
    onText: (String) -> Unit,
): Load<String> {
    var text = ""
    var final: String? = null
    var failure: UiError? = null
    flow.catch { failure = it.toUiError() }.collect { e ->
        when (e) {
            is AskEvent.Delta -> { text += e.text; onText(text) }
            is AskEvent.Done -> final = e.answer.text
            is AskEvent.Failed -> failure = e.error.toUiError()
            is AskEvent.Citations -> Unit
        }
    }
    val shown = (final ?: text).trim()
    return if (shown.isBlank() && failure != null) Load.Failed(failure!!) else Load.Ready(shown)
}

/** Study mode asks "why might this be true?" before the explanation is revealed; Light mode shows it straight away. */
class ExplainViewModel(
    private val bookId: BookId, private val ordinal: Int, private val text: String, private val deps: StudyDeps,
) : ViewModel() {
    private val _state = MutableStateFlow(ExplainState())
    val state: StateFlow<ExplainState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val study = Study.isStudyMode(deps.library.book(bookId).first()?.contentType)
            if (study) _state.update { it.copy(studyMode = true, phase = ExplainPhase.PREDICT) }
            else { _state.update { it.copy(phase = ExplainPhase.REVEALED) }; load() }
        }
    }

    fun setPrediction(v: String) = _state.update { it.copy(prediction = v) }

    /** Reveal is always one tap away, answered or not. */
    fun reveal() {
        if (_state.value.phase == ExplainPhase.REVEALED) return
        _state.update { it.copy(phase = ExplainPhase.REVEALED) }
        load()
    }

    fun load() {
        _state.update { it.copy(explanation = Load.Loading, streaming = true) }
        viewModelScope.launch {
            val r = collectProse(deps.streaming.explainStream(bookId, ordinal, text)) { t ->
                _state.update { it.copy(explanation = Load.Ready(t)) }
            }
            _state.update { it.copy(explanation = r, streaming = false) }
        }
    }
}

// ---------------------------------------------------------------- Define

data class DefineState(val definition: Load<String> = Load.Loading, val streaming: Boolean = true, val savedAsWord: Boolean = false)

class DefineViewModel(
    private val bookId: BookId, private val ordinal: Int, private val term: String, private val deps: StudyDeps,
) : ViewModel() {
    private val _state = MutableStateFlow(DefineState())
    val state: StateFlow<DefineState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(definition = Load.Loading, streaming = true) }
        viewModelScope.launch {
            val r = collectProse(deps.streaming.defineStream(bookId, ordinal, term)) { t ->
                _state.update { it.copy(definition = Load.Ready(t)) }
            }
            _state.update { it.copy(definition = r, streaming = false) }
            if (r is Load.Ready) saveWord(r.value)
        }
    }

    /** Looked-up words become VOCAB cards on their own, within the daily new-card cap, once per word. */
    private suspend fun saveWord(definition: String) {
        val now = deps.clock()
        val all = deps.cards.cards(null).first()
        val word = term.trim()
        if (all.any { it.bookId == bookId && it.type == CardType.VOCAB && it.front.equals(word, ignoreCase = true) }) {
            _state.update { it.copy(savedAsWord = true) }; return
        }
        if (Study.newCardsLeft(all, now, deps.zone) == 0) return
        val context = deps.spine.sentence(bookId, ordinal)?.text
        val back = if (context.isNullOrBlank()) definition else "$definition\n\n— $context"
        deps.cards.add(Study.newCard(bookId, CardType.VOCAB, ordinal, ordinal, word, back, null, now))
        _state.update { it.copy(savedAsWord = true) }
    }
}

// ---------------------------------------------------------------- Card from selection

data class CardDraftState(
    val question: String = "",
    val answer: String = "",
    val suggestions: Load<List<CardSuggestion>> = Load.Idle,
    val chosen: CardSuggestion? = null,
    val check: Load<AnswerCheck> = Load.Idle,
    val newLeft: Int = Study.DAILY_NEW_CARD_CAP,
    val saved: Boolean = false,
) {
    val capReached get() = newLeft <= 0
    val canCheck get() = question.isNotBlank() && answer.isNotBlank() && check !is Load.Loading
    val canSave get() = question.isNotBlank() && answer.isNotBlank() && !capReached && !saved
}

class CardDraftViewModel(
    private val bookId: BookId, private val ordinal: Int, private val text: String,
    initialQuestion: String?, private val deps: StudyDeps,
) : ViewModel() {
    private val _state = MutableStateFlow(CardDraftState(question = initialQuestion.orEmpty()))
    val state: StateFlow<CardDraftState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val all = deps.cards.cards(null).first()
            _state.update { it.copy(newLeft = Study.newCardsLeft(all, deps.clock(), deps.zone)) }
        }
        if (initialQuestion.isNullOrBlank()) suggest()
    }

    fun suggest() {
        _state.update { it.copy(suggestions = Load.Loading) }
        viewModelScope.launch {
            val r = loading {
                val sentence = deps.spine.sentence(bookId, ordinal) ?: throw IllegalStateException("no sentence")
                val all = deps.ai.suggestCards(bookId, sentence.sectionId)
                val near = all.filter { ordinal in it.sourceStart..it.sourceEnd }.ifEmpty { all }
                near.take(3)
            }
            _state.update { it.copy(suggestions = r) }
        }
    }

    fun choose(s: CardSuggestion) = _state.update { it.copy(question = s.question, chosen = s, check = Load.Idle) }
    fun setQuestion(v: String) = _state.update { it.copy(question = v, check = Load.Idle) }
    fun setAnswer(v: String) = _state.update { it.copy(answer = v, check = Load.Idle) }

    /** The back of the card is the source: the suggestion's reference answer, or the passage itself. */
    private fun reference(): String = _state.value.chosen?.answerHint?.takeIf { it.isNotBlank() } ?: text

    fun check() {
        val s = _state.value
        if (!s.canCheck) return
        _state.update { it.copy(check = Load.Loading) }
        viewModelScope.launch {
            val r = loading { deps.ai.checkAnswer(s.question, s.answer, text) }
            _state.update { it.copy(check = r) }
        }
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            val now = deps.clock()
            val all = deps.cards.cards(null).first()
            if (Study.newCardsLeft(all, now, deps.zone) == 0) { _state.update { it.copy(newLeft = 0) }; return@launch }
            val start = s.chosen?.sourceStart ?: ordinal
            val end = s.chosen?.sourceEnd ?: ordinal
            deps.cards.add(Study.newCard(bookId, CardType.QA, start, end, s.question, reference(), s.answer, now))
            _state.update { it.copy(saved = true) }
            onSaved()
        }
    }
}

// ---------------------------------------------------------------- Check-in and chapter recall

/** Which rubric lines the model said the answer covered. Matching is forgiving about wording. */
fun matchRubric(rubric: List<String>, covered: List<String>): List<Boolean> {
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    val cov = covered.map(::norm).filter { it.isNotEmpty() }
    return rubric.map { r ->
        val n = norm(r)
        n.isNotEmpty() && cov.any { c -> c == n || c.contains(n) || n.contains(c) }
    }
}

enum class CheckInPhase { ANSWER, REVEALED }

data class CheckInState(
    val checkIn: Load<CheckIn> = Load.Loading,
    val answer: String = "",
    val phase: CheckInPhase = CheckInPhase.ANSWER,
    val ticks: List<Boolean> = emptyList(),
    val checking: Boolean = false,
)

class CheckInViewModel(private val bookId: BookId, private val sectionId: Long, private val deps: StudyDeps) : ViewModel() {
    private val _state = MutableStateFlow(CheckInState())
    val state: StateFlow<CheckInState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(checkIn = Load.Loading) }
        viewModelScope.launch { val r = loading { deps.ai.checkIn(bookId, sectionId) }; _state.update { it.copy(checkIn = r) } }
    }

    fun setAnswer(v: String) = _state.update { it.copy(answer = v) }

    /** Shows the gist; when something was typed, ticks the rubric lines the answer covered. */
    fun reveal() {
        val s = _state.value
        val ci = (s.checkIn as? Load.Ready)?.value ?: return
        if (s.phase == CheckInPhase.REVEALED) return
        if (s.answer.isBlank()) {
            _state.update { it.copy(phase = CheckInPhase.REVEALED, ticks = List(ci.rubric.size) { false }) }
            return
        }
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val r = loading { deps.ai.checkAnswer(ci.prompt, s.answer, ci.gist + "\n" + ci.rubric.joinToString("\n")) }
            val ticks = (r as? Load.Ready)?.value?.let { matchRubric(ci.rubric, it.covered) } ?: List(ci.rubric.size) { false }
            _state.update { it.copy(phase = CheckInPhase.REVEALED, ticks = ticks, checking = false) }
        }
    }
}

enum class RecallPhase { PREDICT, RECALL, RESULT }

data class RecallState(
    val checkIn: Load<CheckIn> = Load.Loading,
    val phase: RecallPhase = RecallPhase.PREDICT,
    val predicted: Int = 50,
    val answer: String = "",
    val ticks: List<Boolean> = emptyList(),
    val actual: Int = 0,
    val checking: Boolean = false,
    val checkFailed: Boolean = false,
)

/** Chapter recall: predict your score, recall in your own words, see predicted against actual. */
class RecallViewModel(private val bookId: BookId, private val sectionId: Long, private val deps: StudyDeps) : ViewModel() {
    private val _state = MutableStateFlow(RecallState())
    val state: StateFlow<RecallState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(checkIn = Load.Loading) }
        viewModelScope.launch { val r = loading { deps.ai.checkIn(bookId, sectionId) }; _state.update { it.copy(checkIn = r) } }
    }

    fun setPredicted(v: Int) = _state.update { it.copy(predicted = v.coerceIn(0, 100)) }
    fun setAnswer(v: String) = _state.update { it.copy(answer = v) }
    fun startRecall() = _state.update { it.copy(phase = RecallPhase.RECALL) }

    fun finish() {
        val s = _state.value
        val ci = (s.checkIn as? Load.Ready)?.value ?: return
        if (s.checking) return
        _state.update { it.copy(checking = true, checkFailed = false) }
        viewModelScope.launch {
            val r = if (s.answer.isBlank()) Load.Ready(AnswerCheck("", emptyList(), ci.rubric))
            else loading { deps.ai.checkAnswer("What do you remember of this chapter?", s.answer, ci.gist + "\n" + ci.rubric.joinToString("\n")) }
            if (r is Load.Failed) { _state.update { it.copy(checking = false, checkFailed = true) }; return@launch }
            val ticks = matchRubric(ci.rubric, (r as Load.Ready).value.covered)
            val actual = if (ticks.isEmpty()) 0 else Math.round(100f * ticks.count { it } / ticks.size)
            deps.reviewStats.addRecall(RecallResult(bookId, sectionId, s.predicted, actual, deps.clock()))
            _state.update { it.copy(phase = RecallPhase.RESULT, ticks = ticks, actual = actual, checking = false) }
        }
    }
}
