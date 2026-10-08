package app.kepler.engine.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

class RequestBudgeterTest {
    private var now = 1_700_000_000_000L
    private val zone = ZoneId.of("UTC")
    private fun budgeter(
        store: FakeAiStore = FakeAiStore(), perMinute: Int = 15, daily: Int = 50, reserve: Int = 3, spacing: Long = 0,
        waits: MutableList<Long> = ArrayList(),
    ) = RequestBudgeter(
        store, perMinute = perMinute, dailyLimit = daily, clock = { now }, zone = zone,
        sleeper = { waits += it; now += it }, interactiveReservePerMinute = reserve, backgroundSpacingMs = spacing,
    )

    @Test fun perMinuteWindowDelaysBackgroundBeforeInteractive() = runBlocking {
        val waits = ArrayList<Long>()
        val b = budgeter(perMinute = 5, reserve = 2, waits = waits)
        repeat(3) { b.acquire(Priority.BACKGROUND) }
        assertTrue(waits.isEmpty())
        // The 4th background request would eat into the interactive reserve, so it waits for the window.
        b.acquire(Priority.BACKGROUND)
        assertEquals(1, waits.size)
        assertTrue(waits.single() in 1..60_000)
    }

    @Test fun interactiveUsesTheReservedSlots() = runBlocking {
        val waits = ArrayList<Long>()
        val b = budgeter(perMinute = 5, reserve = 2, waits = waits)
        repeat(3) { b.acquire(Priority.BACKGROUND) }
        b.acquire(Priority.INTERACTIVE); b.acquire(Priority.INTERACTIVE)
        assertTrue(waits.isEmpty())
    }

    @Test fun interactiveFailsFastWhenWindowIsFullForLong() = runBlocking {
        val b = budgeter(perMinute = 2, reserve = 0)
        b.acquire(Priority.INTERACTIVE); b.acquire(Priority.INTERACTIVE)
        try { b.acquire(Priority.INTERACTIVE); fail() } catch (e: AiError.RateLimited) { assertTrue((e.retryAfterSeconds ?: 0) > 0) }
    }

    @Test fun backgroundNeverSpendsLastTenPercentOfTheDay() = runBlocking {
        val b = budgeter(perMinute = 1000, daily = 100)
        repeat(90) { b.acquire(Priority.BACKGROUND) }
        try { b.acquire(Priority.BACKGROUND); fail() } catch (e: AiError.BudgetExhausted) { assertTrue(e.backgroundOnly) }
        assertEquals(10, b.remainingToday())
        // Prefetch may use up to 95%, interactive the rest.
        repeat(5) { b.acquire(Priority.PREFETCH) }
        try { b.acquire(Priority.PREFETCH); fail() } catch (e: AiError.BudgetExhausted) { assertTrue(e.backgroundOnly) }
        repeat(5) { b.acquire(Priority.INTERACTIVE) }
        try { b.acquire(Priority.INTERACTIVE); fail() } catch (e: AiError.BudgetExhausted) { assertFalse(e.backgroundOnly) }
        assertEquals(0, b.remainingToday())
    }

    @Test fun countsPersistAcrossInstancesAndResetOnANewLocalDay() = runBlocking {
        val store = FakeAiStore()
        val first = budgeter(store, perMinute = 1000)
        repeat(7) { first.acquire(Priority.INTERACTIVE) }
        assertEquals(7, store.counts.values.sum())
        assertEquals(43, budgeter(store, perMinute = 1000).remainingToday())
        now += 86_400_000L
        assertEquals(50, budgeter(store, perMinute = 1000).remainingToday())
    }

    @Test fun tierSwitchRaisesTheDailyLimit() = runBlocking {
        val b = budgeter()
        assertEquals(50, b.dailyLimit)
        b.setTier(freeTier = false)
        assertEquals(1000, b.dailyLimit)
        assertEquals(1000, b.remainingToday())
    }

