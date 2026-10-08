package app.kepler.feature.library

import app.kepler.core.model.AiBudget
import app.kepler.core.model.AppSettings
import app.kepler.core.model.AudioRepository
import app.kepler.core.model.AudioSegment
import app.kepler.core.model.Book
import app.kepler.core.model.BookId
import app.kepler.core.model.Card
import app.kepler.core.model.CardRepository
import app.kepler.core.model.ContentType
import app.kepler.core.model.ImportResult
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.NarrationConfig
import app.kepler.core.model.NarrationEstimate
import app.kepler.core.model.NarrationService
import app.kepler.core.model.NarrationState
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.SyncRow
import app.kepler.core.model.VoiceOption
import app.kepler.core.model.VoiceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

fun book(
    id: Long, title: String = "Book $id", authors: String = "Author $id", addedAt: Long = id,
    lastOpenedAt: Long? = null, sentences: Int = 5_000, read: Float = 0f, listen: Float = 0f,
) = Book(id, title, authors, "en", ContentType.NONFICTION, "/e/$id.epub", null, addedAt, lastOpenedAt, sentences, read, listen)

class FakeLibrary(initial: List<Book> = emptyList()) : LibraryRepository {
    val all = MutableStateFlow(initial)
    var nextImport: ImportResult = ImportResult.Failed("x")
    var importThrows: Exception? = null
    val imported = mutableListOf<String>()
    val deleted = mutableListOf<BookId>()
    val opened = mutableListOf<BookId>()
    override val books: Flow<List<Book>> = all
    override fun book(id: BookId): Flow<Book?> = all.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun importBook(sourceUri: String): ImportResult {
        imported += sourceUri
        importThrows?.let { throw it }
        return nextImport
    }
    override suspend fun delete(id: BookId) { deleted += id; all.value = all.value.filterNot { it.id == id } }
    override suspend fun markOpened(id: BookId) { opened += id }
}

class FakeSettings(initial: AppSettings = AppSettings()) : SettingsStore {
    private val flow = MutableStateFlow(initial)
    var fish: String? = null
    var openRouter: String? = null
    override val settings: Flow<AppSettings> = flow
    override suspend fun update(transform: (AppSettings) -> AppSettings) { flow.value = transform(flow.value) }
    override suspend fun fishKey() = fish
    override suspend fun setFishKey(key: String?) { fish = key }
    override suspend fun openRouterKey() = openRouter
    override suspend fun setOpenRouterKey(key: String?) { openRouter = key }
    val current: AppSettings get() = flow.value
}

class FakeNarration : NarrationService {
    val states = MutableStateFlow<NarrationState>(NarrationState.Idle)
    var voicesByProvider: Map<String, List<VoiceOption>> = emptyMap()
    var voicesThrow: Exception? = null
    var previewPath = "/tmp/preview.mp3"
    var previewThrows: Exception? = null
    var estimateResult = NarrationEstimate(100_000, 100_000, 0.0, 10, 3_600, 600, true)
    val estimateCalls = mutableListOf<Pair<NarrationConfig, Int>>()
    val started = mutableListOf<NarrationConfig>()
    val stopped = mutableListOf<BookId>()
    override fun state(bookId: BookId): Flow<NarrationState> = states
    override suspend fun voices(provider: String): List<VoiceOption> {
        voicesThrow?.let { throw it }
        return voicesByProvider[provider].orEmpty()
    }
    override suspend fun estimate(bookId: BookId, config: NarrationConfig, fromOrdinal: Int): NarrationEstimate {
        estimateCalls += config to fromOrdinal
        return estimateResult
    }
    override fun start(bookId: BookId, config: NarrationConfig, fromOrdinal: Int) { started += config }
    override fun stop(bookId: BookId) { stopped += bookId }
    override suspend fun ensureAhead(bookId: BookId, config: NarrationConfig, ordinal: Int, minutes: Int) = Unit
    override suspend fun preview(voice: VoiceRef, sampleText: String): String {
        previewThrows?.let { throw it }
        return previewPath
    }
}

class FakeAudio : AudioRepository {
    val coverage = MutableStateFlow(0f)
    override fun segments(bookId: BookId): Flow<List<AudioSegment>> = MutableStateFlow(emptyList())
    override suspend fun addSegment(segment: AudioSegment, rows: List<SyncRow>) = 0L
    override suspend fun segmentFor(bookId: BookId, ordinal: Int): AudioSegment? = null
    override suspend fun syncRows(bookId: BookId, fromOrdinal: Int, toOrdinal: Int): List<SyncRow> = emptyList()
    override fun coverage(bookId: BookId): Flow<Float> = coverage
    override suspend fun deleteSegments(bookId: BookId) = Unit
}

class FakeCards : CardRepository {
    val all = MutableStateFlow<List<Card>>(emptyList())
    override fun dueCards(nowMs: Long, limit: Int): Flow<List<Card>> = all
    override fun cards(bookId: BookId?): Flow<List<Card>> = all
    override fun dueCount(nowMs: Long): Flow<Int> = all.map { it.size }
    override suspend fun add(card: Card) = 0L
    override suspend fun update(card: Card) = Unit
    override suspend fun delete(id: Long) = Unit
    override suspend fun reviewedToday(nowMs: Long) = 0
}

class FakeDeps(
    override val library: FakeLibrary = FakeLibrary(),
    override val settings: FakeSettings = FakeSettings(),
    override val narration: FakeNarration = FakeNarration(),
    override val audio: FakeAudio = FakeAudio(),
    override val cards: FakeCards = FakeCards(),
) : LibraryDeps {
    var fishOk = true
    var orOk = true
    var verifyThrows: Exception? = null
    var battery = false
    var budget: AiBudget? = null
    var models: List<String> = emptyList()
    var spent = 0
    override val verifyFishKey: suspend (String) -> Boolean = { verifyThrows?.let { e -> throw e }; fishOk }
    override val verifyOpenRouterKey: suspend (String) -> Boolean = { verifyThrows?.let { e -> throw e }; orOk }
    override val aiBudget: suspend () -> AiBudget? = { budget }
    override val freeModels: suspend () -> List<String> = { models }
    override val spentThisMonthCents: () -> Int = { spent }
    override val showsBatteryGuide: () -> Boolean = { battery }
}

fun voice(provider: String, id: String) = VoiceOption(VoiceRef(provider, id, id.replaceFirstChar { it.uppercase() }), "", "en", null)
