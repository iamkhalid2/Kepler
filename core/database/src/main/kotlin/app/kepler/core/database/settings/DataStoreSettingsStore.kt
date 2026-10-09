package app.kepler.core.database.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.kepler.core.model.AppSettings
import app.kepler.core.model.ScrubberScope
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.VoiceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.Base64

class DataStoreSettingsStore(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : SettingsStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val d = AppSettings()

    override val settings: Flow<AppSettings> = store.data.map(::readFrom)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p -> write(p, transform(readFrom(p))) }
    }

    private fun readFrom(p: Preferences) = AppSettings(
        onboarded = p[ONBOARDED] ?: d.onboarded,
        seed = p[SEED] ?: d.seed,
        dark = p[DARK] ?: d.dark,
        readerTheme = p[READER_THEME] ?: d.readerTheme,
        fontScale = p[FONT_SCALE] ?: d.fontScale,
        fontFamily = p[FONT_FAMILY] ?: d.fontFamily,
        lineHeight = p[LINE_HEIGHT] ?: d.lineHeight,
        voice = p[VOICE]?.let { runCatching { json.decodeFromString<VoiceRef>(it) }.getOrNull() },
        speed = p[SPEED] ?: d.speed,
        monthlyCapCents = p[MONTHLY_CAP] ?: d.monthlyCapCents,
        openRouterModel = p[OR_MODEL],
        haptics = p[HAPTICS] ?: d.haptics,
        scrubberScope = p[SCRUBBER_SCOPE]?.let { n -> ScrubberScope.entries.firstOrNull { it.name == n } } ?: d.scrubberScope,
    )

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[ONBOARDED] = s.onboarded
        p[SEED] = s.seed
        p[DARK] = s.dark
        p[READER_THEME] = s.readerTheme
        p[FONT_SCALE] = s.fontScale
        p[FONT_FAMILY] = s.fontFamily
        p[LINE_HEIGHT] = s.lineHeight
        val voice = s.voice
        if (voice != null) p[VOICE] = json.encodeToString(VoiceRef.serializer(), voice) else p.remove(VOICE)
        p[SPEED] = s.speed
        p[MONTHLY_CAP] = s.monthlyCapCents
        val model = s.openRouterModel
        if (model != null) p[OR_MODEL] = model else p.remove(OR_MODEL)
        p[HAPTICS] = s.haptics
        p[SCRUBBER_SCOPE] = s.scrubberScope.name
    }

    override suspend fun fishKey(): String? = readSecret(FISH_KEY)
    override suspend fun setFishKey(key: String?) = writeSecret(FISH_KEY, key)
    override suspend fun openRouterKey(): String? = readSecret(OR_KEY)
    override suspend fun setOpenRouterKey(key: String?) = writeSecret(OR_KEY, key)

    private suspend fun readSecret(k: Preferences.Key<String>): String? {
        val stored = store.data.first()[k] ?: return null
        return runCatching {
            val (iv, data) = stored.split(SEP, limit = 2)
            val dec = Base64.getDecoder()
            String(cipher.decrypt(CipherBlob(dec.decode(iv), dec.decode(data))), Charsets.UTF_8)
        }.getOrNull()
    }

    private suspend fun writeSecret(k: Preferences.Key<String>, value: String?) {
        val sealed = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val blob = cipher.encrypt(it.toByteArray(Charsets.UTF_8))
            val enc = Base64.getEncoder()
            enc.encodeToString(blob.iv) + SEP + enc.encodeToString(blob.data)
        }
        store.edit { p -> if (sealed == null) p.remove(k) else p[k] = sealed }
    }

    private companion object {
        const val SEP = ":"
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val SEED = stringPreferencesKey("seed")
        val DARK = booleanPreferencesKey("dark")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val VOICE = stringPreferencesKey("voice")
        val SPEED = floatPreferencesKey("speed")
        val MONTHLY_CAP = intPreferencesKey("monthly_cap_cents")
        val OR_MODEL = stringPreferencesKey("openrouter_model")
        val HAPTICS = booleanPreferencesKey("haptics")
        val SCRUBBER_SCOPE = stringPreferencesKey("scrubber_scope")
        val FISH_KEY = stringPreferencesKey("secret_fish")
        val OR_KEY = stringPreferencesKey("secret_openrouter")
    }
}
