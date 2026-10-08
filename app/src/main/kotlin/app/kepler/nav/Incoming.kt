package app.kepler.nav

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import app.kepler.core.model.BookId

/** A target from `kepler://book/<id>/s/<ordinal>`. */
data class DeepLink(val bookId: BookId, val ordinal: Int)

/** What an external intent asks the app to do. */
sealed interface Incoming {
    data class Link(val link: DeepLink) : Incoming

    /** An EPUB handed over by "Open with" or "Share"; [uri] is a readable `content://` URI. */
    data class EpubFile(val uri: String) : Incoming
}

/** The parts of an [Intent] that decide what it means, kept free of Android types so the rules are unit-testable. */
data class IntentFacts(
    val action: String?,
    val mimeType: String?,
    val dataScheme: String?,
    val dataHost: String?,
    val dataPath: List<String>,
    val dataString: String?,
    val streamUri: String?,
)

fun parseDeepLink(scheme: String?, host: String?, segments: List<String>): DeepLink? {
    if (scheme != "kepler" || host != "book") return null
    if (segments.size != 3 || segments[1] != "s") return null
    val id = segments[0].toLongOrNull() ?: return null
    val ordinal = segments[2].toIntOrNull() ?: return null
    return DeepLink(id, ordinal.coerceAtLeast(0))
}

private const val EPUB_MIME = "application/epub+zip"
private val OPAQUE_MIMES = setOf("application/octet-stream", "application/zip", "binary/octet-stream")

/** Only `content://` is accepted: other apps cannot hand us a readable `file://` path on modern Android. */
private fun looksLikeEpub(mime: String?, uri: String?): Boolean {
    if (uri == null || !uri.startsWith("content://")) return false
    val m = mime?.lowercase()
    return m == null || m == EPUB_MIME || m in OPAQUE_MIMES || uri.substringBefore('?').lowercase().endsWith(".epub")
}

fun classifyIncoming(f: IntentFacts): Incoming? = when (f.action) {
    Intent.ACTION_VIEW -> parseDeepLink(f.dataScheme, f.dataHost, f.dataPath)?.let(Incoming::Link)
        ?: f.dataString?.takeIf { looksLikeEpub(f.mimeType, it) }?.let(Incoming::EpubFile)
    Intent.ACTION_SEND -> f.streamUri?.takeIf { looksLikeEpub(f.mimeType, it) }?.let(Incoming::EpubFile)
    else -> null
}

fun Intent.toIncoming(): Incoming? {
    val data: Uri? = data
    return classifyIncoming(
        IntentFacts(
            action = action,
            mimeType = type,
            dataScheme = data?.scheme,
            dataHost = data?.host,
            dataPath = data?.pathSegments.orEmpty(),
            dataString = data?.toString(),
            streamUri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)?.toString(),
        ),
    )
}
