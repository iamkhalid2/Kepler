package app.betterbooks.engine.ai

import app.betterbooks.core.model.AiStore
import app.betterbooks.core.model.AppSettings
import app.betterbooks.core.model.Book
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.ContentType
import app.betterbooks.core.model.ImportResult
import app.betterbooks.core.model.LibraryRepository
import app.betterbooks.core.model.Position
import app.betterbooks.core.model.PositionKind
import app.betterbooks.core.model.PositionRepository
import app.betterbooks.core.model.Section
import app.betterbooks.core.model.SectionKind
import app.betterbooks.core.model.Sentence
import app.betterbooks.core.model.SettingsStore
import app.betterbooks.core.model.SpineRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeAiStore : AiStore {
    val data = HashMap<Pair<String, String>, String>()
    val counts = HashMap<Pair<String, String>, Int>()
    override suspend fun get(kind: String, key: String) = data[kind to key]
    override suspend fun put(kind: String, key: String, json: String) { data[kind to key] = json }
    override suspend fun requestsOn(day: String, provider: String) = counts[day to provider] ?: 0
    override suspend fun addRequests(day: String, provider: String, count: Int) {
        counts[day to provider] = (counts[day to provider] ?: 0) + count
    }
}

class FakeSettings(var key: String? = "test-key", model: String? = null) : SettingsStore {
    private val state = MutableStateFlow(AppSettings(openRouterModel = model))
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
    override suspend fun fishKey(): String? = null
    override suspend fun setFishKey(key: String?) {}
    override suspend fun openRouterKey(): String? = key
    override suspend fun setOpenRouterKey(key: String?) { this.key = key }
}

class FakePositions(var frontier: Int) : PositionRepository {
    override fun position(bookId: BookId, kind: PositionKind): Flow<Position?> = flowOf(null)
    override suspend fun savePosition(bookId: BookId, kind: PositionKind, ordinal: Int) {}
    override suspend fun markConsumed(bookId: BookId, fromOrdinal: Int, toOrdinal: Int, via: PositionKind) {}
    override suspend fun frontier(bookId: BookId) = frontier
}

class FakeLibrary(private val book: Book) : LibraryRepository {
    override val books: Flow<List<Book>> = flowOf(listOf(book))
    override fun book(id: BookId): Flow<Book?> = flowOf(book.takeIf { it.id == id })
    override suspend fun importBook(sourceUri: String): ImportResult = ImportResult.Failed("unused")
    override suspend fun delete(id: BookId) {}
    override suspend fun markOpened(id: BookId) {}
}

const val MARKER = "ZEBRAMARKERXYZ"

/**
 * Three sections of ten sentences (0-9, 10-19, 20-29). The marker sits at ordinals 17 and 25.
 * A [leaky] spine ignores every upper bound, like a buggy repository would.
 */
class FakeSpine(val leaky: Boolean = false, val bookId: BookId = 1L) : SpineRepository {
    val sections = listOf(
        Section(1, bookId, null, 0, 1, "One", "a.xhtml", SectionKind.BODY, 0, 9, true),
        Section(2, bookId, null, 1, 1, "Two", "b.xhtml", SectionKind.BODY, 10, 19, true),
        Section(3, bookId, null, 2, 1, "Three", "c.xhtml", SectionKind.BODY, 20, 29, true),
    )
    val all = (0..29).map {
        val extra = if (it == 17 || it == 25) " $MARKER" else ""
        Sentence(bookId, it, sections[it / 10].id, sections[it / 10].href, "Sentence $it about habit and focus$extra.", "", "", it)
    }
    override suspend fun sections(bookId: BookId) = sections
    override suspend fun sentence(bookId: BookId, ordinal: Int) = all.getOrNull(ordinal)
    override suspend fun sentences(bookId: BookId, fromOrdinal: Int, toOrdinal: Int) =
        all.filter { it.ordinal >= fromOrdinal && (leaky || it.ordinal <= toOrdinal) }
    override suspend fun sentenceCount(bookId: BookId) = all.size
    override suspend fun search(bookId: BookId, query: String, maxOrdinal: Int?, limit: Int): List<Sentence> {
        val words = query.lowercase().split(" ").filter { it.isNotBlank() }
        return all.filter { s -> words.all { s.text.lowercase().contains(it) } && (leaky || maxOrdinal == null || s.ordinal <= maxOrdinal) }
            .take(limit)
    }
}

fun testBook(type: ContentType) = Book(
    1, "A Test Book", "Some Author", "en", type, "/x.epub", null, 0, null, 30, 0f, 0f,
)

const val FREE_MODELS_JSON = """{"data":[
 {"id":"vendor/big-model:free","name":"Big","created":1750000000,"context_length":131072,
  "pricing":{"prompt":"0","completion":"0"},
  "architecture":{"input_modalities":["text"],"output_modalities":["text"]},
  "supported_parameters":["response_format","structured_outputs","max_tokens"]},
 {"id":"vendor/paid","name":"Paid","created":1760000000,"context_length":200000,
  "pricing":{"prompt":"0.000001","completion":"0.000002"},
  "architecture":{"output_modalities":["text"]},"supported_parameters":["response_format","structured_outputs"]},
 {"id":"vendor/small:free","name":"Small","created":1770000000,"context_length":32768,
  "pricing":{"prompt":"0","completion":"0"},
  "architecture":{"output_modalities":["text"]},"supported_parameters":["response_format"]}
]}"""

const val KEY_JSON = """{"data":{"label":"sk-or-v1-abc","is_free_tier":true,"usage":0,"limit":null}}"""

/** Records every chat request body so tests can assert on what was sent. */
class MockOpenRouter(
    private val chat: (body: String, call: Int) -> Pair<HttpStatusCode, String>,
    private val models: String = FREE_MODELS_JSON,
) {
    val chatBodies = ArrayList<String>()
    val paths = ArrayList<String>()

    val client: OpenRouterClient = OpenRouterClient(HttpClient(MockEngine { req -> handle(req) }))

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.handle(req: HttpRequestData) = run {
        val path = req.url.encodedPath
        paths += path
        when {
            path.endsWith("/key") -> json(HttpStatusCode.OK, KEY_JSON)
            path.endsWith("/models") -> json(HttpStatusCode.OK, models)
            path.endsWith("/chat/completions") -> {
                val body = (req.body as TextContent).text
                chatBodies += body
                val (status, content) = chat(body, chatBodies.size)
                json(status, content)
            }
            else -> json(HttpStatusCode.NotFound, """{"error":{"message":"nope"}}""")
        }
    }
}

fun completion(content: String): String {
    val escaped = kotlinx.serialization.json.JsonPrimitive(content).toString()
    return """{"model":"vendor/big-model:free","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":$escaped}}]}"""
}

val OK = HttpStatusCode.OK
