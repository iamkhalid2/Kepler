package app.kepler.engine.ai

import app.kepler.core.model.AiService
import app.kepler.core.model.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun packJson(body: String): String {
    val ords = Regex("\\[(\\d+)]").findAll(body.substringAfterLast("Passages:")).map { it.groupValues[1].toInt() }.toList()
    val first = ords.first(); val last = ords.last()
    return """{"gist":"Gist of $first-$last","keyIdeas":[{"claim":"Idea","evidenceOrdinals":[$first,999],"rubric":["r1"]},{"claim":"Bad","evidenceOrdinals":[999],"rubric":[]}],
      "events":[],"entities":[{"name":"Habit","kind":"CONCEPT","aliases":["habits"],"definition":"A routine.","firstOrdinal":$first},
      {"name":"Focus","kind":"CONCEPT","aliases":[],"definition":"Attention.","firstOrdinal":$last},
      {"name":"Future","kind":"CONCEPT","aliases":[],"definition":"Beyond.","firstOrdinal":999}],
      "edges":[{"from":"habits","to":"Focus","relation":"causes","evidenceOrdinal":$last},{"from":"Habit","to":"Future","relation":"causes","evidenceOrdinal":$last}],
      "cardCandidates":[{"q":"What is a habit?","hint":"A routine","sourceOrdinals":[$first,$last]}],
      "takeaways":["Take one","Take two"],"checkIn":{"prompt":"Recall it","gist":"Gist","rubric":["a","b"]},"recapSummary":"Recap $first-$last"}"""
}

private fun respond(body: String, call: Int): Pair<HttpStatusCode, String> = when {
    "chapter_pack" in body || "You analyse one section" in body -> OK to completion(packJson(body))
    "answer_check" in body || "You check a learner" in body ->
        OK to completion("""{"verdict":"partial","covered":["a"],"missing":["b"]}""")
    "You answer a reader's question" in body -> {
        val ords = Regex("\\[(\\d+)]").findAll(body.substringAfterLast("Passages:")).map { it.groupValues[1] }.toList()
        OK to completion("It is a routine [${ords.first()}] and also [s${ords.last()}, 5000] and [999].")
    }
    else -> OK to completion("A plain text answer.")
}

private class Rig(
    type: ContentType = ContentType.FICTION, frontier: Int = 14, leaky: Boolean = false,
    chat: (String, Int) -> Pair<HttpStatusCode, String> = ::respond,
) {
    val store = FakeAiStore()
    val mock = MockOpenRouter(chat)
    val spine = FakeSpine(leaky)
    val positions = FakePositions(frontier)
    val service: OpenRouterAiService = OpenRouterAiService(
        FakeSettings(), spine, positions, store, FakeLibrary(testBook(type)), mock.client,
        budgeter = RequestBudgeter(store, perMinute = 1000, sleeper = {}),
    )
}

class FenceLeakTest {
    private suspend fun exerciseEverything(s: AiService) {
        s.explain(1, 14, "habit")
        s.define(1, 13, "focus")
        s.ask(1, "what is a habit and focus?")
        s.recap(1)
        s.takeaways(1, 2)
        s.concepts(1)
        s.suggestCards(1, 2)
        s.checkIn(1, 2)
        s.checkAnswer("q?", "a", "Reference text without the marker")
        for (target in listOf(3L)) {
            try { s.takeaways(1, target); fail("section beyond the fence must not be served") } catch (_: AiError.SectionNotReached) { }
        }
    }

    @Test fun markerBeyondTheFenceNeverAppearsInAnyPrompt() = runBlocking {
        for (leaky in listOf(false, true)) {
            val rig = Rig(ContentType.FICTION, frontier = 14, leaky = leaky)
            exerciseEverything(rig.service)
            assertTrue("requests were made", rig.mock.chatBodies.size >= 6)
            rig.mock.chatBodies.forEach { assertFalse("leak (leaky spine=$leaky)", MARKER in it) }
        }
    }

    @Test fun cardsAndQuizzesStayAtTheFrontierEvenForNonFiction() = runBlocking {
        val rig = Rig(ContentType.NONFICTION, frontier = 14, leaky = true)
        rig.service.suggestCards(1, 2); rig.service.checkIn(1, 2)
        rig.mock.chatBodies.forEach { assertFalse(MARKER in it) }
    }

    @Test fun nonFictionAskMayUseTheWholeBook() = runBlocking {
        val rig = Rig(ContentType.NONFICTION, frontier = 14)
        val answer = rig.service.ask(1, "zebramarkerxyz")
        assertTrue(MARKER in rig.mock.chatBodies.single())
        assertTrue(answer.citations.isNotEmpty())
    }

    @Test fun fictionAskIsFencedAndNothingReadFailsCleanly() = runBlocking {
        val rig = Rig(ContentType.FICTION, frontier = -1)
        try { rig.service.ask(1, "anything"); fail() } catch (_: AiError.NothingRead) { }
        assertEquals(0, rig.mock.chatBodies.size)
    }

