package app.kepler.engine.epub.ingest

import org.jsoup.nodes.Element

/** One TOC node; [path] is a resolved zip path (null for pure grouping labels). */
internal class TocNode(val title: String, val path: String?, val fragment: String?, val children: List<TocNode>)

internal class TocData(val roots: List<TocNode>, val landmarks: List<Landmark>)

internal object TocParser {
    fun parse(archive: EpubArchive, pkg: OpfPackage): TocData {
        var roots: List<TocNode> = emptyList()
        var landmarks: List<Landmark> = emptyList()
        pkg.navItem?.let { nav ->
            archive.read(nav.path, 4_000_000)?.let { bytes ->
                val navs = parseMarkup(bytes).allElements.filter { it.local() == "nav" }
                val toc = navs.firstOrNull { "toc" in types(it) } ?: navs.firstOrNull { "landmarks" !in types(it) && "page-list" !in types(it) }
                if (toc != null) roots = parseNavList(archive, nav.path, toc, 0)
                navs.firstOrNull { "landmarks" in types(it) }?.let { lm ->
                    landmarks = lm.allElements.filter { it.local() == "a" && it.hasAttr("href") }.mapNotNull { a ->
                        val type = types(a).firstOrNull() ?: return@mapNotNull null
                        val path = archive.resolve(nav.path, a.attr("href")) ?: return@mapNotNull null
                        Landmark(type, path, a.attr("href").substringAfter('#', "").ifEmpty { null })
                    }
                }
            }
        }
        if (roots.isEmpty()) {
            pkg.ncxItem?.let { ncx ->
                archive.read(ncx.path, 4_000_000)?.let { roots = parseNcx(archive, ncx.path, parseMarkup(it).allElements.firstOrNull { e -> e.local() == "navmap" }, 0) }
            }
        }
        return TocData(roots, landmarks + pkg.guide)
    }

    private fun types(e: Element): Set<String> =
        OpfPackage.tokens(e.attr("epub:type")).map { it.removePrefix("doc-") }.toSet() +
            OpfPackage.tokens(e.attr("role")).map { it.removePrefix("doc-") }.toSet()

    private const val MAX_DEPTH = 24

    private fun parseNavList(archive: EpubArchive, base: String, container: Element, depth: Int): List<TocNode> {
        if (depth > MAX_DEPTH) return emptyList()
        val ol = container.children().firstOrNull { it.local() == "ol" || it.local() == "ul" } ?: return emptyList()
        return ol.children().filter { it.local() == "li" }.mapNotNull { li ->
            val label = li.children().firstOrNull { it.local() == "a" || it.local() == "span" }
            val title = label?.text()?.trim().orEmpty()
            val href = label?.takeIf { it.local() == "a" }?.attr("href").orEmpty()
            val children = parseNavList(archive, base, li, depth + 1)
            val path = archive.resolve(base, href)
            if (title.isEmpty() && children.isEmpty()) null
            else TocNode(title, path, href.substringAfter('#', "").ifEmpty { null }, children)
        }
    }

    private fun parseNcx(archive: EpubArchive, base: String, container: Element?, depth: Int): List<TocNode> {
        if (container == null || depth > MAX_DEPTH) return emptyList()
        return container.children().filter { it.local() == "navpoint" }.mapNotNull { np ->
            val title = np.children().firstOrNull { it.local() == "navlabel" }?.text()?.trim().orEmpty()
            val src = np.children().firstOrNull { it.local() == "content" }?.attr("src").orEmpty()
            val children = parseNcx(archive, base, np, depth + 1)
            if (title.isEmpty() && children.isEmpty()) null
            else TocNode(title, archive.resolve(base, src), src.substringAfter('#', "").ifEmpty { null }, children)
        }
    }
}
