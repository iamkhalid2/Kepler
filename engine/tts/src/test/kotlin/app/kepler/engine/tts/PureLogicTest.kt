package app.kepler.engine.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

class PureLogicTest {
    private fun clock(iso: String) = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test fun modelPolicySwitchesAfterFreeWindow() {
        assertEquals("s2.1-pro-free", ModelPolicy(clock("2026-10-08T12:00:00Z")).model())
        assertEquals("s2.1-pro-free", ModelPolicy(clock("2026-11-30T23:59:59Z")).model())
        assertEquals("s2.1-pro", ModelPolicy(clock("2026-12-01T00:00:00Z")).model())
        assertEquals(0.0, ModelPolicy(clock("2026-10-08T12:00:00Z")).centsPerByte(), 0.0)
        assertEquals(0.0015, ModelPolicy(clock("2027-01-01T00:00:00Z")).centsPerByte(), 1e-12)
    }

    @Test fun chunkerGroupsByParagraphAndSplitsLongOnes() {
        val long = "x".repeat(500)
        val s = listOf(
            sentence(0, "A one.", 0), sentence(1, "A two.", 0),
            sentence(2, long, 1), sentence(3, long, 1), sentence(4, long, 1),
            sentence(5, "C.", 2),
        )
        val chunks = Chunker.plan(s)
        assertEquals(listOf(0..1, 2..3, 4..4, 5..5), chunks.map { it.startOrdinal..it.endOrdinal })
        assertTrue(chunks.all { it.text.length <= Chunker.MAX_CHARS })
    }

    @Test fun chunkerSkipsCoveredAndCutsMicroSegment() {
        val s = (0..5).map { sentence(it, "S$it.", 0) }
        val chunks = Chunker.plan(s, covered = { it == 3 }, microFirst = 2)
        assertEquals(listOf(0..1, 2..2, 4..5), chunks.map { it.startOrdinal..it.endOrdinal })
    }

    @Test fun alignerMatchesLiveFishSample() {
        // Captured from the live /v1/tts/stream/with-timestamp smoke call on 2026-10-08.
        val words = listOf(
            "It" to (0 to 160), "was" to (160 to 480), "the" to (480 to 560), "best" to (560 to 1040),
            "of" to (1040 to 1200), "times" to (1200 to 1920), "It's" to (2320 to 2560), "1984" to (2560 to 4160),
            "and" to (4160 to 4320), "I've" to (4320 to 4560), "waited" to (4560 to 4960),
        ).map { (w, t) -> WordTiming(w, t.first.toLong(), t.second.toLong()) }
        val r = SentenceAligner.align(listOf("It was the best of times.", "It’s 1984, and I’ve waited."), words, 5248)
        assertFalse(r.proportional)
        assertEquals(1.0, r.confidence, 0.0)
        assertEquals(listOf(SentenceSpan(0, 2320), SentenceSpan(2320, 5248)), r.spans)
    }

    @Test fun alignerFallsBackToProportionalWhenTimingsDisagree() {
        val words = listOf(WordTiming("completely", 0, 500), WordTiming("different", 500, 1000))
        val r = SentenceAligner.align(listOf("Short one.", "A much longer second sentence."), words, 4000)
        assertTrue(r.proportional)
        assertEquals(0L, r.spans.first().startMs)
        assertEquals(4000L, r.spans.last().endMs)
        assertEquals(r.spans[0].endMs, r.spans[1].startMs)
        assertTrue(r.spans[1].endMs - r.spans[1].startMs > r.spans[0].endMs - r.spans[0].startMs)
        assertTrue(SentenceAligner.align(listOf("a", "b"), null, 1000).proportional)
    }

    @Test fun alignerToleratesNormalisedNumbersAndMissingWords() {
        val words = listOf("in", "nineteen", "eighty", "four", "we", "left", "then", "we", "came", "home", "again")
            .mapIndexed { i, w -> WordTiming(w, i * 300L, i * 300L + 300) }
        val r = SentenceAligner.align(listOf("In 1984 we left.", "Then we came home again."), words, 3300)
        assertTrue(r.confidence >= 0.8)
        assertFalse(r.proportional)
        assertEquals(1800L, r.spans[1].startMs)
    }

    @Test fun tokenizerIsCaseApostropheAndDigitAware() {
        assertEquals(listOf("its", "1984", "and", "ive", "314", "cafe"), SentenceAligner.tokenize("It’s 1984, and I've 3.14 Café!"))
    }

