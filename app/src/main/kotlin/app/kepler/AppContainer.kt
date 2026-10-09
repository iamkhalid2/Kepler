package app.kepler

import android.app.Application
import app.kepler.core.database.DataLayer
import app.kepler.core.model.AiBudget
import app.kepler.core.model.AiService
import app.kepler.core.model.AnnotationRepository
import app.kepler.core.model.AudioRepository
import app.kepler.core.model.CardRepository
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.NarrationControls
import app.kepler.core.model.NarrationService
import app.kepler.core.model.PlaybackController
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
import app.kepler.engine.tts.Estimator
import app.kepler.engine.tts.FishProvider
import app.kepler.engine.tts.ModelPolicy
import app.kepler.engine.tts.NarrationEngine
import app.kepler.engine.tts.TtsException
import app.kepler.feature.library.KeyKind
import app.kepler.feature.library.LibraryDeps
import app.kepler.feature.reader.ReaderDeps
import app.kepler.feature.study.StudyDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manual dependency container: one instance per process, created in [KeplerApp.onCreate].
 *
 * Eager: the data layer, the narration engine (a WorkManager job may start this process and look for it) and the
 * playback dependencies (the media service stops itself without them). Everything else is built on first use, so
 * the first frame does not wait for it.
 */
class AppContainer(private val app: Application) {
    val data = DataLayer(app, EpubIngestor(app))
    val narration = NarrationEngine(app, data.settings, data.spine, data.audio)
    val ai: OpenRouterAiService by lazy {
        OpenRouterAiService(data.settings, data.spine, data.positions, data.aiStore, data.library, probeModels = true)
    }
    val scheduler: Scheduler by lazy { FsrsScheduler() }
    val reviewStats: ReviewStatsStore by lazy { PrefsReviewStatsStore(app) }
    val playback: PlaybackClient by lazy { PlaybackClient(app) }

    /** Set by the activity while it is alive; study export goes through the system file picker. */
    @Volatile var documentWriter: (suspend (name: String, mime: String, text: String) -> Unit)? = null

    init {
        PlaybackClient.initialize(
            PlaybackDependencies(data.audio, data.spine, data.positions, narration, data.library, data.settings),
        )
    }

    val libraryDeps: LibraryDeps by lazy { libraryDepsImpl() }
    val readerDeps: ReaderDeps by lazy { readerDepsImpl() }
    val studyDeps: StudyDeps by lazy { studyDepsImpl() }

    /** Opens the database and settings file off the main thread so the first screen finds them warm. */
    fun warmUp(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                data.settings.settings.first()
                data.library.books.first()
            }
        }
    }

    /** One hour of paid Fish audio, from the narration estimator's pace and the model's price per byte. */
    private fun paidCentsPerHour(): Double {
        val sample = "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness."
        val bytesPerSecond = sample.toByteArray(Charsets.UTF_8).size / Estimator.audioSeconds(sample, 1f)
        return bytesPerSecond * 3600 * ModelPolicy.PAID_CENTS_PER_BYTE
    }

    private fun libraryDepsImpl(): LibraryDeps = object : LibraryDeps {
        override val library: LibraryRepository = data.library
        override val settings: SettingsStore = data.settings
        override val narration: NarrationService = this@AppContainer.narration
        override val controls: NarrationControls = this@AppContainer.narration
        override val playback: PlaybackController by lazy { this@AppContainer.playback }
        override val paidCentsPerHour: () -> Double = ::paidCentsPerHour
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

    private fun readerDepsImpl(): ReaderDeps = object : ReaderDeps {
        override val spine: SpineRepository = data.spine
        override val positions: PositionRepository = data.positions
        override val annotations: AnnotationRepository = data.annotations
        override val library: LibraryRepository = data.library
        override val settings: SettingsStore = data.settings
    }

    private fun studyDepsImpl(): StudyDeps = object : StudyDeps {
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
