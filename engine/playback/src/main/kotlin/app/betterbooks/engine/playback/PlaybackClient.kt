package app.betterbooks.engine.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.PlaybackController
import app.betterbooks.core.model.PlaybackInfo
import app.betterbooks.core.model.PlaybackInfoSource
import app.betterbooks.core.model.PlaybackState
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.StateFlow

/**
 * The app-side handle on playback. Connects to [PlaybackService] through a Media3 controller and
 * sends sentence-level commands; state comes from [PlaybackHub], which the service keeps current.
 * Call from the main thread. Create one per process and keep it.
 */
class PlaybackClient(context: Context) : PlaybackController, PlaybackInfoSource {
    private val appContext = context.applicationContext
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    override val state: StateFlow<PlaybackState> get() = PlaybackHub.stateFlow
    override val info: StateFlow<PlaybackInfo> get() = PlaybackHub.infoFlow

    override fun play(bookId: BookId, fromOrdinal: Int?) = custom(PlaybackCommands.PLAY, fromOrdinal?.toLong(), bookId)
    override fun pause() = withController { it.pause() }
    override fun resume() = withController { it.play() }
    override fun seekToSentence(ordinal: Int) = custom(PlaybackCommands.SEEK_SENTENCE, ordinal.toLong())
    override fun skipSentences(delta: Int) = custom(PlaybackCommands.SKIP_SENTENCES, delta.toLong())
    override fun seekByMs(deltaMs: Long) = custom(PlaybackCommands.SEEK_BY_MS, deltaMs)
    override fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }
    override fun setSleepTimer(minutes: Int) = custom(PlaybackCommands.SLEEP, minutes.toLong())

    /** Disconnects. The service keeps playing if it was playing. */
    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        pending.clear()
    }

    private fun custom(command: androidx.media3.session.SessionCommand, value: Long?, book: BookId? = null) =
        withController { c ->
            val args = Bundle().apply {
                putLong(PlaybackCommands.ARG_VALUE, value ?: PlaybackCommands.NONE)
                if (book != null) putLong(PlaybackCommands.ARG_BOOK, book)
            }
            c.sendCustomCommand(command, args)
        }

    private fun withController(block: (MediaController) -> Unit) {
        val ready = controller
        if (ready != null && ready.isConnected) { block(ready); return }
        pending.addLast(block)
        if (future != null && controller == null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val f = MediaController.Builder(appContext, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                pending.clear()
                return@addListener
            }
            controller = c
            while (pending.isNotEmpty()) pending.removeFirst()(c)
        }, ContextCompat.getMainExecutor(appContext))
    }

    companion object {
        /** Hands the service its dependencies. Call once from `Application.onCreate`, before any playback. */
        fun initialize(dependencies: PlaybackDependencies) = PlaybackHub.initialize(dependencies)
    }
}
