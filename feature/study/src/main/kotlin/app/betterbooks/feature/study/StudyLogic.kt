package app.betterbooks.feature.study

import app.betterbooks.core.model.Card
import app.betterbooks.core.model.CardType
import app.betterbooks.core.model.ContentType
import app.betterbooks.core.model.Section
import app.betterbooks.core.model.SectionKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Pure rules shared by the view models. Everything here is JVM-testable. */
object Study {
    const val DAILY_NEW_CARD_CAP = 10
    const val FREEZES_PER_WEEK = 2
    const val DEFAULT_WEEKLY_GOAL = 5
    /** A card graded Again that comes due within this window is shown again in the same session. */
    const val SAME_SESSION_MS = 20 * 60_000L

    /** Non-fiction and mixed books use Study mode (predict before explain, check-ins). Fiction uses Light. */
    fun isStudyMode(type: ContentType?): Boolean = type != ContentType.FICTION

    fun dayKey(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    fun newCardsToday(cards: List<Card>, nowMs: Long, zone: ZoneId): Int {
        val today = dayKey(nowMs, zone)
        return cards.count { dayKey(it.createdAt, zone) == today }
    }

    fun newCardsLeft(cards: List<Card>, nowMs: Long, zone: ZoneId): Int =
        (DAILY_NEW_CARD_CAP - newCardsToday(cards, nowMs, zone)).coerceAtLeast(0)

    /** A card that has never been reviewed; due immediately. The scheduler treats `state == 0` as new. */
    fun newCard(
        bookId: Long, type: CardType, sourceStart: Int, sourceEnd: Int,
        front: String, back: String, userAnswer: String?, nowMs: Long,
    ) = Card(
        id = 0, bookId = bookId, type = type, sourceStart = sourceStart, sourceEnd = sourceEnd,
        front = front.trim(), back = back.trim(), userAnswer = userAnswer?.trim()?.ifEmpty { null },
        dueAt = nowMs, stability = 0.0, difficulty = 0.0, reps = 0, lapses = 0, state = 0,
        lastReviewAt = null, createdAt = nowMs,
    )

    /** A nudge before the answer: the first word, or the first letter plus blanks for a one-word answer. */
    fun hintFor(back: String): String {
        val words = back.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> ""
            words.size == 1 -> {
                val w = words[0]
                w.first() + " " + "_ ".repeat((w.length - 1).coerceIn(1, 12)).trim()
            }
            else -> words[0] + " …"
        }
    }

    /** True when an again-graded card should come back before the session ends. */
    fun repeatsThisSession(dueAt: Long, nowMs: Long) = dueAt - nowMs < SAME_SESSION_MS
}

enum class IntervalUnit { MINUTES, HOURS, DAYS, MONTHS, YEARS }
data class Interval(val value: Int, val unit: IntervalUnit)

fun formatInterval(ms: Long): Interval {
    val min = ms / 60_000
    val hours = ms / 3_600_000
    val days = ms / 86_400_000
    return when {
        min < 1 -> Interval(1, IntervalUnit.MINUTES)
        min < 60 -> Interval(min.toInt(), IntervalUnit.MINUTES)
        hours < 24 -> Interval(hours.toInt(), IntervalUnit.HOURS)
        days < 30 -> Interval(days.toInt(), IntervalUnit.DAYS)
        days < 365 -> Interval((days / 30).toInt(), IntervalUnit.MONTHS)
        else -> Interval((days / 365).toInt(), IntervalUnit.YEARS)
    }
}

/** One of the seven week squares. */
data class WeekDay(val date: LocalDate, val reviewed: Boolean, val isToday: Boolean, val isFuture: Boolean)

data class WeekProgress(
    val days: List<WeekDay>,
    val goal: Int,
    val reviewedDays: Int,
    /** Missed past days forgiven so far (at most [Study.FREEZES_PER_WEEK]). */
    val freezesUsed: Int,
) {
    val freezesLeft get() = Study.FREEZES_PER_WEEK - freezesUsed
    val goalMet get() = reviewedDays + freezesUsed >= goal
}

fun weekProgress(today: LocalDate, reviewDays: Set<String>, goal: Int): WeekProgress {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val days = (0..6).map { i ->
        val d = monday.plusDays(i.toLong())
        WeekDay(d, d.toString() in reviewDays, d == today, d.isAfter(today))
    }
    val reviewed = days.count { it.reviewed }
    val missedPast = days.count { !it.reviewed && it.date.isBefore(today) }
    return WeekProgress(days, goal.coerceIn(1, 7), reviewed, missedPast.coerceAtMost(Study.FREEZES_PER_WEEK))
}

/** The spoiler-fence indicator shown in Ask. */
data class FenceInfo(val wholeBook: Boolean, val sectionTitle: String?, val sectionIndex: Int?)

fun fenceInfo(type: ContentType?, sections: List<Section>, frontier: Int): FenceInfo {
    if (type == ContentType.NONFICTION) return FenceInfo(true, null, null)
    val body = sections.filter { it.included && it.kind == SectionKind.BODY }
    val at = body.lastOrNull { it.startOrdinal <= frontier } ?: return FenceInfo(false, null, null)
    return FenceInfo(false, at.title, body.indexOf(at) + 1)
}

/** Sections the reader has reached (start at or before the fence), in book order. */
fun reachedSections(sections: List<Section>, frontier: Int): List<Section> =
    sections.filter { it.included && it.kind == SectionKind.BODY && it.startOrdinal <= frontier }

fun sectionFor(sections: List<Section>, ordinal: Int): Section? =
    sections.filter { it.kind != SectionKind.NOTES }.lastOrNull { it.startOrdinal <= ordinal && ordinal <= it.endOrdinal }
        ?: sections.lastOrNull { it.startOrdinal <= ordinal }

/** Back-of-book index grouping: first letter of the label, `#` for anything else. */
fun indexLetter(label: String): Char {
    val c = label.trim().firstOrNull()?.uppercaseChar() ?: return '#'
    return if (c.isLetter()) c else '#'
}
