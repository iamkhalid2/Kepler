package app.betterbooks.engine.tts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64

@Serializable
internal data class FishSegment(val text: String = "", val start: Double = 0.0, val end: Double = 0.0)

@Serializable
internal data class FishAlignment(
    val segments: List<FishSegment> = emptyList(),
    @SerialName("audio_duration") val audioDuration: Double? = null,
)

@Serializable
internal data class FishTimestampEvent(
    @SerialName("audio_base64") val audioBase64: String? = null,
    val alignment: FishAlignment? = null,
    @SerialName("chunk_seq") val chunkSeq: Int = 0,
    @SerialName("chunk_audio_offset_sec") val chunkOffsetSec: Double = 0.0,
)

/**
 * Accumulates the `/v1/tts/stream/with-timestamp` SSE stream (verified live 2026-10-08:
 * `event: message` + `data: {audio_base64, alignment{segments,audio_duration}, chunk_seq, chunk_audio_offset_sec}`).
 * Audio bytes are appended in arrival order; alignment is a cumulative snapshot per chunk_seq, so it is replaced.
 */
internal class FishSseAccumulator {
    private val audio = ByteArrayOutputStream()
    private val snapshots = sortedMapOf<Int, Pair<Double, List<FishSegment>>>()
    private val data = StringBuilder()
    private var eventName: String? = null

    fun onLine(line: String) {
        when {
            line.isEmpty() -> dispatch()
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> eventName = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart())
            }
        }
    }

    fun finish() = dispatch()

    private fun dispatch() {
        if (data.isEmpty()) {
            eventName = null
            return
        }
        val payload = data.toString()
        val name = eventName
        data.setLength(0)
        eventName = null
        if (name == "error") throw TtsException.Engine("Fish Audio stream error")
        val event = json.decodeFromString(FishTimestampEvent.serializer(), payload)
        event.audioBase64?.takeIf { it.isNotEmpty() }?.let { audio.write(Base64.getDecoder().decode(it)) }
        event.alignment?.let { snapshots[event.chunkSeq] = event.chunkOffsetSec to it.segments }
    }

    fun audioBytes(): ByteArray = audio.toByteArray()

    fun words(): List<WordTiming> = snapshots.values.flatMap { (offset, segs) ->
        segs.map { WordTiming(it.text, ((offset + it.start) * 1000).toLong(), ((offset + it.end) * 1000).toLong()) }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
