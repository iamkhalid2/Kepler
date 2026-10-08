package app.betterbooks.engine.epub.ingest

import app.betterbooks.core.model.SectionKind

internal data class Classification(val kind: SectionKind, val confident: Boolean, val ambiguous: Boolean = false)

/** A landmark resolved to a global block position in the linear reading stream. */
internal data class LandmarkPos(val type: String, val pos: Int)

/** Pure classification rules, in priority order: epub:type, specific landmarks, title, region landmarks, density. */
internal object SectionClassifier {
    private val bodyTypes = setOf("chapter", "part", "volume", "division", "subchapter", "introduction", "preface", "foreword", "prologue", "epilogue", "conclusion")
    private val notesTypes = setOf("footnote", "footnotes", "endnote", "endnotes", "rearnote", "rearnotes", "notes")
    private val frontTypes = setOf("cover", "titlepage", "halftitle", "title", "fulltitle", "copyright-page", "toc", "loi", "lot", "lov", "dedication", "imprint", "pagelist", "contributors", "other-credits")
    private val backTypes = setOf("appendix", "bibliography", "glossary", "colophon", "afterword", "acknowledgments", "errata", "credits")

    fun fromTypes(types: Set<String>): Classification? {
        if (types.isEmpty()) return null
        if (types.any { it in notesTypes }) return Classification(SectionKind.NOTES, true)
        if ("index" in types) return Classification(SectionKind.INDEX, true)
        if (types.any { it in bodyTypes }) return Classification(SectionKind.BODY, true)
        if (types.any { it in frontTypes }) return Classification(SectionKind.FRONT, true)
        if (types.any { it in backTypes }) return Classification(SectionKind.BACK, true)
        if ("bodymatter" in types) return Classification(SectionKind.BODY, true)
        if ("backmatter" in types) return Classification(SectionKind.BACK, true)
        if ("frontmatter" in types) return Classification(SectionKind.FRONT, true)
        return null
    }

    /** Specific landmarks (toc, index, ...) located inside [start, end). */
    fun fromLandmarks(landmarks: List<LandmarkPos>, start: Int, end: Int): Classification? {
        for (lm in landmarks) {
            if (lm.pos < start || (lm.pos >= end && !(start == end && lm.pos == start))) continue
            fromTypes(setOf(lm.type))?.takeIf { lm.type !in setOf("bodymatter", "backmatter", "frontmatter", "preface", "foreword", "introduction") }?.let { return it }
        }
        return null
    }

    /** bodymatter/text marker splits front from body; backmatter marker starts the back. */
    fun fromRegion(landmarks: List<LandmarkPos>, start: Int): Classification? {
        val body = landmarks.filter { it.type == "bodymatter" }.minOfOrNull { it.pos }
        val back = landmarks.filter { it.type == "backmatter" }.minOfOrNull { it.pos }
        if (back != null && start >= back) return Classification(SectionKind.BACK, true)
        if (body != null) return Classification(if (start < body) SectionKind.FRONT else SectionKind.BODY, true)
        return null
    }

    private fun norm(title: String): String =
        title.lowercase().replace('’', '\'').replace(Regex("^[\\s\\d.:–—-]+"), "").replace(Regex("[\\s.:!–—-]+$"), "")
            .removePrefix("the ").trim()

    private val notesRe = Regex("""^(end ?notes?|foot ?notes?|notes?|chapter notes|notes (and|&) (references|sources|bibliography)|notes (to|on) the (text|chapters)|notes and further reading)$""")
    private val indexRe = Regex("""^((subject|name|general|author|title|scripture)( and (name|subject|author|title))? )?index(es)?( of .*)?$""")
    private val frontRe = Regex(
        """^(cover( page| image)?|title( page)?|half[- ]?title( page)?|(table of |detailed |brief |full )?contents|front ?matter|frontispiece|epigraph|dedication|""" +
            """series( page| title)?|list of (illustrations|figures|tables|maps|plates|abbreviations|contributors)|abbreviations|maps?|""" +
            """(a )?note (on|about) the (text|translation|edition|translator)|publisher'?s? note|before you begin|how to use this book)$""",
    )
    private val ambiguousRe = Regex(
        """^(copyright( page| notice| information)?|copyright (and|&) .*|praise( for .*)?|also by .*|other books by .*|books by .*|about (this|the) (book|ebook)|about the publisher|credits|permissions?|newsletter|discover more.*|sign up.*)$""",
    )
    private val backRe = Regex(
        """^(acknowledg(e)?ments?|about the (author|authors|translator|illustrator)s?|(author'?s? )?bio(graphy)?|(selected |select |annotated )?bibliography|references|works cited|sources|""" +
            """(for )?(further|suggested|recommended) reading|resources|glossary|glossary of .*|appendix.*|appendices|colophon|afterword|back ?matter|end ?matter|""" +
            """(a )?note on sources|reading group guide|discussion questions|questions for discussion|excerpt from .*)$""",
    )

    fun fromTitle(title: String): Classification? {
        val t = norm(title)
        if (t.isEmpty()) return null
        return when {
            notesRe.matches(t) -> Classification(SectionKind.NOTES, true)
            indexRe.matches(t) -> Classification(SectionKind.INDEX, true)
            frontRe.matches(t) -> Classification(SectionKind.FRONT, true)
            ambiguousRe.matches(t) -> Classification(SectionKind.FRONT, true, ambiguous = true)
            backRe.matches(t) -> Classification(SectionKind.BACK, true)
            else -> null
        }
    }

    private val trailingNum = Regex("""[\s,]\d+([\-–,\s]\d+)*[a-z]?\s*$""")
    private val yearRe = Regex("""\b(1[5-9]|20)\d{2}\b""")

    /** Density heuristics for unclassified sections near the end of the book. */
    fun fromDensity(blocks: List<Block>): Classification? {
        if (blocks.size < 30) return null
        val short = blocks.count { it.text.length <= 70 && trailingNum.containsMatchIn(it.text) }
        if (short * 100 / blocks.size >= 60) return Classification(SectionKind.INDEX, true)
        val cites = blocks.count { yearRe.containsMatchIn(it.text) && (it.text.contains("http") || it.text.contains("doi", true) || it.text.contains("pp.") || it.text.contains("(")) }
        if (cites * 100 / blocks.size >= 55) return Classification(SectionKind.BACK, true)
        return null
    }
}
