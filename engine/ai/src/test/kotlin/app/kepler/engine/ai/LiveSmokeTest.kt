package app.kepler.engine.ai

import app.kepler.core.model.ContentType
import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import app.kepler.core.model.Sentence
import app.kepler.core.model.SpineRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real OpenRouter smoke test (about 5 chat requests on a ':free' model). Skipped unless LIVE_SMOKE=1.
 * Reads the key from local.properties inside the process; never prints or stores it.
 */
class LiveSmokeTest {
    private val passage = listOf(
        "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
        "However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered the rightful property of some one or other of their daughters.",
        "\"My dear Mr. Bennet,\" said his lady to him one day, \"have you heard that Netherfield Park is let at last?\"",
        "Mr. Bennet replied that he had not.",
        "\"But it is,\" returned she; \"for Mrs. Long has just been here, and she told me all about it.\"",
        "Mr. Bennet made no answer.",
        "\"Do you not want to know who has taken it?\" cried his wife impatiently.",
        "\"You want to tell me, and I have no objection to hearing it.\"",
        "This was invitation enough.",
        "\"Why, my dear, you must know, Mrs. Long says that Netherfield is taken by a young man of large fortune from the north of England; that he came down on Monday in a chaise and four to see the place, and was so much delighted with it that he agreed with Mr. Morris immediately.\"",
    )

    @Test fun live() = runBlocking {
        assumeTrue(System.getenv("LIVE_SMOKE") == "1")
        val props = java.util.Properties().apply {
            File("../../local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val key = props.getProperty("OPENROUTER_API_KEY")?.trim().orEmpty()
        assumeTrue(key.isNotEmpty())
        val out = StringBuilder()
        fun log(s: String) { out.appendLine(s) }
        try {
            val spine = object : SpineRepository {
                val sec = Section(1, 1, null, 0, 1, "Chapter 1", "c1.xhtml", SectionKind.BODY, 0, passage.lastIndex, true)
                val all = passage.mapIndexed { i, t -> Sentence(1, i, 1, "c1.xhtml", t, "", "", i) }
                override suspend fun sections(bookId: Long) = listOf(sec)
                override suspend fun sentence(bookId: Long, ordinal: Int) = all.getOrNull(ordinal)
                override suspend fun sentences(bookId: Long, fromOrdinal: Int, toOrdinal: Int) = all.filter { it.ordinal in fromOrdinal..toOrdinal }
                override suspend fun sentenceCount(bookId: Long) = all.size
                override suspend fun search(bookId: Long, query: String, maxOrdinal: Int?, limit: Int) =
                    all.filter { s -> query.lowercase().split(" ").any { it.length > 2 && s.text.lowercase().contains(it) } && (maxOrdinal == null || s.ordinal <= maxOrdinal) }.take(limit)
            }
            val store = FakeAiStore()
            val svc = OpenRouterAiService(
                FakeSettings(key = key), spine, FakePositions(passage.lastIndex), store,
                FakeLibrary(testBook(ContentType.FICTION).copy(title = "Pride and Prejudice", authors = "Jane Austen")),
            )
            val info = svc.keyInfo()
            log("key: freeTier=${info?.isFreeTier} usage=${info?.usage} limit=${info?.limit}")
            val models = svc.freeModels()
            log("eligible free models: ${models.size}")
            models.take(8).forEach { log("  ${it.id} ctx=${it.contextLength} structured=${it.supportsStructured} rf=${it.supportsResponseFormat}") }
            log("budget before: ${svc.budget()}")
            val t0 = System.currentTimeMillis()
            val pack = svc.buildPack(1, 1, Priority.INTERACTIVE)
            log("pack ok in ${System.currentTimeMillis() - t0} ms via ${svc.lastModelUsed}: gist=${pack.pack.gist.take(120)}")
            log("  entities=${pack.pack.entities.map { it.name }} events=${pack.pack.events.size} cards=${pack.pack.cardCandidates.size} takeaways=${pack.pack.takeaways.size}")
            log("explain: " + svc.explain(1, 3, "Netherfield Park is let at last").take(200))
            log("ask: " + svc.ask(1, "Who has taken Netherfield?").let { "${it.text.take(200)} cites=${it.citations.map { c -> c.ordinal }}" })
            log("budget after: ${svc.budget()}")
        } catch (e: Throwable) {
            log("FAILED: ${e::class.simpleName}: ${e.message?.take(300)}")
        }
        File(System.getenv("SMOKE_OUT") ?: "/tmp/ai-smoke.txt").writeText(out.toString())
    }
}
