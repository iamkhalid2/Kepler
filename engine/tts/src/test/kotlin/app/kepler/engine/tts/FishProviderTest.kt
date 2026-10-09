package app.kepler.engine.tts

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Collections

class FishProviderTest {
    private val seen = Collections.synchronizedList(mutableListOf<HttpRequestData>())

    private fun provider(
        key: String? = "test-key",
        now: String = "2026-10-08T00:00:00Z",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = FishProvider(
        apiKey = { key },
        policy = ModelPolicy(Clock.fixed(Instant.parse(now), ZoneOffset.UTC)),
        engine = MockEngine { req -> seen += req; handler(req) },
        sleep = {},
    )

    private fun sse(vararg events: String) = events.joinToString("") { "event: message\ndata: $it\n\n" }

    private val audio1 = byteArrayOf(1, 2, 3)
    private val audio2 = byteArrayOf(4, 5)
    private val stream = sse(
        """{"audio_base64":"${Base64.getEncoder().encodeToString(audio1)}","content":"Hi there. Bye.","alignment":{"segments":[{"text":"Hi","start":0.0,"end":0.2}],"audio_duration":0.5},"chunk_seq":0,"chunk_audio_offset_sec":0.0}""",
        """{"audio_base64":"${Base64.getEncoder().encodeToString(audio2)}","alignment":{"segments":[{"text":"Hi","start":0.0,"end":0.2},{"text":"there","start":0.2,"end":0.6}],"audio_duration":0.7},"chunk_seq":0,"chunk_audio_offset_sec":0.0}""",
        """{"audio_base64":"","alignment":{"segments":[{"text":"Bye","start":0.1,"end":0.4}],"audio_duration":0.5},"chunk_seq":1,"chunk_audio_offset_sec":0.8}""",
    )

    private fun MockRequestHandleScope.ok(body: String) =
        respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))

    private val voicesJson = """{"total":1,"items":[{"_id":"abc","title":"Dave","description":"Deep","languages":["en"],"samples":[{"audio":"https://x/a.mp3"}],"extra":1}]}"""

    @Test fun parsesSseAudioAndCumulativeAlignment() = runTest {
        val r = provider { ok(stream) }.synthesize(SynthesisRequest(listOf("Hi there.", "Bye."), "voice"))
        assertArrayEquals(audio1 + audio2, (r.audio as SynthAudio.Encoded).bytes)
        assertEquals(listOf(WordTiming("Hi", 0, 200), WordTiming("there", 200, 600), WordTiming("Bye", 900, 1200)), r.words)
        assertEquals("s2.1-pro-free", r.model)
        val req = seen.single()
        assertEquals("/v1/tts/stream/with-timestamp", req.url.encodedPath)
        assertEquals("Bearer test-key", req.headers[HttpHeaders.Authorization])
        val body = (req.body as io.ktor.http.content.TextContent).text
        assertTrue(body.contains("\"reference_id\":\"voice\""))
        assertTrue(body.contains("\"text\":\"Hi there. Bye.\""))
    }

    @Test fun noRequestLeavesWithoutModelHeader() = runTest {
        for (now in listOf("2026-10-08T00:00:00Z", "2027-02-01T00:00:00Z")) {
            seen.clear()
            var first = true
            val p = provider(now = now) { req ->
                when {
                    req.url.encodedPath == "/model" -> ok(voicesJson)
                    req.url.encodedPath.endsWith("with-timestamp") && first -> { first = false; ok(stream) }
                    req.url.encodedPath.endsWith("with-timestamp") -> respond("", HttpStatusCode.NotFound)
                    else -> respond(ByteReadChannel(byteArrayOf(9, 9)), HttpStatusCode.OK)
                }
            }
            p.synthesize(SynthesisRequest(listOf("a"), "v"))
            val fallback = p.synthesize(SynthesisRequest(listOf("b"), "v")) // 404 -> /v1/tts
            assertNull(fallback.words)
            p.synthesize(SynthesisRequest(listOf("c"), "v")) // goes straight to /v1/tts now
            p.voices("en")
            val expected = if (now.startsWith("2026")) "s2.1-pro-free" else "s2.1-pro"
            assertEquals(5, seen.size)
            assertTrue(seen.all { it.headers["model"] == expected })
            assertEquals(listOf("/v1/tts/stream/with-timestamp", "/v1/tts/stream/with-timestamp", "/v1/tts", "/v1/tts", "/model"),
                seen.map { it.url.encodedPath })
        }
    }

    @Test fun voicesUseLicensedFilter() = runTest {
        val v = provider { ok(voicesJson) }.voices(null)
        assertEquals("abc", v.single().ref.id)
        assertEquals("fish", v.single().ref.provider)
        assertEquals("https://x/a.mp3", v.single().previewUrl)
        assertEquals("true", seen.single().url.parameters["licensed"])
    }

    @Test fun errorTaxonomy() = runTest {
        suspend fun expect(code: HttpStatusCode, check: (TtsException) -> Boolean) {
            try {
                provider { respond("", code) }.synthesize(SynthesisRequest(listOf("x"), "v"))
                fail("expected error for $code")
            } catch (e: TtsException) {
                assertTrue("$code -> $e", check(e))
            }
        }
        expect(HttpStatusCode.Unauthorized) { it is TtsException.InvalidKey }
        expect(HttpStatusCode.PaymentRequired) { it is TtsException.PaymentRequired }
        expect(HttpStatusCode.BadRequest) { it is TtsException.Http && it.code == 400 }
        seen.clear()
        expect(HttpStatusCode.TooManyRequests) { it is TtsException.RateLimited }
        assertEquals(6, seen.size) // retried up to maxAttempts
    }

    @Test fun retriesTransientErrorsThenSucceeds() = runTest {
        var n = 0
        val r = provider { if (n++ < 2) respond("", HttpStatusCode.ServiceUnavailable) else ok(stream) }
            .synthesize(SynthesisRequest(listOf("x"), "v"))
        assertEquals(3, seen.size)
        assertEquals(3, r.words!!.size)
    }

    @Test fun missingKeySendsNothing() = runTest {
        try {
            provider(key = null) { ok(stream) }.synthesize(SynthesisRequest(listOf("x"), "v"))
            fail()
        } catch (e: TtsException.MissingKey) {
            assertTrue(seen.isEmpty())
        }
    }

    // ---- billing accounting (F6)

    /** A 200 response whose body breaks after the first event. */
    private fun MockRequestHandleScope.brokenStream(): io.ktor.client.request.HttpResponseData {
        val channel = io.ktor.utils.io.ByteChannel()
        kotlinx.coroutines.GlobalScope.launch {
            channel.writeFully(sse("""{"audio_base64":"AQID","chunk_seq":0}""").toByteArray())
            channel.flush()
            channel.cancel(java.io.IOException("connection reset"))
        }
        return respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }

    @Test fun midStreamFailureOnThePaidModelIsNeverRepeated() = runTest {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val p = provider(now = "2026-12-05T00:00:00Z") { brokenStream() }
        try {
            p.synthesize(SynthesisRequest(listOf("x"), "v", onAccepted = { accepted.incrementAndGet() }))
            fail()
        } catch (e: TtsException.Network) {
            assertTrue(e.billed)
            assertFalse(e.retryable)
        }
        assertEquals(1, seen.size)
        assertEquals("s2.1-pro", seen.single().headers["model"])
        assertEquals(1, accepted.get())
    }

    @Test fun midStreamFailureOnTheFreeModelRetriesAtMostTwice() = runTest {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val p = provider(now = "2026-10-08T00:00:00Z") { brokenStream() }
        try {
            p.synthesize(SynthesisRequest(listOf("x"), "v", onAccepted = { accepted.incrementAndGet() }))
            fail()
        } catch (e: TtsException.Network) {
            assertFalse(e.billed)
        }
        assertEquals(3, seen.size) // the request and two retries
        assertEquals(3, accepted.get())
    }

    @Test fun notAcceptedRequestsAreNotCountedAndConnectionErrorsBeforeResponseStillRetry() = runTest {
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        var n = 0
        val p = provider(now = "2026-12-05T00:00:00Z") {
            if (n++ < 2) throw java.io.IOException("no route") else ok(stream)
        }
        p.synthesize(SynthesisRequest(listOf("x"), "v", onAccepted = { accepted.incrementAndGet() }))
        assertEquals(3, seen.size)
        assertEquals(1, accepted.get())
    }
}
