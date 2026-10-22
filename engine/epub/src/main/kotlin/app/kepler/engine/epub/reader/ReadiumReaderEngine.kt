@file:OptIn(ExperimentalReadiumApi::class)

package app.kepler.engine.epub.reader

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONTokener
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.css.Color as RsColor
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** Opens EPUB files from app storage as Readium publications. */
class PublicationLoader(context: Context) {
    private val app = context.applicationContext
    private val http = DefaultHttpClient()
    private val assets = AssetRetriever(app.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(app, http, assets, pdfFactory = null))

    /** Failures carry neutral technical messages only. */
    suspend fun open(path: String): Result<Publication> {
        val file = File(path)
        if (!file.canRead()) return Result.failure(IOException("File not found"))
        val asset = assets.retrieve(file).getOrElse { return Result.failure(IOException(it.message)) }
        val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
            asset.close()
            return Result.failure(IOException(it.message))
        }
        return Result.success(publication)
    }
}

/**
 * Restores [EpubNavigatorFragment] after activity recreation. Install it on the host activity
 * before `super.onCreate` with [install]. The live factory is set by the reader while a book is open;
 * without one, Readium's empty placeholder fragment is created.
 */
class ReaderFragmentFactory(private val fallback: FragmentFactory) : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
        if (className == EpubNavigatorFragment::class.java.name) {
            (live ?: EpubNavigatorFragment.createDummyFactory()).instantiate(classLoader, className)
        } else {
            fallback.instantiate(classLoader, className)
        }

    companion object {
        @Volatile internal var live: FragmentFactory? = null

        fun install(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            if (fm.fragmentFactory !is ReaderFragmentFactory) fm.fragmentFactory = ReaderFragmentFactory(fm.fragmentFactory)
        }

        /**
         * Call before `super.onCreate(savedInstanceState)`. After process death no publication is
         * open, so the FragmentManager could only restore Readium's dummy navigator, which crashes as
         * soon as it reports a location. Drop the saved fragment state instead; the reader creates a
         * fresh navigator at the saved position once the book has loaded.
         */
        fun install(activity: FragmentActivity, savedInstanceState: Bundle?) {
            if (live == null && savedInstanceState != null) dropFragmentState(savedInstanceState, depth = 0)
            install(activity)
        }

        private const val FRAGMENTS_KEY = "android:support:fragments"

        private fun dropFragmentState(bundle: Bundle, depth: Int) {
            bundle.remove(FRAGMENTS_KEY)
            if (depth >= 2) return
            for (key in bundle.keySet().toList()) {
                val nested = runCatching { bundle.getBundle(key) }.getOrNull() ?: continue
                dropFragmentState(nested, depth + 1)
            }
        }
    }
}

class ReadiumReaderEngine(val publication: Publication) : ReaderEngine {

    private val navigatorFactory = EpubNavigatorFactory(publication)
    private val linksByHref: Map<String, Link> =
        publication.readingOrder.associateBy { LocatorOrdinalMapper.normalizeHref(it.url().toString()) }

    private var navigator: EpubNavigatorFragment? = null
    private var attachJob: Job? = null
    private var inputListeners: List<InputListener> = emptyList()
    private var style = ReaderStyle()
    private var readAlong = false
    private val doubleTaps = DoubleTapDetector()
    private var pendingTap: Job? = null

