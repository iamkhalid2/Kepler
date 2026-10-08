package app.betterbooks.engine.epub.reader

import app.betterbooks.core.model.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatorOrdinalMapperTest {

    private fun s(o: Int, href: String, text: String, before: String = "", after: String = "") =
        Sentence(1, o, 0, href, text, before, after, o / 3)

    private val ch1 = listOf(
        s(0, "OEBPS/ch1.xhtml", "It was a dark night.", "", "The wind howled."),
        s(1, "OEBPS/ch1.xhtml", "The wind howled.", "It was a dark night.", "“Stay,” she said."),
        s(2, "OEBPS/ch1.xhtml", "“Stay,” she said.", "The wind howled.", "He stayed."),
        s(3, "OEBPS/ch1.xhtml", "He stayed.", "she said.", "The wind howled."),
        s(4, "OEBPS/ch1.xhtml", "The wind howled.", "He stayed.", "Morning came."),
        s(5, "OEBPS/ch1.xhtml", "Morning came.", "The wind howled.", ""),
    )
    private val ch2 = listOf(
        s(6, "OEBPS/ch%202.xhtml", "Chapter two begins here.", "", "A second sentence."),
        s(7, "OEBPS/ch%202.xhtml", "A second sentence.", "begins here.", ""),
    )
    private val mapper = LocatorOrdinalMapper(ch2 + ch1)

    @Test fun exactHighlightMatches() {
        val r = mapper.rangeAt(TextAnchor("OEBPS/ch1.xhtml", highlight = "Morning came."))
        assertEquals(5..5, r)
    }

    @Test fun repeatedTextIsDisambiguatedByContext() {
        val first = mapper.ordinalAt(TextAnchor("OEBPS/ch1.xhtml", before = "a dark night. ", highlight = "The wind howled.", after = " \"Stay,"))
        val second = mapper.ordinalAt(TextAnchor("OEBPS/ch1.xhtml", before = "He stayed.", highlight = "The wind howled.", after = "Morning"))
        assertEquals(1, first)
        assertEquals(4, second)
    }

    @Test fun repeatedTextWithoutContextPrefersProgression() {
        val late = mapper.ordinalAt(TextAnchor("OEBPS/ch1.xhtml", progression = 0.8, highlight = "the wind howled."))
        assertEquals(4, late)
    }

    @Test fun foldsQuotesWhitespaceAndCase() {
        val o = mapper.ordinalAt(TextAnchor("ch1.xhtml", highlight = "\"stay,\"\n   SHE said."))
        assertEquals(2, o)
    }

    @Test fun multiSentenceHighlightGivesRange() {
        val r = mapper.rangeAt(TextAnchor("/OEBPS/ch1.xhtml#p3", highlight = "she said. He stayed. The wind"))
        assertEquals(2..4, r)
    }

    @Test fun progressionOnlyIsCharWeighted() {
        assertEquals(0, mapper.ordinalAt(TextAnchor("OEBPS/ch1.xhtml", progression = 0.0)))
        assertEquals(5, mapper.ordinalAt(TextAnchor("OEBPS/ch1.xhtml", progression = 0.99)))
    }

    @Test fun percentEncodedHrefMatches() {
        assertEquals(7, mapper.ordinalAt(TextAnchor("OEBPS/ch 2.xhtml", highlight = "A second sentence.")))
    }

    @Test fun unknownHrefFallsBackToTotalProgression() {
        assertNull(mapper.ordinalAt(TextAnchor("nope.xhtml")))
        assertEquals(7, mapper.ordinalAt(TextAnchor("nope.xhtml", totalProgression = 1.0)))
        assertEquals(0, mapper.ordinalAt(TextAnchor("nope.xhtml", totalProgression = 0.0)))
    }

    @Test fun roundTripEveryOrdinal() {
        for (o in 0..7) {
            val a = mapper.anchorFor(o)!!
            assertEquals("ordinal $o", o, mapper.ordinalAt(a))
        }
    }

    @Test fun anchorProgressionIncreases() {
        val ps = (0..5).map { mapper.anchorFor(it)!!.progression!! }
        assertTrue(ps.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun quoteAnchorRebuildsContext() {
        val a = mapper.anchorForQuote(1, 2, "howled. “Stay")!!
        assertEquals("howled. “Stay", a.highlight)
        assertTrue(a.before!!.endsWith("The wind"))
        assertTrue(a.after!!.startsWith(",” she said."))
        assertEquals(1..2, mapper.rangeAt(a))
    }
}
