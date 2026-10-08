package app.betterbooks.feature.study

import app.betterbooks.core.model.ContentType
import app.betterbooks.core.model.SectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    @Test fun hintShowsFirstWordOrFirstLetter() {
        assertEquals("Virtue …", Study.hintFor("Virtue is the only good"))
        assertEquals("S _ _ _ _", Study.hintFor("Stoic"))
        assertEquals("", Study.hintFor("  "))
    }

    @Test fun studyModeFollowsContentType() {
        assertTrue(Study.isStudyMode(ContentType.NONFICTION))
        assertTrue(Study.isStudyMode(ContentType.MIXED))
        assertFalse(Study.isStudyMode(ContentType.FICTION))
    }

    @Test fun newCardCapCountsTodayOnly() {
        val yesterday = T0 - 86_400_000L
        val cards = (1..4L).map { card(it) } + (5..7L).map { card(it, created = yesterday) }
        assertEquals(4, Study.newCardsToday(cards, T0, UTC))
        assertEquals(6, Study.newCardsLeft(cards, T0, UTC))
        val ten = (1..10L).map { card(it) }
        assertEquals(0, Study.newCardsLeft(ten + card(11), T0, UTC))
    }

    @Test fun newCardIsDueNowAndUnreviewed() {
        val c = Study.newCard(1, app.betterbooks.core.model.CardType.QA, 3, 4, " Q ", " A ", "  ", T0)
        assertEquals(0, c.state); assertEquals(T0, c.dueAt); assertEquals(null, c.lastReviewAt)
        assertEquals("Q", c.front); assertEquals(null, c.userAnswer)
    }

    @Test fun againRepeatsOnlyWhenSoon() {
        assertTrue(Study.repeatsThisSession(T0 + 60_000, T0))
        assertFalse(Study.repeatsThisSession(T0 + 86_400_000, T0))
    }

    @Test fun intervalsPickReadableUnits() {
        assertEquals(Interval(1, IntervalUnit.MINUTES), formatInterval(10_000))
        assertEquals(Interval(10, IntervalUnit.MINUTES), formatInterval(10 * 60_000L))
        assertEquals(Interval(5, IntervalUnit.HOURS), formatInterval(5 * 3_600_000L))
        assertEquals(Interval(3, IntervalUnit.DAYS), formatInterval(3 * 86_400_000L))
        assertEquals(Interval(2, IntervalUnit.MONTHS), formatInterval(65 * 86_400_000L))
        assertEquals(Interval(1, IntervalUnit.YEARS), formatInterval(400 * 86_400_000L))
    }

    @Test fun weekProgressForgivesAtMostTwoMissedDays() {
        val thursday = LocalDate.of(2025, 10, 9)
        // Mon + Tue reviewed, Wed missed; goal 5 not yet met (2 reviewed + 1 freeze).
        val w = weekProgress(thursday, setOf("2025-10-06", "2025-10-07"), 5)
        assertEquals(7, w.days.size)
        assertEquals(LocalDate.of(2025, 10, 6), w.days.first().date)
        assertEquals(2, w.reviewedDays)
        assertEquals(1, w.freezesUsed) // only Wednesday is a missed past day
        assertEquals(1, w.freezesLeft)
        assertFalse(w.goalMet)
        assertTrue(w.days[3].isToday); assertTrue(w.days[4].isFuture)
        val met = weekProgress(thursday, setOf("2025-10-06", "2025-10-07", "2025-10-09"), 4)
        assertTrue(met.goalMet) // 3 reviewed + 1 freeze
        // A long gap earlier in the week forgives at most two days.
        val late = weekProgress(LocalDate.of(2025, 10, 12), emptySet(), 5)
        assertEquals(2, late.freezesUsed); assertFalse(late.goalMet)
    }

    @Test fun fenceIndicatorWholeBookForNonFictionSectionForFiction() {
        val secs = listOf(section(1, 0, 0, 49, "Book I"), section(2, 1, 50, 99, "Book II"), section(3, 2, 100, 120, "Notes", SectionKind.NOTES))
        assertTrue(fenceInfo(ContentType.NONFICTION, secs, 10).wholeBook)
        val f = fenceInfo(ContentType.FICTION, secs, 60)
        assertEquals("Book II", f.sectionTitle); assertEquals(2, f.sectionIndex)
        assertEquals(null, fenceInfo(ContentType.FICTION, secs, -1).sectionTitle)
        assertEquals(listOf(1L), reachedSections(secs, 10).map { it.id })
    }

    @Test fun indexLettersGroupSymbolsUnderHash() {
        assertEquals('S', indexLetter("stoicism")); assertEquals('#', indexLetter("2nd law")); assertEquals('#', indexLetter(""))
    }

    @Test fun rubricMatchingIgnoresCaseAndPunctuation() {
        val m = matchRubric(listOf("Virtue is enough", "Fate is fixed"), listOf("virtue is enough.", "something else"))
        assertEquals(listOf(true, false), m)
    }

    @Test fun flipFacesSwapAtNinetyDegrees() {
        assertFalse(FlipModel.showsBack(89.9f)); assertTrue(FlipModel.showsBack(90f))
        assertEquals(45f, FlipModel.faceRotation(45f), 0.001f)
        assertEquals(-45f, FlipModel.faceRotation(135f), 0.001f)
        assertEquals(0f, FlipModel.faceRotation(180f), 0.001f)
        assertEquals(1.04f, FlipModel.scale(90f), 0.001f)
        assertEquals(0f, FlipModel.contactWidth(90f), 0.001f)
        assertEquals(0f, FlipModel.shade(0f), 0.001f)
    }
}
