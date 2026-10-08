package app.betterbooks.engine.epub.ingest

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal enum class BlockKind(val singleSentence: Boolean) {
    PARAGRAPH(false), QUOTE(false), HEADING(true), LIST_ITEM(true), VERSE(true), TABLE(true), FIGURE(true), CODE(true), MATH(true),
}

/** One text block of a spine document. [types] are the structural epub:type tokens that enclose it. */
internal class Block(
    val kind: BlockKind,
    val text: String,
    val headingLevel: Int,
    val types: Set<String>,
    val note: Boolean,
    val dialogue: Boolean,
)

internal class ParsedDoc(
    val title: String?,
    val blocks: List<Block>,
    /** element id -> index of the first block that starts at or after that element */
    val anchors: Map<String, Int>,
    val citationMarkers: Int,
)

internal object XhtmlBlocks {
    private val skipTags = setOf("script", "style", "head", "title", "template", "rt", "rp", "svg", "noscript", "audio", "video", "object", "canvas", "iframe", "map")
    private val blockContainers = setOf(
        "div", "section", "article", "aside", "header", "footer", "main", "body", "html", "ul", "ol", "dl", "details", "summary",
        "fieldset", "form", "address", "center", "hgroup", "tbody", "thead", "tfoot", "tr", "td", "th", "nav", "dd", "dt", "li",
        "p", "blockquote", "figure", "pre", "table", "math", "h1", "h2", "h3", "h4", "h5", "h6", "caption", "figcaption",
    )
    private val noteTypes = setOf("footnote", "endnote", "rearnote", "footnotes", "endnotes", "rearnotes", "notes")
    private val skipNavTypes = setOf("toc", "landmarks", "page-list", "lot", "loi", "lov")
    private val structTags = setOf("body", "section", "article", "div", "aside", "nav", "blockquote")
    private val verseClass = Regex("""(?i)(^|[\s_-])(poem|poetry|verse|stanza|lg|linegroup)([\s_-]|\d|$)""")
    private const val QUOTE_START = "\"“‘'«„‹—–―"
    private val wsRe = Regex("[\\s\\u00A0\\u2007\\u202F]+")
    private val noteLabel = Regex("""^\[?\(?[\d*†‡§a-z]{1,3}\)?]?$""")
    private const val MAX_DEPTH = 160

    private data class Ctx(
        val verse: Boolean, val quote: Boolean, val listItem: Boolean, val note: Boolean,
        val types: Set<String>, val structDepth: Int,
    )

    fun extract(root: org.jsoup.nodes.Document): ParsedDoc = Walker().run(root)

    private fun typesOf(el: Element): Set<String> =
        (OpfPackage.tokens(el.attr("epub:type")) + OpfPackage.tokens(el.attr("role"))).map { it.removePrefix("doc-") }.toSet()

    private fun clean(s: String) = s.replace("­", "").replace("​", "").replace("﻿", "")

    private class Walker {
        val blocks = ArrayList<Block>()
        val anchors = HashMap<String, Int>()
        val buf = StringBuilder()
        var citations = 0

        fun run(doc: org.jsoup.nodes.Document): ParsedDoc {
            val all = doc.allElements
            val title = all.firstOrNull { it.local() == "title" }?.text()?.trim()?.ifEmpty { null }
            val body = all.firstOrNull { it.local() == "body" }
            if (body != null) visit(body, Ctx(false, false, false, false, emptySet(), 0), 0)
            return ParsedDoc(title, blocks, anchors, citations)
        }

        private fun appendText(raw: String) {
            if (raw.isEmpty()) return
            val s = wsRe.replace(clean(raw), " ")
            if (s.isEmpty()) return
            if (buf.isNotEmpty() && buf.last() == ' ' && s[0] == ' ') buf.append(s, 1, s.length) else buf.append(s)
        }

        private fun register(el: Element) {
            val id = el.attr("id")
            if (id.isNotEmpty()) anchors.putIfAbsent(id, blocks.size)
            if (el.local() == "a") el.attr("name").takeIf { it.isNotEmpty() }?.let { anchors.putIfAbsent(it, blocks.size) }
        }

