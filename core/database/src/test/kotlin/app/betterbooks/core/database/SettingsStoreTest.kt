package app.betterbooks.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import app.betterbooks.core.database.settings.DataStoreSettingsStore
import app.betterbooks.core.model.VoiceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsStoreTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var prefs: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var store: DataStoreSettingsStore

    @Before fun setUp() {
        dir = Files.createTempDirectory("settings").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") }
        store = DataStoreSettingsStore(prefs, FakeCipher())
    }

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        dir.deleteRecursively()
    }

    @Test fun defaultsThenUpdateRoundTrip() = runBlocking {
        assertEquals(app.betterbooks.core.model.AppSettings(), store.settings.first())
        val voice = VoiceRef("fish", "abc", "Narrator")
        store.update { it.copy(onboarded = true, seed = "Rose", voice = voice, speed = 1.25f, openRouterModel = "x/y:free", monthlyCapCents = 500) }
        val s = store.settings.first()
        assertTrue(s.onboarded)
        assertEquals("Rose", s.seed)
        assertEquals(voice, s.voice)
        assertEquals(1.25f, s.speed, 0f)
        assertEquals("x/y:free", s.openRouterModel)
        store.update { it.copy(voice = null, openRouterModel = null) }
        assertNull(store.settings.first().voice)
        assertNull(store.settings.first().openRouterModel)
    }

    @Test fun keysRoundTripAndAreNeverStoredInPlaintext() = runBlocking {
        assertNull(store.fishKey())
        store.setFishKey("fish-secret-123")
        store.setOpenRouterKey("or-secret-456")
        assertEquals("fish-secret-123", store.fishKey())
        assertEquals("or-secret-456", store.openRouterKey())
        val raw = prefs.data.first().asMap().values.joinToString("|")
        assertFalse(raw.contains("fish-secret-123"))
        assertFalse(raw.contains("or-secret-456"))
        // settings updates must not disturb the keys
        store.update { it.copy(dark = false) }
        assertEquals("fish-secret-123", store.fishKey())
        store.setFishKey(null)
        assertNull(store.fishKey())
        assertEquals("or-secret-456", store.openRouterKey())
    }

    @Test fun undecryptableKeyReadsAsNull() = runBlocking {
        store.setFishKey("k")
        prefs.updateData { p -> p.toMutablePreferences().also { it[stringPreferencesKey("secret_fish")] = "AAAA:BBBB" } }
        assertNull(store.fishKey())
    }

    @Test fun blankKeyClears() = runBlocking {
        store.setFishKey("k")
        store.setFishKey("  ")
        assertNull(store.fishKey())
    }
}