    @Test fun markExhaustedStopsEverything() = runBlocking {
        val b = budgeter()
        b.acquire(Priority.INTERACTIVE)
        b.markExhausted()
        assertEquals(0, b.remainingToday())
        try { b.acquire(Priority.INTERACTIVE); fail() } catch (e: AiError.BudgetExhausted) { }
    }
}

class ModelRankingTest {
    private fun m(id: String, ctx: Int = 131072, created: Long = 1, structured: Boolean = true, rf: Boolean = true, free: Boolean = true) =
        ModelInfo(id, id, ctx, created, free, structured, rf || structured, false)

    @Test fun parsesPricingAndCapabilities() {
        val models = Json.parseToJsonElement(FREE_MODELS_JSON).jsonObject["data"]!!.jsonArray
            .mapNotNull { OpenRouterClient.parseModel(it) }
        assertEquals(listOf(true, false, true), models.map { it.free })
        assertTrue(models[0].supportsStructured)
        assertFalse(models[2].supportsStructured); assertTrue(models[2].supportsResponseFormat)
    }

    @Test fun filtersPaidSmallContextAndNonTextModels() {
        val eligible = ModelRanker.eligible(
            listOf(m("a:free"), m("b", free = false), m("c:free", ctx = 32_000), m("llama-guard:free"), m("openrouter/free")),
        )
        assertEquals(listOf("a:free"), eligible.map { it.id })
    }

    @Test fun ranksStructuredThenContextThenHintThenRecency() {
        val ranked = ModelRanker.rank(
            listOf(
                m("x/plain:free", structured = false, rf = false, ctx = 1_000_000),
                m("x/json-only:free", structured = false, ctx = 1_000_000),
                m("x/small-ctx:free", ctx = 70_000),
                m("x/old:free", created = 1),
                m("x/gpt-oss-120b:free", created = 1),
                m("x/new:free", created = 99),
            ),
        ).map { it.id }
        // Structured outputs first; inside that, 128k tier (hint wins over recency, then newest), small context last.
        assertEquals(
            listOf("x/gpt-oss-120b:free", "x/new:free", "x/old:free", "x/small-ctx:free", "x/json-only:free", "x/plain:free"),
            ranked,
        )
    }

    @Test fun tinyModelsRankLastEvenWithStructuredOutputs() {
        assertTrue(ModelRanker.isSmall("liquid/lfm-2.5-2.6b:free"))
        assertTrue(ModelRanker.isSmall("apodex/apodex-1.1-mini:free"))
        assertFalse(ModelRanker.isSmall("google/gemma-4-26b-a4b-it:free"))
        assertFalse(ModelRanker.isSmall("nvidia/nemotron-3-super-120b-a12b:free"))
        val ranked = ModelRanker.rank(listOf(m("liquid/lfm-2.5-2.6b:free"), m("google/gemma-4-31b-it:free", structured = false))).map { it.id }
        assertEquals(listOf("google/gemma-4-31b-it:free", "liquid/lfm-2.5-2.6b:free"), ranked)
    }

    @Test fun userOverrideBeatsTheRanking() = runBlocking {
        val mock = MockOpenRouter({ _, _ -> OK to "{}" })
        val catalog = ModelCatalog(mock.client, FakeAiStore())
        assertEquals(listOf("vendor/big-model:free"), catalog.candidates("k", null).map { it.id })
        assertEquals(listOf("my/own-model"), catalog.candidates("k", "my/own-model").map { it.id })
    }

    @Test fun keyInfoGivesTheTier() = runBlocking {
        val mock = MockOpenRouter({ _, _ -> OK to "{}" })
        assertTrue(mock.client.keyInfo("k").isFreeTier)
    }

    @Test fun fallsBackToPersistedModelListOffline() = runBlocking {
        val store = FakeAiStore()
        ModelCatalog(MockOpenRouter({ _, _ -> OK to "{}" }).client, store).all("k")
        val offline = OpenRouterClient(io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { throw java.io.IOException("offline") }))
        val got = ModelCatalog(offline, store).all("k")
        assertEquals(3, got.size)
    }
}
