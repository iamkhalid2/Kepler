package app.kepler.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kepler.core.designsystem.KpSeed
import app.kepler.core.model.AiBudget
import app.kepler.core.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUi(
    val app: AppSettings = AppSettings(),
    val fishSet: Boolean = false,
    val openRouterSet: Boolean = false,
    val fishCheck: KeyCheck = KeyCheck.Idle,
    val openRouterCheck: KeyCheck = KeyCheck.Idle,
    val budget: AiBudget? = null,
    val spentCents: Int = 0,
    val models: List<String> = emptyList(),
    val version: String = "",
) {
    val seed: KpSeed get() = KpSeed.fromName(app.seed)
}

class SettingsViewModel(private val deps: LibraryDeps) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUi(version = deps.appVersion))
    val state: StateFlow<SettingsUi> = _state.asStateFlow()

    init {
        deps.settings.settings.onEach { s -> _state.update { it.copy(app = s) } }.launchIn(viewModelScope)
        refresh()
    }

    /** Re-reads key presence, the AI budget, the month's spend and the model list. */
    fun refresh() {
        viewModelScope.launch {
            val fish = deps.settings.fishKey() != null
            val or = deps.settings.openRouterKey() != null
            _state.update { it.copy(fishSet = fish, openRouterSet = or, spentCents = deps.spentThisMonthCents()) }
            if (or) {
                val budget = runCatching { deps.aiBudget() }.getOrNull()
                val models = runCatching { deps.freeModels() }.getOrDefault(emptyList())
                _state.update { it.copy(budget = budget, models = models) }
            } else {
                _state.update { it.copy(budget = null, models = emptyList()) }
            }
        }
    }

    fun verifyAndSave(kind: KeyKind, key: String) {
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
            if (result == KeyCheck.Connected) saveKey(kind, k)
            setCheck(kind, result)
        }
    }

    fun saveUnverified(kind: KeyKind, key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        viewModelScope.launch {
            saveKey(kind, k)
            setCheck(kind, KeyCheck.Connected)
        }
    }

    fun removeKey(kind: KeyKind) {
        viewModelScope.launch {
            if (kind == KeyKind.Fish) deps.settings.setFishKey(null) else deps.settings.setOpenRouterKey(null)
            setCheck(kind, KeyCheck.Idle)
            refresh()
        }
    }

    fun clearCheck(kind: KeyKind) = setCheck(kind, KeyCheck.Idle)

    fun setSeed(seed: KpSeed) = edit { it.copy(seed = seed.name) }
    fun setDark(dark: Boolean) = edit { it.copy(dark = dark) }
    fun setHaptics(on: Boolean) = edit { it.copy(haptics = on) }
    fun setMonthlyCapCents(cents: Int) = edit { it.copy(monthlyCapCents = cents.coerceAtLeast(0)) }
    fun setOpenRouterModel(model: String?) = edit { it.copy(openRouterModel = model) }
    fun setFontScale(scale: Float) = edit { it.copy(fontScale = scale) }
    fun setLineHeight(h: Float) = edit { it.copy(lineHeight = h) }
    fun setFontFamily(f: String) = edit { it.copy(fontFamily = f) }

    private fun edit(t: (AppSettings) -> AppSettings) {
        viewModelScope.launch { deps.settings.update(t) }
    }

    private suspend fun saveKey(kind: KeyKind, key: String) {
        if (kind == KeyKind.Fish) deps.settings.setFishKey(key) else deps.settings.setOpenRouterKey(key)
        refresh()
    }

    private fun setCheck(kind: KeyKind, c: KeyCheck) {
        _state.update { if (kind == KeyKind.Fish) it.copy(fishCheck = c) else it.copy(openRouterCheck = c) }
    }
}
