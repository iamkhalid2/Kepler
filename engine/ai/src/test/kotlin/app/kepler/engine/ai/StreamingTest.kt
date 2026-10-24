package app.kepler.engine.ai

import app.kepler.core.model.AskEvent
import app.kepler.core.model.ContentType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun jsonStr(s: String) = JsonPrimitive(s).toString()

/** One SSE frame carrying a content delta. */
fun contentFrame(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":${jsonStr(text)}}}]}\n\n"
fun reasoningFrame(text: String) = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,\"reasoning\":${jsonStr(text)}}}]}\n\n"

fun sse(vararg frames: String, done: Boolean = true) =
    ": OPENROUTER PROCESSING\n\n" + frames.joinToString("") + (if (done) "data: [DONE]\n\n" else "")

fun sseOf(vararg deltas: String) = sse(*deltas.map { contentFrame(it) }.toTypedArray())

/** What one model does when asked: stream frames after a delay, or fail with a status. */
class Reply(val status: HttpStatusCode = HttpStatusCode.OK, val body: String, val delayMs: Long = 0, val stall: Boolean = false)

/** A chat endpoint that decides per model and records which model each request asked for. */
class StreamMock(
    modelIds: List<String> = listOf("vendor/alpha:free", "vendor/beta:free", "vendor/gamma:free"),
    private val handler: suspend (model: String, body: String, call: Int) -> Reply,
) {
    val requestedModels = ArrayList<String>()
    val bodies = ArrayList<String>()
    private val modelsJson = """{"data":[""" + modelIds.joinToString(",") {
        """{"id":"$it","name":"$it","created":1750000000,"context_length":262144,"pricing":{"prompt":"0","completion":"0"},
        "architecture":{"output_modalities":["text"]},"supported_parameters":["reasoning"],
        "reasoning":{"mandatory":false,"supported_efforts":["medium","low"]}}"""
    } + "]}"

    val client = OpenRouterClient(HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val h = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            path.endsWith("/key") -> respond(KEY_JSON, HttpStatusCode.OK, h)
            path.endsWith("/models") -> respond(modelsJson, HttpStatusCode.OK, h)
            else -> {
                val body = (req.body as TextContent).text
                val model = Regex("\"model\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                val call = synchronized(bodies) { bodies += body; requestedModels += model; bodies.size }
                val r = handler(model, body, call)
                if (r.delayMs > 0) delay(r.delayMs)
                val type = if (r.status.value in 200..299 && r.body.startsWith(":")) "text/event-stream" else "application/json"
                if (r.stall) {
                    // The frames arrive, then the connection goes quiet without ending (a stream cut by the deadline).
                    val ch = io.ktor.utils.io.ByteChannel(autoFlush = true)
                    kotlinx.coroutines.GlobalScope.launch { ch.writeStringUtf8(r.body) }
                    return@MockEngine respond(ch, r.status, headersOf(HttpHeaders.ContentType, type))
                }
                respond(r.body, r.status, headersOf(HttpHeaders.ContentType, type))
            }
        }
    }))
}

class SseParsingTest {
    private fun stream(mock: StreamMock, model: String = "vendor/alpha:free", limits: StreamLimits = StreamLimits()) =
        mock.client.chatStream("k", model, listOf(ChatMessage("user", "hi")), limits = limits)

    @Test fun collectsContentDeltasAndIgnoresReasoningRoleCommentsAndDone() = runBlocking {
        val mock = StreamMock { _, _, _ ->
            Reply(body = sse(reasoningFrame("thinking hard"), contentFrame("Hel"), reasoningFrame("more"), contentFrame("lo "), contentFrame("world")))
        }
        assertEquals(listOf("Hel", "lo ", "world"), stream(mock).toList())
        assertTrue("\"stream\":true" in mock.bodies.single())
    }

