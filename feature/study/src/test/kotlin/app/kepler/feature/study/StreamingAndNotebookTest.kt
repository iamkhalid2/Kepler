package app.kepler.feature.study

import app.kepler.core.model.AiService
import app.kepler.core.model.AiStreaming
import app.kepler.core.model.AskAnswer
import app.kepler.core.model.AskEvent
import app.kepler.core.model.BookId
import app.kepler.core.model.Citation
import app.kepler.engine.ai.AiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A streaming service whose Ask pauses after the first words until [release] is completed. */
private class GatedStreaming(private val ai: FakeAi) : AiService by ai, AiStreaming {
    val release = CompletableDeferred<Unit>()
    var fail: Throwable? = null
    override fun askStream(bookId: BookId, question: String): Flow<AskEvent> = flow {
        emit(AskEvent.Delta("The author argues "))
        release.await()
        fail?.let { emit(AskEvent.Failed(it)); return@flow }
        emit(AskEvent.Delta("for restraint [7]"))
        emit(AskEvent.Citations(listOf(Citation(7, "Book I"))))
        emit(AskEvent.Done(AskAnswer("The author argues for restraint [7]", listOf(Citation(7, "Book I")))))
    }
    override fun explainStream(bookId: BookId, ordinal: Int, selection: String): Flow<AskEvent> = flow { }
    override fun defineStream(bookId: BookId, ordinal: Int, term: String): Flow<AskEvent> = flow { }
    override fun recapStream(bookId: BookId): Flow<String> = flow { emit("so far") }
}

private class StreamingDeps(val streamer: GatedStreaming, base: FakeDeps = FakeDeps()) : StudyDeps by base {
    override val streaming: AiStreaming = streamer
}

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingAskTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun wordsAppearBeforeTheAnswerIsFinishedAndCitationsFollow() = runTest {
        val streamer = GatedStreaming(FakeAi())
        val vm = LearnViewModel(1, StreamingDeps(streamer))
        vm.ask("What is the argument?")
        val mid = vm.state.value
        assertTrue(mid.asking)
        assertEquals("The author argues ", mid.live?.text)
        assertNotNull("first words are timed", mid.live?.firstWordsMs)
        assertTrue(mid.live!!.citations.isEmpty())
        streamer.release.complete(Unit)
        val end = vm.state.value
        assertFalse(end.asking)
        val answer = end.transcript.last() as AskItem.Answer
        assertEquals("The author argues for restraint [7]", answer.answer.text)
        assertEquals(listOf(7), answer.answer.citations.map { it.ordinal })
        assertEquals("What is the argument?", answer.question)
    }

    @Test fun stoppingKeepsWhatWasWritten() = runTest {
        val streamer = GatedStreaming(FakeAi())
        val vm = LearnViewModel(1, StreamingDeps(streamer))
        vm.ask("q")
        vm.stop()
        val end = vm.state.value
        assertFalse(end.asking)
        assertEquals("The author argues", (end.transcript.last() as AskItem.Answer).answer.text)
    }

    @Test fun failureAfterFirstWordsKeepsThePartialAndOffersRetry() = runTest {
        val streamer = GatedStreaming(FakeAi()).apply { fail = AiError.Network(null) }
        val vm = LearnViewModel(1, StreamingDeps(streamer))
        vm.ask("q")
        streamer.release.complete(Unit)
        val items = vm.state.value.transcript
        assertTrue(items[1] is AskItem.Answer)
        val f = items[2] as AskItem.Failure
        assertEquals(UiErrorKind.OFFLINE, f.error.kind)
        assertTrue(f.error.retryable)
    }

    @Test fun recapStopKeepsTheTextSoFar() = runTest {
        val vm = LearnViewModel(1, StreamingDeps(GatedStreaming(FakeAi())))
        vm.loadRecap()
        assertEquals("so far", (vm.state.value.recap as Load.Ready).value)
        vm.stopRecap()
        assertFalse(vm.state.value.recapStreaming)
    }
}

class AnswerStylingTest {
    private val cites = listOf(Citation(3358, "Chapter 2"), Citation(12, "Chapter 1"))

