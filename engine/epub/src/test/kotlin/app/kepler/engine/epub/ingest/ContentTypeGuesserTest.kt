package app.kepler.engine.epub.ingest

import app.kepler.core.model.ContentType
import org.junit.Test
import org.junit.Assert.assertEquals

class ContentTypeGuesserTest {
    private fun stats(dialogue: Int = 0, structural: Int = 0) = ContentStats(
        paragraphs = 1000, dialogueParagraphs = dialogue, words = 100_000,
        structuralBlocks = structural, totalBlocks = 1000, citationMarkers = 0, noteBlocks = 0,
    )

    @Test fun dialogueLightNovelWithoutSubjectsIsNotNonfiction() {
        assertEquals(ContentType.MIXED, ContentTypeGuesser.guess(emptyList(), stats(dialogue = 20)))
    }

    @Test fun clearNonfictionStaysNonfiction() {
        assertEquals(ContentType.NONFICTION, ContentTypeGuesser.guess(listOf("Business"), stats(dialogue = 10)))
    }

    @Test fun dialogueHeavyIsFiction() {
        assertEquals(ContentType.FICTION, ContentTypeGuesser.guess(emptyList(), stats(dialogue = 400)))
    }
}
