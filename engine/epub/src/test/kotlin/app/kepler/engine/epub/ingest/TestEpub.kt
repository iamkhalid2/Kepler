package app.kepler.engine.epub.ingest

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds tiny synthetic EPUBs in a temp dir. */
internal class TestEpub {
    val files = LinkedHashMap<String, ByteArray>()
    var title = "Test Book"
    var author = "Jane Doe"
    var language = "en"
    var subjects = emptyList<String>()
    private val manifest = StringBuilder()
    private val spine = StringBuilder()
    private var navXml: String? = null
    private var ncxXml: String? = null
    private var guideXml = ""
    private var n = 0

    fun chapter(name: String, body: String, linear: Boolean = true, bodyAttrs: String = "", head: String = "") = apply {
        n++
        val id = "c$n"
        files["OEBPS/$name"] = xhtml(body, bodyAttrs, head).toByteArray()
        manifest.append("""<item id="$id" href="$name" media-type="application/xhtml+xml"/>""")
        spine.append("""<itemref idref="$id"${if (linear) "" else """ linear="no""""}/>""")
    }

    fun nav(tocLis: String, landmarks: String = "") = apply {
        navXml = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>nav</title></head><body>
<nav epub:type="toc"><ol>$tocLis</ol></nav>
${if (landmarks.isEmpty()) "" else """<nav epub:type="landmarks"><ol>$landmarks</ol></nav>"""}
</body></html>"""
    }

    fun ncx(points: String) = apply {
        ncxXml = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>$points</navMap></ncx>"""
    }

    fun guide(refs: String) = apply { guideXml = "<guide>$refs</guide>" }

    fun raw(path: String, content: String) = apply { files[path] = content.toByteArray() }

    fun build(dir: File, name: String = "book.epub"): File {
        files["mimetype"] = "application/epub+zip".toByteArray()
        files["META-INF/container.xml"] = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()
        var extra = ""
        navXml?.let { files["OEBPS/nav.xhtml"] = it.toByteArray(); extra += """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" }
        var spineToc = ""
        ncxXml?.let { files["OEBPS/toc.ncx"] = it.toByteArray(); extra += """<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""; spineToc = """ toc="ncx"""" }
        files["OEBPS/content.opf"] = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">urn:uuid:1</dc:identifier><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:language>$language</dc:language>
${subjects.joinToString("") { "<dc:subject>$it</dc:subject>" }}
</metadata><manifest>$extra$manifest</manifest><spine$spineToc>$spine</spine>$guideXml</package>""".toByteArray()
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            files.forEach { (p, b) ->
                z.putNextEntry(ZipEntry(p)); z.write(b); z.closeEntry()
            }
        }
        return f
    }

    companion object {
        fun xhtml(body: String, bodyAttrs: String = "", head: String = "") = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>t</title>$head</head><body$bodyAttrs>$body</body></html>"""
    }
}
