package app.kepler.engine.epub.ingest

import app.kepler.core.model.IngestException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

internal const val ENCRYPTED_MESSAGE = "This file is encrypted and can't be opened."
internal const val MAX_ENTRY_BYTES = 24L * 1024 * 1024

/** Read-only view of the EPUB zip with tolerant path lookup. */
internal class EpubArchive(file: File) : Closeable {
    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: IOException) {
        throw IngestException("This file isn't a valid EPUB.", e)
    }
    private val exact = HashMap<String, String>()
    private val lower = HashMap<String, String>()

    init {
        for (e in zip.entries()) {
            if (e.isDirectory) continue
            exact[e.name] = e.name
            lower.putIfAbsent(e.name.lowercase(), e.name)
        }
    }

    val names: Set<String> get() = exact.keys

    fun find(path: String): String? = exact[path] ?: lower[path.lowercase()]

    /** Bytes of [path], or null when missing or larger than [limit]. */
    fun read(path: String, limit: Long = MAX_ENTRY_BYTES): ByteArray? {
        val name = find(path) ?: return null
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > limit) return null
        return try {
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream(if (entry.size in 1..limit) entry.size.toInt() else 8192)
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) return null // zip bomb guard: declared size lied
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Resolves [href] (may be percent-encoded, may carry a fragment) against the document at [basePath]. */
    fun resolve(basePath: String, href: String): String? {
        val clean = href.substringBefore('#').trim()
        if (clean.isEmpty()) return null
        if (clean.contains("://") || clean.startsWith("data:")) return null
        val decoded = percentDecode(clean)
        val joined = if (decoded.startsWith("/")) decoded.trimStart('/') else basePath.substringBeforeLast('/', "").let { dir ->
            if (dir.isEmpty()) decoded else "$dir/$decoded"
        }
        val parts = ArrayList<String>()
        for (seg in joined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        val path = parts.joinToString("/")
        return find(path)
    }

    /** Detects DRM. Font obfuscation (IDPF/Adobe) in encryption.xml is normal and allowed. */
    fun assertNotEncrypted() {
        read("META-INF/rights.xml", 1_000_000)?.let { b ->
            if (String(b, Charsets.UTF_8).contains("encryptedKey", ignoreCase = true)) throw IngestException(ENCRYPTED_MESSAGE)
        }
        if (find("META-INF/license.lcpl") != null) throw IngestException(ENCRYPTED_MESSAGE)
        if (find("META-INF/sinf.xml") != null) throw IngestException(ENCRYPTED_MESSAGE)
        val enc = read("META-INF/encryption.xml", 4_000_000) ?: return
        val doc = parseMarkup(enc)
        for (data in doc.allElements) {
            if (data.local() != "encrypteddata") continue
            val method = data.allElements.firstOrNull { it.local() == "encryptionmethod" } ?: continue
            val alg = method.attr("Algorithm").trim()
            val uri = data.allElements.firstOrNull { it.local() == "cipherreference" }?.attr("URI").orEmpty()
            val fontAlg = alg == "http://www.idpf.org/2008/embedding" || alg == "http://ns.adobe.com/pdf/enc#RC"
            val ext = uri.substringBefore('#').substringAfterLast('.', "").lowercase()
            val fontFile = ext in FONT_EXTENSIONS || uri.isEmpty()
            if (!fontAlg || !fontFile) throw IngestException(ENCRYPTED_MESSAGE)
        }
    }

    override fun close() {
        try { zip.close() } catch (_: IOException) {}
    }

    private companion object {
        val FONT_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2", "ttc", "eot")
    }
}

internal fun Element.local(): String = tagName().substringAfter(':').lowercase()

internal fun percentDecode(s: String): String {
    if (!s.contains('%')) return s
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 3 <= s.length) {
            val v = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null) { out.write(v); i += 3; continue }
        }
        val cp = s.codePointAt(i)
        out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
        i += Character.charCount(cp)
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/** Percent-encodes a zip path the way a Readium Url prints it. */
internal fun encodeHref(path: String): String {
    val sb = StringBuilder(path.length + 8)
    for (b in path.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (c < 128 && (ch.isLetterOrDigit() || ch in "-._~/!$&'()*+,;=:@")) sb.append(ch)
        else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
    return sb.toString()
}

/** XHTML/OPF/NCX parser: strict XML first (keeps `<a id="x"/>` empty), HTML parser if that yields nothing. */
internal fun parseMarkup(bytes: ByteArray): Document {
    val xml = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
    if (xml.allElements.any { it.local() == "body" || it.local() == "package" || it.local() == "ncx" || it.local() == "encryption" || it.local() == "container" }) return xml
    return Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.htmlParser())
}
