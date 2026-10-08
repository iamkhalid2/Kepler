package app.kepler.engine.epub.ingest

import app.kepler.core.model.ContentType
import app.kepler.core.model.IngestException
import app.kepler.core.model.IngestedBook
import app.kepler.core.model.SectionKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EpubIngestorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ingest(f: File): IngestedBook =
        runBlocking { EpubIngestor().ingest(f.absolutePath, tmp.newFolder().absolutePath) }

    private fun assertInvariants(b: IngestedBook) {
        assertEquals((0 until b.sentences.size).toList(), b.sentences.map { it.ordinal })
        var expect = 0
        for (s in b.sections) {
            assertEquals(expect, s.firstOrdinal)
            assertTrue(s.lastOrdinal >= s.firstOrdinal - 1)
            expect = s.lastOrdinal + 1
        }
        assertEquals(b.sentences.size, expect)
        b.sections.forEachIndexed { i, s -> assertEquals(i, s.index); s.parentIndex?.let { assertTrue(it < i) } }
        b.sentences.forEach { assertTrue(b.sections[it.sectionIndex].let { s -> it.ordinal in s.firstOrdinal..s.lastOrdinal }) }
        assertTrue(b.sentences.zipWithNext().all { (a, c) -> a.paragraphIndex <= c.paragraphIndex })
    }

    @Test fun tocMappingWithFragmentsAndNesting() {
        val e = TestEpub()
        e.chapter("ch1.xhtml", """<h1 id="a">Part One</h1><p>Alpha one. Alpha two.</p><h2 id="b">Chapter One</h2><p>Beta one.</p><h2 id="c">Chapter Two</h2><p>Gamma one. Gamma two.</p>""")
        e.chapter("ch2.xhtml", """<h1 id="d">Part Two</h1><p>Delta one.</p>""")
        e.nav(
            """<li><a href="ch1.xhtml#a">Part One</a><ol><li><a href="ch1.xhtml#b">Chapter One</a></li><li><a href="ch1.xhtml#c">Chapter Two</a></li></ol></li><li><a href="ch2.xhtml">Part Two</a></li>""",
        )
        val b = ingest(e.build(tmp.newFolder()))
        assertInvariants(b)
        assertEquals(listOf("Part One", "Chapter One", "Chapter Two", "Part Two"), b.sections.map { it.title })
        assertEquals(listOf(0, 1, 1, 0), b.sections.map { it.level })
        assertEquals(listOf(null, 0, 0, null), b.sections.map { it.parentIndex })
        assertEquals("OEBPS/ch1.xhtml#b", b.sections[1].href)
        // Part One owns heading + its lead-in paragraph; chapters start at their fragment
        assertEquals("Part One", b.sentences[b.sections[0].firstOrdinal].text)
        assertEquals(listOf("Chapter One", "Beta one."), b.sentences.filter { it.sectionIndex == 1 }.map { it.text })
        assertEquals(listOf("Chapter Two", "Gamma one.", "Gamma two."), b.sentences.filter { it.sectionIndex == 2 }.map { it.text })
        assertEquals("OEBPS/ch2.xhtml", b.sentences.last().href)
        assertEquals("Test Book", b.title)
        assertEquals("Jane Doe", b.authors)
        assertEquals("en", b.language)
        assertTrue(File(b.epubPath).exists())
    }

    @Test fun ncxOnlyToc() {
        val e = TestEpub()
        e.chapter("a.xhtml", "<h1>One</h1><p>Text a.</p>")
        e.chapter("b.xhtml", "<h1>Two</h1><p>Text b.</p>")
        e.ncx("""<navPoint id="1"><navLabel><text>First</text></navLabel><content src="a.xhtml"/></navPoint><navPoint id="2"><navLabel><text>Second</text></navLabel><content src="b.xhtml"/></navPoint>""")
        val b = ingest(e.build(tmp.newFolder()))
        assertInvariants(b)
        assertEquals(listOf("First", "Second"), b.sections.map { it.title })
    }

    @Test fun frontAndBackMatterClassification() {
        val e = TestEpub()
        e.chapter("cover.xhtml", "<h1>Title</h1><p>By somebody.</p>", bodyAttrs = """ epub:type="frontmatter titlepage"""")
        e.chapter("copy.xhtml", "<p>All rights reserved.</p>", head = "")
        e.chapter("c1.xhtml", """<section epub:type="chapter"><h1>Chapter 1</h1><p>Body text one.</p></section>""")
        e.chapter("c2.xhtml", """<h1>Chapter 2</h1><p>Body text two.</p>""")
        e.chapter("ack.xhtml", "<h1>Acknowledgments</h1><p>Thanks to all.</p>")
        e.chapter("notes.xhtml", "<h1>Notes</h1><p>1. First note.</p>")
        e.chapter("idx.xhtml", "<h1>Index</h1><p>apples, 4</p>")
        e.nav(
            """<li><a href="cover.xhtml">Title Page</a></li><li><a href="copy.xhtml">Copyright</a></li><li><a href="c1.xhtml">Chapter 1</a></li><li><a href="c2.xhtml">Chapter 2</a></li>""" +
                """<li><a href="ack.xhtml">Acknowledgments</a></li><li><a href="notes.xhtml">Notes</a></li><li><a href="idx.xhtml">Index</a></li>""",
        )
        val b = ingest(e.build(tmp.newFolder()))
        assertInvariants(b)
        assertEquals(
            listOf(SectionKind.FRONT, SectionKind.FRONT, SectionKind.BODY, SectionKind.BODY, SectionKind.BACK, SectionKind.NOTES, SectionKind.INDEX),
            b.sections.map { it.kind },
        )
    }

    @Test fun landmarksDecideFrontVersusBody() {
        val e = TestEpub()
        e.chapter("pre.xhtml", "<h1>Dear Reader</h1><p>A word first.</p>")
        e.chapter("c1.xhtml", "<h1>Alpha</h1><p>Body.</p>")
        e.chapter("c2.xhtml", "<h1>Omega</h1><p>More body.</p>")
        e.nav(
            """<li><a href="pre.xhtml">Dear Reader</a></li><li><a href="c1.xhtml">Alpha</a></li><li><a href="c2.xhtml">Omega</a></li>""",
            landmarks = """<li><a epub:type="bodymatter" href="c1.xhtml">Start</a></li>""",
        )
        val b = ingest(e.build(tmp.newFolder()))
        assertEquals(listOf(SectionKind.FRONT, SectionKind.BODY, SectionKind.BODY), b.sections.map { it.kind })
    }

    @Test fun nonLinearItemsBecomeNotes() {
        val e = TestEpub()
        e.chapter("c1.xhtml", "<h1>One</h1><p>Main text.</p>")
        e.chapter("side.xhtml", "<h1>Side</h1><p>Hidden-ish aside.</p>", linear = false)
        e.chapter("c2.xhtml", "<h1>Two</h1><p>More text.</p>")
        e.nav("""<li><a href="c1.xhtml">One</a></li><li><a href="side.xhtml">Aside</a></li><li><a href="c2.xhtml">Two</a></li>""")
        val b = ingest(e.build(tmp.newFolder()))
        assertInvariants(b)
        assertEquals(listOf("One", "Two", "Aside"), b.sections.map { it.title })
        assertEquals(SectionKind.NOTES, b.sections.last().kind)
        // linear text keeps a contiguous ordinal range ahead of the non-linear notes
        assertTrue(b.sentences.filter { it.sectionIndex < 2 }.none { it.text.contains("aside") })
    }

    @Test fun footnoteBodiesMoveToNotesSection() {
        val e = TestEpub()
        e.chapter(
            "c1.xhtml",
            """<h1>One</h1><p>Claim made<a epub:type="noteref" href="#n1">1</a> here.</p><aside epub:type="footnote" id="n1"><p>See Smith.</p></aside><p>Next paragraph.</p>""",
        )
        e.nav("""<li><a href="c1.xhtml">One</a></li>""")
        val b = ingest(e.build(tmp.newFolder()))
        assertInvariants(b)
        assertEquals("Claim made here.", b.sentences[1].text)
        assertEquals(SectionKind.NOTES, b.sections.last().kind)
        assertEquals(listOf("See Smith."), b.sentences.filter { it.sectionIndex == b.sections.lastIndex }.map { it.text })
        assertFalse(b.sentences.take(3).any { it.text.contains("Smith") })
    }

    @Test fun abbreviationsDoNotSplit() {
        val e = TestEpub()
        e.chapter("c1.xhtml", "<h1>One</h1><p>Mr. Smith met Dr. Jones at St. Mary's. They left. J. K. Rowling agreed, e.g. in writing.</p>")
        e.nav("""<li><a href="c1.xhtml">One</a></li>""")
        val b = ingest(e.build(tmp.newFolder()))
        assertEquals(
            listOf("One", "Mr. Smith met Dr. Jones at St. Mary's.", "They left.", "J. K. Rowling agreed, e.g. in writing."),
            b.sentences.map { it.text },
        )
    }

    @Test fun placeholdersAndNoiseHandling() {
        val e = TestEpub()
        e.chapter(
            "c1.xhtml",
            """<h1>One</h1><p>Visible &nbsp;text &amp; more&#160;words.</p><p hidden="">Never shown.</p><span epub:type="pagebreak" id="p3" title="3"/><p style="display:none">Gone.</p>""" +
                """<table><caption>Sales by year</caption><tr><th>Y</th><th>S</th></tr><tr><td>1</td><td>2</td></tr></table>""" +
                """<table><tr><td>x</td></tr></table><figure><img src="a.png" alt="A map"/><figcaption>The old harbour</figcaption></figure><ul><li>First item.</li><li>Second item. Still second.</li></ul>""" +
                """<p class="poem">Line one<br/>Line two. Still two<br/>Line three</p><p>* * *</p>""",
        )
        e.nav("""<li><a href="c1.xhtml">One</a></li>""")
        val b = ingest(e.build(tmp.newFolder()))
        assertInvariants(b)
        assertEquals(
            listOf(
                "One", "Visible text & more words.", "Table: Sales by year", "Table.", "Figure: The old harbour",
                "First item.", "Second item. Still second.", "Line one", "Line two. Still two", "Line three",
            ),
            b.sentences.map { it.text },
        )
    }

    @Test fun contextsAndParagraphIndex() {
        val e = TestEpub()
        e.chapter("c1.xhtml", "<h1>One</h1><p>The first sentence is here. The second sentence follows right after it. Third.</p><p>Another paragraph starts now.</p>")
        e.nav("""<li><a href="c1.xhtml">One</a></li>""")
        val b = ingest(e.build(tmp.newFolder()))
        val s = b.sentences
        assertEquals("", s[0].before)
        assertTrue(s[2].before.length <= 40 && "The first sentence is here.".endsWith(s[2].before.trim()) || s[2].before.endsWith("is here."))
        assertTrue(s[2].after.startsWith("Third"))
        assertEquals(listOf(0, 1, 1, 1, 2), s.map { it.paragraphIndex })
    }

    @Test fun headingFallbackWithoutToc() {
        val e = TestEpub()
        e.chapter("c1.xhtml", "<h1>Alpha</h1><p>One.</p><h2>Sub A</h2><p>Two.</p><h1>Beta</h1><p>Three.</p>")
        val b = ingest(e.build(tmp.newFolder()))
        assertInvariants(b)
        assertEquals(listOf("Alpha", "Sub A", "Beta"), b.sections.map { it.title })
        assertEquals(listOf(0, 1, 0), b.sections.map { it.level })
        assertEquals(listOf(null, 0, null), b.sections.map { it.parentIndex })
    }

    @Test fun contentTypeGuess() {
        val dialogue = (1..60).joinToString("") { "<p>“Where are you going?” she asked.</p><p>“Home,” he said.</p>" }
        val f = TestEpub().apply { subjects = listOf("Fiction"); chapter("c1.xhtml", "<h1>One</h1>$dialogue"); nav("""<li><a href="c1.xhtml">One</a></li>""") }
        assertEquals(ContentType.FICTION, ingest(f.build(tmp.newFolder())).contentType)
        val n = TestEpub().apply { chapter("c1.xhtml", "<h1>One</h1><p>Habits are the compound interest of self-improvement.</p>"); nav("""<li><a href="c1.xhtml">One</a></li>""") }
        assertEquals(ContentType.NONFICTION, ingest(n.build(tmp.newFolder())).contentType)
    }

    @Test fun encryptedFilesAreRejectedNeutrally() {
        fun expectEncrypted(configure: TestEpub.() -> Unit) {
            val e = TestEpub().apply { chapter("c1.xhtml", "<h1>One</h1><p>Text.</p>"); configure() }
            try {
                ingest(e.build(tmp.newFolder()))
                fail("expected IngestException")
            } catch (ex: IngestException) {
                assertEquals("This file is encrypted and can't be opened.", ex.message)
            }
        }
        expectEncrypted { raw("META-INF/rights.xml", """<rights><encryptedKey>abc</encryptedKey></rights>""") }
        expectEncrypted { raw("META-INF/license.lcpl", "{}") }
        expectEncrypted {
            raw(
                "META-INF/encryption.xml",
                """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/><enc:CipherData><enc:CipherReference URI="OEBPS/c1.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>""",
            )
        }
        // IDPF algorithm applied to a non-font resource is still not "font obfuscation only"
        expectEncrypted {
            raw(
                "META-INF/encryption.xml",
                """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/><enc:CipherData><enc:CipherReference URI="OEBPS/c1.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>""",
            )
        }
    }

    @Test fun fontObfuscationOnlyOpensFine() {
        val e = TestEpub().apply {
            chapter("c1.xhtml", "<h1>One</h1><p>Readable text.</p>")
            nav("""<li><a href="c1.xhtml">One</a></li>""")
            raw(
                "META-INF/encryption.xml",
                """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/><enc:CipherData><enc:CipherReference URI="OEBPS/fonts/a.otf"/></enc:CipherData></enc:EncryptedData></encryption>""",
            )
        }
        val b = ingest(e.build(tmp.newFolder()))
        assertEquals(listOf("One", "Readable text."), b.sentences.map { it.text })
    }

    @Test fun invalidOrEmptyFilesFailNeutrally() {
        val junk = tmp.newFile("junk.epub").apply { writeText("not a zip") }
        try { ingest(junk); fail() } catch (e: IngestException) { assertNotNull(e.message) }
        val empty = TestEpub().apply { chapter("c1.xhtml", "<p>* * *</p>") }.build(tmp.newFolder())
        try { ingest(empty); fail() } catch (e: IngestException) { assertTrue(e.message!!.contains("readable text")) }
    }

    @Test fun malformedXhtmlDoesNotCrash() {
        val e = TestEpub().apply {
            chapter("c1.xhtml", "<h1>One</h1><p>Unclosed <b>bold <i>text. More text.</p><div><p>Nested ok.</div>")
            raw("OEBPS/broken.xhtml", "<<<not xml at all")
            nav("""<li><a href="c1.xhtml">One</a></li>""")
        }
        val b = ingest(e.build(tmp.newFolder()))
        assertTrue(b.sentences.isNotEmpty())
        assertInvariants(b)
    }

    @Test fun realGutenbergMeditations() {
        val f = File(System.getenv("BB_MEDITATIONS_EPUB") ?: "/private/tmp/claude-501/-Users-khalid-Documents-Codes-OpenAudiobook/f3601d13-d304-49ab-88e5-240324de0bee/scratchpad/meditations.epub")
        org.junit.Assume.assumeTrue(f.exists())
        val b = ingest(f)
        assertInvariants(b)
        println("MEDITATIONS title=${b.title} authors=${b.authors} lang=${b.language} type=${b.contentType} cover=${b.coverPath != null} sections=${b.sections.size} sentences=${b.sentences.size}")
        b.sections.take(40).forEach { println("  SEC ${it.index} p=${it.parentIndex} L${it.level} ${it.kind} [${it.firstOrdinal}..${it.lastOrdinal}] ${it.title.take(50)}") }
        println("  KINDS " + b.sections.groupingBy { it.kind }.eachCount())
        b.sentences.drop(60).take(5).forEach { println("  S ${it.ordinal} ${it.text.take(100)} | before='${it.before}' after='${it.after}'") }
    }
}
