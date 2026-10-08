package app.betterbooks.feature.library

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import app.betterbooks.core.designsystem.BbSeed
import app.betterbooks.core.designsystem.BbTheme
import app.betterbooks.core.designsystem.LocalHapticsEnabled
import app.betterbooks.core.designsystem.bbPalette
import app.betterbooks.core.model.AiBudget
import app.betterbooks.core.model.AppSettings
import app.betterbooks.core.model.AudioRepository
import app.betterbooks.core.model.CardRepository
import app.betterbooks.core.model.LibraryRepository
import app.betterbooks.core.model.NarrationService
import app.betterbooks.core.model.SettingsStore

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

    /** Fish spend so far this month, in USD cents. */
    val spentThisMonthCents: () -> Int get() = { 0 }

    /** Whether to show the ColorOS battery step in onboarding. */
    val showsBatteryGuide: () -> Boolean get() = { isOppoFamily(Build.MANUFACTURER, Build.BRAND) }

    val appVersion: String get() = ""
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
fun BetterBooksTheme(settings: SettingsStore, content: @Composable () -> Unit) {
    val s = settings.settings.collectAsState(initial = AppSettings()).value
    val palette = remember(s.seed, s.dark) { bbPalette(BbSeed.fromName(s.seed).color, s.dark) }
    CompositionLocalProvider(LocalHapticsEnabled provides s.haptics) {
        BbTheme(palette, content)
    }
}
