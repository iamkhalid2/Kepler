package app.kepler.feature.library

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import app.kepler.core.designsystem.KpSeed
import app.kepler.core.designsystem.KpTheme
import app.kepler.core.designsystem.LocalHapticsEnabled
import app.kepler.core.designsystem.kpPalette
import app.kepler.core.model.AiBudget
import app.kepler.core.model.AiStreaming
import app.kepler.core.model.AnnotationRepository
import app.kepler.core.model.ReviewStatsStore
import app.kepler.core.model.SpineRepository
import app.kepler.core.model.AppSettings
import app.kepler.core.model.AudioRepository
import app.kepler.core.model.CardRepository
import app.kepler.core.model.BookId
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.NarrationControls
import app.kepler.core.model.PaidSwitchStatus
import app.kepler.core.model.PlaybackController
import app.kepler.core.model.NarrationService
import app.kepler.core.model.SettingsStore

/** Everything the library, hub, onboarding, narration-setup and settings screens need from the rest of the app. */
interface LibraryDeps {
    val library: LibraryRepository
    val settings: SettingsStore
    val narration: NarrationService
    val audio: AudioRepository
    val cards: CardRepository

    /** Checks a Fish Audio key with one small request. True = accepted. Must not throw for a rejected key. */
    val verifyFishKey: suspend (key: String) -> Boolean

    /** Checks an OpenRouter key with one small request. True = accepted. */
    val verifyOpenRouterKey: suspend (key: String) -> Boolean

    /** Requests left today and the model in use, or null when no OpenRouter key is set. */
    val aiBudget: suspend () -> AiBudget? get() = { null }

    /** Model ids offered in the OpenRouter picker (free models, best first). */
    val freeModels: suspend () -> List<String> get() = { emptyList() }

    /** The model Automatic would use now and its measured time to first words, or null while unmeasured. */
    val modelSpeed: suspend () -> ModelSpeed? get() = { null }

    /** Fish spend so far this month, in USD cents. */
    val spentThisMonthCents: () -> Int get() = { 0 }

    /** Spend, caps, the free-to-paid switch and per-book cancel. The default does nothing, for previews and tests. */
    val controls: NarrationControls get() = NoNarrationControls

    /** The player, so deleting the book that is playing can stop it first. Null = nothing to stop. */
    val playback: PlaybackController? get() = null

    /** Cost of one hour of audio on the paid Fish model, in US cents (from the narration estimator). */
    val paidCentsPerHour: () -> Double get() = { DEFAULT_PAID_CENTS_PER_HOUR }

    /** Whether to show the ColorOS battery step in onboarding. */
    val showsBatteryGuide: () -> Boolean get() = { isOppoFamily(Build.MANUFACTURER, Build.BRAND) }

    val appVersion: String get() = ""

    /** Highlights for Home's recent-highlights strip. Null hides the strip. */
    val annotations: AnnotationRepository? get() = null

    /** Review days for Home's week dots. Null shows an empty week. */
    val reviewStats: ReviewStatsStore? get() = null

    /** Streams the "Previously on" recap. Null hides the card. */
    val aiStreaming: AiStreaming? get() = null

    /** Chapter titles for the Continue card and the Hub chapter list. Null hides both. */
    val spine: SpineRepository? get() = null

    /** Small remembered view choices (library grid or bookcase). The default forgets on restart. */
    val viewPrefs: ViewPrefs get() = MemoryViewPrefs

    /** Debug builds only: a developer key to put in an empty key field. Release builds return "". */
    val devKeyPrefill: (KeyKind) -> String get() = { "" }
}

/** True for OPPO, OnePlus and realme devices (ColorOS / OxygenOS / realme UI). */
fun isOppoFamily(manufacturer: String?, brand: String?): Boolean {
    val names = listOf(manufacturer, brand).mapNotNull { it?.trim()?.lowercase() }
    return names.any { it.contains("oppo") || it.contains("oneplus") || it.contains("realme") }
}

val LocalLibraryDeps = compositionLocalOf<LibraryDeps> {
    error("Wrap the screens in ProvideLibraryDeps, or pass deps explicitly.")
}

@Composable
fun ProvideLibraryDeps(deps: LibraryDeps, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLibraryDeps provides deps, content = content)
}

/**
 * Applies the stored accent, dark/light mode and haptics setting to everything inside. Put it at the
 * root of the app so a change in Settings re-themes the whole app at once.
 */
@Composable
fun KeplerTheme(settings: SettingsStore, content: @Composable () -> Unit) {
    val s = settings.settings.collectAsState(initial = AppSettings()).value
    val palette = remember(s.seed, s.dark) { kpPalette(KpSeed.fromName(s.seed).color, s.dark) }
    CompositionLocalProvider(LocalHapticsEnabled provides s.haptics) {
        KpTheme(palette, content)
    }
}

/** About 9,400 words an hour at 6 bytes a word and 0.0015 cents a byte. Only used when the app supplies nothing better. */
const val DEFAULT_PAID_CENTS_PER_HOUR = 84.0

internal object NoNarrationControls : NarrationControls {
    override fun paidSwitchStatus(): PaidSwitchStatus = PaidSwitchStatus.FREE_ACTIVE
    override fun paidGenerationConfirmed() = false
    override fun confirmPaidGeneration() = Unit
    override fun spentThisMonthCents() = 0.0
    override fun spentOnBookCents(bookId: BookId) = 0.0
    override suspend fun effectiveMonthlyCapCents() = app.kepler.core.model.NarrationLimits.DEFAULT_MONTHLY_CAP_CENTS
    override fun perBookCapCents() = app.kepler.core.model.NarrationLimits.PER_BOOK_CAP_CENTS
    override fun cancelBook(bookId: BookId) = Unit
}

/**
 * Removes a book safely: stops playback if it is the current book, cancels its narration job (so nothing
 * writes audio for a book that no longer exists), then deletes it. A failing cancel never blocks the delete.
 */
suspend fun LibraryDeps.removeBook(id: BookId) {
    try {
        playback?.let { if (it.state.value.bookId == id) it.pause() }
        controls.cancelBook(id)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
    }
    library.delete(id)
}

/** Per-viewer view choices that outlive the process. */
interface ViewPrefs {
    var bookcase: Boolean
}

internal object MemoryViewPrefs : ViewPrefs {
    override var bookcase: Boolean = false
}
