package app.betterbooks.feature.reader

import android.view.KeyEvent
import app.betterbooks.core.model.AnnotationRepository
import app.betterbooks.core.model.LibraryRepository
import app.betterbooks.core.model.PositionRepository
import app.betterbooks.core.model.SettingsStore
import app.betterbooks.core.model.SpineRepository

/** What the reader needs from the rest of the app. */
interface ReaderDeps {
    val spine: SpineRepository
    val positions: PositionRepository
    val annotations: AnnotationRepository
    val library: LibraryRepository
    val settings: SettingsStore
}

/**
 * Volume-key page turns. Android delivers volume keys to the window before any view can claim them,
 * so the host activity forwards them: `override fun dispatchKeyEvent(e: KeyEvent) =
 * ReaderKeys.dispatch(e) || super.dispatchKeyEvent(e)`. Returns false when no reader is showing
 * or the option is off, so the system volume still works everywhere else.
 */
object ReaderKeys {
    @Volatile internal var handler: ((forward: Boolean) -> Unit)? = null

    fun dispatch(event: KeyEvent): Boolean {
        val h = handler ?: return false
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) h(forward)
        return true
    }
}
