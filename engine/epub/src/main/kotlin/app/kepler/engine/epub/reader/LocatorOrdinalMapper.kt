package app.kepler.engine.epub.reader

import app.kepler.core.model.Sentence

/**
 * A Readium-free view of a text location: what the mapper needs from a Readium `Locator`.
 * [progression] is the position inside the resource (0..1), [totalProgression] inside the book.
 */
data class TextAnchor(
    val href: String,
    val progression: Double? = null,
    val before: String? = null,
    val highlight: String? = null,
    val after: String? = null,
    val totalProgression: Double? = null,
    /** Readium's 1-based position (about one screen of text), when known. Used as a folio. */
    val position: Int? = null,
)

/**
 * Maps text locations to sentence ordinals of the Text Spine and back.
 *
 * Matching is done on a folded form of the text (whitespace collapsed, case and typographic quotes
 * folded one char to one char) so DOM text and ingested text compare equal despite formatting.
 */
class LocatorOrdinalMapper(sentences: List<Sentence>) {

    private class Run(val sentences: List<Sentence>) {
        val key: String
        val starts: IntArray

        init {
            val sb = StringBuilder()
            starts = IntArray(sentences.size)
            sentences.forEachIndexed { i, s ->
                if (i > 0) sb.append(' ')
                starts[i] = sb.length
                sb.append(fold(s.text))
            }
            key = sb.toString()
        }

        fun indexAtOffset(offset: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun indexAtProgression(p: Double): Int =
            indexAtOffset((p.coerceIn(0.0, 1.0) * key.length).toInt().coerceAtMost(key.length - 1).coerceAtLeast(0))
    }

    private val ordered = sentences.sortedBy { it.ordinal }
    private val byOrdinal = ordered.associateBy { it.ordinal }
    private val runs: Map<String, Run> = ordered.groupBy { normalizeHref(it.href) }.mapValues { Run(it.value) }
    private val runOrder: List<Run> = runs.values.sortedBy { it.sentences.first().ordinal }
    private val bookChars: Long = runOrder.sumOf { it.key.length.toLong() }
    private val hrefCache = HashMap<String, Run?>()

    val isEmpty: Boolean get() = ordered.isEmpty()

    fun sentence(ordinal: Int): Sentence? = byOrdinal[ordinal]

    /** First sentence ordinal at [anchor], or null when the anchor's resource has no sentences. */
    fun ordinalAt(anchor: TextAnchor): Int? = rangeAt(anchor)?.first

    /** Sentence ordinals covered by [anchor]'s highlight (a single ordinal without text). */
    fun rangeAt(anchor: TextAnchor): IntRange? {
        val run = runFor(anchor.href) ?: return byTotalProgression(anchor.totalProgression)
        val progIdx = anchor.progression?.let { run.indexAtProgression(it) }
        val hl = anchor.highlight?.let(::fold)?.trim()?.takeIf { it.isNotEmpty() }
        if (hl != null) {
            val progOffset = progIdx?.let { run.starts[it] }
            var at = bestOccurrence(run, hl, anchor.before, anchor.after, progOffset)
            if (at == null && hl.length > PREFIX) {
                // The element may cross markup the ingester split differently: anchor on its start.
                at = bestOccurrence(run, hl.take(PREFIX), anchor.before, null, progOffset)
            }
            if (at != null) {
                val first = run.indexAtOffset(at)
                val last = run.indexAtOffset((at + hl.length - 1).coerceAtMost(run.key.length - 1))
                // A long first-visible element (paragraph) may start on the previous page: the
                // progression estimate is more precise when it falls inside the matched block.
                val start = if (progIdx != null && progIdx in first..last) progIdx else first
                return run.sentences[start].ordinal..run.sentences[last].ordinal
            }
        }
        val idx = progIdx ?: 0
        val o = run.sentences[idx].ordinal
        return o..o
    }

    /** A location pointing at the start of sentence [ordinal]. */
    fun anchorFor(ordinal: Int): TextAnchor? {
        val s = byOrdinal[ordinal] ?: return null
        val run = runs[normalizeHref(s.href)] ?: return null
        val idx = run.sentences.indexOfFirst { it.ordinal == ordinal }
        val progression = if (run.key.isEmpty()) 0.0 else run.starts[idx].toDouble() / run.key.length
        return TextAnchor(
            href = s.href, progression = progression,
            before = s.before.takeLast(CONTEXT), highlight = s.text, after = s.after.take(CONTEXT),
        )
    }

