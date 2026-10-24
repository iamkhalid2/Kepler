package app.kepler.feature.study

import app.kepler.core.model.Card
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How long ago something happened, in the units the notebook shows ("today", "3 days ago"). */
enum class AgeUnit { TODAY, YESTERDAY, DAYS, WEEKS, MONTHS, YEARS }

data class Age(val unit: AgeUnit, val value: Int)

/** Calendar-day based, so a highlight made at 23:50 reads "yesterday" a few minutes later. */
fun ageOf(thenMs: Long, nowMs: Long, zone: ZoneId): Age {
    val then = Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate()
    val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then, now).coerceAtLeast(0).toInt()
    return when {
        days == 0 -> Age(AgeUnit.TODAY, 0)
        days == 1 -> Age(AgeUnit.YESTERDAY, 1)
        days < 14 -> Age(AgeUnit.DAYS, days)
        days < 60 -> Age(AgeUnit.WEEKS, days / 7)
        days < 730 -> Age(AgeUnit.MONTHS, days / 30)
        else -> Age(AgeUnit.YEARS, days / 365)
    }
}

/** Case-insensitive match over a highlight's quote, note and chapter title; a blank query keeps everything. */
fun filterHighlights(groups: List<BookHighlights>, query: String): List<BookHighlights> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return groups
    return groups.mapNotNull { g ->
        val bookHit = g.book.title.lowercase().contains(q) || g.book.authors.lowercase().contains(q)
        val sections = g.sections.mapNotNull { sh ->
            val chapterHit = sh.section?.title?.lowercase()?.contains(q) == true
            val items = if (bookHit || chapterHit) sh.items
            else sh.items.filter { it.quote.lowercase().contains(q) || it.note.orEmpty().lowercase().contains(q) }
            if (items.isEmpty()) null else sh.copy(items = items)
        }
        if (sections.isEmpty()) null else g.copy(sections = sections)
    }
}

fun filterCards(groups: List<BookCards>, query: String): List<BookCards> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return groups
    return groups.mapNotNull { g ->
        val bookHit = g.book.title.lowercase().contains(q)
        val cards = if (bookHit) g.cards else g.cards.filter { it.front.lowercase().contains(q) || it.back.lowercase().contains(q) }
        if (cards.isEmpty()) null else g.copy(cards = cards)
    }
}

/** When a card is next due, as an interval from now; null for a card due now or overdue. */
fun dueIn(card: Card, nowMs: Long): Interval? = (card.dueAt - nowMs).takeIf { it > 60_000L }?.let(::formatInterval)

/** Shares of new / learning / mature cards for the maturity bar; all zero for an empty deck. */
fun maturityShares(stats: ReviewStats): Triple<Float, Float, Float> {
    val total = stats.cards
    if (total == 0) return Triple(0f, 0f, 0f)
    return Triple(stats.newCards / total.toFloat(), stats.learning / total.toFloat(), stats.mature / total.toFloat())
}

fun highlightCount(groups: List<BookHighlights>): Int = groups.sumOf { it.count }
