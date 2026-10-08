package app.betterbooks.feature.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportersTest {
    private val b = book(7, title = "On \"Duty\": a book")
    private val sections = listOf(section(1, 0, 0, 49, "Book I"), section(2, 1, 50, 99, "Book II"))
    // Deliberately out of book order.
    private val ann = listOf(annotation(3, 60, "late\nline two", note = "my note"), annotation(1, 5, "early"), annotation(2, 55, "middle"))

    @Test fun markdownHasFrontMatterAndBookOrder() {
        val doc = MarkdownExporter.export(b, sections, ann, T0)
        assertEquals("text/markdown", doc.mime)
        assertEquals("On Duty a book.md", doc.name)
        val t = doc.text
        assertTrue(t.startsWith("---\n"))
        assertTrue(t.contains("title: \"On \\\"Duty\\\": a book\""))
        assertTrue(t.contains("betterbooks_id: 7"))
        assertTrue(t.contains("exported: 2025-10-09"))
        assertTrue(t.contains("highlights: 3"))
        assertTrue(t.indexOf("early") < t.indexOf("middle") && t.indexOf("middle") < t.indexOf("late"))
        assertTrue(t.indexOf("## Book I") < t.indexOf("## Book II"))
        assertTrue(t.contains("> late\n> line two"))
        assertTrue(t.contains("**Note:** my note"))
        assertTrue(t.contains("(betterbooks://book/7/s/60)"))
        assertTrue(t.contains("(betterbooks://book/7/s/5)"))
    }

    @Test fun markdownFileNameIsSafe() {
        assertEquals("a b.md", safeFileName("a/b", ".md"))
        assertEquals("Book.md", safeFileName("   ", ".md"))
    }

    @Test fun webAnnotationIsValidCollectionWithSelectors() {
        val doc = WebAnnotationExporter.export(b, ann)
        assertEquals("application/ld+json", doc.mime)
        val root = Json.parseToJsonElement(doc.text).jsonObject
        assertEquals("AnnotationCollection", root["type"]!!.jsonPrimitive.content)
        assertEquals(3, root["total"]!!.jsonPrimitive.content.toInt())
        val items = root["items"]!!.jsonArray
        val first = items[0].jsonObject
        assertEquals("Annotation", first["type"]!!.jsonPrimitive.content)
        assertEquals("highlighting", first["motivation"]!!.jsonPrimitive.content)
        val sel = first["target"]!!.jsonObject["selector"]!!.jsonArray
        assertEquals("early", sel[0].jsonObject["exact"]!!.jsonPrimitive.content)
        assertEquals("s=5,5", sel[1].jsonObject["value"]!!.jsonPrimitive.content)
        // Only the annotation with a note carries a body.
        val withNote = items.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content.endsWith("/3") }
        assertEquals("my note", withNote["body"]!!.jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(items[0].jsonObject["body"] == null)
    }

    @Test fun deepLinkFormat() = assertEquals("betterbooks://book/3/s/12", deepLink(3, 12))
}
