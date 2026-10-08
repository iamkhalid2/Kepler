package app.betterbooks.core.spine

import app.betterbooks.core.model.Sentence

/** What a reader needs to build a text locator for one sentence. */
data class LocatorHint(val href: String, val highlight: String, val before: String, val after: String)

/**
 * Maps between sentence ordinals and (href, text snippet) pairs such as Readium text locators or
 * user selections. Matching is whitespace-insensitive and uses before/after context to disambiguate
 * repeated text.
 */
class TextLocatorMapper(sentences: List<Sentence>) {
    private class Stream(val text: String, val starts: IntArray, val ordinals: IntArray)

    private val byOrdinal: Map<Int, Sentence> = sentences.associateBy { it.ordinal }
    private val streams: Map<String, Stream>

    init {
        val groups = sentences.groupBy { hrefKey(it.href) }
        streams = groups.mapValues { (_, list) ->
            val sorted = list.sortedBy { it.ordinal }
            val sb = StringBuilder()
            val starts = IntArray(sorted.size)
            val ords = IntArray(sorted.size)
            sorted.forEachIndexed { i, s ->
                if (i > 0) sb.append(' ')
                starts[i] = sb.length
                ords[i] = s.ordinal
                sb.append(normalize(s.text))
            }
            Stream(sb.toString(), starts, ords)
        }
    }

    fun locatorOf(ordinal: Int): LocatorHint? =
        byOrdinal[ordinal]?.let { LocatorHint(it.href, it.text, it.before, it.after) }

    /** Ordinal of the first sentence the snippet touches, or null if it is not found in [href]. */
    fun ordinalFor(href: String, snippet: String, before: String? = null, after: String? = null): Int? =
        rangeFor(href, snippet, before, after)?.first

    /** Inclusive ordinal range covered by the snippet (a selection may span several sentences). */
    fun rangeFor(href: String, snippet: String, before: String? = null, after: String? = null): IntRange? {
        val stream = streams[hrefKey(href)] ?: return null
        val needle = normalize(snippet)
        if (needle.isEmpty()) return null
        val ctxBefore = before?.let(::normalize).orEmpty()
        val ctxAfter = after?.let(::normalize).orEmpty()
        var best = -1
        var bestScore = -1
        var from = 0
        var seen = 0
        while (seen < 64) {
            val idx = stream.text.indexOf(needle, from)
            if (idx < 0) break
            val score = commonSuffix(stream.text, idx, ctxBefore) + commonPrefix(stream.text, idx + needle.length, ctxAfter)
            if (score > bestScore) { best = idx; bestScore = score }
            from = idx + 1
            seen++
        }
        if (best < 0) return null
        val first = ordinalAt(stream, best)
        val last = ordinalAt(stream, best + needle.length - 1)
        return first..maxOf(first, last)
    }

    private fun ordinalAt(stream: Stream, offset: Int): Int {
        var lo = 0
        var hi = stream.starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (stream.starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return stream.ordinals[lo]
    }

    private fun commonSuffix(text: String, end: Int, ctx: String): Int {
        var e = end
        if (e > 0 && text[e - 1] == ' ') e--
        var n = 0
        while (n < ctx.length && e - 1 - n >= 0 && text[e - 1 - n] == ctx[ctx.length - 1 - n]) n++
        return n
    }

    private fun commonPrefix(text: String, start: Int, ctx: String): Int {
        val st = if (start < text.length && text[start] == ' ') start + 1 else start
        var n = 0
        while (n < ctx.length && st + n < text.length && text[st + n] == ctx[n]) n++
        return n
    }

    companion object {
        private val ws = Regex("""\s+""")
        fun normalize(s: String): String = ws.replace(s, " ").trim()
        private fun hrefKey(href: String) = href.substringBefore('#').trimStart('/')
    }
}
