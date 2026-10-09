@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.kepler.engine.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** Custom session commands for the things the Media3 player API has no word for. */
internal object PlaybackCommands {
    private const val PREFIX = "app.kepler.playback."
    val PLAY = SessionCommand(PREFIX + "PLAY", Bundle.EMPTY)
    val SEEK_SENTENCE = SessionCommand(PREFIX + "SEEK_SENTENCE", Bundle.EMPTY)
    val SKIP_SENTENCES = SessionCommand(PREFIX + "SKIP_SENTENCES", Bundle.EMPTY)
    val SEEK_BY_MS = SessionCommand(PREFIX + "SEEK_BY_MS", Bundle.EMPTY)
    val SLEEP = SessionCommand(PREFIX + "SLEEP", Bundle.EMPTY)
    val ALL = listOf(PLAY, SEEK_SENTENCE, SKIP_SENTENCES, SEEK_BY_MS, SLEEP)

    const val ARG_BOOK = "book"
    const val ARG_VALUE = "value"
    const val NONE = -1L
}

/**
 * Foreground media service. Owns one [androidx.media3.session.MediaLibrarySession] around a
 * [ReadAlongPlayer], which in turn is driven by the [PlaybackEngine]. The app must call
 * [PlaybackHub.initialize] before this service starts.
 */
class PlaybackService : MediaLibraryService() {
    private var engine: PlaybackEngine? = null
    private var player: ReadAlongPlayer? = null
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val deps = PlaybackHub.dependencies
        if (deps == null) {
            stopSelf()
            return
        }
        val engine = PlaybackEngine(this, deps)
        val player = ReadAlongPlayer(Looper.getMainLooper(), engine)
        engine.onExternalChange = player::refresh
        this.engine = engine
        this.player = player
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val builder = MediaLibrarySession.Builder(this, player, Callback(engine, deps))
        if (launch != null) {
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        engine?.release()
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    private inner class Callback(
        private val engine: PlaybackEngine,
        private val deps: PlaybackDependencies,
    ) : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val trusted = controller.packageName == packageName || controller.isTrusted
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .also { b -> if (controller.packageName == packageName) PlaybackCommands.ALL.forEach { b.add(it) } }
                .build()
            if (!trusted) {
                // Unknown apps get no library browsing and no custom commands.
                return MediaSession.ConnectionResult.reject()
            }
            return MediaSession.ConnectionResult.accept(commands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            val value = args.getLong(PlaybackCommands.ARG_VALUE, PlaybackCommands.NONE)
            when (customCommand.customAction) {
                PlaybackCommands.PLAY.customAction ->
                    engine.play(args.getLong(PlaybackCommands.ARG_BOOK), value.takeIf { it != PlaybackCommands.NONE }?.toInt())
                PlaybackCommands.SEEK_SENTENCE.customAction -> engine.seekToSentence(value.toInt())
                PlaybackCommands.SKIP_SENTENCES.customAction -> engine.skipSentences(value.toInt())
                PlaybackCommands.SEEK_BY_MS.customAction -> engine.seekByMs(value)
                PlaybackCommands.SLEEP.customAction -> engine.setSleepTimer(value.toInt())
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)

        override fun onMediaButtonEvent(
            session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent,
        ): Boolean {
            if (deps.onHighlightGesture == null) return false
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            val hook = key.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || key.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            if (hook && key.action == KeyEvent.ACTION_DOWN && key.isLongPress) {
                engine.highlightCurrent()
                return true
            }
            return false
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder().setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS).build(),
                ).build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                runCatching {
                    val books = if (parentId == ROOT_ID) deps.library.books.first() else emptyList()
                    val items = books.sortedByDescending { it.lastOpenedAt ?: it.addedAt }
                        .drop(page * pageSize).take(pageSize).map(::bookItem)
                    result.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                }.onFailure { result.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)) }
            }
            return result
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val result = SettableFuture.create<LibraryResult<MediaItem>>()
            val id = mediaId.removePrefix("book:").toLongOrNull()
            scope.launch {
                val book = id?.let { runCatching { deps.library.book(it).first() }.getOrNull() }
                result.set(
                    if (book != null) LibraryResult.ofItem(bookItem(book), null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
                )
            }
            return result
        }

        private fun bookItem(book: app.kepler.core.model.Book): MediaItem = MediaItem.Builder()
            .setMediaId("book:${book.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title).setArtist(book.authors)
                    .setArtworkUri(book.coverPath?.let { File(it).toUri() })
                    .setIsBrowsable(false).setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build(),
            ).build()
    }

    private companion object {
        const val ROOT_ID = "root"
    }
}