    @Test fun markersBecomeOneBasedNumbersAndUnknownOnesVanish() {
        val styled = styleAnswer("Restraint matters [3358][12]. Also [99] this.", cites, androidx.compose.ui.graphics.Color.Red)
        assertEquals("Restraint matters1,2. Also this.", styled.text)
    }

    @Test fun aHalfArrivedMarkerIsHiddenWhileStreaming() {
        assertEquals("Restraint matters", trimPartialMarker("Restraint matters [33"))
        assertEquals("Restraint matters", trimPartialMarker("Restraint matters ["))
        assertEquals("Restraint [3] matters", trimPartialMarker("Restraint [3] matters"))
    }

    @Test fun citationNumberIsOneBased() {
        val a = AskAnswer("x", cites)
        assertEquals(2, citationNumber(a, 12))
        assertNull(citationNumber(a, 5))
        assertNull(citationNumber(a, null))
    }
}

class NotebookLogicTest {
    private val day = 86_400_000L

    @Test fun agesAreCalendarDays() {
        assertEquals(Age(AgeUnit.TODAY, 0), ageOf(T0 - 1_000, T0, UTC))
        assertEquals(Age(AgeUnit.YESTERDAY, 1), ageOf(T0 - day, T0, UTC))
        assertEquals(Age(AgeUnit.DAYS, 5), ageOf(T0 - 5 * day, T0, UTC))
        assertEquals(Age(AgeUnit.WEEKS, 3), ageOf(T0 - 22 * day, T0, UTC))
        assertEquals(Age(AgeUnit.MONTHS, 4), ageOf(T0 - 125 * day, T0, UTC))
        assertEquals(Age(AgeUnit.YEARS, 3), ageOf(T0 - 1_100 * day, T0, UTC))
        // a future timestamp never reads as negative
        assertEquals(Age(AgeUnit.TODAY, 0), ageOf(T0 + day, T0, UTC))
    }

    private fun groups() = listOf(
        BookHighlights(
            book(1, title = "Meditations"),
            listOf(
                SectionHighlights(section(1, 0, 0, 49, "Book I"), listOf(annotation(1, 3, "Waste no more time"), annotation(2, 9, "Be like the promontory", note = "my favourite"))),
                SectionHighlights(section(2, 1, 50, 99, "Book II"), listOf(annotation(3, 60, "Begin the morning by saying"))),
            ),
        ),
        BookHighlights(book(2, title = "Walden"), listOf(SectionHighlights(null, listOf(annotation(4, 5, "Simplify, simplify", bookId = 2))))),
    )

    @Test fun searchMatchesQuoteNoteChapterAndBook() {
        assertEquals(4, filterHighlights(groups(), "  ").sumOf { it.count })
        assertEquals(listOf(1L), filterHighlights(groups(), "waste").map { it.book.id })
        assertEquals(1, filterHighlights(groups(), "FAVOURITE").sumOf { it.count })
        assertEquals(1, filterHighlights(groups(), "book ii").sumOf { it.count })
        assertEquals(3, filterHighlights(groups(), "meditations").sumOf { it.count })
        assertTrue(filterHighlights(groups(), "zebra").isEmpty())
    }

    @Test fun cardSearchLooksAtBothSides() {
        val cards = listOf(BookCards(book(1), listOf(card(1, "What is virtue?", "Excellence"), card(2, "Name the author", "Marcus"))))
        assertEquals(1, filterCards(cards, "virtue").single().cards.size)
        assertEquals(1, filterCards(cards, "marcus").single().cards.size)
        assertEquals(2, filterCards(cards, "meditations").single().cards.size)
        assertTrue(filterCards(cards, "nope").isEmpty())
    }

    @Test fun dueInIsNullWhenDueNow() {
        assertNull(dueIn(card(1, due = T0 - 5), T0))
        assertNull(dueIn(card(1, due = T0 + 30_000), T0))
        assertEquals(Interval(3, IntervalUnit.DAYS), dueIn(card(1, due = T0 + 3 * day), T0))
    }

    @Test fun maturitySharesSumToOne() {
        val (n, l, m) = maturityShares(ReviewStats(newCards = 2, learning = 1, mature = 1))
        assertEquals(1f, n + l + m, 0.001f)
        assertEquals(0.5f, n, 0.001f)
        assertEquals(Triple(0f, 0f, 0f), maturityShares(ReviewStats()))
    }
}
