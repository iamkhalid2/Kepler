package app.kepler.engine.playback

import app.kepler.core.model.AudioSegment
import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import app.kepler.core.model.SyncRow
import app.kepler.core.model.VoiceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ava = VoiceRef("fish", "ava", "Ava")

private fun seg(id: Long, start: Int, end: Int, ms: Long = 10_000, take: Int = 0, voice: VoiceRef = ava) =
    AudioSegment(id, 1, start, end, "/tmp/$id.m4a", ms, voice, take)

private fun section(id: Long, start: Int, end: Int, level: Int = 0, included: Boolean = true, kind: SectionKind = SectionKind.BODY) =
    Section(id, 1, null, id.toInt(), level, "S$id", "s$id.xhtml", kind, start, end, included)

class SyncLookupTest {
    private val rows = listOf(
        SyncRow(1, 10, 1, 0, 1_000), SyncRow(1, 11, 1, 1_200, 2_500), SyncRow(1, 12, 1, 2_600, 4_000),
    )
    private val lookup = SyncLookup.of(rows)

    @Test fun findsSentenceByPosition() {
        assertEquals(10, lookup.ordinalAt(0))
        assertEquals(10, lookup.ordinalAt(999))
        assertEquals(11, lookup.ordinalAt(1_200))
        assertEquals(12, lookup.ordinalAt(3_999))
    }

    @Test fun pauseBetweenSentencesBelongsToThePreviousOne() {
        assertEquals(10, lookup.ordinalAt(1_100))
        assertEquals(11, lookup.ordinalAt(2_550))
    }

    @Test fun beforeFirstAndAfterLast() {
        assertEquals(10, lookup.ordinalAt(-5))
        assertEquals(12, lookup.ordinalAt(99_000))
    }

    @Test fun startAndEndByOrdinal() {
        assertEquals(1_200L, lookup.startMs(11))
        assertEquals(2_500L, lookup.endMs(11))
        assertNull(lookup.startMs(99))
        assertTrue(lookup.contains(12))
        assertFalse(lookup.contains(13))
    }

    @Test fun uniformFallbackSplitsEvenly() {
        val u = SyncLookup.uniform(seg(1, 0, 3, ms = 4_000))
        assertEquals(0, u.ordinalAt(500))
        assertEquals(2, u.ordinalAt(2_500))
        assertEquals(3, u.ordinalAt(3_999))
    }
}

class PlaybackPlanTest {
    private fun plan(): PlaybackPlan {
        val segs = listOf(seg(1, 0, 4, 10_000), seg(2, 5, 9, 20_000), seg(3, 10, 14, 5_000))
        // 1 -> 2 new paragraph, 2 -> 3 new section
        val items = PlaybackPlan.itemsFor(null, segs, GapPolicy()) { prevEnd, _ ->
            if (prevEnd == 4) Boundary.PARAGRAPH else Boundary.SECTION
        }
        return PlaybackPlan.EMPTY + items
    }

    @Test fun insertsGapsBetweenSegments() {
        val p = plan()
        assertEquals(5, p.size)
        assertEquals(PlanItem.Silence(450, 4), p.items[1])
        assertEquals(PlanItem.Silence(1_100, 9), p.items[3])
        assertEquals(10_000 + 450 + 20_000 + 1_100 + 5_000L, p.totalMs)
    }

    @Test fun sameParagraphBoundariesHaveNoGap() {
        val items = PlaybackPlan.itemsFor(null, listOf(seg(1, 0, 0), seg(2, 1, 1)), GapPolicy()) { _, _ -> Boundary.SAME_PARAGRAPH }
        assertEquals(2, items.size)
    }

    @Test fun locatesGlobalPositions() {
        val p = plan()
        assertEquals(0 to 0L, p.locate(0))
        assertEquals(0 to 9_999L, p.locate(9_999))
        assertEquals(1 to 0L, p.locate(10_000))
        assertEquals(2 to 100L, p.locate(10_550))
        assertEquals(4 to 5_000L, p.locate(1_000_000))
        assertEquals(10_450L, p.globalMs(2, 0))
    }

    @Test fun mapsOrdinalsAndItems() {
        val p = plan()
        assertEquals(2, p.indexOfOrdinal(7))
        assertEquals(-1, p.indexOfOrdinal(99))
        assertEquals(14, p.lastOrdinal)
        val look = { s: AudioSegment -> SyncLookup.uniform(s) }
        assertEquals(4, p.ordinalAt(1, 100, look))
        assertEquals(5, p.ordinalAt(2, 0, look))
        assertEquals(7, p.ordinalAt(2, 10_000, look))
    }

    @Test fun appendKeepsOffsets() {
        val p = plan()
        val more = PlaybackPlan.itemsFor(p.lastSegment, listOf(seg(4, 15, 19, 1_000)), GapPolicy()) { _, _ -> Boundary.PARAGRAPH }
        val q = p + more
        assertEquals(p.totalMs + 450 + 1_000, q.totalMs)
        assertEquals(p.startMs(2), q.startMs(2))
        assertEquals(19, q.lastOrdinal)
    }

