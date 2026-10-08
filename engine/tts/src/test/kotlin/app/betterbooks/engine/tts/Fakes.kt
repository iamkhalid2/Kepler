package app.betterbooks.engine.tts

import app.betterbooks.core.model.AudioRepository
import app.betterbooks.core.model.AudioSegment
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.Sentence
import app.betterbooks.core.model.SyncRow
import app.betterbooks.core.model.VoiceOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

fun sentence(ordinal: Int, text: String, paragraph: Int, section: Long = 1) =
    Sentence(1, ordinal, section, "ch.xhtml", text, "", "", paragraph)

class FakeAudioRepo : AudioRepository {
    val segs = MutableStateFlow<List<AudioSegment>>(emptyList())
    val rows = mutableListOf<SyncRow>()
    private var next = 1L
    override fun segments(bookId: BookId): Flow<List<AudioSegment>> = segs
    override suspend fun addSegment(segment: AudioSegment, rows: List<SyncRow>): Long = synchronized(this) {
        val id = next++
        segs.value = segs.value + segment.copy(id = id)
        this.rows += rows.map { it.copy(segmentId = id) }
        id
    }
    override suspend fun segmentFor(bookId: BookId, ordinal: Int) =
        segs.value.firstOrNull { ordinal in it.startOrdinal..it.endOrdinal }
    override suspend fun syncRows(bookId: BookId, fromOrdinal: Int, toOrdinal: Int) =
        rows.filter { it.ordinal in fromOrdinal..toOrdinal }
    override fun coverage(bookId: BookId): Flow<Float> = segs.map { 0f }
    override suspend fun deleteSegments(bookId: BookId) { segs.value = emptyList() }
}

/** Speaks every sentence as 1 s of tone with 200 ms of silence before and after the whole request. */
class FakeProvider(override val id: String = "fake", private val pricePerByte: Double = 0.0) : TtsProvider {
    val calls = AtomicInteger()
    val requests = mutableListOf<SynthesisRequest>()
    override val capabilities = TtsCapabilities(false, true, false, 1200, MarkupDialect.NONE, 0.5f..2f, 2)
    override fun currentModel() = "m1"
    override fun centsPerByte() = pricePerByte
    override suspend fun voices(language: String?) = emptyList<VoiceOption>()
    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult {
        calls.incrementAndGet()
        synchronized(requests) { requests += request }
        val rate = 1000
        val parts = mutableListOf(ShortArray(200))
        val words = mutableListOf<WordTiming>()
        var t = 200L
        for (s in request.sentences) {
            val toks = s.split(" ")
            toks.forEachIndexed { i, w ->
                val a = t + i * 1000L / toks.size
                words += WordTiming(w, a, a + 1000L / toks.size)
            }
            parts += ShortArray(1000) { if (it % 2 == 0) 8000 else -8000 }
            t += 1000
        }
        parts += ShortArray(200)
        return SynthesisResult(SynthAudio.Pcm(AudioDsp.concat(parts), rate), words, model = "m1")
    }
}

class FakeTranscoder : AudioTranscoder {
    override fun decode(audio: SynthAudio, scratchDir: File) = audio as SynthAudio.Pcm
    override fun encodeAac(pcm: SynthAudio.Pcm, out: File) {
        out.parentFile?.mkdirs()
        out.writeText("aac:${pcm.samples.size}")
    }
}
