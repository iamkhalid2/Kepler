package app.kepler.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogicTest {
    private val day = 86_400_000L

    @Test fun currentBookIsTheMostRecentlyOpenedThenNewest() {
        val a = book(1, addedAt = 10, lastOpenedAt = 500)
        val b = book(2, addedAt = 20, lastOpenedAt = 900)
        val c = book(3, addedAt = 30, lastOpenedAt = null)
        assertEquals(2L, currentBook(listOf(a, b, c))?.id)
        assertEquals(3L, currentBook(listOf(c, book(4, addedAt = 5, lastOpenedAt = null)))?.id)
        assertNull(currentBook(emptyList()))
    }

    @Test fun previouslyOnOnlyAfterThreeDaysInAStartedBook() {
        val now = 100 * day
        assertNull(daysAway(book(1, lastOpenedAt = now - 2 * day, read = 0.2f), now))
        assertEquals(4, daysAway(book(1, lastOpenedAt = now - 4 * day, read = 0.2f), now))
        assertNull(daysAway(book(1, lastOpenedAt = now - 9 * day), now))
    }

    @Test fun reviewMinutesRoundUpToAMinute() {
        assertEquals(1, reviewMinutes(1))
        assertEquals(6, reviewMinutes(12))
    }
}
