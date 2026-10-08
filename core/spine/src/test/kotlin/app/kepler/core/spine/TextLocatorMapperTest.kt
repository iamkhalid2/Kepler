package app.kepler.core.spine

import app.kepler.core.model.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextLocatorMapperTest {
    private fun s(o: Int, href: String, text: String, before: String = "", after: String = "") =
        Sentence(1, o, 1, href, text, before, after, o)

    private val mapper = TextLocatorMapper(
        listOf(
            s(0, "ch1.xhtml", "Call me Ishmael.", "", "Some years ago"),
            s(1, "ch1.xhtml", "Some years ago, never mind how long precisely."),
            s(2, "ch2.xhtml", "He said no.", "", "Then nothing."),
            s(3, "ch2.xhtml", "Then nothing."),
            s(4, "ch2.xhtml", "He said no.", "Then nothing.", ""),
        ),
    )

    @Test fun exactAndPartial() {
        assertEquals(0, mapper.ordinalFor("ch1.xhtml", "Call me Ishmael."))
        assertEquals(1, mapper.ordinalFor("ch1.xhtml#x", "never   mind how\nlong"))
    }

    @Test fun contextDisambiguatesDuplicates() {
        assertEquals(2, mapper.ordinalFor("ch2.xhtml", "He said no.", after = "Then nothing."))
        assertEquals(4, mapper.ordinalFor("ch2.xhtml", "He said no.", before = "Then nothing."))
    }

    @Test fun selectionSpanningSentences() {
        assertEquals(0..1, mapper.rangeFor("ch1.xhtml", "Ishmael. Some years ago"))
    }

    @Test fun missing() {
        assertNull(mapper.ordinalFor("ch1.xhtml", "absent text"))
        assertNull(mapper.ordinalFor("zzz.xhtml", "Call me"))
    }

    @Test fun reverse() {
        assertEquals("Then nothing.", mapper.locatorOf(3)?.highlight)
        assertNull(mapper.locatorOf(99))
    }
}