        private fun flush(ctx: Ctx) {
            if (buf.isEmpty()) return
            val text = buf.toString().trim()
            buf.setLength(0)
            val kind = when {
                ctx.verse -> BlockKind.VERSE
                ctx.listItem -> BlockKind.LIST_ITEM
                ctx.quote -> BlockKind.QUOTE
                else -> BlockKind.PARAGRAPH
            }
            emit(kind, text, 0, ctx)
        }

        private fun emit(kind: BlockKind, text: String, level: Int, ctx: Ctx) {
            val t = wsRe.replace(text, " ").trim()
            if (t.none { it.isLetterOrDigit() }) return
            blocks.add(Block(kind, t, level, ctx.types, ctx.note, t[0] in QUOTE_START))
        }

        private fun isHidden(el: Element): Boolean {
            if (el.hasAttr("hidden")) return true
            val style = el.attr("style")
            if (style.isNotEmpty()) {
                val st = style.replace(" ", "").lowercase()
                if (st.contains("display:none") || st.contains("visibility:hidden")) return true
            }
            return false
        }

        private fun isNoteRef(el: Element, types: Set<String>): Boolean {
            if ("noteref" in types) return true
            if (el.local() == "a") {
                val href = el.attr("href")
                val sup = el.parent()?.local() == "sup" || el.children().any { it.local() == "sup" }
                if (sup && href.contains('#') && noteLabel.matches(el.text().trim())) return true
            }
            return false
        }

        /** Inline text of [el] into [sb]; `br` becomes [sep] (a space or a line separator). */
        private fun inline(el: Element, sb: StringBuilder, sep: Char, depth: Int) {
            for (n in el.childNodes()) inlineNode(n, sb, sep, depth)
        }

        private fun inlineNode(n: Node, sb: StringBuilder, sep: Char, depth: Int) {
            when (n) {
                is TextNode -> {
                    val s = wsRe.replace(clean(n.wholeText), " ")
                    if (sb.isNotEmpty() && sb.last() == ' ' && s.startsWith(" ")) sb.append(s, 1, s.length) else sb.append(s)
                }
                is Element -> {
                    register(n)
                    val name = n.local()
                    val types = typesOf(n)
                    if (name in skipTags || isHidden(n) || "pagebreak" in types) return
                    if (isNoteRef(n, types)) { citations++; return }
                    if (name == "br") { sb.append(sep); return }
                    if (depth > MAX_DEPTH) { sb.append(' ').append(n.text()); return }
                    inline(n, sb, sep, depth + 1)
                    if (name in blockContainers && sb.isNotEmpty() && sb.last() != ' ' && sb.last() != sep) sb.append(' ')
                }
                else -> {}
            }
        }

        private fun hasBlockChild(el: Element): Boolean = el.children().any { it.local() in blockContainers }

