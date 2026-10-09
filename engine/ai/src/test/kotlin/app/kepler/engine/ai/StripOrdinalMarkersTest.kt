package app.kepler.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class StripOrdinalMarkersTest {
    @Test fun removesPassageTagsAndTidiesPunctuation() {
        val raw = "Moderation orders the soul (see [145]-[147]). Externals are indifferent (see [136]-[140]). It holds under the rack [148]."
        assertEquals(
            "Moderation orders the soul. Externals are indifferent. It holds under the rack.",
            stripOrdinalMarkers(raw),
        )
    }

    @Test fun leavesOrdinaryBracketsAndNumbersAlone() {
        val raw = "In AD 121 he was born [sic], aged 3."
        assertEquals(raw, stripOrdinalMarkers(raw))
    }
}
