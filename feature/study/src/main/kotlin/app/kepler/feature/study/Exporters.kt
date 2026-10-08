package app.kepler.feature.study

import app.kepler.core.model.Annotation
import app.kepler.core.model.Book
import app.kepler.core.model.Section
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** A document ready for `writeDocument(name, mime, text)`. */
data class ExportDoc(val name: String, val mime: String, val text: String)

fun deepLink(bookId: Long, ordinal: Int) = "kepler://book/$bookId/s/$ordinal"

internal fun safeFileName(title: String, ext: String): String {
    val base = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
    return (base.ifEmpty { "Book" }) + ext
}

/** Highlights of one book in book order, grouped under the section that holds them. */
internal fun highlightsInBookOrder(sections: List<Section>, annotations: List<Annotation>): List<Pair<Section?, List<Annotation>>> {
    val sorted = annotations.sortedWith(compareBy({ it.startOrdinal }, { it.endOrdinal }, { it.id }))
    val ordered = sections.sortedBy { it.startOrdinal }
    return sorted
        .groupBy { a -> ordered.lastOrNull { it.startOrdinal <= a.startOrdinal } }
        .entries.sortedBy { it.key?.startOrdinal ?: -1 }
        .map { it.key to it.value }
}

/** One .md per book with YAML front matter; highlights in book order with sentence deep links. */
object MarkdownExporter {
    fun export(book: Book, sections: List<Section>, annotations: List<Annotation>, exportedAtMs: Long): ExportDoc {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("title: ").append(yaml(book.title)).append('\n')
        sb.append("author: ").append(yaml(book.authors)).append('\n')
        sb.append("kepler_id: ").append(book.id).append('\n')
        sb.append("link: ").append(yaml("kepler://book/${book.id}")).append('\n')
        sb.append("exported: ").append(Instant.ofEpochMilli(exportedAtMs).toString().substringBefore('T')).append('\n')
        sb.append("highlights: ").append(annotations.size).append('\n')
        sb.append("tags: [kepler, highlights]\n")
        sb.append("---\n\n")
        sb.append("# ").append(book.title.replace("\n", " ")).append("\n\n")
        for ((section, items) in highlightsInBookOrder(sections, annotations)) {
            if (section != null) sb.append("## ").append(section.title.replace("\n", " ").ifBlank { "Untitled" }).append("\n\n")
            for (a in items) {
                a.quote.trim().lines().forEach { sb.append("> ").append(it.trim()).append('\n') }
                sb.append('\n')
                if (!a.note.isNullOrBlank()) sb.append("**Note:** ").append(a.note!!.trim()).append("\n\n")
                sb.append("[Open in Kepler](").append(deepLink(book.id, a.startOrdinal)).append(")\n\n")
            }
        }
        return ExportDoc(safeFileName(book.title, ".md"), "text/markdown", sb.toString().trimEnd() + "\n")
    }

    private fun yaml(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}

/** W3C Web Annotation Data Model: one AnnotationCollection per book, quote and sentence-range selectors. */
object WebAnnotationExporter {
    private const val CONTEXT = "http://www.w3.org/ns/anno.jsonld"
    const val ORDINAL_SPEC = "https://github.com/kepler/kepler/blob/main/docs/decisions.md#sentence-ordinal"
    private val json = Json { prettyPrint = true }

    fun export(book: Book, annotations: List<Annotation>): ExportDoc {
        val items = annotations.sortedWith(compareBy({ it.startOrdinal }, { it.id })).map { a -> item(book, a) }
        val root = buildJsonObject {
            put("@context", CONTEXT)
            put("id", "kepler://book/${book.id}/annotations")
            put("type", "AnnotationCollection")
            put("label", book.title)
            put("total", items.size)
            put("items", JsonArray(items))
        }
        return ExportDoc(safeFileName(book.title, ".annotations.json"), "application/ld+json", json.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    private fun item(book: Book, a: Annotation): JsonObject = buildJsonObject {
        put("@context", CONTEXT)
        put("id", "kepler://book/${book.id}/annotation/${a.id}")
        put("type", "Annotation")
        put("motivation", "highlighting")
        put("created", Instant.ofEpochMilli(a.createdAt).toString())
        if (!a.note.isNullOrBlank()) {
            put("body", buildJsonArray {
                add(buildJsonObject {
                    put("type", "TextualBody"); put("purpose", "commenting")
                    put("format", "text/plain"); put("value", a.note!!.trim())
                })
            })
        }
        put("target", buildJsonObject {
            put("source", "kepler://book/${book.id}")
            put("selector", buildJsonArray {
                add(buildJsonObject { put("type", "TextQuoteSelector"); put("exact", a.quote) })
                add(buildJsonObject {
                    put("type", "FragmentSelector")
                    put("conformsTo", ORDINAL_SPEC)
                    put("value", "s=${a.startOrdinal},${a.endOrdinal}")
                })
            })
            put("id", deepLink(book.id, a.startOrdinal))
        })
    }
}
