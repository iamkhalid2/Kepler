package app.kepler.core.spine

import java.text.BreakIterator
import java.util.Locale

/**
 * Pure-JVM sentence splitter: [BreakIterator] plus a guard that re-joins splits after abbreviations,
 * initials and dotted acronyms. Longer-than-needed sentences are preferred over broken ones.
 */
object SentenceSegmenter {
    /** Sentences longer than this are split at the best clause boundary (TTS requests stay small). */
    const val MAX_SENTENCE_CHARS = 700

    /** Abbreviations that never end a sentence (the next segment is always glued on). */
    private val alwaysAbbrev = setOf(
        "mr", "mrs", "ms", "mx", "dr", "prof", "sr", "jr", "st", "mt", "ft", "vs", "cf", "viz", "fig", "figs", "eq", "eqs",
        "vol", "vols", "pp", "ch", "sec", "gen", "col", "lt", "capt", "cpt", "sgt", "cmdr", "adm", "gov", "hon", "rev",
        "messrs", "mme", "mlle", "mmes", "esq", "approx", "dept", "est", "inc", "ltd", "co", "corp", "ed", "eds", "trans",
        "e.g", "i.e", "a.m", "p.m", "ca", "al", "op", "cit", "ibid", "ie", "eg", "pt", "ex", "ff", "supt", "pres", "rep",
        "sen", "rte", "ave", "blvd", "rd", "bros", "ph.d", "m.d", "b.a", "m.a", "d.c", "u.s", "u.k", "u.n", "no",
    )

    /** Ambiguous abbreviations: glued on only when the next segment starts with a digit or lowercase letter. */
    private val softAbbrev = setOf(
        "etc", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "mon", "tue", "wed", "thu", "fri", "sat", "sun",
    )

    /** Words that plausibly begin a new sentence after a dotted acronym ("... the U.S. The ..."). */
    private val sentenceStarters = setOf(
        "the", "he", "she", "it", "they", "we", "i", "in", "but", "and", "this", "that", "there", "his", "her", "as",
        "when", "if", "then", "so", "after", "before", "at", "on", "for", "with", "what", "who", "how", "why", "these",
        "those", "you", "my", "our", "their", "a", "an",
    )

    private val acronymRe = Regex("""^(?:\p{L}\.){2,}$""")
    private val singleInitialRe = Regex("""^\p{Lu}\.$""")
    private val trailingTokenRe = Regex("""([\p{L}\p{N}.'’-]+)[)"”’'\]]*$""")

    fun segment(text: String, locale: Locale = Locale.ROOT): List<String> {
        val src = text.trim()
        if (src.isEmpty()) return emptyList()
        val bi = BreakIterator.getSentenceInstance(locale)
        bi.setText(src)
        val raw = ArrayList<String>()
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val s = src.substring(start, end).trim()
            if (s.isNotEmpty()) raw.add(s)
            start = end
            end = bi.next()
        }
        val merged = ArrayList<String>(raw.size)
        for (s in raw) {
            if (merged.isNotEmpty() && shouldGlue(merged.last(), s)) {
                merged[merged.lastIndex] = merged.last() + " " + s
            } else {
                merged.add(s)
            }
        }
        val out = ArrayList<String>(merged.size)
        for (s in merged) splitLong(s, out)
        return out
    }

    private fun shouldGlue(prev: String, next: String): Boolean {
        if (!prev.endsWith(".") && !prev.endsWith(".\"") && !prev.endsWith(".”") && !prev.endsWith(".)")) return false
        val token = trailingTokenRe.find(prev)?.groupValues?.get(1) ?: return false
        if (!token.endsWith(".")) return false
        val firstChar = next.firstOrNull { it.isLetterOrDigit() } ?: return false
        val nextStartsLowerOrDigit = firstChar.isLowerCase() || firstChar.isDigit()
        val bare = token.dropLast(1).lowercase()
        if (bare in alwaysAbbrev) return true
        if (bare in softAbbrev) return nextStartsLowerOrDigit
        if (token.length == 2 && token[0].isLowerCase()) return nextStartsLowerOrDigit
        if (singleInitialRe.matches(token)) {
            // "I." and "A." are real words; other initials glue onto a following capitalised word or initial.
            return token != "I." && token != "A." && firstChar.isUpperCase()
        }
        if (acronymRe.matches(token)) {
            if (nextStartsLowerOrDigit) return true
            val nextWord = next.takeWhile { !it.isWhitespace() }.trim('"', '“', '‘', '\'', '(').lowercase()
            return nextWord !in sentenceStarters
        }
        return false
    }

    private fun splitLong(s: String, out: MutableList<String>) {
        var rest = s
        while (rest.length > MAX_SENTENCE_CHARS) {
            val window = rest.substring(0, MAX_SENTENCE_CHARS)
            var cut = maxOf(window.lastIndexOf("; "), window.lastIndexOf(": "), window.lastIndexOf(", "))
            if (cut < MAX_SENTENCE_CHARS / 3) cut = window.lastIndexOf(' ')
            if (cut < MAX_SENTENCE_CHARS / 3) cut = MAX_SENTENCE_CHARS - 1
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
    }
}
