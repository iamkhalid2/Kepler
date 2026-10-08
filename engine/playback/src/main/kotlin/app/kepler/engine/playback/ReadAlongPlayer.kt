package app.kepler.engine.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures

/** What the outside world (notification, lock screen, headset, Auto) sees: one media item, the current section. */
data class ExternalState(
    val mediaId: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val artworkUri: Uri? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val playWhenReady: Boolean = false,
    /** One of the `Player.STATE_*` constants. */
    val playbackState: Int = Player.STATE_IDLE,
    val speed: Float = 1f,
    val error: String? = null,
)

/** The engine behind [ReadAlongPlayer]. Called on the application looper. */
interface ReadAlongHost {
    fun externalState(): ExternalState
    fun onPlayWhenReady(play: Boolean)
    fun onSeekInSection(positionMs: Long)
    fun onSkipSentences(delta: Int)
    fun onSeekByMs(deltaMs: Long)
    fun onSetSpeed(speed: Float)
    fun onStop()
    fun onRelease()
    /** A controller asked to play this media id (Android Auto, Bluetooth browsing). */
    fun onPlayMediaId(mediaId: String)
}

/**
 * The Media3 face of playback. Notification, lock screen, headset buttons and controllers talk to this
 * player; it translates them into sentence-level operations on the engine. Next and previous are
 * sentence skips, seek back and forward are the 15 s and 30 s jumps.
 */
class ReadAlongPlayer(looper: Looper, private val host: ReadAlongHost) : SimpleBasePlayer(looper) {

    /** Call after the engine's state changed in a way that the outside world shows. */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = host.externalState()
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(s.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(s.playbackState)
            .setPlaybackParameters(PlaybackParameters(s.speed, 1f))
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setMaxSeekToPreviousPositionMs(0)
        if (s.error != null && s.playbackState == Player.STATE_IDLE) {
            builder.setPlayerError(androidx.media3.common.PlaybackException(s.error, null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        }
        val id = s.mediaId ?: return builder.build()
        val metadata = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.subtitle)
            .setDisplayTitle(s.title)
            .setSubtitle(s.subtitle)
            .setArtworkUri(s.artworkUri)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .build()
        val item = MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
        val duration = if (s.durationMs > 0) s.durationMs * 1000 else C.TIME_UNSET
        val current = MediaItemData.Builder(id).setMediaItem(item).setMediaMetadata(metadata)
            .setDurationUs(duration).setIsSeekable(true).build()
        val position = s.positionMs.coerceAtLeast(0)
        return builder.setPlaylist(listOf(current)).setCurrentMediaItemIndex(0)
            .setContentPositionMs(
                if (s.playWhenReady && s.playbackState == Player.STATE_READY) PositionSupplier.getExtrapolating(position, s.speed)
                else PositionSupplier.getConstant(position),
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        host.onPlayWhenReady(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        host.onStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        host.onRelease()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        host.onSetSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> host.onSkipSentences(1)
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> host.onSkipSentences(-1)
            COMMAND_SEEK_BACK -> host.onSeekByMs(-SEEK_BACK_MS)
            COMMAND_SEEK_FORWARD -> host.onSeekByMs(SEEK_FORWARD_MS)
            else -> if (positionMs != C.TIME_UNSET) host.onSeekInSection(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        mediaItems.firstOrNull()?.mediaId?.let(host::onPlayMediaId)
        return Futures.immediateVoidFuture()
    }

    companion object {
        const val SEEK_BACK_MS = 15_000L
        const val SEEK_FORWARD_MS = 30_000L

        private val COMMANDS = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_RELEASE,
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SET_SPEED_AND_PITCH, COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS,
            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA, COMMAND_GET_AUDIO_ATTRIBUTES,
        ).build()
    }
}
