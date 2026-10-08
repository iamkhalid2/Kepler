package app.kepler.nav

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.kepler.core.model.BookId
import app.kepler.core.model.ReaderMode
import app.kepler.feature.study.LearnTab

enum class Tab { HOME, LIBRARY, NOTEBOOK }

/** Destinations of the state-based back stack. Each encodes to one string so the stack survives recreation. */
sealed interface Route {
    data object Onboarding : Route
    data class Tabs(val tab: Tab) : Route
    data class Hub(val bookId: BookId) : Route
    data class Reader(val bookId: BookId, val mode: ReaderMode = ReaderMode.READ) : Route
    data class Player(val bookId: BookId) : Route
    data class Learn(val bookId: BookId, val tab: LearnTab = LearnTab.ASK) : Route
    data object Review : Route
    data class Setup(val bookId: BookId) : Route
    data object Settings : Route

    fun encode(): String = when (this) {
        Onboarding -> "onboarding"
        is Tabs -> "tabs/${tab.name}"
        is Hub -> "hub/$bookId"
        is Reader -> "reader/$bookId/${mode.name}"
        is Player -> "player/$bookId"
        is Learn -> "learn/$bookId/${tab.name}"
        Review -> "review"
        is Setup -> "setup/$bookId"
        Settings -> "settings"
    }

    companion object {
        fun decode(s: String): Route? = runCatching {
            val p = s.split('/')
            when (p[0]) {
                "onboarding" -> Onboarding
                "tabs" -> Tabs(Tab.valueOf(p[1]))
                "hub" -> Hub(p[1].toLong())
                "reader" -> Reader(p[1].toLong(), ReaderMode.valueOf(p[2]))
                "player" -> Player(p[1].toLong())
                "learn" -> Learn(p[1].toLong(), LearnTab.valueOf(p[2]))
                "review" -> Review
                "setup" -> Setup(p[1].toLong())
                "settings" -> Settings
                else -> null
            }
        }.getOrNull()
    }
}

/** A target from `kepler://book/<id>/s/<ordinal>`. */
data class DeepLink(val bookId: BookId, val ordinal: Int)

fun parseDeepLink(uri: Uri?): DeepLink? {
    if (uri == null || uri.scheme != "kepler" || uri.host != "book") return null
    val seg = uri.pathSegments
    if (seg.size < 3 || seg[1] != "s") return null
    val id = seg[0].toLongOrNull() ?: return null
    val ordinal = seg[2].toIntOrNull() ?: return null
    return DeepLink(id, ordinal.coerceAtLeast(0))
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
