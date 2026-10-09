package app.kepler.engine.ai

import app.kepler.core.model.Citation

/** `[123]`, `[s123]`, `[123, 124]` and `[123-125]`: the ways models write passage markers. */
internal val CITATION = Regex("\\[\\s*s?(\\d+(?:\\s*[,;\\-–]\\s*s?\\d+)*)\\s*]")

/**
 * Drops `<think>...</think>` blocks from a stream of chunks, even when a tag is split across chunks.
 * Reasoning normally arrives in separate delta fields, but some models inline it.
 */
internal class ThinkStripper {
    private var inThink = false
    private val hold = StringBuilder()

    fun feed(chunk: String): String {
        hold.append(chunk)
        val out = StringBuilder()
        while (true) {
            if (inThink) {
                val i = hold.indexOf(CLOSE)
                if (i < 0) {
                    if (hold.length > CLOSE.length) hold.delete(0, hold.length - (CLOSE.length - 1))
                    break
                }
                hold.delete(0, i + CLOSE.length); inThink = false
            } else {
                val i = hold.indexOf(OPEN)
                if (i >= 0) { out.append(hold, 0, i); hold.delete(0, i + OPEN.length); inThink = true; continue }
                val keep = partialSuffix(hold, OPEN)
                out.append(hold, 0, hold.length - keep)
                hold.delete(0, hold.length - keep)
                break
            }
        }
        return out.toString()
    }

    fun finish(): String = if (inThink) "" else hold.toString().also { hold.clear() }

    private fun partialSuffix(s: CharSequence, tag: String): Int {
        for (k in minOf(tag.length - 1, s.length) downTo 1) {
            if (s.endsWith(tag.substring(0, k))) return k
        }
        return 0
    }

    private fun CharSequence.endsWith(suffix: String) =
        length >= suffix.length && subSequence(length - suffix.length, length).toString() == suffix

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

/**
 * Resolves `[n]` passage markers while text streams in. A marker may be split across chunks, so text from an
 * opening `[` is held back until it closes or is clearly not a marker. Markers are normalised to `[n]`, kept only
 * for ordinals that were really in the prompt (and inside the fence), and dropped otherwise.
 */
internal class CitationStream(
    private val allowed: Set<Int>,
    private val max: Int,
    private val label: (Int) -> String,
) {
    private val pending = StringBuilder()
    private val cited = LinkedHashSet<Int>()
    private var lastChar = ' '
    private var dropNextSpace = false
    private var changed = false

    val citations: List<Citation> get() = cited.map { Citation(it, label(it)) }

    /** True once after a marker added a new citation. */
    fun takeChanged(): Boolean = changed.also { changed = false }

    fun feed(chunk: String): String {
        val out = StringBuilder()
        fun put(c: Char) {
            if (dropNextSpace) { dropNextSpace = false; if (c == ' ') return }
            out.append(c); lastChar = c
        }
        for (ch in chunk) {
            if (pending.isEmpty()) {
                if (ch == '[') pending.append(ch) else put(ch)
                continue
            }
            if (ch == '[') { // the held text was not a marker; this may start a real one
                pending.forEach { put(it) }; pending.clear(); pending.append(ch); continue
            }
            pending.append(ch)
            when {
                ch == ']' -> {
                    val text = pending.toString(); pending.clear()
                    val resolved = resolve(text)
                    if (resolved == null) text.forEach { put(it) }
                    else if (resolved.isEmpty()) { if (lastChar == ' ') dropNextSpace = true }
                    else resolved.forEach { put(it) }
                }
                pending.length > MAX_MARKER || ch !in MARKER_CHARS && !ch.isDigit() -> {
                    pending.forEach { put(it) }; pending.clear()
                }
            }
        }
        return out.toString()
    }

    /** Whatever is still held back (an unclosed `[`), as plain text. */
    fun finish(): String = pending.toString().also { pending.clear() }

    /** Null = not a marker (keep literally); empty = a marker with no valid ordinal (drop). */
    private fun resolve(text: String): String? {
        val m = CITATION.matchEntire(text) ?: return null
        val nums = Regex("\\d+").findAll(m.groupValues[1]).mapNotNull { it.value.toIntOrNull() }.toList()
        val ok = nums.filter { it in allowed && it <= max }
        for (o in ok) if (cited.add(o)) changed = true
        return ok.joinToString("") { "[$it]" }
    }

    private companion object {
        const val MAX_MARKER = 28
        const val MARKER_CHARS = " ,;-–s"
    }
}
