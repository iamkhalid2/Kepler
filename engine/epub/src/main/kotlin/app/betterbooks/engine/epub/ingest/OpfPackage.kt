package app.betterbooks.engine.epub.ingest

import app.betterbooks.core.model.IngestException
import org.jsoup.nodes.Element

internal data class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: Set<String>)
internal data class SpineItem(val item: ManifestItem, val linear: Boolean)

/** A landmark / guide pointer: [type] is an epub:type value, [path] a zip path, [fragment] optional. */
internal data class Landmark(val type: String, val path: String, val fragment: String?)

internal class OpfPackage(
    val opfPath: String,
    val title: String?,
    val authors: List<String>,
    val language: String?,
    val subjects: List<String>,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    val navItem: ManifestItem?,
    val ncxItem: ManifestItem?,
    val coverPath: String?,
    val guide: List<Landmark>,
) {
    companion object {
        fun load(archive: EpubArchive): OpfPackage {
            val opfPath = locateOpf(archive) ?: throw IngestException("This file isn't a valid EPUB (no package document).")
            val bytes = archive.read(opfPath, 8_000_000) ?: throw IngestException("This file isn't a valid EPUB (unreadable package document).")
            val doc = parseMarkup(bytes)
            val all = doc.allElements
            val manifest = LinkedHashMap<String, ManifestItem>()
            all.filter { it.local() == "item" && it.parent()?.local() == "manifest" }.forEach { el ->
                val id = el.attr("id")
                val path = archive.resolve(opfPath, el.attr("href")) ?: return@forEach
                if (id.isNotEmpty()) manifest[id] = ManifestItem(id, path, el.attr("media-type").lowercase(), tokens(el.attr("properties")))
            }
            val spineEl = all.firstOrNull { it.local() == "spine" }
            val spine = ArrayList<SpineItem>()
            spineEl?.children()?.filter { it.local() == "itemref" }?.forEach { ref ->
                val item = manifest[ref.attr("idref")] ?: return@forEach
                if (item.mediaType.contains("html") || item.mediaType == "image/svg+xml" || item.path.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm", "xml")) {
                    spine.add(SpineItem(item, !ref.attr("linear").equals("no", ignoreCase = true)))
                }
            }
            val metadata = all.firstOrNull { it.local() == "metadata" }
            val meta = metadata?.children().orEmpty()
            val refinesMain = meta.filter { it.local() == "meta" && it.attr("property") == "title-type" && it.text().trim() == "main" }
                .map { it.attr("refines").removePrefix("#") }.toSet()
            val titles = meta.filter { it.local() == "title" && it.text().isNotBlank() }
            val title = (titles.firstOrNull { it.id() in refinesMain } ?: titles.firstOrNull())?.text()?.trim()
            val authors = meta.filter { it.local() == "creator" && it.text().isNotBlank() }.map { it.text().trim() }
            val language = meta.firstOrNull { it.local() == "language" && it.text().isNotBlank() }?.text()?.trim()
            val subjects = meta.filter { it.local() == "subject" && it.text().isNotBlank() }.map { it.text().trim() }

            val navItem = manifest.values.firstOrNull { "nav" in it.properties }
            val tocId = spineEl?.attr("toc").orEmpty()
            val ncxItem = manifest[tocId]?.takeIf { it.path.endsWith(".ncx", true) || it.mediaType.contains("ncx") }
                ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }

            val guide = ArrayList<Landmark>()
            all.filter { it.local() == "reference" && it.parent()?.local() == "guide" }.forEach { ref ->
                val path = archive.resolve(opfPath, ref.attr("href")) ?: return@forEach
                val type = GUIDE_TYPES[ref.attr("type").lowercase()] ?: return@forEach
                guide.add(Landmark(type, path, ref.attr("href").substringAfter('#', "").ifEmpty { null }))
            }
            val cover = findCover(archive, opfPath, manifest, meta, guide)
            return OpfPackage(opfPath, title, authors, language, subjects, manifest, spine, navItem, ncxItem, cover, guide)
        }

        private fun locateOpf(archive: EpubArchive): String? {
            archive.read("META-INF/container.xml", 1_000_000)?.let { b ->
                val root = parseMarkup(b).allElements.firstOrNull { it.local() == "rootfile" && it.hasAttr("full-path") }
                root?.attr("full-path")?.let { p -> archive.find(percentDecode(p).trimStart('/'))?.let { return it } }
            }
            return archive.names.firstOrNull { it.endsWith(".opf", true) }
        }

        private fun findCover(archive: EpubArchive, opfPath: String, manifest: Map<String, ManifestItem>, meta: List<Element>, guide: List<Landmark>): String? {
            manifest.values.firstOrNull { "cover-image" in it.properties && it.mediaType.startsWith("image/") }?.let { return it.path }
            val coverId = meta.firstOrNull { it.local() == "meta" && it.attr("name") == "cover" }?.attr("content") ?: ""
            manifest[coverId]?.takeIf { it.mediaType.startsWith("image/") }?.let { return it.path }
            guide.firstOrNull { it.type == "cover" }?.let { g ->
                if (isImage(g.path)) return g.path
                val page = archive.read(g.path, 2_000_000)
                if (page != null) {
                    val img = parseMarkup(page).allElements.firstOrNull { it.local() == "img" || it.local() == "image" }
                    val src = img?.attr("src")?.ifEmpty { null } ?: img?.attr("xlink:href")?.ifEmpty { null } ?: img?.attr("href")
                    if (!src.isNullOrEmpty()) archive.resolve(g.path, src)?.takeIf { isImage(it) }?.let { return it }
                }
            }
            return manifest.values.firstOrNull { it.mediaType.startsWith("image/") && (it.id.contains("cover", true) || it.path.substringAfterLast('/').contains("cover", true)) }?.path
        }

        private fun isImage(path: String) = path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp")

        internal fun tokens(s: String): Set<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

        private val GUIDE_TYPES = mapOf(
            "text" to "bodymatter", "cover" to "cover", "title-page" to "titlepage", "toc" to "toc",
            "copyright-page" to "copyright-page", "preface" to "preface", "foreword" to "foreword", "index" to "index",
            "bibliography" to "bibliography", "glossary" to "glossary", "acknowledgements" to "acknowledgments",
            "dedication" to "dedication", "epigraph" to "epigraph", "notes" to "footnotes", "colophon" to "colophon",
            "loi" to "loi", "lot" to "lot",
        )
    }
}
