@file:OptIn(ExperimentalReadiumApi::class)

package app.kepler.engine.epub.reader

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
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
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
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
        val listeners = buildList {
            if (style.tapZones) add(DirectionalNavigationAdapter(fragment, animatedTransition = true))
            add(object : InputListener {
                override fun onTap(event: TapEvent): Boolean = _events.tryEmit(ReaderEvent.CenterTap)
            })
        }
        listeners.forEach(fragment::addInputListener)
        inputListeners = listeners
        fragment.addDecorationListener(GROUP_HIGHLIGHTS, decorationListener)
        attachScope = scope
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

    fun detach() {
        attachJob?.cancel()
        attachJob = null
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
        if (tapsChanged) {
            inputListeners.firstOrNull { it is DirectionalNavigationAdapter }?.let {
                nav.removeInputListener(it)
                inputListeners = inputListeners - it
            }
            if (style.tapZones) {
                val adapter = DirectionalNavigationAdapter(nav, animatedTransition = true)
                // Edge taps must win over the centre-tap listener, so re-add that one after.
                inputListeners.forEach(nav::removeInputListener)
                inputListeners = listOf(adapter) + inputListeners
                inputListeners.forEach(nav::addInputListener)
            }
        }
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
        /** How long a reflow is given before the held position is restored. */
        private const val SETTLE_MS = 350L
        private const val SETTLE_AFTER_GO_MS = 200L

        private val bandTemplate = HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { d ->
                val t = (d.style as? Decoration.Style.Tinted)?.tint ?: 0
                val a = ((t ushr 24) and 0xFF) / 255.0
                val rgba = "rgba(${(t shr 16) and 0xFF}, ${(t shr 8) and 0xFF}, ${t and 0xFF}, ${"%.3f".format(java.util.Locale.ROOT, a)})"
                """<div class="bb-band" style="background-color: $rgba !important"/>"""
            },
            stylesheet = ".bb-band { margin-left: -2px; padding-right: 4px; border-radius: 2px; box-sizing: border-box; }",
        )

        const val GROUP_HIGHLIGHTS = "highlights"
        const val GROUP_READ_ALONG = "readalong"
        const val GROUP_SEARCH = "search"
    }
}
