package app.kepler.engine.epub.ingest

import android.content.Context
import android.net.Uri
import app.kepler.core.model.BookIngestor
import app.kepler.core.model.IngestException
import app.kepler.core.model.IngestedBook
import app.kepler.core.model.SectionDraft
import app.kepler.core.model.SectionKind
import app.kepler.core.model.SentenceDraft
import app.kepler.core.spine.SentenceSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/** Opens the user-supplied source (content:// URI, file:// URI or plain path). */
fun interface SourceOpener {
    fun open(source: String): InputStream
}

object FileSourceOpener : SourceOpener {
    override fun open(source: String): InputStream {
        val path = if (source.startsWith("file://")) java.net.URI(source).path else source
        return FileInputStream(path)
    }
}

class ContentSourceOpener(private val context: Context) : SourceOpener {
    override fun open(source: String): InputStream =
        if (source.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(source)) ?: throw java.io.FileNotFoundException(source)
        } else FileSourceOpener.open(source)
}

/**
 * Copies an EPUB into `workDir/book.epub`, extracts metadata + cover, and builds the Text Spine:
 * sections from the TOC (or headings), blocks from the XHTML, sentences with dense global ordinals.
 * Hrefs in sections/sentences are zip paths, percent-encoded, relative to the archive root
 * (e.g. "OEBPS/ch1.xhtml"), with an optional "#fragment" on sections.
 */
class EpubIngestor(private val opener: SourceOpener = FileSourceOpener) : BookIngestor {
    constructor(context: Context) : this(ContentSourceOpener(context.applicationContext))

    override suspend fun ingest(sourceUri: String, workDir: String): IngestedBook = withContext(Dispatchers.IO) {
        try {
            doIngest(sourceUri, File(workDir))
        } catch (e: IngestException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw IngestException("This file is too large to open.", e)
        } catch (e: Exception) {
            throw IngestException("This file couldn't be read.", e)
        }
    }

    private class LinearDoc(val path: String, val href: String, val parsed: ParsedDoc, val offset: Int)
    private class LB(val doc: Int, val block: Block)
    private class Entry(val title: String, val level: Int, val parent: Int?, val gpos: Int, val href: String)
    private class Sec(
        val title: String, val level: Int, val parent: Int?, val start: Int, val end: Int, val href: String,
        val leading: Boolean = false,
    )
    private class OutSection(
        val title: String, val level: Int, val parent: Int?, val href: String, val kind: SectionKind,
        val blocks: List<LB>, val docHref: String?,
    )