    @Test fun cacheKeyIsStableAndSensitiveToEveryInput() {
        val k = CacheKeys.of("t", "fish", "s2.1-pro-free", "v", 1f)
        assertEquals(k, CacheKeys.of("t", "fish", "s2.1-pro-free", "v", 1f))
        assertEquals(64, k.length)
        listOf(
            CacheKeys.of("u", "fish", "s2.1-pro-free", "v", 1f),
            CacheKeys.of("t", "system", "s2.1-pro-free", "v", 1f),
            CacheKeys.of("t", "fish", "s2.1-pro", "v", 1f),
            CacheKeys.of("t", "fish", "s2.1-pro-free", "w", 1f),
            CacheKeys.of("t", "fish", "s2.1-pro-free", "v", 1.25f),
        ).forEach { assertNotEquals(k, it) }
    }

    @Test fun estimateCostRequestsAudioAndEta() {
        val words = (1..260).joinToString(" ") { "word" } // 1299 bytes, 100 s of audio
        val chunks = listOf(Chunk(listOf(sentence(0, words, 0))), Chunk(listOf(sentence(1, "été", 1))))
        val paid = Estimator.estimate(chunks, ModelPolicy.PAID_CENTS_PER_BYTE, 1f, throughput = 10.0)
        assertEquals(2, paid.requests)
        assertEquals(1299L + 5L, paid.utf8Bytes)
        assertEquals((1299 + 5) * 0.0015, paid.costCents, 1e-9)
        assertFalse(paid.freeTier)
        assertEquals(100L, paid.audioSeconds)
        assertEquals(11L, paid.etaSeconds) // ceil(100.38 / 10)
        val free = Estimator.estimate(chunks, 0.0, 1f, 10.0)
        assertEquals(0.0, free.costCents, 0.0)
        assertTrue(free.freeTier)
    }

    @Test fun throughputEwmaMovesTowardSamples() {
        val e = ThroughputEwma(4.0, alpha = 0.5)
        e.record(audioMs = 10_000, wallMs = 1_000)
        assertEquals(7.0, e.value, 1e-9)
    }

    @Test fun backoffGrowsAndIsJittered() {
        val r = Random(1)
        val d1 = FishProvider.backoffMs(1, r)
        val d5 = FishProvider.backoffMs(5, r)
        assertTrue(d1 in 500..1000)
        assertTrue(d5 in 8000..16000)
        assertTrue(FishProvider.backoffMs(20, r) <= 30_000)
    }

    @Test fun trimFindsSpeechAndNormalizeHitsTarget() {
        val rate = 1000
        val pcm = ShortArray(3000) { if (it in 1000 until 2000) (if (it % 2 == 0) 3000 else -3000).toShort() else 0 }
        val trim = AudioDsp.findTrim(pcm, rate, padMs = 0)
        assertEquals(1000, trim.startSample)
        assertEquals(2000, trim.endSample)
        val norm = AudioDsp.normalize(pcm.copyOfRange(trim.startSample, trim.endSample))
        assertEquals(-18.0, AudioDsp.rmsDbfs(norm), 0.2)
    }

    @Test fun normalizeLimitsPeaks() {
        val spiky = ShortArray(1000) { if (it == 0) 30000 else 10 }
        val out = AudioDsp.normalize(spiky)
        assertTrue(out.maxOf { kotlin.math.abs(it.toInt()) } <= 32767 * 0.9)
    }

    @Test fun parsesStereoWavToMono() {
        val frames = 4
        val buf = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(22050).putInt(22050 * 4).putShort(4).putShort(16)
        buf.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { buf.putShort(100).putShort(300) }
        val pcm = AudioDsp.parseWav(buf.array())
        assertEquals(22050, pcm.sampleRate)
        assertEquals(listOf<Short>(200, 200, 200, 200), pcm.samples.toList())
    }

    @Test fun shiftKeepsSpansContiguousOnTrimmedTimeline() {
        val spans = listOf(SentenceSpan(0, 1200), SentenceSpan(1200, 2400))
        assertEquals(listOf(SentenceSpan(0, 1000), SentenceSpan(1000, 2000)), SegmentRenderer.shift(spans, 200, 2000))
    }

    @Test fun jobOrderWrapsAroundAndPages() {
        assertEquals(listOf(5 until 10, 0 until 5), NarrationEngine.jobOrder(5, 10))
        assertEquals(listOf(0 until 10), NarrationEngine.jobOrder(0, 10))
        assertEquals(listOf(0 to 499, 500 to 999, 1000 to 1001), NarrationEngine.pages(0..1001))
    }
}
