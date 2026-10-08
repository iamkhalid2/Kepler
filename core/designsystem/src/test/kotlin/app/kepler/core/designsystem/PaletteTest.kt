package app.kepler.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaletteTest {
    private fun near(expected: Long, actual: Color, tol: Int = 6) {
        val e = Color(expected).toArgb(); val a = actual.toArgb()
        for (shift in intArrayOf(16, 8, 0)) {
            val d = abs(((e shr shift) and 0xFF) - ((a shr shift) and 0xFF))
            assertTrue("expected ${expected.toString(16)} got ${a.toUInt().toString(16)} (channel shift $shift off by $d)", d <= tol)
        }
    }

    @Test fun oklchRoundTrips() {
        val c = Color(0xFF4D8BFF)
        val back = c.toLch().toColor()
        near(0xFF4D8BFF, back, 1)
    }

    @Test fun cobaltDarkMatchesSpec() {
        val p = kpPalette(KpSeed.Cobalt.color, dark = true)
        near(0xFF4D8BFF, p.accent, 2)
        near(0xFF78A8FE, p.accentText, 10)
        near(0xFF19347F, p.materials.cloth.face, 10)
        near(0xFF2DD4BF, p.secondary, 10)
        assertTrue(contrastRatio(p.accentText, p.bg) >= 7f)
    }

    @Test fun everySeedIsReadableInBothModes() {
        for (seed in KpSeed.entries) for (dark in listOf(true, false)) {
            val p = kpPalette(seed.color, dark)
            assertTrue("${seed.name} accent dark=$dark", contrastRatio(p.accent, p.bg) >= 2.95f)
            assertTrue("${seed.name} accentText dark=$dark", contrastRatio(p.accentText, p.bg) >= if (dark) 6.9f else 4.45f)
            assertTrue("${seed.name} onAccent dark=$dark", contrastRatio(p.onAccent, p.accent) >= 4.4f)
            assertTrue("${seed.name} silk edgeB dark=$dark", contrastRatio(p.materials.silk.edgeB, p.bg) >= 2.9f)
            assertTrue(contrastRatio(p.text2, p.bg) >= 4.5f)
        }
    }

    @Test fun clashRuleMovesStatusColourAwayFromAccent() {
        val p = kpPalette(KpSeed.Vermilion.color, dark = true)
        val neg = p.negative.toLch().h
        val acc = p.accent.toLch().h
        assertTrue(hueDistance(neg, acc) >= 20f)
    }

    @Test fun lightModeKeyTurnsInk() {
        val p = kpPalette(KpSeed.Cobalt.color, dark = false)
        assertEquals(Color(0xFF201E1A).toArgb(), p.key.toArgb())
        assertTrue(p.materials.env.contactAlpha < 0.5f)
    }
}