    @Test fun askStaysFencedWhenAPerBookModeTriesToOpenAFictionBook() = runBlocking {
        val spine = FakeSpine(false)
        val positions = FakePositions(3)
        val fence = FenceService(positions, FakeLibrary(testBook(ContentType.FICTION)), spine) { FenceMode.WHOLE_BOOK }
        assertEquals(3, fence.maxOrdinal(1, FenceFeature.ASK))
        val nonfiction = FenceService(positions, FakeLibrary(testBook(ContentType.NONFICTION)), spine)
        assertEquals(spine.sentenceCount(1) - 1, nonfiction.maxOrdinal(1, FenceFeature.ASK))
    }

    @Test fun packHashChangesWithTheFenceSoAWiderPackIsNeverReusedForANarrowerFence() = runBlocking {
        val rig = Rig(ContentType.FICTION, frontier = 19)
        rig.service.takeaways(1, 2)
        val wideCalls = rig.mock.chatBodies.size
        rig.positions.frontier = 14
        rig.service.takeaways(1, 2)
        assertEquals(wideCalls + 1, rig.mock.chatBodies.size)
        assertFalse(MARKER in rig.mock.chatBodies.last())
        assertTrue(MARKER in rig.mock.chatBodies.first()) // ordinal 17 was inside the wider fence
    }
}

class ServiceBehaviourTest {
    @Test fun secondCallForTheSameSectionMakesNoRequest() = runBlocking {
        val rig = Rig(ContentType.NONFICTION, frontier = 29)
        assertEquals(listOf("Take one", "Take two"), rig.service.takeaways(1, 2))
        val after = rig.mock.chatBodies.size
        assertEquals(1, after)
        rig.service.takeaways(1, 2); rig.service.suggestCards(1, 2); rig.service.checkIn(1, 2)
        assertEquals(after, rig.mock.chatBodies.size)
    }

    @Test fun packIsSanitisedToTheTextTheModelSaw() = runBlocking {
        val rig = Rig(ContentType.NONFICTION, frontier = 29)
        val cards = rig.service.suggestCards(1, 2)
        assertEquals(1, cards.size)
        assertEquals(10, cards[0].sourceStart); assertEquals(19, cards[0].sourceEnd)
        val check = rig.service.checkIn(1, 2)
        assertEquals("Recall it", check.prompt); assertEquals(listOf("a", "b"), check.rubric)
    }

    @Test fun conceptsAreMergedAndFiltered() = runBlocking {
        val rig = Rig(ContentType.NONFICTION, frontier = 14)
        rig.service.takeaways(1, 1)
        val map = rig.service.concepts(1)
        assertEquals(listOf("Habit", "Focus"), map.nodes.map { it.label })
        assertEquals(1, map.edges.size)
        assertEquals("habit", map.edges[0].from); assertEquals("focus", map.edges[0].to)
        assertTrue(map.nodes.all { it.firstOrdinal <= 14 })
    }

    @Test fun recapStitchesCachedSummariesAndBuildsThePartialSection() = runBlocking {
        val rig = Rig(ContentType.NONFICTION, frontier = 14)
        rig.service.buildPack(1, 1, Priority.INTERACTIVE)
        val before = rig.mock.chatBodies.size
        val recap = rig.service.recap(1)
        assertEquals(before + 1, rig.mock.chatBodies.size) // only the partial section 2 was built
        assertTrue(recap.contains("One: Recap 0-9")); assertTrue(recap.contains("Two: Recap 10-14"))
        rig.service.recap(1)
        assertEquals(before + 1, rig.mock.chatBodies.size)
    }

    @Test fun askKeepsOnlyRealCitationsAndCachesTheAnswer() = runBlocking {
        val rig = Rig(ContentType.NONFICTION, frontier = 29)
        val a = rig.service.ask(1, "What is a habit?")
        assertFalse("[999]" in a.text)
        assertTrue(a.citations.all { it.ordinal in 0..29 })
        assertEquals(2, a.citations.size)
        val n = rig.mock.chatBodies.size
        assertEquals(a.text, rig.service.ask(1, "what is a habit?").text)
        assertEquals(n, rig.mock.chatBodies.size)
    }

    @Test fun explainIsCached() = runBlocking {
        val rig = Rig(frontier = 14)
        rig.service.explain(1, 12, "habit"); rig.service.explain(1, 12, "habit")
        assertEquals(1, rig.mock.chatBodies.size)
    }

    @Test fun checkAnswerParsesAVerdict() = runBlocking {
        val rig = Rig()
        val r = rig.service.checkAnswer("q", "a", "ref")
        assertEquals("partial", r.verdict); assertEquals(listOf("b"), r.missing)
    }

    @Test fun missingKeyIsATypedError() = runBlocking {
        val rig = Rig()
        val svc = OpenRouterAiService(FakeSettings(key = null), rig.spine, rig.positions, rig.store, FakeLibrary(testBook(ContentType.FICTION)), rig.mock.client)
        try { svc.explain(1, 3, "x"); fail() } catch (_: AiError.MissingKey) { }
    }

