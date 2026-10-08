package app.betterbooks.engine.ai

import app.betterbooks.core.model.AiStore
import app.betterbooks.core.model.Book
import app.betterbooks.core.model.ContentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class KeyIdea(val claim: String = "", val evidenceOrdinals: List<Int> = emptyList(), val rubric: List<String> = emptyList())

@Serializable
data class PackEvent(val summary: String = "", val ordinals: List<Int> = emptyList())

@Serializable
data class PackEntity(
    val name: String = "", val kind: String = "CONCEPT", val aliases: List<String> = emptyList(),
    val definition: String = "", val firstOrdinal: Int = -1,
)

@Serializable
data class PackEdge(val from: String = "", val to: String = "", val relation: String = "", val evidenceOrdinal: Int = -1)

@Serializable
data class PackCard(val q: String = "", val hint: String = "", val sourceOrdinals: List<Int> = emptyList())

@Serializable
data class PackCheckIn(val prompt: String = "", val gist: String = "", val rubric: List<String> = emptyList())

/** Everything the study features need from one section, produced by one request and cached. */
@Serializable
data class ChapterPack(
    val gist: String = "",
    val keyIdeas: List<KeyIdea> = emptyList(),
    val events: List<PackEvent> = emptyList(),
    val entities: List<PackEntity> = emptyList(),
    val edges: List<PackEdge> = emptyList(),
    val cardCandidates: List<PackCard> = emptyList(),
    val takeaways: List<String> = emptyList(),
    val checkIn: PackCheckIn = PackCheckIn(),
    val recapSummary: String = "",
)

/** A pack together with the ordinal range it was built from. */
class PackResult(val pack: ChapterPack, val firstOrdinal: Int, val lastOrdinal: Int, val complete: Boolean)

/**
 * One request per section, cached forever. The cache key hashes the exact (fence-clipped) text that was sent,
 * so a pack built from more text than the current fence allows can never be returned for a stricter fence.
 */
internal class ChapterPackService(
    private val runner: LlmRunner,
    private val store: AiStore,
    private val includeBookMeta: Boolean = true,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun spec(variantTag: String) = JsonSpec(
        name = "chapter_pack", schema = Schemas.pack, serializer = ChapterPack.serializer(),
        validate = { if (it.gist.isBlank()) "gist is empty" else null },
    ).also { variantTag.length }

    private fun variant(book: Book) =
        if (book.contentType == ContentType.FICTION) PromptRegistry.PACK_FICTION else PromptRegistry.PACK_NONFICTION

    private fun key(book: Book, text: SectionText) = sha256("pack", variant(book).tag, text.hash)

    suspend fun cached(book: Book, text: SectionText): PackResult? =
        store.get(KIND, key(book, text))?.let { decode(it, text) }

    /** Returns the cached pack, or builds it with one request when [generate] is true. */
    suspend fun get(book: Book, text: SectionText, priority: Priority, generate: Boolean = true): PackResult? {
        val k = key(book, text)
        store.get(KIND, k)?.let { decode(it, text)?.let { r -> return r } }
        if (!generate) return null
        return locks.computeIfAbsent(k) { Mutex() }.withLock {
            store.get(KIND, k)?.let { decode(it, text)?.let { r -> return@withLock r } }
            val prompt = variant(book)
            val pack = sanitize(
                runner.json(priority, spec(prompt.tag), prompt.system, userMessage(book, text), maxTokens = 3500),
                text,
            )
            store.put(KIND, k, AiJson.encodeToString(ChapterPack.serializer(), pack))
            PackResult(pack, text.firstOrdinal, text.lastOrdinal, text.complete)
        }
    }

    private fun userMessage(book: Book, text: SectionText) = buildString {
        if (includeBookMeta) appendLine("Book: ${book.title} by ${book.authors}")
        appendLine("Section: ${text.section.title}")
        appendLine("Passages:")
        append(text.rendered)
    }

    private fun decode(json: String, text: SectionText): PackResult? = try {
        PackResult(AiJson.decodeFromString(ChapterPack.serializer(), json), text.firstOrdinal, text.lastOrdinal, text.complete)
    } catch (e: Exception) { null }

    /** Drops anything citing ordinals outside the text the model was shown. Ids and labels only; nothing is rewritten. */
    internal fun sanitize(p: ChapterPack, text: SectionText): ChapterPack {
        val range = text.firstOrdinal..text.lastOrdinal
        fun ords(l: List<Int>) = l.filter { it in range }.distinct()
        return p.copy(
            keyIdeas = p.keyIdeas.map { it.copy(evidenceOrdinals = ords(it.evidenceOrdinals)) }
                .filter { it.claim.isNotBlank() && it.evidenceOrdinals.isNotEmpty() },
            events = p.events.map { it.copy(ordinals = ords(it.ordinals)) }
                .filter { it.summary.isNotBlank() && it.ordinals.isNotEmpty() },
            entities = p.entities.filter { it.name.isNotBlank() && it.firstOrdinal in range },
            edges = p.edges.filter { it.from.isNotBlank() && it.to.isNotBlank() && it.evidenceOrdinal in range },
            cardCandidates = p.cardCandidates.map { it.copy(sourceOrdinals = ords(it.sourceOrdinals)) }
                .filter { it.q.isNotBlank() && it.sourceOrdinals.isNotEmpty() },
            takeaways = p.takeaways.filter { it.isNotBlank() },
        )
    }

    private companion object { const val KIND = "chapter-pack" }
}
