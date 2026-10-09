package app.kepler.feature.library

import app.kepler.core.model.VoiceOption
import app.kepler.core.model.VoiceRef
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLanguageTest {
    private fun v(id: String, lang: String?) = VoiceOption(VoiceRef("fish", id, id), "", lang, null)

    @Test fun keepsOnlyVoicesInTheBookLanguage() {
        val all = listOf(v("ja1", "ja"), v("en1", "en"), v("es1", "es"), v("en2", "en-US"))
        assertEquals(listOf("en1", "en2"), voicesForLanguage(all, "en-GB").map { it.ref.id })
    }

    @Test fun fallsBackToEveryVoiceWhenNoneMatchOrLanguageUnknown() {
        val all = listOf(v("ja1", "ja"), v("x", null))
        assertEquals(all, voicesForLanguage(all, "fr"))
        assertEquals(all, voicesForLanguage(all, null))
    }
}
