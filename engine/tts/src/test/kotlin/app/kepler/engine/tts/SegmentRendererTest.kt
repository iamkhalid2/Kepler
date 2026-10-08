package app.kepler.engine.tts

import app.kepler.core.model.VoiceRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentRendererTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun rendersAlignsTrimsAndNeverRepeatsARequest() = runTest {
        val repo = FakeAudioRepo()
        val provider = FakeProvider()
        val renderer = SegmentRenderer(tmp.newFolder("audio"), tmp.newFolder("scratch"), FakeTranscoder(), repo)
        val voice = VoiceRef("fake", "v1", "V")
        val chunk = Chunk(listOf(sentence(10, "one two three four", 3), sentence(11, "five six seven eight", 3)))

        val first = renderer.render(7, chunk, provider, voice, 1f)
        assertTrue(first.requested)
        assertEquals(10, first.segment.startOrdinal)
        assertEquals(11, first.segment.endOrdinal)
        assertTrue(first.segment.filePath.endsWith(".m4a"))
        assertTrue(first.segment.filePath.contains("/7/"))
        // 200 ms of leading silence trimmed (minus 40 ms of padding): sentence 2 starts at 1200 - 160 ms.
        val rows = repo.rows.sortedBy { it.ordinal }
        assertEquals(listOf(10, 11), rows.map { it.ordinal })
        assertEquals(0L, rows[0].startMs)
        assertEquals(1040L, rows[0].endMs)
        assertEquals(1040L, rows[1].startMs)
        assertEquals(first.segment.durationMs, rows[1].endMs)

        val again = renderer.render(7, chunk, provider, voice, 1f)
        assertFalse(again.requested)
        assertEquals(1, provider.calls.get())
        assertEquals(first.segment.durationMs, again.segment.durationMs)

        renderer.render(7, chunk, provider, voice, 1.5f)
        assertEquals(2, provider.calls.get())

        // The same text later in the book reuses the audio under its own ordinals.
        val repeat = Chunk(listOf(sentence(40, "one two three four", 9), sentence(41, "five six seven eight", 9)))
        val reused = renderer.render(7, repeat, provider, voice, 1f)
        assertFalse(reused.requested)
        assertEquals(2, provider.calls.get())
        assertEquals(40, reused.segment.startOrdinal)
        assertEquals(listOf(40, 41), repo.rows.filter { it.ordinal >= 40 }.map { it.ordinal }.sorted())
    }
}
