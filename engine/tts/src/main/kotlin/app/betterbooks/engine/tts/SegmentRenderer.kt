package app.betterbooks.engine.tts

import app.betterbooks.core.model.AudioRepository
import app.betterbooks.core.model.AudioSegment
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.SyncRow
import app.betterbooks.core.model.VoiceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class SegmentMeta(
    val startOrdinal: Int,
    val endOrdinal: Int,
    val durationMs: Long,
    val voice: VoiceRef,
    /** [startMs, endMs] per sentence, in ordinal order. */
    val spans: List<List<Long>>,
    val confidence: Double,
)

data class RenderOutcome(
    val segment: AudioSegment,
    /** False when the audio came from the on-disk cache (no provider request). */
    val requested: Boolean,
    val utf8Bytes: Long,
    val wallMs: Long,
)

/**
 * Turns one [Chunk] into an .m4a under `<audioRoot>/<bookId>/<cacheKey>.m4a` plus its sync rows.
 * Identical requests are never repeated: the cache key covers text, provider, model, voice and params,
 * and a sidecar JSON makes the file self-describing so it can be re-registered after a crash.
 */
class SegmentRenderer(
    private val audioRoot: File,
    private val scratchDir: File,
    internal val transcoder: AudioTranscoder,
    private val repo: AudioRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Serialises renders of the same cache key so two identical chunks never write one file at once. */
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    fun cacheKey(chunk: Chunk, provider: TtsProvider, voice: VoiceRef, speed: Float): String =
        CacheKeys.of(chunk.text, provider.id, provider.currentModel(), voice.id, speed)

    suspend fun render(bookId: BookId, chunk: Chunk, provider: TtsProvider, voice: VoiceRef, speed: Float): RenderOutcome {
        val started = now()
        val key = cacheKey(chunk, provider, voice, speed)
        val dir = File(audioRoot, bookId.toString())
        val audioFile = File(dir, "$key.m4a")
        val metaFile = File(dir, "$key.json")
        val bytes = chunk.text.toByteArray(Charsets.UTF_8).size.toLong()

        return keyLocks.computeIfAbsent(key) { Mutex() }.withLock {
            val cached = withContext(Dispatchers.IO) {
                if (audioFile.exists() && metaFile.exists()) {
                    // Identical text elsewhere in the book (a repeated line) reuses the same audio and timings.
                    runCatching { json.decodeFromString(SegmentMeta.serializer(), metaFile.readText()) }.getOrNull()
                        ?.takeIf { it.spans.size == chunk.sentences.size }
                        ?.copy(startOrdinal = chunk.startOrdinal, endOrdinal = chunk.endOrdinal, voice = voice)
                } else {
                    null
                }
            }
            if (cached != null) {
                return@withLock RenderOutcome(register(bookId, audioFile, cached), requested = false, utf8Bytes = bytes, wallMs = now() - started)
            }

            val result = provider.synthesize(SynthesisRequest(chunk.texts, voice.id, speed))
            val meta = withContext(Dispatchers.Default) {
                val decoded = transcoder.decode(result.audio, scratchDir)
                val totalMs = decoded.durationMs
                val trim = AudioDsp.findTrim(decoded.samples, decoded.sampleRate)
                val trimmed = decoded.samples.copyOfRange(trim.startSample, trim.endSample)
                val pcm = SynthAudio.Pcm(AudioDsp.normalize(trimmed), decoded.sampleRate)
                val leadMs = trim.startSample * 1000L / decoded.sampleRate

                val exact = result.sentenceSpans?.takeIf { it.size == chunk.sentences.size }
                val (raw, confidence) = if (exact != null) {
                    exact.map { SentenceSpan(it.first, it.second) } to 1.0
                } else {
                    val a = SentenceAligner.align(chunk.texts, result.words, totalMs)
                    a.spans to a.confidence
                }
                val spans = shift(raw, leadMs, pcm.durationMs)
                transcoder.encodeAac(pcm, audioFile)
                val m = SegmentMeta(chunk.startOrdinal, chunk.endOrdinal, pcm.durationMs, voice, spans.map { listOf(it.startMs, it.endMs) }, confidence)
                metaFile.writeText(json.encodeToString(SegmentMeta.serializer(), m))
                m
            }
            RenderOutcome(register(bookId, audioFile, meta), requested = true, utf8Bytes = bytes, wallMs = now() - started)
        }
    }

    private suspend fun register(bookId: BookId, file: File, meta: SegmentMeta): AudioSegment {
        val segment = AudioSegment(
            id = 0, bookId = bookId, startOrdinal = meta.startOrdinal, endOrdinal = meta.endOrdinal,
            filePath = file.absolutePath, durationMs = meta.durationMs, voice = meta.voice, take = 1,
        )
        val rows = meta.spans.mapIndexed { i, s ->
            SyncRow(bookId, meta.startOrdinal + i, segmentId = 0, startMs = s[0], endMs = s[1])
        }
        val id = repo.addSegment(segment, rows)
        return segment.copy(id = id)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Moves spans onto the trimmed timeline and keeps them contiguous over [0, durationMs]. */
        internal fun shift(spans: List<SentenceSpan>, leadMs: Long, durationMs: Long): List<SentenceSpan> {
            if (spans.isEmpty()) return spans
            val starts = spans.map { (it.startMs - leadMs).coerceIn(0, durationMs) }.toMutableList()
            starts[0] = 0
            for (i in 1 until starts.size) starts[i] = starts[i].coerceAtLeast(starts[i - 1])
            return starts.mapIndexed { i, s -> SentenceSpan(s, if (i + 1 < starts.size) starts[i + 1] else durationMs) }
        }
    }
}