    private suspend fun doIngest(source: String, dir: File): IngestedBook {
        dir.mkdirs()
        val epub = File(dir, "book.epub")
        copy(source, epub)
        EpubArchive(epub).use { archive ->
            archive.assertNotEncrypted()
            val pkg = OpfPackage.load(archive)
            if (pkg.spine.isEmpty()) throw IngestException("This file has no readable content.")

            // ---- parse spine documents, one at a time
            val linear = ArrayList<LinearDoc>()
            val nonLinear = ArrayList<LinearDoc>()
            var offset = 0
            var totalChars = 0L
            for (item in pkg.spine) {
                currentCoroutineContext().ensureActive()
                val bytes = archive.read(item.item.path)
                val parsed = if (bytes == null) EMPTY_DOC else try {
                    XhtmlBlocks.extract(parseMarkup(bytes))
                } catch (e: Exception) {
                    EMPTY_DOC
                }
                totalChars += parsed.blocks.sumOf { it.text.length.toLong() }
                if (totalChars > MAX_TOTAL_CHARS) throw IngestException("This file is too large to open.")
                val doc = LinearDoc(item.item.path, encodeHref(item.item.path), parsed, if (item.linear) offset else 0)
                if (item.linear) { linear.add(doc); offset += parsed.blocks.size } else nonLinear.add(doc)
            }
            val total = offset
            val flat = ArrayList<LB>(total)
            linear.forEachIndexed { i, d -> d.parsed.blocks.forEach { flat.add(LB(i, it)) } }
            if (flat.isEmpty() && nonLinear.all { it.parsed.blocks.isEmpty() }) throw IngestException("No readable text was found in this file.")

            val docIndex = linear.withIndex().associate { it.value.path to it.index }
            val nonLinearByPath = nonLinear.associateBy { it.path }
            fun gposOf(path: String, fragment: String?): Int? {
                val i = docIndex[path] ?: return null
                val d = linear[i]
                val idx = fragment?.let { d.parsed.anchors[percentDecode(it)] } ?: 0
                return d.offset + idx.coerceAtMost(d.parsed.blocks.size)
            }

            // ---- sections
            val toc = TocParser.parse(archive, pkg)
            val nonLinearTitles = HashMap<String, String>()
            val entries = ArrayList<Entry>()
            fun firstStart(n: TocNode): Int? =
                n.path?.let { gposOf(it, n.fragment) } ?: n.children.firstNotNullOfOrNull { firstStart(it) }

            fun flatten(nodes: List<TocNode>, level: Int, parent: Int?) {
                for (n in nodes) {
                    val p = n.path
                    if (p != null && p in nonLinearByPath) {
                        nonLinearTitles.putIfAbsent(p, n.title)
                        flatten(n.children, level, parent)
                        continue
                    }
                    val g = firstStart(n)
                    if (g == null) { flatten(n.children, level, parent); continue }
                    var kept: Int? = null
                    var prev = entries.lastOrNull()
                    if (prev != null && g < prev.gpos) {
                        flatten(n.children, level, parent)
                        continue
                    }
                    if (prev != null && g == prev.gpos && parent != entries.lastIndex) entries.removeAt(entries.lastIndex)
                    val href = (n.path ?: firstHref(n))?.let { encodeHref(it) + (if (n.path != null && n.fragment != null) "#" + n.fragment else "") } ?: ""
                    entries.add(Entry(n.title.ifBlank { "Untitled" }, level, parent, g, href))
                    kept = entries.lastIndex
                    flatten(n.children, level + 1, kept)
                }
            }
            flatten(toc.roots, 0, null)
            if (entries.isEmpty()) entries.addAll(headingEntries(linear, flat))

            val secs = ArrayList<Sec>()
            val first = entries.firstOrNull()?.gpos ?: total
            val hasLeading = first > 0 && total > 0
            if (hasLeading) {
                val head = flat.subList(0, first).firstOrNull { it.block.kind == BlockKind.HEADING }?.block?.text
                secs.add(Sec(head ?: if (entries.isEmpty()) pkg.title ?: "Text" else "Front matter", 0, null, 0, first, linear[flat[0].doc].href, leading = true))
            }
            val shift = if (hasLeading) 1 else 0
            entries.forEachIndexed { i, e ->
                val end = entries.getOrNull(i + 1)?.gpos ?: total
                secs.add(Sec(e.title, e.level, e.parent?.plus(shift), e.gpos, end, e.href))
            }

            // ---- classification
            val lmPos = toc.landmarks.mapNotNull { lm -> gposOf(lm.path, lm.fragment)?.let { LandmarkPos(lm.type, it) } }
            val kinds = ArrayList<SectionKind>()
            var seenBody = false
            secs.forEachIndexed { i, s ->
                val blocks = flat.subList(s.start, s.end)
                val types = blocks.firstOrNull()?.block?.types.orEmpty()
                var cls = SectionClassifier.fromTypes(types)
                    ?: SectionClassifier.fromLandmarks(lmPos, s.start, s.end)
                    ?: SectionClassifier.fromTitle(s.title)
                    ?: SectionClassifier.fromRegion(lmPos, s.start)
                if (cls == null && s.start * 10 >= total * 6) cls = SectionClassifier.fromDensity(blocks.map { it.block })
                if (cls == null) {
                    val parentKind = s.parent?.let { kinds[it] }
                    cls = when {
                        s.leading && entries.isNotEmpty() -> Classification(SectionKind.FRONT, false)
                        parentKind != null && parentKind != SectionKind.BODY -> Classification(parentKind, false)
                        else -> Classification(SectionKind.BODY, false)
                    }
                }
                val kind = if (cls.ambiguous) (if (seenBody) SectionKind.BACK else SectionKind.FRONT) else cls.kind
                if (kind == SectionKind.BODY) seenBody = true
                kinds.add(kind)
            }

            // ---- assemble output sections (linear, diverted footnotes, non-linear)
            val out = ArrayList<OutSection>()
            val diverted = LinkedHashMap<Int, MutableList<LB>>()
            secs.forEachIndexed { i, s ->
                val kind = kinds[i]
                val all = flat.subList(s.start, s.end)
                val kept = if (kind == SectionKind.NOTES) all else all.filter { lb ->
                    if (lb.block.note) { diverted.getOrPut(lb.doc) { ArrayList() }.add(lb); false } else true
                }
                out.add(OutSection(s.title, s.level, s.parent, s.href, kind, kept, null))
            }
            for ((docI, list) in diverted) {
                out.add(OutSection("Notes", 0, null, linear[docI].href, SectionKind.NOTES, list, linear[docI].href))
            }
            for (d in nonLinear) {
                if (d.parsed.blocks.isEmpty()) continue
                val title = nonLinearTitles[d.path] ?: "Notes"
                out.add(OutSection(title, 0, null, d.href, SectionKind.NOTES, d.parsed.blocks.map { LB(-1, it) }, d.href))
            }

            // ---- sentences
            val locale = pkg.language?.let { Locale.forLanguageTag(it.replace('_', '-')) } ?: Locale.ROOT
            val sectionDrafts = ArrayList<SectionDraft>()
            val texts = ArrayList<String>()
            val hrefs = ArrayList<String>()
            val sectionOf = ArrayList<Int>()
            val paraOf = ArrayList<Int>()
            var para = 0
            val nonLinearHref = nonLinear.associate { it.parsed.blocks to it.href }
            for ((idx, sec) in out.withIndex()) {
                currentCoroutineContext().ensureActive()
                val firstOrd = texts.size
                for (lb in sec.blocks) {
                    val href = if (lb.doc >= 0) linear[lb.doc].href else sec.docHref ?: sec.href.substringBefore('#')
                    val parts = if (lb.block.kind.singleSentence && lb.block.text.length <= SINGLE_LIMIT) listOf(lb.block.text)
                    else SentenceSegmenter.segment(lb.block.text, locale)
                    if (parts.isEmpty()) continue
                    for (p in parts) {
                        texts.add(p); hrefs.add(href); sectionOf.add(idx); paraOf.add(para)
                    }
                    para++
                    if (texts.size > MAX_SENTENCES) throw IngestException("This file is too large to open.")
                }
                sectionDrafts.add(SectionDraft(idx, sec.parent, sec.level, sec.title, sec.href, sec.kind, firstOrd, texts.size - 1))
            }
            if (texts.isEmpty()) throw IngestException("No readable text was found in this file.")
            val sentences = ArrayList<SentenceDraft>(texts.size)
            for (i in texts.indices) {
                sentences.add(SentenceDraft(i, sectionOf[i], hrefs[i], texts[i], context(texts, hrefs, i, -1), context(texts, hrefs, i, 1), paraOf[i]))
            }

            // ---- content type
            val bodyBlocks = out.filter { it.kind == SectionKind.BODY }.flatMap { it.blocks }.map { it.block }
            val paragraphs = bodyBlocks.filter { it.kind == BlockKind.PARAGRAPH || it.kind == BlockKind.QUOTE }
            val allBlocks = out.flatMap { it.blocks }.map { it.block }
            val stats = ContentStats(
                paragraphs = paragraphs.size,
                dialogueParagraphs = paragraphs.count { it.dialogue },
                words = bodyBlocks.sumOf { b -> b.text.count { it == ' ' } + 1L },
                structuralBlocks = allBlocks.count { it.kind == BlockKind.TABLE || it.kind == BlockKind.FIGURE || it.kind == BlockKind.MATH || it.kind == BlockKind.CODE },
                totalBlocks = allBlocks.size,
                citationMarkers = linear.sumOf { it.parsed.citationMarkers },
                noteBlocks = out.filter { it.kind == SectionKind.NOTES }.sumOf { it.blocks.size },
            )

            return IngestedBook(
                title = pkg.title?.ifBlank { null } ?: "Untitled",
                authors = pkg.authors.joinToString(", "),
                language = pkg.language,
                contentType = ContentTypeGuesser.guess(pkg.subjects, stats),
                epubPath = epub.absolutePath,
                coverPath = writeCover(archive, pkg.coverPath, dir),
                sections = sectionDrafts,
                sentences = sentences,
            )
        }
    }