        private fun visit(el: Element, ctx: Ctx, depth: Int) {
            register(el)
            val name = el.local()
            if (name in skipTags || isHidden(el)) return
            val own = typesOf(el)
            if ("pagebreak" in own) return
            if (isNoteRef(el, own)) { citations++; return }
            if (depth > MAX_DEPTH) { appendText(el.text()); return }
            val noteHere = ctx.note || own.any { it in noteTypes }

            when {
                name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> {
                    flush(ctx)
                    val sb = StringBuilder()
                    inline(el, sb, ' ', 0)
                    emit(BlockKind.HEADING, sb.toString(), name[1] - '0', ctx.copy(note = noteHere))
                }
                name == "br" -> if (ctx.verse) flush(ctx) else appendText(" ")
                name == "pre" -> {
                    flush(ctx)
                    val first = el.wholeText().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                    inline(el, StringBuilder(), ' ', 0) // registers inner anchors
                    if (first.isNotEmpty()) emit(BlockKind.CODE, "Code: " + first.take(80), 0, ctx.copy(note = noteHere))
                }
                name == "table" -> table(el, ctx, depth)
                name == "figure" -> {
                    flush(ctx)
                    val cap = el.allElements.firstOrNull { it.local() == "figcaption" }?.text()?.trim().orEmpty()
                        .ifEmpty { el.allElements.firstOrNull { it.local() == "img" && it.attr("alt").isNotBlank() }?.attr("alt")?.trim().orEmpty() }
                    inline(el, StringBuilder(), ' ', 0)
                    if (cap.isNotEmpty()) emit(BlockKind.FIGURE, "Figure: $cap", 0, ctx.copy(note = noteHere))
                }
                name == "math" -> {
                    flush(ctx)
                    val t = el.attr("alttext").ifBlank { el.text() }.trim()
                    if (t.isNotEmpty()) emit(BlockKind.MATH, "Formula: " + t.take(200), 0, ctx.copy(note = noteHere))
                }
                name == "nav" && own.any { it in skipNavTypes } -> {}
                name == "p" || name == "dd" || name == "dt" || name == "figcaption" || name == "caption" || name == "li" -> {
                    flush(ctx)
                    val verse = ctx.verse || (name == "p" && verseClass.containsMatchIn(el.className()))
                    val inner = ctx.copy(verse = verse, listItem = ctx.listItem && name != "p" || name == "li" || name == "dt", note = noteHere)
                    if (hasBlockChild(el)) {
                        walkChildren(el, inner, depth)
                        flush(inner)
                    } else if (verse && el.children().any { it.local() == "br" }) {
                        val sb = StringBuilder()
                        inline(el, sb, '\n', 0)
                        for (line in sb.split('\n')) emit(BlockKind.VERSE, line, 0, inner)
                    } else {
                        val sb = StringBuilder()
                        inline(el, sb, ' ', 0)
                        val kind = when {
                            verse -> BlockKind.VERSE
                            inner.listItem -> BlockKind.LIST_ITEM
                            ctx.quote -> BlockKind.QUOTE
                            else -> BlockKind.PARAGRAPH
                        }
                        emit(kind, sb.toString(), 0, inner)
                    }
                }
                name == "blockquote" -> {
                    flush(ctx)
                    val inner = ctx.copy(quote = true, note = noteHere)
                    walkChildren(el, inner, depth)
                    flush(inner)
                }
                name in blockContainers -> {
                    flush(ctx)
                    var types = ctx.types
                    var sd = ctx.structDepth
                    if (name in structTags && ctx.structDepth <= 2) {
                        types = types + own.filter { it != "epigraph" && it != "subtitle" && it != "title" }
                        sd++
                    }
                    val verse = ctx.verse || verseClass.containsMatchIn(el.className())
                    val inner = ctx.copy(verse = verse, note = noteHere, types = types, structDepth = sd, listItem = ctx.listItem && (name == "ul" || name == "ol" || name == "dl"))
                    walkChildren(el, inner, depth)
                    flush(inner)
                }
                else -> walkChildren(el, ctx, depth) // inline element: text joins the pending buffer
            }
        }

        private fun walkChildren(el: Element, ctx: Ctx, depth: Int) {
            for (n in el.childNodes()) {
                when (n) {
                    is TextNode -> appendText(n.wholeText)
                    is Element -> visit(n, ctx, depth + 1)
                    else -> {}
                }
            }
        }

        private fun table(el: Element, ctx: Ctx, depth: Int) {
            flush(ctx)
            val cells = el.allElements.count { it.local() == "td" || it.local() == "th" }
            val total = el.text().length
            val caption = el.allElements.firstOrNull { it.local() == "caption" }?.text()?.trim().orEmpty()
            val layout = caption.isEmpty() && cells > 0 && total > 600 && total / cells > 150 && el.allElements.none { it.local() == "th" }
            if (layout) {
                walkChildren(el, ctx, depth)
                flush(ctx)
                return
            }
            inline(el, StringBuilder(), ' ', 0)
            emit(BlockKind.TABLE, if (caption.isNotEmpty()) "Table: $caption" else "Table.", 0, ctx)
        }
    }
}
