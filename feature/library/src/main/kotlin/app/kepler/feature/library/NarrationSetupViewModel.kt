package app.kepler.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.model.Book
import app.kepler.core.model.BookId
import app.kepler.core.model.NarrationConfig
import app.kepler.core.model.NarrationEstimate
import app.kepler.core.model.NarrationState
import app.kepler.core.model.VoiceOption
import app.kepler.core.model.VoiceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val PROVIDER_FISH = "fish"
const val PROVIDER_SYSTEM = "system"

val SPEED_CHOICES = listOf(0.8f, 1.0f, 1.2f, 1.5f)

sealed interface VoicesUi {
    data object Loading : VoicesUi
    data class Ready(val voices: List<VoiceOption>) : VoicesUi
    data class Unavailable(val problem: VoiceProblem) : VoicesUi
}

enum class VoiceProblem { NoFishKey, Network }

sealed interface EstimateUi {
    data object None : EstimateUi
    data object Loading : EstimateUi
    data class Ready(val estimate: NarrationEstimate) : EstimateUi
    data object Unavailable : EstimateUi
}

data class SetupUi(
    val book: Book? = null,
    val provider: String = PROVIDER_SYSTEM,
    val hasFishKey: Boolean = false,
    val voices: VoicesUi = VoicesUi.Loading,
    val selected: VoiceRef? = null,
    val speed: Float = 1f,
    val estimate: EstimateUi = EstimateUi.None,
    val narration: NarrationState = NarrationState.Idle,
    /** Voice id whose preview is being rendered. */
    val previewing: String? = null,
    val previewFailed: Boolean = false,
) {
    val canStart: Boolean get() = selected != null && narration !is NarrationState.Running
}

/** [sampleText] is read aloud by the preview key. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NarrationSetupViewModel(
    private val deps: LibraryDeps,
    val bookId: BookId,
    private val sampleText: String,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUi())
    val state: StateFlow<SetupUi> = _state.asStateFlow()

    private val _previews = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** File paths of rendered previews; the screen plays them. */
    val previews: SharedFlow<String> = _previews.asSharedFlow()

    private val estimateInput = MutableStateFlow<Pair<VoiceRef, Float>?>(null)

    init {
        deps.library.book(bookId).onEach { b -> _state.update { it.copy(book = b) } }.launchIn(viewModelScope)
        deps.narration.state(bookId).onEach { n -> _state.update { it.copy(narration = n) } }.launchIn(viewModelScope)
        viewModelScope.launch {
            estimateInput.filterNotNull().debounce(250).collectLatest { (voice, speed) -> computeEstimate(voice, speed) }
        }
        viewModelScope.launch {
            val s = deps.settings.settings.first()
            val hasKey = deps.settings.fishKey() != null
            val saved = s.voice
            val provider = when {
                saved != null -> saved.provider
                hasKey -> PROVIDER_FISH
                else -> PROVIDER_SYSTEM
            }
            _state.update { it.copy(hasFishKey = hasKey, provider = provider, speed = s.speed, selected = saved?.takeIf { v -> v.provider == provider }) }
            loadVoices(provider)
        }
    }

    fun setProvider(provider: String) {
        if (provider == _state.value.provider) return
        _state.update { it.copy(provider = provider, selected = null, voices = VoicesUi.Loading, estimate = EstimateUi.None) }
        estimateInput.value = null
        viewModelScope.launch { loadVoices(provider) }
    }

    fun select(voice: VoiceRef) {
        _state.update { it.copy(selected = voice) }
        publishEstimateInput()
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        publishEstimateInput()
    }

    fun preview(option: VoiceOption) {
        _state.update { it.copy(previewing = option.ref.id, previewFailed = false) }
        viewModelScope.launch {
            try {
                _previews.emit(deps.narration.preview(option.ref, sampleText))
                _state.update { it.copy(previewing = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(previewing = null, previewFailed = true) }
            }
        }
    }

    fun start() {
        val s = _state.value
        val voice = s.selected ?: return
        if (s.narration is NarrationState.Running) return
        viewModelScope.launch {
            deps.settings.update { it.copy(voice = voice, speed = s.speed) }
            deps.narration.start(bookId, NarrationConfig(voice, s.speed))
        }
    }

    fun stop() = deps.narration.stop(bookId)

    /** After a Fish stop or limit: continue with the system voice. */
    fun useSystemVoice() {
        setProvider(PROVIDER_SYSTEM)
        viewModelScope.launch {
            // wait for the voice list, then start with the first voice
            var tries = 0
            while (_state.value.selected == null && tries++ < 50) kotlinx.coroutines.delay(100)
            start()
        }
    }

    private fun publishEstimateInput() {
        val s = _state.value
        val v = s.selected ?: return
        estimateInput.value = v to s.speed
    }

    private suspend fun loadVoices(provider: String) {
        if (provider == PROVIDER_FISH && deps.settings.fishKey() == null) {
            _state.update { it.copy(voices = VoicesUi.Unavailable(VoiceProblem.NoFishKey)) }
            return
        }
        val voices = try {
            voicesForLanguage(deps.narration.voices(provider), deps.library.book(bookId).first()?.language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value.provider == provider) _state.update { it.copy(voices = VoicesUi.Unavailable(VoiceProblem.Network)) }
            return
        }
        if (_state.value.provider != provider) return
        _state.update { s ->
            val keep = s.selected?.takeIf { sel -> voices.any { it.ref.id == sel.id } }
            s.copy(voices = VoicesUi.Ready(voices), selected = keep ?: voices.firstOrNull()?.ref)
        }
        publishEstimateInput()
    }

    private suspend fun computeEstimate(voice: VoiceRef, speed: Float) {
        _state.update { it.copy(estimate = EstimateUi.Loading) }
        val est = try {
            EstimateUi.Ready(deps.narration.estimate(bookId, NarrationConfig(voice, speed)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EstimateUi.Unavailable
        }
        _state.update { it.copy(estimate = est) }
    }
}

/** Whole hours and remaining minutes for a duration in seconds. */
fun splitHoursMinutes(seconds: Long): Pair<Int, Int> {
    val totalMinutes = ((seconds + 30) / 60).toInt()
    return totalMinutes / 60 to totalMinutes % 60
}

/** 0..100 for a 0..1 fraction. */
fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).toInt()

/** Voices that speak the book's language (by primary subtag), or every voice when none match. */
internal fun voicesForLanguage(voices: List<VoiceOption>, language: String?): List<VoiceOption> {
    val want = language?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() } ?: return voices
    val matching = voices.filter { it.language?.substringBefore('-')?.substringBefore('_')?.lowercase() == want }
    return matching.ifEmpty { voices }
}

/** Plain copy for a NarrationState.Paused reason code from the narration engine. */
internal fun pausedReasonRes(reason: String): Int = when (reason) {
    "monthly_cap" -> R.string.narration_paused_cap
    "offline" -> R.string.narration_paused_offline
    else -> R.string.narration_paused_system
}
