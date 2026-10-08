package app.kepler.engine.playback

import app.kepler.core.model.AudioRepository
import app.kepler.core.model.BookId
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.NarrationService
import app.kepler.core.model.PlaybackInfo
import app.kepler.core.model.PlaybackState
import app.kepler.core.model.PositionRepository
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.SpineRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** Everything the playback service needs from the app. Build it once and pass it to [PlaybackHub.initialize]. */
class PlaybackDependencies(
    val audio: AudioRepository,
    val spine: SpineRepository,
    val positions: PositionRepository,
    val narration: NarrationService,
    val library: LibraryRepository,
    val settings: SettingsStore,
    /** Called when the user long-presses the headset button: highlight the sentence being spoken. */
    val onHighlightGesture: ((BookId, Int) -> Unit)? = null,
)

/**
 * Process-wide handoff between the app and the playback service. The app initialises it in
 * `Application.onCreate`; the service reads the dependencies and publishes state here, so UI code can
 * observe playback without waiting for a session connection.
 */
object PlaybackHub {
    @Volatile
    var dependencies: PlaybackDependencies? = null
        private set

    internal val stateFlow = MutableStateFlow(PlaybackState())
    internal val infoFlow = MutableStateFlow(PlaybackInfo())

    fun initialize(deps: PlaybackDependencies) {
        dependencies = deps
    }
}
