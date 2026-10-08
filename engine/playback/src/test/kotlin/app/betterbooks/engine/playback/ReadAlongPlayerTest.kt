package app.betterbooks.engine.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadAlongPlayerTest {
    private class FakeHost(var state: ExternalState) : ReadAlongHost {
        val calls = mutableListOf<String>()
        override fun externalState() = state
        override fun onPlayWhenReady(play: Boolean) { calls += "play=$play" }
        override fun onSeekInSection(positionMs: Long) { calls += "seek=$positionMs" }
        override fun onSkipSentences(delta: Int) { calls += "skip=$delta" }
        override fun onSeekByMs(deltaMs: Long) { calls += "by=$deltaMs" }
        override fun onSetSpeed(speed: Float) { calls += "speed=$speed" }
        override fun onStop() { calls += "stop" }
        override fun onRelease() { calls += "release" }
        override fun onPlayMediaId(mediaId: String) { calls += "id=$mediaId" }
    }

    private val loaded = ExternalState(
        mediaId = "book:1:s:2", title = "Meditations", subtitle = "Book II", durationMs = 600_000, positionMs = 12_000,
        playWhenReady = true, playbackState = Player.STATE_READY, speed = 1.5f,
    )

    private fun player(host: FakeHost) = ReadAlongPlayer(android.os.Looper.getMainLooper(), host)

    @Test fun exposesSectionAsCurrentItem() {
        val p = player(FakeHost(loaded))
        assertEquals("Meditations", p.mediaMetadata.title.toString())
        assertEquals("Book II", p.mediaMetadata.artist.toString())
        assertEquals(600_000, p.duration)
        assertEquals(1.5f, p.playbackParameters.speed, 0f)
        assertTrue(p.playWhenReady)
    }

    @Test fun nextAndPreviousSkipSentences() {
        val host = FakeHost(loaded)
        val p = player(host)
        p.seekToNext()
        p.seekToPrevious()
        p.seekToNextMediaItem()
        p.seekToPreviousMediaItem()
        assertEquals(listOf("skip=1", "skip=-1", "skip=1", "skip=-1"), host.calls)
    }

    @Test fun seekBackAndForwardAreFifteenAndThirty() {
        val host = FakeHost(loaded)
        val p = player(host)
        p.seekBack()
        p.seekForward()
        assertEquals(listOf("by=-15000", "by=30000"), host.calls)
    }

    @Test fun playPauseSeekAndSpeedAreForwarded() {
        val host = FakeHost(loaded)
        val p = player(host)
        p.pause()
        p.play()
        p.seekTo(45_000)
        p.setPlaybackSpeed(2f)
        assertEquals(listOf("play=false", "play=true", "seek=45000", "speed=2.0"), host.calls)
    }

    @Test fun emptyWhenNothingLoaded() {
        val p = player(FakeHost(ExternalState()))
        assertEquals(0, p.mediaItemCount)
        assertEquals(Player.STATE_IDLE, p.playbackState)
    }
}
