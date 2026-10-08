package app.betterbooks.feature.study

import app.betterbooks.core.model.ContentType
import app.betterbooks.core.model.Annotation
import app.betterbooks.core.model.Rating
import app.betterbooks.engine.ai.AiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ------------------------------------------------------------ Review

    @Test fun reviewQueueIsASnapshotAndGradingNeedsTheAnswerFirst() = runTest {
        val deps = FakeDeps(cards = FakeCards(listOf(card(1), card(2), card(3, due = T0 + 1_000_000))))
        val vm = ReviewViewModel(deps)
        assertEquals(2, vm.state.value.queue.size)
        assertEquals(2, vm.state.value.left)
        vm.grade(Rating.GOOD) // not flipped: ignored
        assertEquals(0, vm.state.value.index)
        vm.flip()
        assertEquals(4, vm.state.value.intervals.size)
        vm.grade(Rating.GOOD)
        val s = vm.state.value
        assertEquals(1, s.index); assertFalse(s.flipped); assertEquals(1, s.left)
        assertEquals(setOf("2025-10-09"), deps.reviewStats.days.value)
        assertEquals(3 * 86_400_000L, deps.cards.all.value.first { it.id == 1L }.dueAt - T0)
    }

    @Test fun againCardsComeBackThisSessionAndSessionEnds() = runTest {
        val deps = FakeDeps(cards = FakeCards(listOf(card(1))))
        val vm = ReviewViewModel(deps)
        vm.flip(); vm.grade(Rating.AGAIN)
        assertEquals(2, vm.state.value.queue.size) // re-queued
        assertNotNull(vm.state.value.current)
        vm.flip(); vm.grade(Rating.GOOD)
        val s = vm.state.value
        assertTrue(s.finished); assertFalse(s.nothingDue); assertEquals(2, s.reviewedThisSession)
        assertNotNull(s.week)
    }

    @Test fun emptyQueueIsNothingDue() = runTest {
        val vm = ReviewViewModel(FakeDeps())
        assertTrue(vm.state.value.nothingDue)
    }

    @Test fun hintOnlyBeforeAnswer() = runTest {
        val vm = ReviewViewModel(FakeDeps(cards = FakeCards(listOf(card(1, back = "Virtue alone")))))
        assertNull(vm.state.value.hint)
        vm.showHint(); assertEquals("Virtue …", vm.state.value.hint)
        vm.flip(); assertNull(vm.state.value.hint)
    }

    // ------------------------------------------------------------ Learn

    @Test fun learnLoadsFenceAndBudgetAndAnswers() = runTest {
        val deps = FakeDeps(positions = FakePositions(60), booksState = listOf(book(type = ContentType.FICTION)))
        val vm = LearnViewModel(1, deps)
        val s0 = vm.state.value
        assertEquals("Book II", s0.fence.sectionTitle)
        assertEquals(18, s0.budget?.requestsLeftToday)
        assertEquals(2, s0.reached.size)
        vm.ask("  Who is X? ")
        val s = vm.state.value
        assertEquals(2, s.transcript.size)
        assertEquals("Who is X?", (s.transcript[0] as AskItem.Question).text)
        assertEquals(7, (s.transcript[1] as AskItem.Answer).answer.citations[0].ordinal)
        assertEquals(17, s.budget?.requestsLeftToday)
    }

    @Test fun nonFictionAskIsWholeBook() = runTest {
        val vm = LearnViewModel(1, FakeDeps())
        assertTrue(vm.state.value.fence.wholeBook)
        assertTrue(vm.state.value.studyMode)
    }

    @Test fun budgetExhaustedIsATypedStateNotACrash() = runTest {
        val deps = FakeDeps()
        deps.ai.failure = AiError.BudgetExhausted(20)
        val vm = LearnViewModel(1, deps)
        vm.ask("q")
        val f = vm.state.value.transcript.last() as AskItem.Failure
        assertEquals(UiErrorKind.BUDGET, f.error.kind)
        assertFalse(f.error.retryable)
        assertFalse(vm.state.value.asking)
        // Retry replaces the failed turn instead of stacking questions.
        deps.ai.failure = null
        vm.retry(f)
        assertEquals(2, vm.state.value.transcript.size)
        assertTrue(vm.state.value.transcript.last() is AskItem.Answer)
    }

    @Test fun errorKindsMapFromAiErrors() {
        assertEquals(UiErrorKind.NO_KEY, AiError.MissingKey().toUiError().kind)
        assertEquals(UiErrorKind.OFFLINE, AiError.Network(null).toUiError().kind)
        assertEquals(UiErrorKind.RATE_LIMITED, AiError.RateLimited(5).toUiError().kind)
        assertEquals(UiErrorKind.OTHER, IllegalStateException().toUiError().kind)
    }

    @Test fun recapConceptsAndTakeawaysLoadWithStates() = runTest {
        val deps = FakeDeps()
        val vm = LearnViewModel(1, deps)
        vm.loadRecap(); vm.loadConcepts(); vm.loadTakeaways(1)
        val s = vm.state.value
        assertEquals("recap", (s.recap as Load.Ready).value)
        assertEquals(2, s.positions.size)
        assertEquals(listOf("t1"), (s.takeaways[1L] as Load.Ready).value)
        deps.ai.failure = AiError.MissingKey()
        vm.loadRecap(force = true)
        assertEquals(UiErrorKind.NO_KEY, (vm.state.value.recap as Load.Failed).error.kind)
    }

    @Test fun draggingPinsANodeAndRelayoutKeepsIt() = runTest {
        val vm = LearnViewModel(1, FakeDeps())
        vm.loadConcepts()
        vm.moveNode("a", 0.3f, 2f)
        assertTrue("a" in vm.state.value.pinned)
        assertEquals(0.9f, vm.state.value.positions["a"]!!.y, 0.0001f) // clamped
        vm.relayout()
        assertEquals(0.3f, vm.state.value.positions["a"]!!.x, 0.0001f)
        vm.togglePin("a"); assertFalse("a" in vm.state.value.pinned)
    }

    // ------------------------------------------------------------ Explain / Define

    @Test fun studyModeAsksBeforeRevealing() = runTest {
        val deps = FakeDeps()
        val vm = ExplainViewModel(1, 5, "text", deps)
        assertEquals(ExplainPhase.PREDICT, vm.state.value.phase)
        assertEquals(0, deps.ai.explainCalls)
        vm.setPrediction("because")
        vm.reveal()
        assertEquals(ExplainPhase.REVEALED, vm.state.value.phase)
        assertEquals("explained", (vm.state.value.explanation as Load.Ready).value)
        assertEquals(1, deps.ai.explainCalls)
    }

    @Test fun lightModeExplainsStraightAway() = runTest {
        val deps = FakeDeps(booksState = listOf(book(type = ContentType.FICTION)))
        val vm = ExplainViewModel(1, 5, "text", deps)
        assertEquals(ExplainPhase.REVEALED, vm.state.value.phase)
        assertEquals(1, deps.ai.explainCalls)
    }

    @Test fun definingSavesAWordOnceWithinTheCap() = runTest {
        val deps = FakeDeps()
        DefineViewModel(1, 5, "Logos", deps)
        DefineViewModel(1, 5, "logos", deps)
        val words = deps.cards.all.value
        assertEquals(1, words.size)
        assertEquals("Logos", words[0].front)
        assertTrue(words[0].back.contains("Sentence 5."))
        // Cap: ten new cards already made today.
        val full = FakeDeps(cards = FakeCards((1..10L).map { card(it) }))
        DefineViewModel(1, 5, "Telos", full)
        assertEquals(10, full.cards.all.value.size)
    }

    // ------------------------------------------------------------ Card from selection

    @Test fun cardNeedsUserAnswerThenSavesWithNewState() = runTest {
        val deps = FakeDeps()
        val vm = CardDraftViewModel(1, 5, "Selected passage", null, deps)
        assertEquals("Why?", (vm.state.value.suggestions as Load.Ready).value[0].question)
        vm.choose((vm.state.value.suggestions as Load.Ready).value[0])
        assertFalse(vm.state.value.canSave) // the user has not written an answer
        vm.setAnswer("my words")
        vm.check()
        assertEquals("correct", (vm.state.value.check as Load.Ready).value.verdict)
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)
        val c = deps.cards.all.value.single()
        assertEquals("Why?", c.front); assertEquals("Because.", c.back); assertEquals("my words", c.userAnswer)
        assertEquals(0, c.state); assertEquals(T0, c.dueAt); assertEquals(5, c.sourceStart); assertEquals(6, c.sourceEnd)
    }

    @Test fun cardCapBlocksTheEleventhNewCard() = runTest {
        val deps = FakeDeps(cards = FakeCards((1..10L).map { card(it) }))
        val vm = CardDraftViewModel(1, 5, "Selected", "Q?", deps)
        vm.setAnswer("a")
        assertTrue(vm.state.value.capReached)
        vm.save {}
        assertEquals(10, deps.cards.all.value.size)
    }

    @Test fun cardCanBeSavedWhenCheckingFails() = runTest {
        val deps = FakeDeps()
        deps.ai.failure = AiError.Network(null)
        val vm = CardDraftViewModel(1, 5, "Selected passage", "Own question?", deps)
        vm.setAnswer("a"); vm.check()
        assertTrue(vm.state.value.check is Load.Failed)
        vm.save {}
        assertEquals("Selected passage", deps.cards.all.value.single().back) // no suggestion: the passage is the source
    }

    // ------------------------------------------------------------ Check-in and recall

    @Test fun checkInTicksCoveredRubricLines() = runTest {
        val vm = CheckInViewModel(1, 1, FakeDeps())
        vm.setAnswer("claim a is right")
        vm.reveal()
        assertEquals(CheckInPhase.REVEALED, vm.state.value.phase)
        assertEquals(listOf(true, false), vm.state.value.ticks)
    }

    @Test fun checkInRevealWithoutAnswerShowsGistWithNoTicksAndNoRequest() = runTest {
        val deps = FakeDeps()
        deps.ai.failure = null
        val vm = CheckInViewModel(1, 1, deps)
        vm.reveal()
        assertEquals(listOf(false, false), vm.state.value.ticks)
    }

    @Test fun recallStoresPredictedAgainstActual() = runTest {
        val deps = FakeDeps()
        val vm = RecallViewModel(1, 1, deps)
        vm.setPredicted(90); vm.startRecall(); vm.setAnswer("claim a")
        vm.finish()
        val s = vm.state.value
        assertEquals(RecallPhase.RESULT, s.phase); assertEquals(50, s.actual)
        val r = deps.reviewStats.recallList.value.single()
        assertEquals(90, r.predicted); assertEquals(50, r.actual)
    }

    // ------------------------------------------------------------ Notebook

    @Test fun notebookGroupsHighlightsAndSplitsCards() = runTest {
        val deps = FakeDeps(
            annotations = FakeAnnotations(listOf(annotation(1, 60), annotation(2, 5), annotation(3, 7))),
            cards = FakeCards(listOf(card(1), card(2, type = app.betterbooks.core.model.CardType.VOCAB))),
        )
        val vm = NotebookViewModel(deps)
        val job = launch { vm.data.collect {} }
        val d = vm.data.first { it.loaded }
        assertEquals(1, d.highlights.size)
        assertEquals(3, d.highlights[0].count)
        assertEquals(listOf("Book I", "Book II"), d.highlights[0].sections.map { it.section?.title })
        assertEquals(1, d.cards.sumOf { it.cards.size }); assertEquals(1, d.words.sumOf { it.cards.size })
        assertEquals(2, d.dueCount)
        job.cancel()
    }

    @Test fun exportGoesThroughWriteDocument() = runTest {
        val deps = FakeDeps(annotations = FakeAnnotations(listOf(annotation(1, 5))))
        val vm = NotebookViewModel(deps)
        vm.export(book(), ExportFormat.MARKDOWN)
        assertEquals(ExportStatus.DONE, vm.export.value.status)
        assertEquals("text/markdown", deps.written.single().second)
        vm.export(book(), ExportFormat.WEB_ANNOTATION)
        assertEquals("application/ld+json", deps.written.last().second)
        deps.writeFails = true
        vm.export(book(), ExportFormat.MARKDOWN)
        assertEquals(ExportStatus.FAILED, vm.export.value.status)
    }
}
