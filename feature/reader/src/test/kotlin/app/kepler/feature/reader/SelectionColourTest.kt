package app.kepler.feature.reader

import app.kepler.engine.epub.reader.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionColourTest {
    @Test fun compositesAlphaOverThePage() {
        // accent #4D8BFF at .35 over the night page #1B1B1F
        val c = compositeOver(0x594D8BFF, 0xFF1B1B1F.toInt())
        assertEquals(0xFF, c ushr 24 and 0xFF)
        assertEquals((0x4D * 0.349f + 0x1B * 0.651f + 0.5f).toInt(), c shr 16 and 0xFF)
        assertEquals((0xFF * 0.349f + 0x1F * 0.651f + 0.5f).toInt(), c and 0xFF)
    }

    @Test fun opaqueColourWins() = assertEquals(0xFF123456.toInt(), compositeOver(0xFF123456.toInt(), 0xFFFFFFFF.toInt()))
}
