package app.kepler.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelSpeedTest {
    private val probe = """
        {"lastProbeAt":1760000000000,"models":{
          "a/slow-model:free":{"samples":[2400,2600,2500]},
          "b/ling-3.1-flash:free":{"samples":[1500,1300,1400]},
          "c/fast-but-failed:free":{"samples":[300],"failedAt":1760000000001,"failure":"429"},
          "d/never-answered:free":{"samples":[]}
        }}
    """.trimIndent()

    @Test fun picksTheFastestModelThatHasNotFailed() {
        val s = parseModelSpeed(probe)
        assertEquals(ModelSpeed("b/ling-3.1-flash:free", 1400), s)
    }

    @Test fun missingOrBrokenRecordsGiveNothing() {
        assertNull(parseModelSpeed(null))
        assertNull(parseModelSpeed(""))
        assertNull(parseModelSpeed("not json"))
        assertNull(parseModelSpeed("""{"models":{}}"""))
        assertNull(parseModelSpeed("""{"lastProbeAt":0}"""))
    }

    @Test fun shortNamesDropTheVendorAndTheFreeTag() {
        assertEquals("ling-3.1-flash", shortModelName("b/ling-3.1-flash:free"))
        assertEquals("plain", shortModelName("plain"))
    }
}
