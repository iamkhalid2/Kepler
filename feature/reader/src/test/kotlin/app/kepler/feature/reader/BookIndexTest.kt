package app.kepler.feature.reader

import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import app.kepler.core.model.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookIndexTest {
    // 20 sentences of 5 words; chapter 1 = 0..9 (with a subsection 5..9), chapter 2 = 10..19.
    private val sentences = (0 until 20).map { Sentence(1, it, 0, "c.xhtml", "one two three four five.", "", "", it) }
    private val sections = listOf(
        Section(1, 1, null, 0, 0, "One", "c1.xhtml", SectionKind.BODY, 0, 9, true),
        Section(2, 1, 1, 1, 1, "One B", "c1.xhtml#b", SectionKind.BODY, 5, 9, true),
        Section(3, 1, null, 2, 0, "Two", "c2.xhtml", SectionKind.BODY, 10, 19, true),
        Section(4, 1, null, 3, 0, "Notes", "n.xhtml", SectionKind.NOTES, 20, 25, false),
    )
    private val index = BookIndex(sentences, sections)

    @Test fun countsWords() {
        assertEquals(3, BookIndex.countWords("Don't stop — now."))
        assertEquals(50, index.words(0, 9))
        assertEquals(5, index.words(7, 7))
        assertEquals(0, index.words(9, 7))
    }

    @Test fun chaptersAreTopLevelIncludedSections() {
        assertEquals(listOf("One", "Two"), index.chapters.map { it.title })
        assertEquals("One B", index.sectionAt(6)?.title)
        assertEquals("One", index.chapterAt(6)?.title)
        assertNull(index.sectionAt(22))
    }

    @Test fun fractionAndInverse() {
        assertEquals(0f, index.fraction(0))
        assertEquals(1f, index.fraction(19))
        assertEquals(0, index.ordinalAt(0f))
        assertEquals(19, index.ordinalAt(1f))
        assertEquals(10, index.ordinalAt(index.fraction(10)))
    }

    @Test fun minutesLeftRunToChapterEnd() {
        // 3 sentences (7, 8, 9) of 5 words at 15 wpm = 1 minute.
        assertEquals(1.0, index.minutesLeftInChapter(7, wpm = 15), 1e-9)
        assertEquals(50.0 / 10, index.minutesLeftInChapter(10, wpm = 10), 1e-9)
    }
}
