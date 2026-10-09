package app.kepler.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// ================= Narration (implemented by :engine:tts) =================

data class NarrationConfig(val voice: VoiceRef, val speed: Float = 1f)

data class NarrationEstimate(
    val characters: Long, val utf8Bytes: Long, val costCents: Double, val requests: Int,
    val audioSeconds: Long, val etaSeconds: Long, val freeTier: Boolean,
)

sealed interface NarrationState {
    data object Idle : NarrationState
    data class Running(val bookId: BookId, val doneSentences: Int, val totalSentences: Int, val etaSeconds: Long) : NarrationState
    data class Paused(val bookId: BookId, val reason: String) : NarrationState
    data class Failed(val bookId: BookId, val message: String) : NarrationState
    data class Done(val bookId: BookId) : NarrationState
}

data class VoiceOption(val ref: VoiceRef, val description: String, val language: String?, val previewUrl: String?)

interface NarrationService {
    fun state(bookId: BookId): Flow<NarrationState>
    suspend fun voices(provider: String): List<VoiceOption>
    suspend fun estimate(bookId: BookId, config: NarrationConfig, fromOrdinal: Int = 0): NarrationEstimate
    /** Generate the whole book in the background (resumable, checkpointed per segment). */
    fun start(bookId: BookId, config: NarrationConfig, fromOrdinal: Int = 0)
    fun stop(bookId: BookId)
    /** Make sure audio exists for ~`minutes` after `ordinal` (just-in-time). Suspends until the first segment is ready. */
    suspend fun ensureAhead(bookId: BookId, config: NarrationConfig, ordinal: Int, minutes: Int)
    /** Render one short preview of `voice` using `sampleText`; returns an audio file path. */
    suspend fun preview(voice: VoiceRef, sampleText: String): String
}

// ================= Playback (implemented by :engine:playback) =================

data class PlaybackState(
    val bookId: BookId? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ordinal: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepRemainingMs: Long? = null,
    val title: String? = null,
    val error: String? = null,
    /**
     * Chapter timing. All durations are media milliseconds at 1x (divide by [speed] for wall-clock time).
     * [currentSectionId] is the id of the current ChapterMark (the scrubber chapter), null before playback.
     * [sectionElapsedMs] / [sectionDurationMs] cover the whole chapter, not just the audio loaded so far.
     * [sectionDurationEstimated] is true while part of the chapter has no narration yet and its length is
     * estimated from the word count. [bookRemainingMs] is the narrated time left in the book.
     */
    val currentSectionId: Long? = null,
    val sectionElapsedMs: Long = 0,
    val sectionDurationMs: Long = 0,
    val sectionDurationEstimated: Boolean = false,
    val bookRemainingMs: Long = 0,
    val bookRemainingEstimated: Boolean = false,
)

interface PlaybackController {
    val state: StateFlow<PlaybackState>
    fun play(bookId: BookId, fromOrdinal: Int? = null)
    fun pause()
    fun resume()
    fun seekToSentence(ordinal: Int)
    fun skipSentences(delta: Int)
    fun seekByMs(deltaMs: Long)
    fun setSpeed(speed: Float)
    /** `minutes` > 0 = timer, 0 = off, -1 = end of current section. */
    fun setSleepTimer(minutes: Int)

    /**
     * Seeks to [fraction] (0..1) of the current chapter. Chapters are measured by [PlaybackState.sectionDurationMs]
     * and may be partly estimated; the default moves by the time difference and engines override it with an
     * exact, sentence-aware version.
     */
    fun seekWithinSection(fraction: Float) {
        val s = state.value
        if (s.sectionDurationMs <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * s.sectionDurationMs).toLong()
        seekByMs(target - s.sectionElapsedMs)
    }

    /** Seeks to [fraction] (0..1) of the narrated book. Default is a no-op for controllers without a book clock. */
    fun seekWithinBook(fraction: Float) {}
}

// ================= AI (implemented by :engine:ai) =================

data class Citation(val ordinal: Int, val label: String)

data class AskAnswer(val text: String, val citations: List<Citation>)

data class ConceptNode(val id: String, val label: String, val kind: String, val description: String, val firstOrdinal: Int)
data class ConceptEdge(val from: String, val to: String, val relation: String, val evidenceOrdinal: Int)
data class ConceptMap(val nodes: List<ConceptNode>, val edges: List<ConceptEdge>)

data class CardSuggestion(val question: String, val sourceStart: Int, val sourceEnd: Int, val answerHint: String)

data class AnswerCheck(val verdict: String, val covered: List<String>, val missing: List<String>)

data class AiBudget(val requestsLeftToday: Int, val dailyLimit: Int, val free: Boolean, val model: String?)

/** Every method enforces the spoiler fence (`frontier`/modes) before any text reaches a prompt. */
interface AiService {
    suspend fun budget(): AiBudget
    suspend fun explain(bookId: BookId, ordinal: Int, selection: String): String
    suspend fun define(bookId: BookId, ordinal: Int, term: String): String
    suspend fun ask(bookId: BookId, question: String): AskAnswer
    suspend fun recap(bookId: BookId): String
    suspend fun takeaways(bookId: BookId, sectionId: Long): List<String>
    suspend fun concepts(bookId: BookId): ConceptMap
    suspend fun suggestCards(bookId: BookId, sectionId: Long): List<CardSuggestion>
    suspend fun checkAnswer(question: String, userAnswer: String, sourceText: String): AnswerCheck
    /** Predicted per-section check-in prompt and the reference gist (for retrieval practice). */
    suspend fun checkIn(bookId: BookId, sectionId: Long): CheckIn
}

data class CheckIn(val prompt: String, val gist: String, val rubric: List<String>)

// ================= Spaced repetition (implemented by :engine:srs, persisted by :core:database) =================

enum class Rating { AGAIN, HARD, GOOD, EASY }
enum class CardType { QA, CLOZE, VOCAB }

data class Card(
    val id: Long, val bookId: BookId, val type: CardType,
    val sourceStart: Int, val sourceEnd: Int,
    val front: String, val back: String, val userAnswer: String?,
    val dueAt: Long, val stability: Double, val difficulty: Double,
    val reps: Int, val lapses: Int, val state: Int, val lastReviewAt: Long?, val createdAt: Long,
)

interface Scheduler {
    /** Returns the card after a review, with the next due time. */
    fun review(card: Card, rating: Rating, nowMs: Long): Card
    /** Next interval (ms) for each rating, to label the grade buttons. */
    fun preview(card: Card, nowMs: Long): Map<Rating, Long>
}

interface CardRepository {
    fun dueCards(nowMs: Long, limit: Int = 50): Flow<List<Card>>
    fun cards(bookId: BookId?): Flow<List<Card>>
    fun dueCount(nowMs: Long): Flow<Int>
    suspend fun add(card: Card): Long
    suspend fun update(card: Card)
    suspend fun delete(id: Long)
    suspend fun reviewedToday(nowMs: Long): Int
    /** Live version of [reviewedToday]: re-emits when a card is reviewed, so counters never lag a refresh behind. */
    fun reviewedTodayFlow(nowMs: Long): Flow<Int> = kotlinx.coroutines.flow.flow { emit(reviewedToday(nowMs)) }
}