    private fun firstHref(n: TocNode): String? = n.path ?: n.children.firstNotNullOfOrNull { firstHref(it) }

    private fun headingEntries(linear: List<LinearDoc>, flat: List<LB>): List<Entry> {
        val heads = flat.withIndex().filter { it.value.block.kind == BlockKind.HEADING && it.value.block.headingLevel <= 4 && !it.value.block.note }
        if (heads.isEmpty()) return emptyList()
        val min = heads.minOf { it.value.block.headingLevel }
        val result = ArrayList<Entry>()
        val stack = ArrayList<Pair<Int, Int>>() // (heading level, entry index)
        for ((g, lb) in heads) {
            val hl = lb.block.headingLevel
            while (stack.isNotEmpty() && stack.last().first >= hl) stack.removeAt(stack.lastIndex)
            val parent = stack.lastOrNull()?.second
            result.add(Entry(lb.block.text, (hl - min).coerceAtMost(stack.size), parent, g, linear[lb.doc].href))
            stack.add(hl to result.lastIndex)
        }
        // headings that share a start position keep only the last (e.g. "Part I" immediately followed by "Chapter 1" stays: positions differ)
        return result
    }

    private fun context(texts: List<String>, hrefs: List<String>, i: Int, dir: Int): String {
        val sb = StringBuilder()
        var j = i + dir
        while (j in texts.indices && hrefs[j] == hrefs[i] && sb.length < CONTEXT_CHARS * 2) {
            if (dir < 0) sb.insert(0, texts[j] + " ") else sb.append(texts[j]).append(' ')
            j += dir
        }
        val s = sb.toString().trim()
        if (s.length <= CONTEXT_CHARS) return s
        return if (dir < 0) {
            val cut = s.substring(s.length - CONTEXT_CHARS)
            if (s[s.length - CONTEXT_CHARS - 1] == ' ') cut else cut.substringAfter(' ', cut)
        } else {
            val cut = s.substring(0, CONTEXT_CHARS)
            if (s[CONTEXT_CHARS] == ' ') cut else cut.substringBeforeLast(' ', cut)
        }
    }

    private fun writeCover(archive: EpubArchive, path: String?, dir: File): String? {
        if (path == null) return null
        val ext = when (path.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "jpg"; "png" -> "png"; "gif" -> "gif"; "webp" -> "webp"; else -> return null
        }
        val bytes = archive.read(path, 15L * 1024 * 1024) ?: return null
        val f = File(dir, "cover.$ext")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    private fun copy(source: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            opener.open(source).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) throw IngestException("This file is too large to open.")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: java.io.IOException) {
            tmp.delete()
            throw IngestException("This file couldn't be read.", e)
        }
    }

    private companion object {
        val EMPTY_DOC = ParsedDoc(null, emptyList(), emptyMap(), 0)
        const val MAX_TOTAL_CHARS = 150_000_000L
        const val MAX_FILE_BYTES = 1_500_000_000L
        const val MAX_SENTENCES = 3_000_000
        const val SINGLE_LIMIT = 1200
        const val CONTEXT_CHARS = 40
    }
}
