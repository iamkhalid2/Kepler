package app.kepler.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { Welcome, Pillars, Keys, Privacy, Battery }

enum class KeyCheck {
    Idle, Checking, Connected,
    /** The provider answered and did not accept the key. */
    Rejected,
    /** The check could not reach the provider; the key may still be fine. */
    Unreachable,
}

/** Which provider a key belongs to. */
enum class KeyKind { Fish, OpenRouter }

data class OnboardingUi(
    val steps: List<OnboardingStep>,
    val index: Int = 0,
    val fish: KeyCheck = KeyCheck.Idle,
    val openRouter: KeyCheck = KeyCheck.Idle,
    val finished: Boolean = false,
) {
    val step: OnboardingStep get() = steps[index]
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == steps.lastIndex
}

class OnboardingViewModel(private val deps: LibraryDeps) : ViewModel() {
    private val _state = MutableStateFlow(
        OnboardingUi(
            buildList {
                add(OnboardingStep.Welcome); add(OnboardingStep.Pillars); add(OnboardingStep.Keys); add(OnboardingStep.Privacy)
                if (deps.showsBatteryGuide()) add(OnboardingStep.Battery)
            },
        ),
    )
    val state: StateFlow<OnboardingUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val fish = deps.settings.fishKey() != null
            val or = deps.settings.openRouterKey() != null
            _state.update {
                it.copy(
                    fish = if (fish) KeyCheck.Connected else it.fish,
                    openRouter = if (or) KeyCheck.Connected else it.openRouter,
                )
            }
        }
    }

    fun next() {
        _state.update { if (it.isLast) it else it.copy(index = it.index + 1) }
    }

    fun back() {
        _state.update { if (it.isFirst) it else it.copy(index = it.index - 1) }
    }

    /** Verifies [key] through the injected lambda and stores it only when accepted. */
    fun verify(kind: KeyKind, key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        setCheck(kind, KeyCheck.Checking)
        viewModelScope.launch {
            val result = try {
                val ok = if (kind == KeyKind.Fish) deps.verifyFishKey(k) else deps.verifyOpenRouterKey(k)
                if (ok) KeyCheck.Connected else KeyCheck.Rejected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyCheck.Unreachable
            }
            if (result == KeyCheck.Connected) store(kind, k)
            setCheck(kind, result)
        }
    }

    /** For an [KeyCheck.Unreachable] check: keep the key without having verified it. */
    fun saveUnverified(kind: KeyKind, key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        viewModelScope.launch {
            store(kind, k)
            setCheck(kind, KeyCheck.Connected)
        }
    }

    fun finish() {
        viewModelScope.launch {
            deps.settings.update { it.copy(onboarded = true) }
            _state.update { it.copy(finished = true) }
        }
    }

    private suspend fun store(kind: KeyKind, key: String) {
        if (kind == KeyKind.Fish) deps.settings.setFishKey(key) else deps.settings.setOpenRouterKey(key)
    }

    private fun setCheck(kind: KeyKind, c: KeyCheck) {
        _state.update { if (kind == KeyKind.Fish) it.copy(fish = c) else it.copy(openRouter = c) }
    }
}
