package app.kepler.engine.ai

import app.kepler.core.model.AskEvent
import app.kepler.core.model.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real OpenRouter latency check on ':free' models (about 11 chat requests). Skipped unless LIVE_SPEED=1.
 * Writes a plain report to the path in LIVE_SPEED_OUT; reads the key inside the process and never prints it.
 */
class LiveSpeedTest {
    @Test fun live() = runBlocking {
        assumeTrue(System.getenv("LIVE_SPEED") == "1")
        val props = java.util.Properties().apply {
            File("../../local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val key = props.getProperty("OPENROUTER_API_KEY")?.trim().orEmpty()
        assumeTrue(key.isNotEmpty())
        val out = StringBuilder()
        fun log(s: String) { out.appendLine(s) }
        try {
            val client = OpenRouterClient()
            // Survives between runs, so a second run reuses the probe results instead of spending requests again.
            val storeFile = File(System.getenv("LIVE_SPEED_OUT") + ".store.json")
            val store = object : app.kepler.core.model.AiStore {
                val inner = FakeAiStore()
                init {
                    runCatching {
                        kotlinx.serialization.json.Json.parseToJsonElement(storeFile.readText()).let { el ->
                            (el as kotlinx.serialization.json.JsonObject).forEach { (k, v) ->
                                val (kind, key) = k.split("|", limit = 2)
                                kotlinx.coroutines.runBlocking { inner.put(kind, key, (v as kotlinx.serialization.json.JsonPrimitive).content) }
                            }
                        }
                    }
                }
                override suspend fun get(kind: String, key: String) = inner.get(kind, key)
                override suspend fun put(kind: String, key: String, json: String) { inner.put(kind, key, json); persist() }
                override suspend fun requestsOn(day: String, provider: String) = inner.requestsOn(day, provider)
                override suspend fun addRequests(day: String, provider: String, count: Int) { inner.addRequests(day, provider, count); persist() }
                fun persist() {
                    val obj = kotlinx.serialization.json.JsonObject(inner.data.filterKeys { it.first == "model-probe" }
                        .mapKeys { "${it.key.first}|${it.key.second}" }.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })
                    storeFile.writeText(obj.toString())
                }
            }
            val budgeter = RequestBudgeter(store)
            val all = client.models(key)
            val free = ModelRanker.interactivePrior(all)
            log("eligible free models (interactive prior order): " + free.joinToString { it.id })
            val prober = ModelProber(client, store, budgeter, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
            val t0 = System.nanoTime()
            if (prober.measurements().isEmpty()) prober.probeNow(key, free) else log("(reusing stored probe results)")
            log("probe round took ${(System.nanoTime() - t0) / 1_000_000} ms")
            val order = prober.order(key, free, awaitFirstUse = false)
            log("candidate order after probing (p50 ttft ms):")
            for (m in order) log("  ${m.id}  ${prober.p50(m.id) ?: "unmeasured"}")
            val dropped = free.map { it.id } - order.map { it.id }.toSet()
            log("dropped (failed/429/slow): $dropped")
            log("failure reasons: ${prober.failures()}")

            val spine = FakeSpine()
            val service = OpenRouterAiService(
                FakeSettings(key), spine, FakePositions(29), store, FakeLibrary(testBook(ContentType.NONFICTION)), client,
                budgeter = budgeter, prober = prober,
            )
            for (q in listOf("What does the text say about habit and focus?")) {
                val start = System.nanoTime()
                var first = -1L
                var text = StringBuilder()
                var terminal: AskEvent? = null
                service.askStream(1, q).collect { ev ->
                    if (ev is AskEvent.Delta) { if (first < 0) first = (System.nanoTime() - start) / 1_000_000; text.append(ev.text) }
                    if (ev is AskEvent.Done || ev is AskEvent.Failed) terminal = ev
                }
                log("ASK \"$q\": first token ${first} ms, total ${(System.nanoTime() - start) / 1_000_000} ms, " +
                    "model ${service.lastModelUsed}, ended ${terminal?.let { it::class.simpleName }}, ${text.length} chars")
                log("  answer: " + text.toString().take(220).replace('\n', ' '))
            }
            val before = store.requestsOn(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(), "openrouter")
            val p0 = System.nanoTime()
            try {
                service.buildPack(1, 2, Priority.BACKGROUND)
                log("one background pack build: ${(System.nanoTime() - p0) / 1_000_000} ms, model ${service.lastModelUsed}")
            } catch (e: AiError) {
                log("pack build failed after ${(System.nanoTime() - p0) / 1_000_000} ms: ${e::class.simpleName} ${e.message}")
            }
            val after = store.requestsOn(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(), "openrouter")
            log("chat requests spent this run (budgeter): $after (pack stage: ${after - before})")
        } catch (e: Throwable) {
            log("FAILED: ${e::class.simpleName}: ${e.message}")
        }
        File(System.getenv("LIVE_SPEED_OUT") ?: "/tmp/live-speed.txt").writeText(out.toString())
    }
}