    @Test fun requestsLowReasoningWhereTheModelSupportsIt() = runBlocking {
        val mock = StreamMock { _, _, _ -> Reply(body = sseOf("ok")) }
        val model = OpenRouterClient.parseModel(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"id":"a/b","pricing":{"prompt":"0","completion":"0"},"reasoning":{"mandatory":false,"supported_efforts":["medium","low"]}}""",
            ),
        )!!
        mock.client.chatStream("k", "a/b", listOf(ChatMessage("user", "x")), reasoning = model.reasoningRequest()).toList()
        assertTrue("\"reasoning\":{\"effort\":\"low\"}" in mock.bodies.single())
        val plain = OpenRouterClient.parseModel(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"a/c","pricing":{"prompt":"0","completion":"0"},"reasoning":{"mandatory":false}}"""))!!
        assertEquals("""{"enabled":false}""", plain.reasoningRequest().toString())
        val forced = OpenRouterClient.parseModel(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"a/d","pricing":{"prompt":"0","completion":"0"},"reasoning":{"mandatory":true}}"""))!!
        assertNull(forced.reasoningRequest())
    }

    @Test fun errorFrameBeforeAnyTextIsATypedError() = runBlocking {
        val mock = StreamMock { _, _, _ ->
            Reply(body = sse("data: {\"error\":{\"code\":429,\"message\":\"Provider returned error\"}}\n\n"))
        }
        try { stream(mock).toList(); fail() } catch (e: AiError.RateLimited) { }
    }

    @Test fun errorFrameAfterSomeTextKeepsTheTextAndThenFails() = runBlocking {
        val mock = StreamMock { _, _, _ ->
            Reply(body = sse(contentFrame("partial"), "data: {\"error\":{\"code\":502,\"message\":\"upstream died\"}}\n\n"))
        }
        val got = ArrayList<String>()
        try { stream(mock).collect { got += it }; fail() } catch (e: AiError.ModelUnavailable) { }
        assertEquals(listOf("partial"), got)
    }

    @Test fun httpErrorsMapLikeTheNonStreamingCall() = runBlocking {
        val mock = StreamMock { _, _, _ -> Reply(HttpStatusCode.PaymentRequired, """{"error":{"message":"credits"}}""") }
        try { stream(mock).toList(); fail() } catch (e: AiError.InsufficientCredits) { }
    }

    @Test fun aServerThatIgnoresStreamStillWorks() = runBlocking {
        val mock = StreamMock { _, _, _ -> Reply(body = completion("whole reply")) }
        assertEquals(listOf("whole reply"), stream(mock).toList())
    }

    @Test fun silenceUntilTheFirstTokenDeadlineIsAFirstTokenTimeout() = runBlocking {
        val mock = StreamMock { _, _, _ -> Reply(body = sseOf("late"), delayMs = 1_500) }
        val t0 = System.currentTimeMillis()
        try { stream(mock, limits = StreamLimits(200, 5_000)).toList(); fail() } catch (e: FirstTokenTimeout) { }
        assertTrue("gave up quickly", System.currentTimeMillis() - t0 < 1_200)
    }
}

class StreamTextTest {
    @Test fun markersSplitAcrossDeltasAreResolvedAndNormalised() {
        val s = CitationStream(setOf(12, 13, 40), 40) { "Section $it" }
        val out = StringBuilder()
        for (chunk in listOf("It is a habit [1", "2] and ", "also [s", "13, 99", "9] but not [", "5] or [note] ", "or [40", "-41]. Done [")) out.append(s.feed(chunk))
        out.append(s.finish())
        // 99/999/5 are not in the prompt, 41 is beyond the fence: dropped; "[note]" is plain text; the unclosed "[" survives.
        assertEquals("It is a habit [12] and also [13] but not or [note] or [40]. Done [", out.toString())
        assertEquals(listOf(12, 13, 40), s.citations.map { it.ordinal })
        assertEquals("Section 12", s.citations.first().label)
    }

    @Test fun takeChangedReportsEachNewCitationOnce() {
        val s = CitationStream(setOf(1, 2), 10) { "" }
        s.feed("a [1")
        assertFalse(s.takeChanged())
        s.feed("] b [1]")
        assertTrue(s.takeChanged()); assertFalse(s.takeChanged())
        s.feed(" c [2]")
        assertTrue(s.takeChanged())
    }

    @Test fun thinkBlocksAreRemovedEvenWhenTagsAreSplit() {
        val t = ThinkStripper()
        val out = listOf("Hi <th", "ink>secret ", "stuff</thi", "nk> there <", "b>ok").joinToString("") { t.feed(it) } + t.finish()
        assertEquals("Hi  there <b>ok", out)
    }
}

private fun freeModel(id: String) = ModelInfo(id, id, 262144, 1, true, false, false, false)

class ModelProberTest {
    private class Rig(
        val mock: StreamMock,
        val store: FakeAiStore = FakeAiStore(),
        var now: Long = 1_000_000_000L,
    ) {
        val budgeter = RequestBudgeter(store, perMinute = 1000, sleeper = {}, clock = { now }, backgroundSpacingMs = 0)
        fun prober(first: Long = 300) = ModelProber(
            mock.client, store, budgeter, clock = { now },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), probeFirstTokenMs = first,
        )
        val models = listOf("vendor/alpha:free", "vendor/beta:free", "vendor/gamma:free", "vendor/delta:free").map(::freeModel)
    }

    private fun mock() = StreamMock(listOf("vendor/alpha:free", "vendor/beta:free", "vendor/gamma:free", "vendor/delta:free")) { model, _, _ ->
        when (model) {
            "vendor/alpha:free" -> Reply(body = sseOf("Hello"), delayMs = 250)
            "vendor/beta:free" -> Reply(body = sseOf("Hello"), delayMs = 40)
            "vendor/gamma:free" -> Reply(HttpStatusCode.TooManyRequests, """{"error":{"message":"slow down"}}""")
            else -> Reply(body = sseOf("Hello"), delayMs = 1_500) // longer than the 300 ms first-token limit
        }
    }

    @Test fun ordersByMeasuredTtftAndDropsFailuresAndTimeouts() = runBlocking {
        val rig = Rig(mock())
        val prober = rig.prober()
        prober.probeNow("k", rig.models)
        val order = prober.order("k", rig.models, awaitFirstUse = false).map { it.id }
        assertEquals(listOf("vendor/beta:free", "vendor/alpha:free"), order)
        val m = prober.measurements()
        assertTrue(m.getValue("vendor/beta:free") < m.getValue("vendor/alpha:free"))
        // The request budget saw all four probes.
        assertEquals(4, rig.store.requestsOn(java.time.LocalDate.ofEpochDay(rig.now / 86_400_000L).toString(), "openrouter"))
    }

    @Test fun unmeasuredModelsComeAfterMeasuredOnes() = runBlocking {
        val rig = Rig(mock())
        val prober = rig.prober()
        prober.recordSuccess("vendor/gamma:free", 900)
        val order = prober.order("k", rig.models, awaitFirstUse = false).map { it.id }
        assertEquals("vendor/gamma:free", order.first())
        assertEquals(listOf("vendor/alpha:free", "vendor/beta:free", "vendor/delta:free"), order.drop(1))
    }

    @Test fun resultsPersistAndAreNotReprobedWithinADay() = runBlocking {
        val rig = Rig(mock())
        rig.prober().probeNow("k", rig.models)
        val calls = rig.mock.bodies.size
        val again = rig.prober()
        val order = again.order("k", rig.models, awaitFirstUse = true).map { it.id }
        assertEquals(listOf("vendor/beta:free", "vendor/alpha:free"), order)
        delay(100)
        assertEquals("no new probe inside 24 h", calls, rig.mock.bodies.size)
        rig.now += 25 * 3_600_000L
        again.order("k", rig.models, awaitFirstUse = false)
        delay(1_800)
        assertTrue("probe again after 24 h", rig.mock.bodies.size > calls)
    }

    @Test fun failedModelsReturnAfterTheirCoolOff() = runBlocking {
        val rig = Rig(mock())
        val prober = rig.prober()
        prober.recordFailure("vendor/beta:free", "429")
        assertFalse("vendor/beta:free" in prober.order("k", rig.models, false).map { it.id })
        rig.now += 4 * 3_600_000L
        assertTrue("vendor/beta:free" in prober.order("k", rig.models, false).map { it.id })
    }

    @Test fun oneModelsNetworkErrorOrBadKeyDoesNotAbortTheRound() = runBlocking {
        val rig = Rig(StreamMock(listOf("vendor/alpha:free", "vendor/beta:free", "vendor/gamma:free", "vendor/delta:free")) { model, _, _ ->
            when (model) {
                "vendor/alpha:free" -> Reply(HttpStatusCode.Unauthorized, """{"error":{"message":"no"}}""")
                "vendor/gamma:free" -> Reply(HttpStatusCode.PaymentRequired, """{"error":{"message":"pay"}}""")
                else -> Reply(body = sseOf("Hello"), delayMs = 20)
            }
        })
        val prober = rig.prober()
        prober.probeNow("k", rig.models)
        assertEquals(setOf("vendor/beta:free", "vendor/delta:free"), prober.measurements().keys)
        val calls = rig.mock.bodies.size
        prober.order("k", rig.models, awaitFirstUse = false)
        delay(150)
        assertEquals("the round counted: no new probe inside a day", calls, rig.mock.bodies.size)
    }

    @Test fun aRoundWhereNothingAnsweredIsRetriedAfterABackoffNotOnEveryAsk() = runBlocking {
        val rig = Rig(StreamMock(listOf("vendor/alpha:free", "vendor/beta:free")) { _, _, _ ->
            Reply(HttpStatusCode.Unauthorized, """{"error":{"message":"bad key"}}""")
        })
        val prober = rig.prober()
        prober.probeNow("k", rig.models.take(2))
        val calls = rig.mock.bodies.size
        repeat(5) { prober.order("k", rig.models.take(2), awaitFirstUse = false) }
        delay(150)
        assertEquals("no probe storm", calls, rig.mock.bodies.size)
        rig.now += 4 * 3_600_000L
        prober.order("k", rig.models.take(2), awaitFirstUse = false)
        delay(300)
        assertTrue("retried after the back-off and the models cool-off", rig.mock.bodies.size > calls)
    }

    @Test fun probesSpendBackgroundBudgetOnly() = runBlocking {
        // 50/day free tier: BACKGROUND stops at 90% (45), so a probe can never use the user's last requests.
        val rig = Rig(mock())
        rig.store.counts[java.time.LocalDate.ofEpochDay(rig.now / 86_400_000L).toString() to "openrouter"] = 45
        val prober = rig.prober()
        prober.probeNow("k", rig.models)
        assertEquals("nothing sent once the background share is spent", 0, rig.mock.bodies.size)
    }

    @Test fun firstUseWaitsForTheFirstAnswerOnly() = runBlocking {
        val rig = Rig(mock())
        val prober = rig.prober()
        val t0 = System.currentTimeMillis()
        val order = prober.order("k", rig.models, awaitFirstUse = true).map { it.id }
        val took = System.currentTimeMillis() - t0
        assertEquals("vendor/beta:free", order.first())
        assertTrue("did not wait for the slow probes ($took ms)", took < 1_200)
    }
}

class FallbackAndFenceTest {
    private fun runner(mock: StreamMock, prober: ModelProber?, limits: StreamLimits): LlmRunner {
        val store = FakeAiStore()
        return LlmRunner(
            mock.client, ModelCatalog(mock.client, store), RequestBudgeter(store, perMinute = 1000, sleeper = {}), FakeSettings(),
            prober = prober, limits = limits,
        )
    }

    @Test fun silentModelIsSkippedAfterTheFirstTokenLimitAndTheNextOneAnswers() = runBlocking {
        val mock = StreamMock { model, _, _ ->
            if (model == "vendor/alpha:free") Reply(body = sseOf("never"), delayMs = 2_000) else Reply(body = sseOf("Quick ", "answer"))
        }
        val r = runner(mock, null, StreamLimits(250, 5_000))
        val t0 = System.currentTimeMillis()
        val text = r.text(Priority.INTERACTIVE, "sys", "user")
        assertEquals("Quick answer", text)
        assertEquals(2, mock.requestedModels.size)
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
        assertEquals("vendor/beta:free", r.lastModelUsed)
    }

    @Test fun rateLimitedAndBrokenModelsFallThroughInstantly() = runBlocking {
        val mock = StreamMock { model, _, _ ->
            when (model) {
                "vendor/alpha:free" -> Reply(HttpStatusCode.TooManyRequests, """{"error":{"message":"busy"}}""")
                "vendor/beta:free" -> Reply(HttpStatusCode.BadGateway, """{"error":{"message":"down"}}""")
                else -> Reply(body = sseOf("fine"))
            }
        }
        assertEquals("fine", runner(mock, null, StreamLimits()).text(Priority.INTERACTIVE, "s", "u"))
        assertEquals(3, mock.requestedModels.size)
    }

    @Test fun fallbackAttemptsAreNotChargedAgain() = runBlocking {
        val mock = StreamMock { model, _, _ ->
            when (model) {
                "vendor/alpha:free" -> Reply(HttpStatusCode.TooManyRequests, """{"error":{"message":"busy"}}""")
                "vendor/beta:free" -> Reply(HttpStatusCode.BadGateway, """{"error":{"message":"down"}}""")
                else -> Reply(body = sseOf("fine"))
            }
        }
        val store = FakeAiStore()
        val r = LlmRunner(mock.client, ModelCatalog(mock.client, store), RequestBudgeter(store, perMinute = 1000, sleeper = {}), FakeSettings())
        assertEquals("fine", r.text(Priority.INTERACTIVE, "s", "u"))
        assertEquals(3, mock.requestedModels.size)
        assertEquals("one logical request", 1, store.counts.values.sum())
    }

    @Test fun measuredFastestModelIsTriedFirstAndRealRequestsFeedTheNumbers() = runBlocking {
        val mock = StreamMock { _, _, _ -> Reply(body = sseOf("ok")) }
        val store = FakeAiStore()
        val prober = ModelProber(mock.client, store, RequestBudgeter(store, perMinute = 1000, sleeper = {}), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        prober.recordSuccess("vendor/gamma:free", 100)
        prober.recordSuccess("vendor/alpha:free", 700)
        val r = runner(mock, prober, StreamLimits())
        r.text(Priority.INTERACTIVE, "s", "u")
        assertEquals("vendor/gamma:free", mock.requestedModels.first())
        assertTrue(prober.p50("vendor/gamma:free")!! < 700)
    }

    private class AskRig(type: ContentType, frontier: Int, leaky: Boolean = false, handler: suspend (String, String, Int) -> Reply) {
        val store = FakeAiStore()
        val mock = StreamMock(handler = handler)
        val spine = FakeSpine(leaky)
        val positions = FakePositions(frontier)
        val service = OpenRouterAiService(
            FakeSettings(), spine, positions, store, FakeLibrary(testBook(type)), mock.client,
            budgeter = RequestBudgeter(store, perMinute = 1000, sleeper = {}),
        )
    }

    @Test fun askStreamsDeltasThenCitationsThenDoneWithMarkersResolvedAcrossDeltas() = runBlocking {
        val rig = AskRig(ContentType.NONFICTION, 29) { _, body, _ ->
            val ords = Regex("\\[(\\d+)]").findAll(body.substringAfterLast("Passages:")).map { it.groupValues[1] }.toList()
            Reply(body = sseOf("A habit is a routine [", ords.first().take(1), ords.first().drop(1) + "] and ", "more [999] text."))
        }
        val events = rig.service.askStream(1, "What is a habit?").toList()
        val deltas = events.filterIsInstance<AskEvent.Delta>().joinToString("") { it.text }
        val done = events.last() as AskEvent.Done
        assertTrue(events.first() is AskEvent.Delta)
        assertTrue(events.any { it is AskEvent.Citations })
        assertEquals(1, done.answer.citations.size)
        assertFalse("[999]" in done.answer.text)
        assertEquals(done.answer.text, deltas.trim())
        assertEquals(1, rig.mock.bodies.size) // retrieval only: no chapter pack was generated
        assertTrue("reasoning" in rig.mock.bodies.single()) // low-effort reasoning requested
    }

    @Test fun aStreamCutByTheDeadlineIsShownButNeverCached() = runBlocking {
        val store = FakeAiStore()
        val mock = StreamMock { _, _, _ -> Reply(body = sse(contentFrame("A half"), done = false), stall = true) }
        val service = OpenRouterAiService(
            FakeSettings(), FakeSpine(false), FakePositions(29), store, FakeLibrary(testBook(ContentType.NONFICTION)), mock.client,
            budgeter = RequestBudgeter(store, perMinute = 1000, sleeper = {}), streamLimits = StreamLimits(2_000, 400),
        )
        val ask = service.askStream(1, "What is a habit?").toList()
        assertEquals("A half", (ask.last() as AskEvent.Done).answer.text)
        service.explainStream(1, 14, "habit").toList()
        service.defineStream(1, 13, "focus").toList()
        assertTrue("no truncated answer was stored", store.data.keys.none { it.first in setOf("ask", "explain", "define", "recap") })
    }

    @Test fun askFailureArrivesAsAFailedEventNotAnException() = runBlocking {
        val rig = AskRig(ContentType.NONFICTION, 29) { _, _, _ -> Reply(HttpStatusCode.PaymentRequired, """{"error":{"message":"x"}}""") }
        val last = rig.service.askStream(1, "what is a habit?").toList().last()
        assertTrue(last is AskEvent.Failed && last.error is AiError.InsufficientCredits)
    }

    @Test fun streamedPromptsStayFencedLikeTheOldOnes() = runBlocking {
        for (leaky in listOf(false, true)) {
            val rig = AskRig(ContentType.FICTION, 14, leaky) { _, _, _ -> Reply(body = sseOf("Answer [3].")) }
            rig.service.askStream(1, "what about focus and habit").toList()
            rig.service.explainStream(1, 14, "habit").toList()
            rig.service.defineStream(1, 13, "focus").toList()
            rig.service.recapStream(1).toList()
            assertTrue(rig.mock.bodies.size >= 4)
            rig.mock.bodies.forEach { assertFalse("leak (leaky=$leaky)", MARKER in it) }
        }
    }

    @Test fun explainStripsPassageNumbersAndCachesTheResult() = runBlocking {
        val rig = AskRig(ContentType.FICTION, 14) { _, _, _ -> Reply(body = sseOf("It means focus [1", "2] matters (see [13]).")) }
        val first = rig.service.explainStream(1, 12, "habit").toList()
        val text = (first.last() as AskEvent.Done).answer.text
        assertFalse(Regex("\\[\\d+]").containsMatchIn(text))
        assertTrue(first.none { it is AskEvent.Citations })
        rig.service.explainStream(1, 12, "habit").toList()
        assertEquals(1, rig.mock.bodies.size)
    }

    @Test fun prefetchBuildsPreviousCurrentAndNextOnceAndIsQuietWhenCached() = runBlocking {
        val rig = AskRig(ContentType.NONFICTION, 29) { _, body, _ ->
            val ords = Regex("\\[(\\d+)]").findAll(body.substringAfterLast("Passages:")).map { it.groupValues[1].toInt() }.toList()
            Reply(body = completion("""{"gist":"g ${ords.first()}","keyIdeas":[],"events":[],"entities":[],"edges":[],"cardCandidates":[],"takeaways":["t"],"checkIn":{"prompt":"p","gist":"","rubric":[]},"recapSummary":"r ${ords.first()}"}"""))
        }
        rig.service.prefetchPacks(1, 15) // reading section 2: sections 1, 2 and 3 are all inside the fence
        assertEquals(3, rig.mock.bodies.size)
        rig.service.prefetchPacks(1, 15)
        assertEquals("cached packs make no new request", 3, rig.mock.bodies.size)
        assertTrue("small output cap", rig.mock.bodies.all { "\"max_tokens\":900" in it })
        // and the recap now needs no model call at all
        val recap = rig.service.recapStream(1).toList().joinToString("")
        assertTrue(recap.contains("One: r 0") && recap.contains("Three: r 20"))
        assertEquals(3, rig.mock.bodies.size)
    }

    @Test fun prefetchStaysInsideTheFence() = runBlocking {
        val rig = AskRig(ContentType.FICTION, 14) { _, body, _ ->
            Reply(body = completion("""{"gist":"g","keyIdeas":[],"events":[],"entities":[],"edges":[],"cardCandidates":[],"takeaways":["t"],"checkIn":{"prompt":"p","gist":"","rubric":[]},"recapSummary":"r"}"""))
        }
        rig.service.prefetchPacks(1, 12)
        assertTrue(rig.mock.bodies.isNotEmpty())
        rig.mock.bodies.forEach { assertFalse(MARKER in it) } // ordinal 17 is beyond the frontier of 14
    }
}