    /**
     * A location for an annotation [quote] that starts in sentence [startOrdinal] and ends in [endOrdinal].
     * The context around the quote is rebuilt from the spine so Readium can find the exact range.
     */
    fun anchorForQuote(startOrdinal: Int, endOrdinal: Int, quote: String): TextAnchor? {
        val s = byOrdinal[startOrdinal] ?: return null
        val href = normalizeHref(s.href)
        val span = (startOrdinal..maxOf(startOrdinal, endOrdinal)).mapNotNull { byOrdinal[it] }
            .filter { normalizeHref(it.href) == href }
        val e = span.last()
        val text = span.joinToString(" ") { collapse(it.text) }
        val q = collapse(quote)
        val i = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
        return if (i >= 0) {
            TextAnchor(
                href = s.href,
                progression = anchorFor(startOrdinal)?.progression,
                before = (collapse(s.before) + " " + text.substring(0, i)).trim().takeLast(CONTEXT),
                highlight = text.substring(i, i + q.length),
                after = (text.substring(i + q.length) + " " + collapse(e.after)).trim().take(CONTEXT),
            )
        } else {
            TextAnchor(
                href = s.href, progression = anchorFor(startOrdinal)?.progression,
                before = s.before.takeLast(CONTEXT), highlight = quote, after = e.after.take(CONTEXT),
            )
        }
    }

    private fun byTotalProgression(total: Double?): IntRange? {
        if (total == null || runOrder.isEmpty() || bookChars == 0L) return null
        var target = (total.coerceIn(0.0, 1.0) * bookChars).toLong()
        for (run in runOrder) {
            if (target < run.key.length) {
                val o = run.sentences[run.indexAtOffset(target.toInt())].ordinal
                return o..o
            }
            target -= run.key.length
        }
        val o = runOrder.last().sentences.last().ordinal
        return o..o
    }

    private fun runFor(href: String): Run? = hrefCache.getOrPut(href) {
        val n = normalizeHref(href)
        runs[n] ?: runs.entries.firstOrNull { (k, _) -> k.endsWith("/$n") || n.endsWith("/$k") }?.value
    }

    private fun bestOccurrence(run: Run, needle: String, before: String?, after: String?, near: Int?): Int? {
        val b = before?.let(::fold)?.trimEnd().orEmpty()
        val a = after?.let(::fold)?.trimStart().orEmpty()
        var best: Int? = null
        var bestScore = -1
        var bestDist = Int.MAX_VALUE
        var from = 0
        var seen = 0
        while (seen < MAX_OCCURRENCES) {
            val at = run.key.indexOf(needle, from)
            if (at < 0) break
            seen++
            val score = commonSuffix(b, run.key.substring(0, at).trimEnd()) +
                commonPrefix(a, run.key.substring(at + needle.length).trimStart())
            val dist = near?.let { kotlin.math.abs(it - at) } ?: 0
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                best = at; bestScore = score; bestDist = dist
            }
            from = at + 1
        }
        return best
    }

    companion object {
        private const val CONTEXT = 64
        private const val PREFIX = 32
        private const val MAX_OCCURRENCES = 200
        private const val CONTEXT_SCORE = 40

        private fun commonSuffix(a: String, b: String): Int {
            var n = 0
            while (n < CONTEXT_SCORE && n < a.length && n < b.length && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
            return n
        }

        private fun commonPrefix(a: String, b: String): Int {
            var n = 0
            while (n < CONTEXT_SCORE && n < a.length && n < b.length && a[n] == b[n]) n++
            return n
        }

        internal fun collapse(s: String): String {
            val sb = StringBuilder(s.length)
            var space = false
            for (c in s) {
                if (c == '­' || c == '​' || c == '﻿') continue
                if (c.isWhitespace()) {
                    space = true
                } else {
                    if (space && sb.isNotEmpty()) sb.append(' ')
                    space = false
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        /** [collapse] plus a one-to-one fold of case, quotes and dashes. */
        internal fun fold(s: String): String {
            val c = collapse(s)
            val sb = StringBuilder(c.length)
            for (ch in c) {
                sb.append(
                    when (ch) {
                        '‘', '’', '‛', '′' -> '\''
                        '“', '”', '‟', '″', '«', '»' -> '"'
                        '–', '—', '−' -> '-'
                        ' ' -> ' '
                        else -> Character.toLowerCase(ch)
                    },
                )
            }
            return sb.toString()
        }

        fun normalizeHref(href: String): String {
            var h = href.substringBefore('#').substringBefore('?')
            while (h.startsWith("./")) h = h.removePrefix("./")
            h = h.trimStart('/')
            return percentDecode(h)
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.write(v); i += 3; continue }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