    private val tapListener = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            if (!readAlong) return _events.tryEmit(ReaderEvent.CenterTap)
            val p = event.point
            pendingTap?.cancel()
            pendingTap = null
            if (doubleTaps.onTap(p.x, p.y, SystemClock.uptimeMillis())) {
                _events.tryEmit(ReaderEvent.DoubleTap(p.x, p.y))
            } else {
                // Hold the single tap until a second one could no longer arrive.
                val scope = attachScope
                if (scope == null) _events.tryEmit(ReaderEvent.CenterTap)
                else pendingTap = scope.launch { delay(DOUBLE_TAP_MS); _events.tryEmit(ReaderEvent.CenterTap) }
            }
            return true
        }
    }
    private var liveFactory: FragmentFactory? = null
    private val marks = LinkedHashMap<String, List<Decoration>>()

    private val _location = MutableStateFlow<TextAnchor?>(null)
    override val location: StateFlow<TextAnchor?> = _location.asStateFlow()
    private val _events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 16)
    override val events: Flow<ReaderEvent> = _events.asSharedFlow()

    private val decorationListener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean =
            _events.tryEmit(ReaderEvent.MarkTapped(event.group, event.decoration.id))
    }

    private val navListener = object : EpubNavigatorFragment.Listener {
        override fun onExternalLinkActivated(url: AbsoluteUrl) {
            _events.tryEmit(ReaderEvent.ExternalLink(url.toString()))
        }

        override fun shouldFollowInternalLink(
            link: Link,
            context: org.readium.r2.navigator.HyperlinkNavigator.LinkContext?,
        ): Boolean {
            _events.tryEmit(ReaderEvent.LinkJump)
            return true
        }
    }

    /**
     * The factory that creates the navigator fragment. Also registered globally so the fragment
     * survives configuration changes (see [ReaderFragmentFactory]).
     */
    fun fragmentFactory(
        initial: TextAnchor?,
        style: ReaderStyle,
        selectionCallback: ActionMode.Callback?,
    ): FragmentFactory = fragmentFactory({ initial }, style, selectionCallback)

    /**
     * Like the overload above, but [initial] is read when the fragment is instantiated. After a
     * configuration change the FragmentManager re-creates the navigator inside the new activity's
     * `onCreate`, before any composition runs; reading the anchor (and the latest style and
     * selection callback) at that moment keeps the page the reader was on.
     */
    fun fragmentFactory(
        initial: () -> TextAnchor?,
        style: ReaderStyle,
        selectionCallback: ActionMode.Callback?,
    ): FragmentFactory {
        this.style = style
        this.selectionCallback = selectionCallback
        val factory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                navigatorFragmentFactory(initial()).instantiate(classLoader, className)
        }
        liveFactory = factory
        ReaderFragmentFactory.live = factory
        return factory
    }

    private var selectionCallback: ActionMode.Callback? = null

    /** Forwards to the latest host callback, so a restored fragment never talks to a disposed composition. */
    private val forwardingSelectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
            selectionCallback?.onCreateActionMode(mode, menu) ?: false
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
            selectionCallback?.onPrepareActionMode(mode, menu) ?: false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
            selectionCallback?.onActionItemClicked(mode, item) ?: false
        override fun onDestroyActionMode(mode: ActionMode) {
            selectionCallback?.onDestroyActionMode(mode)
        }
    }

    private fun navigatorFragmentFactory(initial: TextAnchor?): FragmentFactory {
        val configuration = EpubNavigatorFragment.Configuration {
            servedAssets = listOf("fonts/.*")
            readiumCssRsProperties = RsProperties(
                selectionBackgroundColor = style.selectionColor?.let { RsColor.Int(it) },
                selectionTextColor = style.selectionInk?.let { RsColor.Int(it) },
            )
            decorationTemplates = HtmlDecorationTemplates.defaultTemplates().copy().apply {
                set(BandStyle::class, bandTemplate)
            }
            selectionActionModeCallback = forwardingSelectionCallback
            // The Compose host pads the navigator around its own chrome and the system bars.
            shouldApplyInsetsPadding = false
            addFontFamilyDeclaration(FontFamily(ReaderFont.LITERATA.css!!)) {
                addFontFace {
                    addSource("fonts/literata.ttf")
                    setFontStyle(FontStyle.NORMAL)
                    setFontWeight(200..900)
                }
            }
        }
        return navigatorFactory.createFragmentFactory(
            initialLocator = initial?.let(::toLocator),
            initialPreferences = style.toPreferences(),
            listener = navListener,
            configuration = configuration,
        )
    }

    /** Binds the hosted fragment. [scope] should be tied to the hosting composition. */
    fun attach(fragment: EpubNavigatorFragment, scope: CoroutineScope) {
        if (fragment === navigator) return
        detach()
        navigator = fragment
        attachScope = scope
        installInputListeners(fragment)
        fragment.addDecorationListener(GROUP_HIGHLIGHTS, decorationListener)
        fragment.view?.addOnLayoutChangeListener(resizeListener)
        attachJob = scope.launch {
            launch { marks.forEach { (group, list) -> fragment.applyDecorations(list, group) } }
            fragment.currentLocator.collectLatest { loc ->
                // While the WebView reflows it reports passing positions (often the resource start).
                if (settling?.isActive == true) return@collectLatest
                publish(fragment, loc)
            }
        }
    }

    private suspend fun publish(fragment: EpubNavigatorFragment, loc: Locator) {
        stableLocator = loc
        val first = runCatching { fragment.firstVisibleElementLocator() }.getOrNull()
            ?.takeIf { it.href.removeFragment() == loc.href.removeFragment() }
        _location.value = loc.toAnchor(textFrom = first ?: loc)
        // A follow can land in a freshly loaded resource, or reflow the one shown: lay the band out again there.
        paragraphTint?.let { t -> runCatching { fragment.evaluateJavascript(paragraphScript(rgba(t))) } }
    }

    private var attachScope: CoroutineScope? = null
    private var stableLocator: Locator? = null
    private var settling: Job? = null

    private val resizeListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        val was = (or - ol) to (ob - ot)
        if (was.first > 0 && was.second > 0 && was != (r - l) to (b - t)) settle()
    }

    /**
     * Readium's paginated WebView can lose its place when it reflows (rotation, window resize, a
     * font size change) and lands near the start of the resource. Hold the last settled position
     * and go back to it once the reflow is done.
     */
    private fun settle() {
        val nav = navigator ?: return
        val target = stableLocator ?: return
        val scope = attachScope ?: return
        settling?.cancel()
        settling = scope.launch {
            delay(SETTLE_MS)
            nav.go(target, animated = false)
            delay(SETTLE_AFTER_GO_MS)
        }
        scope.launch {
            settling?.join()
            if (navigator === nav) publish(nav, nav.currentLocator.value)
        }
    }

    /** Edge taps turn pages unless read-along wants every tap for its double tap; the tap listener always comes last. */
    private fun installInputListeners(nav: EpubNavigatorFragment) {
        inputListeners.forEach(nav::removeInputListener)
        inputListeners = buildList {
            if (style.tapZones && !readAlong) add(DirectionalNavigationAdapter(nav, animatedTransition = true))
            add(tapListener)
        }
        inputListeners.forEach(nav::addInputListener)
    }

    fun detach() {
        attachJob?.cancel()
        attachJob = null
        pendingTap?.cancel()
        pendingTap = null
        settling?.cancel()
        settling = null
        attachScope = null
        navigator?.let { nav ->
            nav.view?.removeOnLayoutChangeListener(resizeListener)
            inputListeners.forEach(nav::removeInputListener)
            nav.removeDecorationListener(decorationListener)
        }
        inputListeners = emptyList()
        navigator = null
    }

    /** Releases the publication. The engine is unusable afterwards. */
    fun close() {
        detach()
        if (ReaderFragmentFactory.live === liveFactory) ReaderFragmentFactory.live = null
        publication.close()
    }

    override fun go(anchor: TextAnchor, animated: Boolean): Boolean =
        navigator?.go(toLocator(anchor), animated) ?: false

    override fun goForward(animated: Boolean): Boolean = navigator?.goForward(animated) ?: false
    override fun goBackward(animated: Boolean): Boolean = navigator?.goBackward(animated) ?: false

    override fun submit(style: ReaderStyle) {
        val tapsChanged = style.tapZones != this.style.tapZones
        val changed = style != this.style
        this.style = style
        val nav = navigator ?: return
        nav.submitPreferences(style.toPreferences())
        if (changed) settle()
        if (tapsChanged) installInputListeners(nav)
    }

    override fun setReadAlong(on: Boolean) {
        if (on == readAlong) return
        readAlong = on
        if (!on) { pendingTap?.cancel(); pendingTap = null }
        navigator?.let(::installInputListeners)
    }

    override suspend fun markReadAlongParagraph(tint: Int?) {
        paragraphTint = tint
        val nav = navigator ?: return
        runCatching { nav.evaluateJavascript(paragraphScript(tint?.let(::rgba))) }
    }

    /** The paragraph band's tint while read-along is painted; re-laid whenever the page settles somewhere new. */
    private var paragraphTint: Int? = null

    override suspend fun blockTextAt(x: Float, y: Float): String? {
        val nav = navigator ?: return null
        val raw = runCatching { nav.evaluateJavascript(blockTextScript(x, y)) }.getOrNull() ?: return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    override suspend fun applyMarks(group: String, marks: List<TextMark>) {
        val decorations = marks.map { m ->
            val style: Decoration.Style = when {
                m.band -> BandStyle(m.tint)
                m.underline -> Decoration.Style.Underline(m.tint)
                else -> Decoration.Style.Highlight(m.tint)
            }
            Decoration(id = m.id, locator = toLocator(m.anchor), style = style)
        }
        this.marks[group] = decorations
        navigator?.applyDecorations(decorations, group)
    }

    override suspend fun selection(): TextSelection? {
        val sel = navigator?.currentSelection() ?: return null
        return TextSelection(sel.locator.toAnchor(textFrom = sel.locator), sel.rect?.top, sel.rect?.bottom)
    }

    override fun clearSelection() {
        navigator?.clearSelection()
    }

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val iterator = publication.search(query) ?: return emptyList()
        val hits = ArrayList<SearchHit>()
        try {
            while (hits.size < limit) {
                val page = iterator.next().getOrNull() ?: break
                page.locators.forEach { hits += SearchHit(it.toAnchor(textFrom = it), it.title) }
            }
        } finally {
            iterator.close()
        }
        return hits.take(limit)
    }

    private fun toLocator(anchor: TextAnchor): Locator {
        val link = linksByHref[LocatorOrdinalMapper.normalizeHref(anchor.href)]
        val href = link?.url() ?: Url(anchor.href) ?: Url.fromDecodedPath(anchor.href)
            ?: publication.readingOrder.first().url()
        return Locator(
            href = href,
            mediaType = link?.mediaType ?: MediaType.XHTML,
            locations = Locator.Locations(
                progression = anchor.progression,
                totalProgression = anchor.totalProgression,
            ),
            text = Locator.Text(before = anchor.before, highlight = anchor.highlight, after = anchor.after),
        )
    }

    private fun Locator.toAnchor(textFrom: Locator) = TextAnchor(
        href = href.toString(),
        progression = locations.progression,
        before = textFrom.text.before,
        highlight = textFrom.text.highlight,
        after = textFrom.text.after,
        totalProgression = locations.totalProgression,
        position = locations.position,
    )

    private fun ReaderStyle.toPreferences(): EpubPreferences = EpubPreferences(
        backgroundColor = Color(pageColor),
        textColor = Color(inkColor),
        theme = when (theme) {
            ReaderThemeId.SEPIA -> Theme.SEPIA
            else -> if (isDark) Theme.DARK else Theme.LIGHT
        },
        fontFamily = font.css?.let(::FontFamily),
        fontSize = fontScale,
        lineHeight = if (publisherStyles) null else lineHeight,
        pageMargins = pageMargins,
        publisherStyles = publisherStyles,
        scroll = scroll,
        textAlign = if (publisherStyles) null else if (justify) TextAlign.JUSTIFY else TextAlign.START,
    )

    /** The read-along sentence band: like a highlight, but honouring the tint's own alpha. */
    class BandStyle(override val tint: Int) : Decoration.Style, Decoration.Style.Tinted {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(tint)
        override fun equals(other: Any?) = other is BandStyle && other.tint == tint
        override fun hashCode() = tint

        companion object {
            @JvmField val CREATOR = object : Parcelable.Creator<BandStyle> {
                override fun createFromParcel(source: Parcel) = BandStyle(source.readInt())
                override fun newArray(size: Int) = arrayOfNulls<BandStyle>(size)
            }
        }
    }

    companion object {
        /** How long a single tap waits for a second one while read-along is on. */
        private const val DOUBLE_TAP_MS = 260L

        /** How long a reflow is given before the held position is restored. */
        private const val SETTLE_MS = 350L
        private const val SETTLE_AFTER_GO_MS = 200L

        private fun rgba(d: Decoration): String = rgba((d.style as? Decoration.Style.Tinted)?.tint ?: 0)

        private fun rgba(t: Int): String {
            val a = ((t ushr 24) and 0xFF) / 255.0
            return "rgba(${(t shr 16) and 0xFF}, ${(t shr 8) and 0xFF}, ${t and 0xFF}, ${"%.3f".format(java.util.Locale.ROOT, a)})"
        }

        // The spoken sentence: r4 boxes hugging each line of words.
        private val bandTemplate = HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { d -> """<div class="kp-sentence" style="background-color: ${rgba(d)} !important"/>""" },
            stylesheet = ".kp-sentence { margin: -1px 0 0 -3px; padding: 1px 3px; border-radius: 4px; box-sizing: content-box; }",
        )

        /**
         * Lays a rounded band behind the block element (paragraph, list item...) that holds the read-along sentence
         * decoration, or clears it when [rgba] is null. One box per fragment of the block (a paragraph split across
         * page columns gets one per column, never a box spanning both), padded 10 x 6 px past the text so the band
         * breathes without reflowing anything. Drawn first in the body so the sentence mark sits on top.
         */
        private fun paragraphScript(rgba: String?): String = """
            (function(tint) {
              // Readium may still have the sentence update queued behind this script: lay out now, and once more
              // when that has landed.
              clearTimeout(window.kpParagraphTimer);
              window.kpParagraphTimer = setTimeout(function() { window.kpLayParagraph(tint); }, 180);
              window.kpLayParagraph = window.kpLayParagraph || function(tint) {
                var old = document.getElementById('kp-paragraph-layer');
                if (old) old.remove();
                if (!tint) return 0;
                var g = window.readium && readium.getDecorations ? readium.getDecorations('$GROUP_READ_ALONG') : null;
                var item = g && g.items && g.items.length ? g.items[0] : null;
                if (!item || !item.range) return 0;
                // The matched range can start in the whitespace that ends the previous paragraph: anchor on the
                // first text inside it that has words.
                var range = item.range, root = range.commonAncestorContainer, n = null;
                if (root.nodeType === 3) n = root; else {
                  var walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                  while (walk.nextNode()) {
                    var t = walk.currentNode;
                    if (range.intersectsNode(t) && /\S/.test(t.data.substring(t === range.startContainer ? range.startOffset : 0))) { n = t; break; }
                  }
                }
                var el = n ? n.parentElement : null;
                var block = el ? el.closest('p, li, blockquote, h1, h2, h3, h4, h5, h6, dd, dt, figcaption, pre, td') : null;
                if (!block || (block.textContent || '').length > 6000) return 0;
                var layer = document.createElement('div');
                layer.id = 'kp-paragraph-layer';
                layer.style.cssText = 'position: absolute; left: 0; top: 0; width: 0; height: 0; pointer-events: none;';
                var rects = block.getClientRects();
                var sx = window.scrollX, sy = window.scrollY;
                for (var i = 0; i < rects.length; i++) {
                  var r = rects[i];
                  if (r.width < 1 || r.height < 1) continue;
                  var d = document.createElement('div');
                  // ReadiumCSS caps max-width at 100% of the (zero-width) layer, so lift the cap.
                  d.style.cssText = 'position: absolute; pointer-events: none; max-width: none !important; margin: 0; padding: 0;' +
                    ' border-radius: 8px; background-color: ' + tint +
                    ' !important; left: ' + (r.left + sx - 10) + 'px; top: ' + (r.top + sy - 6) + 'px; width: ' +
                    (r.width + 20) + 'px; height: ' + (r.height + 12) + 'px;';
                  layer.appendChild(d);
                }
                document.body.insertBefore(layer, document.body.firstChild);
                return rects.length;
              };
              return window.kpLayParagraph(tint);
            })(${if (rgba == null) "null" else "'$rgba'"})
        """.trimIndent()

        /** The text of the block under a navigator point; the point is in device px, the DOM wants CSS px. */
        private fun blockTextScript(x: Float, y: Float): String = """
            (function(px, py) {
              var r = window.devicePixelRatio || 1;
              var range = document.caretRangeFromPoint(px / r, py / r);
              if (!range) return null;
              var n = range.startContainer;
              var el = n.nodeType === 1 ? n : n.parentElement;
              if (!el) return null;
              var block = el.closest('p, li, blockquote, h1, h2, h3, h4, h5, h6, dd, dt, figcaption, pre, td') || el;
              var t = (block.textContent || '').replace(/\s+/g, ' ').trim();
              return t.length ? t.substring(0, 400) : null;
            })($x, $y)
        """.trimIndent()

        const val GROUP_HIGHLIGHTS = "highlights"
        const val GROUP_READ_ALONG = "readalong"
        const val GROUP_SEARCH = "search"
    }
}
