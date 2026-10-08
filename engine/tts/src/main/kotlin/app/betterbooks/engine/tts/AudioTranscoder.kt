package app.betterbooks.engine.tts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Decodes provider audio to PCM and writes the final AAC file. Swappable for JVM tests. */
interface AudioTranscoder {
    fun decode(audio: SynthAudio, scratchDir: File): SynthAudio.Pcm

    /** Writes [pcm] as AAC-LC 64 kbps mono in an MP4 (.m4a) container. */
    fun encodeAac(pcm: SynthAudio.Pcm, out: File)
}

class MediaCodecTranscoder : AudioTranscoder {

    override fun decode(audio: SynthAudio, scratchDir: File): SynthAudio.Pcm = when (audio) {
        is SynthAudio.Pcm -> audio
        is SynthAudio.Encoded -> {
            if (audio.extension == "wav") {
                AudioDsp.parseWav(audio.bytes)
            } else {
                scratchDir.mkdirs()
                val tmp = File.createTempFile("dec", ".${audio.extension}", scratchDir)
                try {
                    tmp.writeBytes(audio.bytes)
                    decodeFile(tmp)
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private fun decodeFile(file: File): SynthAudio.Pcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw TtsException.Audio("No audio track")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inFormat, null, null, 0)
                start()
            }
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val out = ShortArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset).limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (fr in 0 until frames) {
                                var acc = 0f
                                for (c in 0 until channels) acc += fb.get(fr * channels + c)
                                out.add((acc / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / channels
                            for (fr in 0 until frames) {
                                var acc = 0
                                for (c in 0 until channels) acc += sb.get(fr * channels + c)
                                out.add((acc / channels).toShort())
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return SynthAudio.Pcm(out.toArray(), rate)
        } catch (e: TtsException) {
            throw e
        } catch (e: Exception) {
            throw TtsException.Audio("Audio decode failed", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    override fun encodeAac(pcm: SynthAudio.Pcm, out: File) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val n = minOf(buf.capacity() / 2, pcm.samples.size - offset)
                        val ptsUs = offset * 1_000_000L / pcm.sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.asShortBuffer().put(pcm.samples, offset, n)
                            codec.queueInputBuffer(idx, 0, n * 2, ptsUs, 0)
                            offset += n
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && muxerStarted) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw TtsException.Audio("Audio encode failed", e)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
        if (!tmp.renameTo(out)) throw TtsException.Audio("Could not write ${out.name}")
    }

    private class ShortArrayBuilder {
        private var data = ShortArray(1 shl 16)
        private var size = 0
        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): ShortArray = data.copyOf(size)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
