package app.betterbooks.core.spine

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSegmenterTest {
    private fun seg(s: String) = SentenceSegmenter.segment(s)

    @Test fun splitsPlainSentences() {
        assertEquals(listOf("It was late.", "He left.", "Why?"), seg("It was late. He left. Why?"))
    }

    @Test fun titlesDoNotSplit() {
        assertEquals(1, seg("Mr. Smith met Dr. Jones at St. Mary's church.").size)
        assertEquals(1, seg("Prof. Higgins arrived.").size)
    }

    @Test fun latinAbbreviationsDoNotSplit() {
        assertEquals(1, seg("Many fruits, e.g. apples and pears, are sweet.").size)
        assertEquals(1, seg("Some fruit, i.e. the apple, fell.").size)
        assertEquals(2, seg("He bought apples, pears, etc. Then he left.").size)
        assertEquals(1, seg("He bought apples, pears, etc. and left.").size)
    }

    @Test fun initialsDoNotSplit() {
        assertEquals(listOf("J. K. Rowling wrote it."), seg("J. K. Rowling wrote it."))
        assertEquals(1, seg("The author T. S. Eliot died in 1965.").size)
    }

    @Test fun acronymsGlue() {
        assertEquals(1, seg("He joined the U.S. Army in May.").size)
    }

    @Test fun numberAbbreviation() {
        assertEquals(1, seg("See No. 5 for details.").size)
    }

    @Test fun quotesStayAttached() {
        val r = seg("\"Go home,\" she said. \"Now.\"")
        assertEquals(2, r.size)
    }

    @Test fun longSentenceIsSplit() {
        val long = (1..400).joinToString(" ") { "word$it" }
        val r = seg(long)
        assert(r.size > 1)
        assert(r.all { it.length <= SentenceSegmenter.MAX_SENTENCE_CHARS })
        assertEquals(long, r.joinToString(" "))
    }

    @Test fun blankIsEmpty() {
        assertEquals(emptyList<String>(), seg("   "))
    }
}