    @Test fun chapterSpanStopsAtChapterAndIgnoresTrailingSilence() {
        val p = plan()
        // segments 1 and 2 share chapter 1, segment 3 is chapter 2
        val span = p.chapterSpan(1) { if (it < 10) 1 else 2 }
        assertEquals(0L, span.first)
        assertEquals(30_450L, span.last)
        val second = p.chapterSpan(4) { if (it < 10) 1 else 2 }
        assertEquals(p.startMs(4), second.first)
    }
}

class SectionIndexTest {
    private val sections = listOf(
        section(1, 0, 9, kind = SectionKind.FRONT, included = false),
        section(2, 10, 59, level = 0),
        section(3, 10, 29, level = 1),
        section(4, 30, 59, level = 1),
        section(5, 60, 69, included = false, kind = SectionKind.BACK),
        section(6, 70, 99, level = 0),
    )
    private val index = SectionIndex(sections, 100, "Book")

    @Test fun narratedIsTheUnionOfIncludedSections() {
        assertFalse(index.isNarrated(5))
        assertTrue(index.isNarrated(10))
        assertTrue(index.isNarrated(59))
        assertFalse(index.isNarrated(65))
        assertTrue(index.isNarrated(99))
        assertEquals(10, index.firstNarrated)
        assertEquals(99, index.lastNarrated)
    }

    @Test fun firstNarratedFromSkipsExcludedText() {
        assertEquals(10, index.firstNarratedFrom(0))
        assertEquals(20, index.firstNarratedFrom(20))
        assertEquals(70, index.firstNarratedFrom(60))
        assertNull(index.firstNarratedFrom(100))
    }

    @Test fun stepCrossesExcludedGaps() {
        assertEquals(21, index.step(20, 1))
        assertEquals(70, index.step(59, 1))
        assertEquals(59, index.step(70, -1))
        assertEquals(10, index.step(10, -3))
        assertEquals(99, index.step(98, 5))
        assertEquals(82, index.step(58, 14))
    }

    @Test fun chaptersAreInnermostIncludedSections() {
        assertEquals(listOf(3L, 4L, 6L), index.marks.map { it.sectionId })
        assertEquals(3L, index.chapterAt(15)?.sectionId)
        assertEquals(4L, index.chapterAt(59)?.sectionId)
        assertEquals(6L, index.chapterAt(80)?.sectionId)
        assertEquals(29, index.chapterAt(15)?.endOrdinal)
        assertEquals(99, index.chapterAt(80)?.endOrdinal)
    }

    @Test fun noSectionsMeansOneChapterForTheWholeBook() {
        val one = SectionIndex(emptyList(), 50, "Essay")
        assertEquals(1, one.marks.size)
        assertEquals("Essay", one.marks[0].title)
        assertEquals(0, one.firstNarrated)
        assertEquals(49, one.lastNarrated)
    }

    @Test fun denseMarksAreThinned() {
        val many = (0 until 400).map { section(it + 1L, it * 10, it * 10 + 9, level = 1) }
        val idx = SectionIndex(many, 4_000, "Dense")
        assertTrue(idx.marks.size <= 100)
    }
}

class SegmentPickerTest {
    @Test fun newestTakeWinsOverlaps() {
        val out = SegmentPicker.active(listOf(seg(1, 0, 9, take = 0), seg(2, 5, 9, take = 1), seg(3, 10, 19)), ava)
        assertEquals(listOf(2L, 3L), out.map { it.id })
    }

    @Test fun regeneratedRangeReplacesTheOldOne() {
        val out = SegmentPicker.active(listOf(seg(1, 0, 9, take = 0), seg(2, 0, 9, take = 1)), ava)
        assertEquals(listOf(2L), out.map { it.id })
    }

    @Test fun usesTheRequestedVoiceOtherwiseTheNewest() {
        val bob = VoiceRef("fish", "bob", "Bob")
        val all = listOf(seg(1, 0, 9, voice = ava), seg(2, 10, 19, voice = bob))
        assertEquals(listOf(1L), SegmentPicker.active(all, ava).map { it.id })
        assertEquals(listOf(2L), SegmentPicker.active(all, null).map { it.id })
        assertEquals(listOf(2L), SegmentPicker.active(all, VoiceRef("fish", "zed", "Zed")).map { it.id })
    }
}

class SleepTimerTest {
    @Test fun idleWhenOff() {
        val t = SleepTimer()
        val r = t.tick(0, null, false)
        assertEquals(1f, r.volume, 0f)
        assertFalse(r.expired)
        assertNull(r.remainingMs)
    }

