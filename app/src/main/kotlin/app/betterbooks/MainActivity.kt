package app.betterbooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.FragmentActivity
import app.betterbooks.engine.epub.reader.ReaderFragmentFactory
import app.betterbooks.feature.reader.ReaderKeys
import app.betterbooks.nav.AppRoot
import app.betterbooks.nav.DeepLink
import app.betterbooks.nav.parseDeepLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/** The single activity. A [FragmentActivity] because the Readium navigator is a fragment. */
class MainActivity : FragmentActivity() {

    private val deepLinks = MutableSharedFlow<DeepLink>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var pendingDocument: CompletableDeferred<Uri?>? = null

    private val createDocument = registerForActivityResult(CreateDocumentContract()) { uri ->
        pendingDocument?.complete(uri)
        pendingDocument = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ReaderFragmentFactory.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BetterBooksApp).container
        container.documentWriter = ::writeDocument
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AppRoot(container, deepLinks, onFinish = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        val container = (application as BetterBooksApp).container
        if (container.documentWriter == ::writeDocument) container.documentWriter = null
        pendingDocument?.complete(null)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = ReaderKeys.dispatch(event) || super.dispatchKeyEvent(event)

    private fun handleIntent(intent: Intent?) {
        parseDeepLink(intent?.data)?.let { deepLinks.tryEmit(it) }
    }

    private suspend fun writeDocument(name: String, mime: String, text: String) {
        val deferred = CompletableDeferred<Uri?>()
        pendingDocument?.complete(null)
        pendingDocument = deferred
        createDocument.launch(name to mime)
        val uri = deferred.await() ?: throw IllegalStateException("cancelled")
        withContext(Dispatchers.IO) {
            val out = contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file")
            out.use { it.write(text.toByteArray()) }
        }
    }

    /** ACTION_CREATE_DOCUMENT with the MIME type chosen per call (the stock contract fixes it up front). */
    private class CreateDocumentContract : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.second)
                .putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}