    @Test fun budgetReportsRemainingRequestsAndModel() = runBlocking {
        val rig = Rig(frontier = 14)
        rig.service.explain(1, 3, "x")
        val b = rig.service.budget()
        assertEquals(49, b.requestsLeftToday); assertEquals(50, b.dailyLimit)
        assertTrue(b.free); assertEquals("vendor/big-model:free", b.model)
    }

    @Test fun exhaustedBudgetIsATypedErrorBeforeAnyRequest() = runBlocking {
        val rig = Rig(frontier = 14)
        rig.store.addRequests(java.time.LocalDate.now().toString(), "openrouter", 50)
        try { rig.service.explain(1, 3, "x"); fail() } catch (_: AiError.BudgetExhausted) { }
        assertEquals(0, rig.mock.chatBodies.size)
    }

    @Test fun transportAndHttpFailuresMapToTypedErrors() = runBlocking {
        suspend fun status(code: HttpStatusCode, body: String): AiError {
            val rig = Rig(frontier = 14, chat = { _, _ -> code to body })
            return try { rig.service.explain(1, 3, "x"); throw AssertionError("no error") } catch (e: AiError) { e }
        }
        assertTrue(status(HttpStatusCode.Unauthorized, """{"error":{"message":"bad"}}""") is AiError.Unauthorized)
        assertTrue(status(HttpStatusCode.PaymentRequired, """{"error":{"message":"credits"}}""") is AiError.InsufficientCredits)
        assertTrue(status(HttpStatusCode.TooManyRequests, """{"error":{"message":"slow down"}}""") is AiError.RateLimited)
        assertTrue(status(HttpStatusCode.TooManyRequests, """{"error":{"message":"Rate limit exceeded: free-models-per-day"}}""") is AiError.BudgetExhausted)
        assertTrue(status(HttpStatusCode.BadGateway, """{"error":{"message":"upstream"}}""") is AiError.ModelUnavailable)
    }
}

class JsonRepairTest {
    private val spec = JsonSpec("t", Sch.obj("n" to Sch.int()), Num.serializer(), validate = { if (it.n < 0) "n must be >= 0" else null })

    @kotlinx.serialization.Serializable
    class Num(val n: Int = 0)

    private fun runner(mock: MockOpenRouter): LlmRunner {
        val store = FakeAiStore()
        return LlmRunner(mock.client, ModelCatalog(mock.client, store), RequestBudgeter(store, perMinute = 1000, sleeper = {}), FakeSettings())
    }

    @Test fun oneRepairRetryFixesAMalformedReply() = runBlocking {
        val mock = MockOpenRouter({ _, call -> OK to completion(if (call == 1) "Sure! {\"n\": " else "```json\n{\"n\": 7}\n```") })
        assertEquals(7, runner(mock).json(Priority.INTERACTIVE, spec, "sys", "user").n)
        assertEquals(2, mock.chatBodies.size)
        assertTrue("not usable" in mock.chatBodies[1])
    }

    @Test fun semanticallyInvalidReplyTriggersRepairToo() = runBlocking {
        val mock = MockOpenRouter({ _, call -> OK to completion("""{"n": ${if (call == 1) -1 else 2}}""") })
        assertEquals(2, runner(mock).json(Priority.INTERACTIVE, spec, "sys", "user").n)
    }

    @Test fun twoBadRepliesBecomeInvalidResponseNotAnException() = runBlocking {
        val mock = MockOpenRouter({ _, _ -> OK to completion("I cannot do that") })
        try { runner(mock).json(Priority.INTERACTIVE, spec, "sys", "user"); fail() } catch (_: AiError.InvalidResponse) { }
        // Two attempts per model, up to three models; the mock lists one eligible model plus the router fallback.
        assertTrue(mock.chatBodies.size in 2..6)
    }

    @Test fun structuredModelGetsASchemaOthersGetJsonInPrompt() = runBlocking {
        val mock = MockOpenRouter({ _, _ -> OK to completion("""{"n":1}""") })
        runner(mock).json(Priority.INTERACTIVE, spec, "sys", "user")
        assertTrue("json_schema" in mock.chatBodies[0] && "require_parameters" in mock.chatBodies[0])
        val plain = MockOpenRouter({ _, _ -> OK to completion("""{"n":1}""") }, models = """{"data":[{"id":"a/b:free","context_length":100000,
            "pricing":{"prompt":"0","completion":"0"},"architecture":{"output_modalities":["text"]},"supported_parameters":[]}]}""")
        runner(plain).json(Priority.INTERACTIVE, spec, "sys", "user")
        assertFalse("json_schema" in plain.chatBodies[0])
        assertTrue("JSON schema" in plain.chatBodies[0])
    }

    @Test fun extractJsonHandlesFencesThinkTagsAndNesting() {
        assertEquals("""{"a":{"b":"}"}}""", extractJson("<think>x {y}</think>```json\n{\"a\":{\"b\":\"}\"}} trailing"))
        assertEquals(null, extractJson("no json here"))
    }
}
