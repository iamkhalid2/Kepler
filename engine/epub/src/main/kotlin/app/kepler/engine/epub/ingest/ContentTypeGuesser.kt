package app.kepler.engine.epub.ingest

import app.kepler.core.model.ContentType

internal data class ContentStats(
    val paragraphs: Int, val dialogueParagraphs: Int, val words: Long,
    val structuralBlocks: Int, val totalBlocks: Int, val citationMarkers: Int, val noteBlocks: Int,
)

/** Heuristic fiction / non-fiction guess (no LLM). MIXED when unsure (never silently NONFICTION: that would lift the spoiler fence). */
internal object ContentTypeGuesser {
    private val nonfictionSubject = Regex(
        """non-?fiction|biograph|autobiograph|memoir|history|self-help|business|economic|science|psycholog|philosoph|health|religio|politic|""" +
            """computers|education|reference|essays|sociolog|mathemat|cooking|travel|nature|technology|finance|management|leadership|spiritual|stoic""",
        RegexOption.IGNORE_CASE,
    )
    private val fictionSubject = Regex("""(?<!non-)(?<!non)fiction|novel|stories|fantasy|romance|mystery|thriller|horror|adventure|western|detective|fairy|juvenile|poetry|drama""", RegexOption.IGNORE_CASE)

    fun guess(subjects: List<String>, s: ContentStats): ContentType {
        var fiction = 0
        var non = 0
        val subj = subjects.joinToString(" | ")
        if (subj.isNotEmpty()) {
            if (nonfictionSubject.containsMatchIn(subj)) non += 2
            if (fictionSubject.containsMatchIn(subj.replace(Regex("(?i)non-?fiction"), ""))) fiction += 2
        }
        val dialogue = if (s.paragraphs > 0) s.dialogueParagraphs.toDouble() / s.paragraphs else 0.0
        when {
            dialogue > 0.20 -> fiction += 2
            dialogue > 0.12 -> fiction += 1
            dialogue < 0.05 -> non += 1
        }
        val per1000 = if (s.words > 0) s.citationMarkers * 1000.0 / s.words else 0.0
        if (per1000 >= 1.5) non += 1
        if (s.totalBlocks > 0 && (s.structuralBlocks + s.noteBlocks) * 100.0 / s.totalBlocks > 3.0) non += 1
        return when {
            fiction >= 2 && non == 0 -> ContentType.FICTION
            fiction >= 1 && non >= 1 && fiction + non >= 4 -> ContentType.MIXED
            fiction >= 3 && non <= 1 -> ContentType.FICTION
            non >= 2 && fiction == 0 -> ContentType.NONFICTION
            else -> ContentType.MIXED
        }
    }
}
