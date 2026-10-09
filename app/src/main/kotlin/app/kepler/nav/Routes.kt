package app.kepler.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.kepler.core.model.BookId
import app.kepler.core.model.ReaderMode
import app.kepler.feature.study.LearnTab

enum class Tab { HOME, LIBRARY, NOTEBOOK }

/** The two faces of the one read + listen destination (decision #112). */
enum class ReaderFace { LISTEN, READ }

/** Destinations of the state-based back stack. Each encodes to one string so the stack survives recreation. */
sealed interface Route {
    data object Onboarding : Route
    data class Tabs(val tab: Tab) : Route
    data class Hub(val bookId: BookId) : Route
    /**
     * Read + listen, one destination (#112). [face] picks the Listen face (the full player) or the Read face;
     * switching it keeps the same back-stack entry. [nonce] distinguishes a reader opened afresh at a new
     * position from the same reader switching mode or face.
     */
    data class Reader(
        val bookId: BookId,
        val mode: ReaderMode = ReaderMode.READ,
        val nonce: Int = 0,
        val face: ReaderFace = ReaderFace.READ,
    ) : Route
    data class Learn(val bookId: BookId, val tab: LearnTab = LearnTab.ASK) : Route
    data object Review : Route
    data class Setup(val bookId: BookId) : Route
    data object Settings : Route

    fun encode(): String = when (this) {
        Onboarding -> "onboarding"
        is Tabs -> "tabs/${tab.name}"
        is Hub -> "hub/$bookId"
        is Reader -> "reader/$bookId/${mode.name}/$nonce/${face.name}"
        is Learn -> "learn/$bookId/${tab.name}"
        Review -> "review"
        is Setup -> "setup/$bookId"
        Settings -> "settings"
    }

    companion object {
        /** The old player route is the Listen face of the reader now (#112). */
        @Suppress("FunctionName")
        fun Player(bookId: BookId): Reader = Reader(bookId, face = ReaderFace.LISTEN)

        fun decode(s: String): Route? = runCatching {
            val p = s.split('/')
            when (p[0]) {
                "onboarding" -> Onboarding
                "tabs" -> Tabs(Tab.valueOf(p[1]))
                "hub" -> Hub(p[1].toLong())
                "reader" -> Reader(
                    p[1].toLong(), ReaderMode.valueOf(p[2]), p.getOrNull(3)?.toInt() ?: 0,
                    p.getOrNull(4)?.let(ReaderFace::valueOf) ?: ReaderFace.READ,
                )
                // Saved by builds that still had a separate player route.
                "player" -> Reader(p[1].toLong(), face = ReaderFace.LISTEN)
                "learn" -> Learn(p[1].toLong(), LearnTab.valueOf(p[2]))
                "review" -> Review
                "setup" -> Setup(p[1].toLong())
                "settings" -> Settings
                else -> null
            }
        }.getOrNull()
    }
}

class BackStack(initial: List<Route>) {
    val entries: SnapshotStateList<Route> = mutableStateListOf<Route>().apply { addAll(initial) }
    val top: Route get() = entries.last()

    fun push(route: Route) {
        if (entries.lastOrNull() != route) entries.add(route)
    }

    /** Returns false when there is nothing left to pop (the activity should finish). */
    fun pop(): Boolean {
        if (entries.size <= 1) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /**
     * Opens the reader for a book at a freshly saved position. A reader of the same book already on top is
     * replaced (bumping [Route.Reader.nonce] so it reloads the position); otherwise the reader is pushed, so
     * Back from a passage opened out of Learn or the Notebook returns there.
     */
    fun showReader(route: Route.Reader) {
        val top = entries.lastOrNull()
        if (top is Route.Reader && top.bookId == route.bookId) {
            entries[entries.lastIndex] = route.copy(nonce = top.nonce + 1)
        } else {
            push(route)
        }
    }

    /**
     * Shows the Listen face of a book. If that book's reader is already on top it just turns to the Listen face
     * (same entry, no navigation); otherwise the Listen face is pushed.
     */
    fun showListen(bookId: BookId) {
        val top = entries.lastOrNull()
        if (top is Route.Reader && top.bookId == bookId) {
            entries[entries.lastIndex] = top.copy(face = ReaderFace.LISTEN)
        } else {
            push(Route.Reader(bookId, face = ReaderFace.LISTEN))
        }
    }

    /** Turns the reader on top to [face] in place (same entry). */
    fun setFace(bookId: BookId, face: ReaderFace, mode: ReaderMode? = null) {
        val top = entries.lastOrNull()
        if (top is Route.Reader && top.bookId == bookId) {
            entries[entries.lastIndex] = top.copy(face = face, mode = mode ?: top.mode)
        }
    }

    fun replaceTop(route: Route) {
        entries[entries.lastIndex] = route
    }

    fun resetTo(route: Route) {
        entries.clear()
        entries.add(route)
    }

    companion object {
        val Saver: Saver<BackStack, ArrayList<String>> = Saver(
            save = { ArrayList(it.entries.map(Route::encode)) },
            restore = { saved -> BackStack(saved.mapNotNull(Route::decode).ifEmpty { listOf(Route.Tabs(Tab.HOME)) }) },
        )
    }
}