    @Test fun countdownFadesOverTheLastTenSeconds() {
        val t = SleepTimer()
        t.set(5, 1_000)
        assertEquals(1f, t.tick(1_000 + 60_000, null, false).volume, 0f)
        val fading = t.tick(1_000 + 5 * 60_000 - 5_000, null, false)
        assertEquals(0.5f, fading.volume, 0.01f)
        assertEquals(5_000L, fading.remainingMs)
        val done = t.tick(1_000 + 5 * 60_000, null, false)
        assertTrue(done.expired)
        assertFalse(t.active)
    }

    @Test fun sectionEndUsesTheTimeLeftInTheSection() {
        val t = SleepTimer()
        t.set(-1, 0)
        assertTrue(t.atSectionEnd)
        val mid = t.tick(100, 120_000, false)
        assertEquals(1f, mid.volume, 0f)
        assertEquals(120_000L, mid.remainingMs)
        assertEquals(0.2f, t.tick(200, 2_000, false).volume, 0.01f)
        assertTrue(t.tick(300, 0, false).expired)
    }

    @Test fun sectionEndFallsBackToPassingTheBoundary() {
        val t = SleepTimer()
        t.set(-1, 0)
        assertFalse(t.tick(10, null, false).expired)
        assertTrue(t.tick(20, null, true).expired)
    }

    @Test fun extendAddsTimeToARunningCountdown() {
        val t = SleepTimer()
        t.set(1, 0)
        t.extend(10, 30_000)
        assertFalse(t.tick(30_000 + 9 * 60_000, null, false).expired)
        assertTrue(t.tick(30_000 + 11 * 60_000, null, false).expired)
    }

    @Test fun zeroTurnsItOff() {
        val t = SleepTimer()
        t.set(5, 0)
        t.set(0, 10)
        assertFalse(t.active)
    }
}

class SmartRewindTest {
    @Test fun shortPauseDoesNothing() {
        assertEquals(5_800L, SmartRewind.targetMs(5_800, 4_000, 400))
    }

    @Test fun goesBackToTheStartOfTheSentence() {
        assertEquals(4_000L, SmartRewind.targetMs(5_800, 4_000, 5_000))
    }

    @Test fun longPauseGoesTwoSecondsFurther() {
        assertEquals(2_000L, SmartRewind.targetMs(5_800, 4_000, 60_000))
    }

    @Test fun neverBelowZero() {
        assertEquals(0L, SmartRewind.targetMs(1_000, 500, 120_000))
    }
}

class ConsumeTrackerTest {
    private val next = { p: Int, n: Int -> n == p + 1 }

    @Test fun completedSentencesAreReportedAsOneRun() {
        val t = ConsumeTracker()
        t.onSeek(10)
        for (o in 11..14) t.onOrdinal(o, next)
        assertEquals(listOf(10..13), t.drain())
        assertFalse(t.hasPending())
    }

    @Test fun seekEndsTheRun() {
        val t = ConsumeTracker()
        t.onSeek(0)
        t.onOrdinal(1, next); t.onOrdinal(2, next)
        t.onSeek(50)
        t.onOrdinal(51, next)
        assertEquals(listOf(0..1, 50..50), t.drain())
    }

    @Test fun jumpWithoutSeekIsNotConsumed() {
        val t = ConsumeTracker()
        t.onSeek(0)
        t.onOrdinal(1, next)
        t.onOrdinal(30, next)
        t.onOrdinal(31, next)
        assertEquals(listOf(0..0, 30..30), t.drain())
    }

    @Test fun drainingMidPlayKeepsLaterSentences() {
        val t = ConsumeTracker()
        t.onSeek(0)
        t.onOrdinal(1, next); t.onOrdinal(2, next)
        assertEquals(listOf(0..1), t.drain())
        t.onOrdinal(3, next)
        assertEquals(listOf(2..2), t.drain())
    }

    @Test fun staysEmptyUntilSomethingPlays() {
        val t = ConsumeTracker()
        t.onSeek(5)
        assertTrue(t.drain().isEmpty())
    }
}

class ShakeDetectorTest {
    @Test fun twoSpikesInsideTheWindowAreAShake() {
        val d = ShakeDetector()
        assertFalse(d.onSample(0f, 0f, 9.8f, 0))
        assertFalse(d.onSample(15f, 10f, 5f, 100))
        assertTrue(d.onSample(-16f, 12f, 4f, 400))
    }

    @Test fun slowSpikesAreNot() {
        val d = ShakeDetector()
        assertFalse(d.onSample(20f, 0f, 0f, 0))
        assertFalse(d.onSample(20f, 0f, 0f, 2_000))
    }

    @Test fun bookTimelineEstimate() {
        assertEquals(app.kepler.core.model.ListenPace.DEFAULT_MS_PER_SENTENCE, BookTimeline.msPerSentence(0, 0), 0.0)
        assertEquals(2_500.0, BookTimeline.msPerSentence(25_000, 10), 0.0)
    }
}
