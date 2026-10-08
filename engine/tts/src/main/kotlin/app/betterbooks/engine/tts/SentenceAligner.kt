package app.betterbooks.engine.tts

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToLong

data class SentenceSpan(val startMs: Long, val endMs: Long)

data class AlignmentResult(val spans: List<SentenceSpan>, val confidence: Double, val proportional: Boolean)

/**
 * Maps provider word timings (on normalised text) back to source sentences with a token-level
 * Needleman-Wunsch alignment. Below [MIN_CONFIDENCE] matched tokens it falls back to spreading
 * the audio over sentences proportionally to their character counts.
 */
object SentenceAligner {
    const val MIN_CONFIDENCE = 0.8

    private const val MATCH = 2
    private const val NEAR = 1
    private const val MISMATCH = -1
    private const val GAP = -1

    fun align(sentences: List<String>, words: List<WordTiming>?, totalMs: Long): AlignmentResult {
        if (sentences.isEmpty()) return AlignmentResult(emptyList(), 1.0, false)
        if (words.isNullOrEmpty()) return AlignmentResult(proportional(sentences, totalMs), 0.0, true)

        val src = ArrayList<Pair<String, Int>>() // token, sentence index
        sentences.forEachIndexed { i, s -> tokenize(s).forEach { src += it to i } }
        val hyp = ArrayList<Pair<String, WordTiming>>()
        words.forEach { w -> tokenize(w.text).forEach { hyp += it to w } }
        if (src.isEmpty() || hyp.isEmpty()) return AlignmentResult(proportional(sentences, totalMs), 0.0, true)

        val pairs = needlemanWunsch(src.map { it.first }, hyp.map { it.first })
        val confidence = pairs.size.toDouble() / src.size
        if (confidence < MIN_CONFIDENCE) return AlignmentResult(proportional(sentences, totalMs), confidence, true)

        val first = LongArray(sentences.size) { -1 }
        val last = LongArray(sentences.size) { -1 }
        for ((i, j) in pairs) {
            val si = src[i].second
            val w = hyp[j].second
            if (first[si] < 0) first[si] = w.startMs
            last[si] = max(last[si], w.endMs)
        }
        return AlignmentResult(contiguous(sentences, first, last, totalMs), confidence, false)
    }

    /** Builds gap-free spans: a sentence starts where its first word starts and ends where the next one starts. */
    private fun contiguous(sentences: List<String>, first: LongArray, last: LongArray, totalMs: Long): List<SentenceSpan> {
        val n = sentences.size
        val starts = LongArray(n)
        var i = 0
        while (i < n) {
            if (first[i] >= 0) {
                starts[i] = first[i]
                i++
                continue
            }
            // Unmatched run [i, j): share the time between the previous end and the next start by characters.
            var j = i
            while (j < n && first[j] < 0) j++
            val from = if (i == 0) 0L else max(last[i - 1], starts[i - 1])
            val to = if (j < n) first[j] else totalMs
            val chars = (i until j).sumOf { sentences[it].length.coerceAtLeast(1) }.toDouble()
            var acc = 0
            for (k in i until j) {
                starts[k] = from + ((to - from) * (acc / chars)).roundToLong()
                acc += sentences[k].length.coerceAtLeast(1)
            }
            i = j
        }
        starts[0] = 0
        for (k in 1 until n) starts[k] = starts[k].coerceIn(starts[k - 1], totalMs)
        return List(n) { k -> SentenceSpan(starts[k], if (k + 1 < n) starts[k + 1] else totalMs) }
    }

    fun proportional(sentences: List<String>, totalMs: Long): List<SentenceSpan> {
        val weights = sentences.map { it.trim().length.coerceAtLeast(1).toDouble() }
        val sum = weights.sum()
        var acc = 0.0
        return weights.mapIndexed { i, w ->
            val start = (totalMs * acc / sum).roundToLong()
            acc += w
            val end = if (i == weights.lastIndex) totalMs else (totalMs * acc / sum).roundToLong()
            SentenceSpan(start, end)
        }
    }

    /** Case- and punctuation-insensitive, apostrophe-dropping, digit-aware tokens. */
    fun tokenize(text: String): List<String> {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("['’‘`]"), "")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var digits = false
        fun flush() {
            if (sb.isNotEmpty()) out += sb.toString()
            sb.setLength(0)
        }
        for (c in folded) {
            when {
                c.isDigit() -> {
                    if (!digits) flush()
                    digits = true
                    sb.append(c)
                }
                c.isLetter() -> {
                    if (digits) flush()
                    digits = false
                    sb.append(c)
                }
                (c == ',' || c == '.') && digits -> Unit // 1,000 and 3.14 stay one token
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** Returns matched (srcIndex, hypIndex) pairs from a global alignment. */
    internal fun needlemanWunsch(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val score = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) score[i][0] = i * GAP
        for (j in 1..m) score[0][j] = j * GAP
        for (i in 1..n) for (j in 1..m) {
            score[i][j] = maxOf(
                score[i - 1][j - 1] + similarity(a[i - 1], b[j - 1]),
                score[i - 1][j] + GAP,
                score[i][j - 1] + GAP,
            )
        }
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val s = similarity(a[i - 1], b[j - 1])
            when {
                score[i][j] == score[i - 1][j - 1] + s -> {
                    if (s > 0) pairs += (i - 1) to (j - 1)
                    i--; j--
                }
                score[i][j] == score[i - 1][j] + GAP -> i--
                else -> j--
            }
        }
        pairs.reverse()
        return pairs
    }

    private fun similarity(x: String, y: String): Int = when {
        x == y -> MATCH
        x.length >= 4 && y.length >= 4 && (x.startsWith(y) || y.startsWith(x) || editDistanceAtMostOne(x, y)) -> NEAR
        else -> MISMATCH
    }

    private fun editDistanceAtMostOne(x: String, y: String): Boolean {
        if (kotlin.math.abs(x.length - y.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < x.length && j < y.length) {
            if (x[i] == y[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                x.length > y.length -> i++
                x.length < y.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (x.length - i) + (y.length - j) <= 1
    }
}
