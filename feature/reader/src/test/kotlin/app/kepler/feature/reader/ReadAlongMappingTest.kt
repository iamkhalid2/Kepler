package app.kepler.feature.reader

import app.kepler.core.model.Sentence
import app.kepler.engine.epub.reader.DoubleTapDetector
import app.kepler.engine.epub.reader.LocatorOrdinalMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The read-along pieces the unified screen leans on: tap-to-sentence and double taps. */
class ReadAlongMappingTest {
    private fun s(o: Int, text: String, para: Int, href: String = "c1.xhtml") = Sentence(1, o, 1, href, text, "", "", para)

    private val mapper = LocatorOrdinalMapper(
        listOf(
            s(0, "Chapter one.", 0),
            s(1, "It was a bright cold day.", 1),
            s(2, "The clocks were striking thirteen.", 1),
            s(3, "Winston slipped quickly through the doors.", 1),
            s(4, "The hallway smelt of boiled cabbage.", 2),
            s(5, "Next file.", 0, href = "c2.xhtml"),
        ),
    )

    @Test fun tappedParagraphTextFindsItsFirstSentence() {
        assertEquals(1, mapper.ordinalOfText("c1.xhtml", "It was a bright cold day. The clocks were striking thirteen."))
        assertEquals(4, mapper.ordinalOfText("c1.xhtml", "The  hallway smelt of BOILED cabbage."))
        assertNull(mapper.ordinalOfText("c1.xhtml", "Not in this book at all."))
        assertNull(mapper.ordinalOfText("missing.xhtml", "Chapter one."))
    }

    @Test fun doubleTapNeedsTwoCloseQuickTaps() {
        val d = DoubleTapDetector(windowMs = 300, slopPx = 50f)
        assertFalse(d.onTap(100f, 100f, 1_000))
        assertTrue(d.onTap(110f, 95f, 1_200))
        // A third tap starts over rather than chaining.
        assertFalse(d.onTap(110f, 95f, 1_300))
        assertFalse(d.onTap(300f, 95f, 1_400))
        assertFalse(d.onTap(300f, 95f, 2_000))
    }
}
