package app.betterbooks.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperPaletteTest {
    private val pages = listOf(
        Triple(Color(0xFFF4ECD8), Color(0xFF3B2F22), false),
        Triple(Color(0xFFFFFFFF), Color(0xFF1A1A1A), false),
        Triple(Color(0xFF1B1B1F), Color(0xFFD6D3CC), true),
        Triple(Color(0xFF000000), Color(0xFFC9C9C9), true),
    )

    @Test fun neutralsComeFromThePageAndStayReadable() {
        for (seed in BbSeed.presets) for ((page, ink, dark) in pages) {
            val p = paperPalette(seed.color, page, ink, dark)
            assertEquals(page, p.bg)
            assertEquals(ink, p.text)
            assertTrue("text2 on $page", contrastRatio(p.text2, page) >= 4.4f)
            assertTrue("accentText ${seed.name} on $page", contrastRatio(p.accentText, page) >= 4.4f)
            assertTrue("onKey on key", contrastRatio(p.onKey, p.key) >= 4.5f)
        }
    }
}
