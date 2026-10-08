package app.kepler.nav

import android.content.Intent
import app.kepler.core.model.ReaderMode
import app.kepler.feature.study.LearnTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLogicTest {

    private fun facts(
        action: String? = Intent.ACTION_VIEW,
        mime: String? = null,
        scheme: String? = null,
        host: String? = null,
        path: List<String> = emptyList(),
        data: String? = null,
        stream: String? = null,
    ) = IntentFacts(action, mime, scheme, host, path, data, stream)

    // ---- deep links

    @Test fun deepLinkParses() {
        assertEquals(DeepLink(42, 1234), parseDeepLink("kepler", "book", listOf("42", "s", "1234")))
    }

    @Test fun deepLinkRejectsWrongShape() {
        assertNull(parseDeepLink("https", "book", listOf("42", "s", "1")))
        assertNull(parseDeepLink("kepler", "shelf", listOf("42", "s", "1")))
        assertNull(parseDeepLink("kepler", "book", listOf("42")))
        assertNull(parseDeepLink("kepler", "book", listOf("42", "p", "1")))
        assertNull(parseDeepLink("kepler", "book", listOf("abc", "s", "1")))
        assertNull(parseDeepLink("kepler", "book", listOf("42", "s", "x")))
        assertNull(parseDeepLink("kepler", "book", listOf("42", "s", "1", "extra")))
        assertNull(parseDeepLink(null, null, emptyList()))
    }

    @Test fun deepLinkClampsNegativeOrdinal() {
        assertEquals(DeepLink(7, 0), parseDeepLink("kepler", "book", listOf("7", "s", "-5")))
    }

    // ---- intent classification

    @Test fun viewDeepLink() {
        val f = facts(scheme = "kepler", host = "book", path = listOf("3", "s", "9"), data = "kepler://book/3/s/9")
        assertEquals(Incoming.Link(DeepLink(3, 9)), classifyIncoming(f))
    }

    @Test fun viewEpubByMime() {
        val f = facts(mime = "application/epub+zip", scheme = "content", data = "content://docs/doc/12")
        assertEquals(Incoming.EpubFile("content://docs/doc/12"), classifyIncoming(f))
    }

    @Test fun viewOpaqueTypeWithEpubName() {
        val f = facts(mime = "application/octet-stream", scheme = "content", data = "content://dl/My%20Book.epub")
        assertEquals(Incoming.EpubFile("content://dl/My%20Book.epub"), classifyIncoming(f))
    }

    @Test fun viewOtherTypeIsIgnored() {
        assertNull(classifyIncoming(facts(mime = "application/pdf", scheme = "content", data = "content://dl/a.pdf")))
        assertNull(classifyIncoming(facts(mime = "text/html", scheme = "https", data = "https://example.com/a.epub")))
    }

    @Test fun fileSchemeIsNeverImported() {
        assertNull(classifyIncoming(facts(mime = "application/epub+zip", scheme = "file", data = "file:///sdcard/a.epub")))
    }

    @Test fun sendEpub() {
        val f = facts(action = Intent.ACTION_SEND, mime = "application/epub+zip", stream = "content://x/1")
        assertEquals(Incoming.EpubFile("content://x/1"), classifyIncoming(f))
    }

    @Test fun sendWithoutStreamOrWrongTypeIsIgnored() {
        assertNull(classifyIncoming(facts(action = Intent.ACTION_SEND, mime = "application/epub+zip")))
        assertNull(classifyIncoming(facts(action = Intent.ACTION_SEND, mime = "text/plain", stream = "content://x/1")))
    }

    @Test fun otherActionsAreIgnored() {
        assertNull(classifyIncoming(facts(action = Intent.ACTION_MAIN)))
        assertNull(classifyIncoming(facts(action = null)))
    }

    // ---- routes

    @Test fun routesRoundTrip() {
        val routes = listOf(
            Route.Onboarding,
            Route.Tabs(Tab.NOTEBOOK),
            Route.Hub(5),
            Route.Reader(5, ReaderMode.READ_LISTEN, 3),
            Route.Player(5),
            Route.Learn(5, LearnTab.RECAP),
            Route.Review,
            Route.Setup(5),
            Route.Settings,
        )
        routes.forEach { assertEquals(it, Route.decode(it.encode())) }
    }

    @Test fun oldReaderEncodingStillDecodes() {
        assertEquals(Route.Reader(5, ReaderMode.READ, 0), Route.decode("reader/5/READ"))
    }

    @Test fun garbageDoesNotDecode() {
        assertNull(Route.decode("nope"))
        assertNull(Route.decode("hub/xyz"))
        assertNull(Route.decode("tabs/ELSEWHERE"))
    }

    // ---- back stack

    @Test fun popStopsAtRoot() {
        val s = BackStack(listOf(Route.Tabs(Tab.HOME), Route.Hub(1)))
        assertTrue(s.pop())
        assertFalse(s.pop())
        assertEquals(Route.Tabs(Tab.HOME), s.top)
    }

    @Test fun showReaderReplacesReaderOnTopAndBumpsNonce() {
        val s = BackStack(listOf(Route.Tabs(Tab.HOME), Route.Hub(1), Route.Reader(1, ReaderMode.READ_LISTEN, 4)))
        s.showReader(Route.Reader(1))
        assertEquals(listOf(Route.Tabs(Tab.HOME), Route.Hub(1), Route.Reader(1, ReaderMode.READ, 5)), s.entries.toList())
    }

    @Test fun showReaderPushesWhenReaderIsNotOnTop() {
        val s = BackStack(listOf(Route.Tabs(Tab.HOME), Route.Reader(1), Route.Learn(1)))
        s.showReader(Route.Reader(1))
        assertEquals(listOf(Route.Tabs(Tab.HOME), Route.Reader(1), Route.Learn(1), Route.Reader(1)), s.entries.toList())
    }

    @Test fun showReaderPushesForAnotherBook() {
        val s = BackStack(listOf(Route.Tabs(Tab.HOME), Route.Reader(2)))
        s.showReader(Route.Reader(1))
        assertEquals(listOf(Route.Tabs(Tab.HOME), Route.Reader(2), Route.Reader(1)), s.entries.toList())
    }

    @Test fun resetKeepsOnlyTarget() {
        val s = BackStack(listOf(Route.Tabs(Tab.HOME), Route.Hub(1)))
        s.resetTo(Route.Tabs(Tab.LIBRARY))
        assertEquals(listOf<Route>(Route.Tabs(Tab.LIBRARY)), s.entries.toList())
    }
}
