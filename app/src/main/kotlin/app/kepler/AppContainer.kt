package app.kepler

import android.app.Application
import app.kepler.core.database.DataLayer
import app.kepler.core.model.AiBudget
import app.kepler.core.model.AiService
import app.kepler.core.model.AnnotationRepository
import app.kepler.core.model.AudioRepository
import app.kepler.core.model.CardRepository
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.NarrationService
import app.kepler.core.model.PositionRepository
import app.kepler.core.model.ReviewStatsStore
import app.kepler.core.model.Scheduler
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.SpineRepository
import app.kepler.engine.ai.AiError
import app.kepler.engine.ai.OpenRouterAiService
import app.kepler.engine.ai.OpenRouterClient
import app.kepler.engine.epub.ingest.EpubIngestor
import app.kepler.engine.playback.PlaybackClient
import app.kepler.engine.playback.PlaybackDependencies
import app.kepler.engine.srs.FsrsScheduler
import app.kepler.engine.tts.FishProvider
import app.kepler.engine.tts.NarrationEngine
import app.kepler.engine.tts.TtsException
import app.kepler.feature.library.KeyKind
import app.kepler.feature.library.LibraryDeps
import app.kepler.feature.reader.ReaderDeps
import app.kepler.feature.study.StudyDeps

/** Manual dependency container: one instance per process, created in [KeplerApp]. */
class AppContainer(private val app: Application) {
    val data = DataLayer(app, EpubIngestor(app))
    val narration = NarrationEngine(app, data.settings, data.spine, data.audio)
    val ai = OpenRouterAiService(data.settings, data.spine, data.positions, data.aiStore, data.library)
    val scheduler: Scheduler = FsrsScheduler()
    val reviewStats: ReviewStatsStore = PrefsReviewStatsStore(app)
    val playback: PlaybackClient by lazy { PlaybackClient(app) }

    /** Set by the activity while it is alive; study export goes through the system file picker. */
    @Volatile var documentWriter: (suspend (name: String, mime: String, text: String) -> Unit)? = null

    init {
        PlaybackClient.initialize(
            PlaybackDependencies(data.audio, data.spine, data.positions, narration, data.library, data.settings),
        )
    }

    val libraryDeps: LibraryDeps = object : LibraryDeps {
        override val library: LibraryRepository = data.library
        override val settings: SettingsStore = data.settings
        override val narration: NarrationService = this@AppContainer.narration
        override val audio: AudioRepository = data.audio
        override val cards: CardRepository = data.cards
        override val verifyFishKey: suspend (String) -> Boolean = { key ->
            try {
                FishProvider({ key }, maxAttempts = 1).voices(null)
                true
            } catch (e: TtsException.InvalidKey) {
                false
            } catch (e: TtsException.PaymentRequired) {
                true
            }
        }
        override val verifyOpenRouterKey: suspend (String) -> Boolean = { key ->
            try {
                OpenRouterClient().keyInfo(key)
                true
            } catch (e: AiError.Unauthorized) {
                false
            }
        }
        override val aiBudget: suspend () -> AiBudget? = {
            if (data.settings.openRouterKey() == null) null else runCatching { ai.budget() }.getOrNull()
        }
        override val freeModels: suspend () -> List<String> = {
            runCatching { ai.freeModels().map { it.id } }.getOrDefault(emptyList())
        }
        override val spentThisMonthCents: () -> Int = { this@AppContainer.narration.spentThisMonthCents().toInt() }
        override val appVersion: String = BuildConfig.VERSION_NAME
        override val devKeyPrefill: (KeyKind) -> String = { kind ->
            if (!BuildConfig.DEBUG) "" else when (kind) {
                KeyKind.Fish -> BuildConfig.FISH_API_KEY
                KeyKind.OpenRouter -> BuildConfig.OPENROUTER_API_KEY
            }
        }
    }

    val readerDeps: ReaderDeps = object : ReaderDeps {
        override val spine: SpineRepository = data.spine
        override val positions: PositionRepository = data.positions
        override val annotations: AnnotationRepository = data.annotations
        override val library: LibraryRepository = data.library
        override val settings: SettingsStore = data.settings
    }

    val studyDeps: StudyDeps = object : StudyDeps {
        override val ai: AiService = this@AppContainer.ai
        override val cards: CardRepository = data.cards
        override val scheduler: Scheduler = this@AppContainer.scheduler
        override val annotations: AnnotationRepository = data.annotations
        override val positions: PositionRepository = data.positions
        override val spine: SpineRepository = data.spine
        override val library: LibraryRepository = data.library
        override val reviewStats: ReviewStatsStore = this@AppContainer.reviewStats
        override val writeDocument: suspend (String, String, String) -> Unit = { name, mime, text ->
            val w = documentWriter ?: error("No window to pick a file")
            w(name, mime, text)
        }
    }
}

class KeplerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
