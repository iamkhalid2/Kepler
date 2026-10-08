package app.betterbooks.engine.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.betterbooks.core.model.VoiceOption
import app.betterbooks.core.model.VoiceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Android's on-device TextToSpeech. Each sentence is rendered to its own WAV with
 * synthesizeToFile, so sentence timing is exact.
 */
class SystemTtsProvider(context: Context, private val scratchDir: File) : TtsProvider {
    private val appContext = context.applicationContext

    override val id: String = ID
    override val capabilities = TtsCapabilities(
        streaming = false, timestamps = false, sentenceAccurate = true, maxChars = 3900,
        markup = MarkupDialect.NONE, speedRange = 0.25f..4f, concurrency = 1,
    )

    private val initLock = Mutex()
    private val synthLock = Mutex()
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    override fun currentModel(): String = "system"
    override fun centsPerByte(): Double = 0.0

    private suspend fun engine(): TextToSpeech = initLock.withLock {
        tts?.let { return it }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var created: TextToSpeech? = null
                created = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        created!!.setOnUtteranceProgressListener(listener)
                        tts = created
                        cont.resume(created!!)
                    } else {
                        cont.resumeWith(Result.failure(TtsException.Engine("System text-to-speech is unavailable")))
                    }
                }
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(true) }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }
    }

    override suspend fun voices(language: String?): List<VoiceOption> {
        val engine = engine()
        return engine.voices.orEmpty()
            .filter { !it.isNetworkConnectionRequired }
            .filter { language == null || it.locale.language == language }
            .sortedBy { it.name }
            .map { v ->
                VoiceOption(
                    ref = VoiceRef(ID, v.name, v.locale.displayName),
                    description = v.name,
                    language = v.locale.toLanguageTag(),
                    previewUrl = null,
                )
            }
    }

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult = synthLock.withLock {
        val engine = engine()
        engine.voices?.firstOrNull { it.name == request.voiceId }?.let { engine.voice = it }
        engine.setSpeechRate(request.speed.coerceIn(capabilities.speedRange))
        scratchDir.mkdirs()
        val parts = ArrayList<ShortArray>()
        val spans = ArrayList<Pair<Long, Long>>()
        var rate = 0
        var cursor = 0L
        for (sentence in request.sentences) {
            val file = File(scratchDir, "sys-${UUID.randomUUID()}.wav")
            try {
                val id = file.name
                val done = CompletableDeferred<Boolean>().also { pending[id] = it }
                val queued = engine.synthesizeToFile(sentence, Bundle(), file, id)
                if (queued != TextToSpeech.SUCCESS) {
                    pending.remove(id)
                    throw TtsException.Engine("System text-to-speech rejected the text")
                }
                val ok = withTimeout(60_000) { done.await() }
                if (!ok || !file.exists()) throw TtsException.Engine("System text-to-speech failed")
                val pcm = AudioDsp.parseWav(file.readBytes())
                if (rate == 0) rate = pcm.sampleRate
                if (pcm.sampleRate != rate) throw TtsException.Audio("Inconsistent sample rate")
                val gap = AudioDsp.silence(SENTENCE_GAP_MS, rate)
                parts += pcm.samples
                parts += gap
                val end = cursor + pcm.durationMs + SENTENCE_GAP_MS
                spans += cursor to end
                cursor = end
            } finally {
                file.delete()
            }
        }
        if (rate == 0) throw TtsException.Engine("Nothing to synthesize")
        SynthesisResult(SynthAudio.Pcm(AudioDsp.concat(parts), rate), words = null, sentenceSpans = spans, model = "system")
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        const val ID = "system"
        const val SENTENCE_GAP_MS = 120
    }
}
