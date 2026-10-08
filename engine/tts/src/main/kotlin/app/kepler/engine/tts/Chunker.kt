package app.kepler.engine.tts

import app.kepler.core.model.Sentence

/** One synthesis request: consecutive sentences of a single paragraph. */
data class Chunk(val sentences: List<Sentence>) {
    init {
        require(sentences.isNotEmpty())
    }

    val startOrdinal: Int get() = sentences.first().ordinal
    val endOrdinal: Int get() = sentences.last().ordinal
    val texts: List<String> get() = sentences.map { it.text.trim() }
    val text: String get() = texts.joinToString(" ")
}

object Chunker {
    const val MAX_CHARS = 1200

    /**
     * Groups [sentences] (ordered by ordinal) into per-paragraph chunks, skipping sentences that
     * [covered] says already have audio. A paragraph longer than [maxChars] is split at sentence
     * boundaries. When [microFirst] > 0 the very first chunk is cut to that many sentences so the
     * first audio arrives quickly.
     */
    fun plan(
        sentences: List<Sentence>,
        covered: (Int) -> Boolean = { false },
        maxChars: Int = MAX_CHARS,
        microFirst: Int = 0,
    ): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        var run = ArrayList<Sentence>()
        var runChars = 0
        fun flush() {
            if (run.isNotEmpty()) chunks += Chunk(run)
            run = ArrayList()
            runChars = 0
        }
        var prev: Sentence? = null
        for (s in sentences) {
            if (s.text.isBlank()) continue
            val p = prev
            val contiguous = p != null && s.ordinal == p.ordinal + 1 && s.paragraphIndex == p.paragraphIndex &&
                s.sectionId == p.sectionId
            if (!contiguous) flush()
            prev = s
            if (covered(s.ordinal)) {
                flush()
                prev = null
                continue
            }
            val len = s.text.trim().length + if (run.isEmpty()) 0 else 1
            if (run.isNotEmpty() && runChars + len > maxChars) flush()
            run += s
            runChars += len
            if (microFirst > 0 && chunks.isEmpty() && run.size >= microFirst) flush()
        }
        flush()
        return chunks
    }
}
