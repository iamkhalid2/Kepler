package app.kepler.feature.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kepler.core.designsystem.CoverRequest
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpMotion
import app.kepler.core.designsystem.KpToastHost
import app.kepler.core.designsystem.LocalBbPalette
import app.kepler.core.designsystem.SilkRibbon
import app.kepler.core.designsystem.paperPalette
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.designsystem.rememberRibbonSwing
import app.kepler.core.model.BookId
import app.kepler.core.model.PlaybackController
import app.kepler.engine.epub.reader.ReaderEvent
import app.kepler.engine.epub.reader.ReaderFragmentFactory
import app.kepler.engine.epub.reader.ReaderStyle
import app.kepler.engine.epub.reader.ReadiumReaderEngine
import app.kepler.engine.epub.reader.compositeOver
import app.kepler.feature.player.ListenFace
import app.kepler.feature.player.PlaybackDock
import app.kepler.feature.player.select
import app.kepler.feature.player.selectInfo
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * The one read + listen screen for [bookId] (#112). The Read face (the Readium reader) is always composed; the
 * Listen face (the full player) lies over it and crossfades in and out, so switching faces never reloads the page
 * and both stay on the same sentence ordinal: while narration runs, the hidden page follows the spoken sentence.
 *
 * Host requirements: the activity must be a [FragmentActivity] (the Readium navigator is a fragment), should
 * call [ReaderFragmentFactory.install] before `super.onCreate` so the navigator survives recreation, and should
 * forward volume keys through [ReaderKeys.dispatch].
 *
 * [onFaceChange] turns the screen to the Listen face (true) or the Read face (false) in place. [onListenStart]
 * runs before this screen starts narration (the host asks for the notification permission). [onSectionChange]
 * fires with the current ordinal whenever the reader or the voice enters another section.
 */
@Composable
fun ReaderRoute(
    deps: ReaderDeps,
    playback: PlaybackController,
    bookId: BookId,
    listenFace: Boolean,
    onFaceChange: (listen: Boolean) -> Unit,
    onListenStart: () -> Unit,
    onSectionChange: (ordinal: Int) -> Unit,
    onExplain: (ordinal: Int, text: String) -> Unit,
    onAsk: (ordinal: Int, text: String) -> Unit,
    onCard: (ordinal: Int, text: String) -> Unit,
    onDefine: (ordinal: Int, term: String) -> Unit,
    onOpenLearn: () -> Unit,
    onVoice: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReaderViewModel = viewModel(key = "reader-$bookId") {
        ReaderViewModel(context.applicationContext, deps, bookId)
    }
    val load by vm.load.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val book by vm.book.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val audioHere by playback.select { it.bookId == bookId }
    val spoken by playback.select { if (it.bookId == bookId) it.ordinal else null }
    val reduced = rememberReducedMotion()
    var toast by remember { mutableStateOf<String?>(null) }

    // Until the book row arrives, a book that is already playing is described by the player (in memory), so the
    // shared cover from the mini player lands on the first frame.
    val playingCover by playback.selectInfo {
        if (it.bookId == bookId) CoverRequest(bookId, it.bookTitle.orEmpty(), it.author.orEmpty(), it.coverPath) else null
    }
    val cover = remember(book, playingCover) { book?.let { CoverRequest(it.id, it.title, it.authors, it.coverPath) } ?: playingCover }

    // Opened on the Listen face for a book that is not playing: start it (once per entry, not on rotation).
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            if (listenFace && playback.state.value.bookId != bookId) {
                onListenStart()
                playback.play(bookId)
            }
        }
    }
    SideEffect { vm.setSpoken(spoken) }
    LaunchedEffect(audioHere) { vm.setListening(audioHere) }

    /** Starts narration at [ordinal], reusing the loaded book when it is this one. */
    fun playFrom(ordinal: Int?) {
        val st = playback.state.value
        if (st.bookId == bookId && ordinal != null) {
            playback.seekToSentence(ordinal)
            if (!st.playing) playback.resume()
        } else if (st.bookId == bookId) {
            if (!st.playing) playback.resume()
        } else {
            onListenStart()
            playback.play(bookId, ordinal)
        }
    }

    fun showListen() {
        if (playback.state.value.bookId != bookId) playFrom(vm.ordinal.value)
        onFaceChange(true)
    }

    fun showRead() {
        vm.reattach()
        spoken?.let(vm::follow)
        onFaceChange(false)
    }

    val savedLabel = stringResource(R.string.reader_bookmark_saved)
    val removedLabel = stringResource(R.string.reader_bookmark_removed)
    val spokenMarked = spoken?.let { o -> bookmarks.any { it.ordinal in o - ReaderViewModel.BOOKMARK_SLACK..o + ReaderViewModel.BOOKMARK_SLACK } } == true

    val pageOrdinal by vm.ordinal.collectAsStateWithLifecycle()
    val pageChapter = (load as? ReaderLoad.Ready)?.index?.let { idx -> pageOrdinal?.let { idx.sectionAt(it)?.title } }

    // Once the Listen face has fully faded in, the page under it stops drawing (it still lays out and follows).
    var covered by remember { mutableStateOf(listenFace) }
    LaunchedEffect(listenFace, reduced) {
        if (listenFace) {
            delay((if (reduced) KpMotion.REDUCED_MS else KpMotion.FACE_SWITCH_MS) + 40L)
            covered = true
        } else {
            covered = false
        }
    }

    val page = Color(style.pageColor)
    Box(Modifier.fillMaxSize().background(if (listenFace) Kp.palette.bg else page)) {
        when (val l = load) {
            ReaderLoad.Loading -> ReaderLoading(page, Color(style.inkColor), style.isDark)
            is ReaderLoad.Failed -> if (!listenFace) ReaderFailed(page, Color(style.inkColor), onClose)
            is ReaderLoad.Ready -> ReaderScreen(
                vm = vm, ready = l, playback = playback, audioHere = audioHere, spoken = spoken,
                visible = !listenFace, covered = covered, cover = cover,
                onShowListen = ::showListen,
                onPlayFrom = { o -> playFrom(o) },
                onSectionChange = onSectionChange,
                onExplain = onExplain, onAsk = onAsk, onCard = onCard, onDefine = onDefine,
                onOpenLearn = onOpenLearn, onClose = onClose,
                onToast = { toast = it },
            )
        }

        AnimatedVisibility(
            listenFace,
            enter = fadeIn(tween(if (reduced) KpMotion.REDUCED_MS else KpMotion.FACE_SWITCH_MS)),
            exit = fadeOut(tween(if (reduced) KpMotion.REDUCED_MS else KpMotion.FACE_SWITCH_MS)),
        ) {
            BackHandler(onBack = onClose)
            ListenFace(
                controller = playback,
                bookId = bookId,
                cover = cover,
                title = book?.title ?: cover?.title.orEmpty(),
                author = book?.authors ?: cover?.author.orEmpty(),
                chapter = pageChapter,
                bookmarked = spokenMarked,
                onBookmark = {
                    val o = playback.state.value.ordinal
                    toast = if (spokenMarked) removedLabel else savedLabel
                    vm.toggleBookmark(o, around = true)
                },
                onRead = ::showRead,
                onLearn = onOpenLearn,
                onVoice = onVoice,
                onClose = onClose,
            )
        }

        KpToastHost(
            toast, { toast = null },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (listenFace) 88.dp else if (audioHere) 200.dp else 16.dp),
        )
    }
}

/** The web content's selection: the palette's selection mark composited over the page, with the page ink. */
private fun ReaderStyle.withPaletteSelection(selection: Color): ReaderStyle =
    copy(selectionColor = compositeOver(selection.toArgb(), pageColor), selectionInk = inkColor)

private sealed interface Sheet {
    data object None : Sheet
    data object Contents : Sheet
    data object Search : Sheet
    data object Typography : Sheet
    data class Note(val id: Long) : Sheet
}

@Composable
private fun ReaderScreen(
    vm: ReaderViewModel,
    ready: ReaderLoad.Ready,
    playback: PlaybackController,
    audioHere: Boolean,
    spoken: Int?,
    visible: Boolean,
    covered: Boolean,
    cover: CoverRequest?,
    onShowListen: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    onSectionChange: (Int) -> Unit,
    onExplain: (Int, String) -> Unit,
    onAsk: (Int, String) -> Unit,
    onCard: (Int, String) -> Unit,
    onDefine: (Int, String) -> Unit,
    onOpenLearn: () -> Unit,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
) {
    val p = Kp.palette
    val context = LocalContext.current
    val density = LocalDensity.current
    val reduced = rememberReducedMotion()
    val engine = ready.engine
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val baseStyle by vm.style.collectAsStateWithLifecycle()
    val selectionMark = remember(p.seed, baseStyle.pageColor, baseStyle.inkColor, baseStyle.isDark) {
        paperPalette(p.seed, Color(baseStyle.pageColor), Color(baseStyle.inkColor), baseStyle.isDark).materials.marks.selection
    }
    val style = remember(baseStyle, selectionMark) { baseStyle.withPaletteSelection(selectionMark) }
    val local by vm.localPrefs.collectAsStateWithLifecycle()
    val ordinal by vm.ordinal.collectAsStateWithLifecycle()
    val detached by vm.detached.collectAsStateWithLifecycle()
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val location by engine.location.collectAsStateWithLifecycle()

    var chrome by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet>(Sheet.None) }
    var selectionActive by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<ReaderSelection?>(null) }
    var navigatorTop by remember { mutableFloatStateOf(0f) }
    var dockPx by remember { mutableIntStateOf(0) }

    val page = Color(style.pageColor)
    val ink = Color(style.inkColor)
    // Chrome, sheets and the dock are cut from the page's own paper.
    val paper = remember(p.seed, page, ink, style.isDark) { paperPalette(p.seed, page, ink, style.isDark) }

    // ------------------------------------------------------------- navigator host

    val activity = remember(context) { context.findFragmentActivity() }
    // System bar icons follow the page while it shows, not the app theme: a paper page needs dark icons.
    val view = LocalView.current
    val appDark = p.isDark
    DisposableEffect(activity, style.isDark, appDark, visible) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val dark = if (visible) style.isDark else appDark
        bars?.isAppearanceLightStatusBars = !dark
        bars?.isAppearanceLightNavigationBars = !dark
        onDispose {
            bars?.isAppearanceLightStatusBars = !appDark
            bars?.isAppearanceLightNavigationBars = !appDark
        }
    }
    val selectionCallback = remember(engine) {
        object : ActionMode.Callback {
            // The system menu stays empty; the selection strip is drawn in Compose instead.
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.clear()
                selectionActive = true
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.clear()
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false

            override fun onDestroyActionMode(mode: ActionMode) {
                selectionActive = false
            }
        }
    }
    remember(engine, activity) {
        activity?.let(ReaderFragmentFactory::install)
        engine.fragmentFactory({ vm.currentAnchor() }, style, selectionCallback)
    }
    DisposableEffect(engine) { onDispose { engine.detach() } }

    LaunchedEffect(engine, style) { engine.submit(style) }

    LaunchedEffect(selectionActive) {
        if (selectionActive) {
            // Let the WebView settle the selection before reading it back.
            delay(120)
            selection = vm.selection()
        } else {
            selection = null
        }
    }

    // Saved highlights.
    val tints = highlightTints().map { it.toArgb() }
    LaunchedEffect(engine, annotations, tints) { vm.paintHighlights(annotations, tints) }

    // Read-along: paragraph band + sentence mark, and the page follows the voice unless the reader moved away.
    // The hidden page follows too, so turning to the Read face lands on the spoken sentence with no jump.
    val paragraphTint = paper.readAlong.toArgb()
    val sentenceTint = paper.readAlongLine.toArgb()
    LaunchedEffect(engine, spoken, audioHere, paragraphTint, sentenceTint) {
        val o = spoken.takeIf { audioHere }
        vm.paintReadAlong(o, paragraphTint, sentenceTint)
        if (o != null && !vm.detached.value) {
            val top = vm.ordinal.value
            val visibleEnough = style.scroll && top != null && o in top..top + SCROLL_FOLLOW_WINDOW
            if (!visibleEnough) vm.follow(o)
        }
    }

    // Study packs are prepared in the background as the reader or the voice enters a section (#108).
    val focus = (spoken.takeIf { audioHere } ?: ordinal)
    val focusSection = focus?.let { ready.index.sectionAt(it)?.id }
    val sectionCallback by rememberUpdatedState(onSectionChange)
    LaunchedEffect(focusSection) { if (focusSection != null && focus != null) sectionCallback(focus) }

    val currentOnClose by rememberUpdatedState(onClose)
    val currentPlayFrom by rememberUpdatedState(onPlayFrom)
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(engine) {
        engine.events.collect { e ->
            when (e) {
                ReaderEvent.CenterTap -> if (sheet == Sheet.None && currentVisible) chrome = !chrome
                is ReaderEvent.DoubleTap -> scope.launch {
                    vm.ordinalAtPoint(e.x, e.y)?.let { o ->
                        haptics.play(HapticToken.Confirm)
                        vm.reattach()
                        currentPlayFrom(o)
                    }
                }
                is ReaderEvent.MarkTapped -> if (e.group == ReadiumReaderEngine.GROUP_HIGHLIGHTS) {
                    e.id.toLongOrNull()?.let { sheet = Sheet.Note(it) }
                }
                ReaderEvent.LinkJump -> vm.onLinkJump()
                is ReaderEvent.ExternalLink -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url)))
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }

    // One-time hint the first time narration runs under the text.
    val hint = stringResource(R.string.reader_double_tap_hint)
    LaunchedEffect(audioHere, visible, local.doubleTapHintShown) {
        if (audioHere && visible && !local.doubleTapHintShown) {
            delay(900)
            onToast(hint)
            vm.markDoubleTapHintShown()
        }
    }

    // Volume keys, forwarded by the host activity.
    DisposableEffect(engine, local.volumeKeys, visible) {
        val handler: (Boolean) -> Unit = { forward -> if (forward) engine.goForward() else engine.goBackward() }
        if (local.volumeKeys && visible) ReaderKeys.handler = handler
        onDispose { if (ReaderKeys.handler === handler) ReaderKeys.handler = null }
    }

    // Reading time only counts while the page shows and nothing covers it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pauseTracking() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (sheet == Sheet.None && visible) vm.resumeTracking() }
    LaunchedEffect(sheet, visible) { if (sheet == Sheet.None && visible) vm.resumeTracking() else vm.pauseTracking() }
    LaunchedEffect(visible) { if (!visible) { chrome = false; sheet = Sheet.None } }

    BackHandler(enabled = visible && sheet != Sheet.None) { sheet = Sheet.None }
    BackHandler(enabled = visible && sheet == Sheet.None) { currentOnClose() }

    // ------------------------------------------------------------- layout

    val chapterTitle = ordinal?.let { ready.index.sectionAt(it)?.title ?: ready.index.chapterAt(it)?.title }

    CompositionLocalProvider(LocalBbPalette provides paper) {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = if (covered) 0f else 1f }.background(page)) {
        val compactDock = maxHeight < 560.dp
        // Running head and folio sit outside the WebView and are never transformed.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 26.dp, end = 26.dp, top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                (chapterTitle ?: ready.book.title).uppercase(), Modifier.weight(1f),
                style = ReaderType.caps(ink.copy(alpha = 0.55f), size = 10.5f, tracking = 0.16f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            location?.position?.let {
                Spacer(Modifier.width(12.dp))
                BasicText(it.toString(), style = ReaderType.numeral(ink.copy(alpha = 0.55f), 12f))
            }
        }

        // The page ends above the dock while narration runs. In paginated mode it also clears the dock's fade so
        // no line sits under it; in scroll mode the text runs softly into the fade.
        val fade = with(density) { 32.dp.roundToPx() }
        val dockInset = with(density) { (if (audioHere) (if (style.scroll) dockPx - fade else dockPx) else 0).coerceAtLeast(0).toDp() }
        // Rotation, a resized window or the dock appearing reflow the page; that must not read as the reader
        // paging away from the narration.
        LaunchedEffect(maxWidth, maxHeight, dockInset) { vm.expectReflow() }
        AndroidFragment<EpubNavigatorFragment>(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 44.dp)
                .then(if (audioHere) Modifier.padding(bottom = dockInset) else Modifier.navigationBarsPadding().padding(bottom = 24.dp))
                .onGloballyPositioned { navigatorTop = it.positionInRoot().y },
            onUpdate = { fragment -> engine.attach(fragment, scope) },
        )

        Bookmark(
            marked = ordinal?.let { o -> bookmarks.any { it.ordinal in o..o + ReaderViewModel.BOOKMARK_SLACK } } == true,
            chrome = chrome,
            reduced = reduced,
            onToggle = { ordinal?.let { vm.toggleBookmark(it) } },
        )

        // Top chrome.
        AnimatedVisibility(
            chrome,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(ReaderMotion.snap(reduced)),
            exit = fadeOut(ReaderMotion.snap(reduced)),
        ) {
            ReaderTopBar(
                onBack = onClose,
                onListen = onShowListen,
                onTypography = { sheet = Sheet.Typography },
                onLearn = onOpenLearn,
            )
        }

        // Bottom: back-to-narration pill, then the dock (narration) or the idle bar (with the chrome).
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(
                audioHere && detached,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enter = slideInVertically(ReaderMotion.settle(reduced)) { it / 2 } + fadeIn(ReaderMotion.settle(reduced)),
                exit = slideOutVertically(ReaderMotion.settle(reduced)) { it / 2 } + fadeOut(ReaderMotion.settle(reduced)),
            ) {
                BackToNarrationPill(
                    onClick = {
                        vm.reattach()
                        spoken?.let(vm::follow)
                    },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (audioHere) {
                DockSurface(page, Modifier.onSizeChanged { dockPx = it.height }) {
                    PlaybackDock(playback, cover, onShowListen, compact = compactDock)
                }
            } else {
                AnimatedVisibility(
                    chrome,
                    enter = fadeIn(ReaderMotion.snap(reduced)),
                    exit = fadeOut(ReaderMotion.snap(reduced)),
                ) {
                    val o = ordinal ?: ready.index.firstOrdinal
                    val progress = remember(o, ready.index) { ready.index.sectionProgress(o) }
                    ReaderIdleBar(
                        chapter = chapterTitle,
                        progress = progress,
                        bookFraction = ready.index.fraction(o),
                        onSeek = { f -> progress?.let { vm.jumpTo(ready.index.ordinalInRange(it.range, f)) } },
                        onContents = { sheet = Sheet.Contents },
                        onListen = { chrome = false; onPlayFrom(o) },
                    )
                }
            }
        }

        // Selection strip.
        selection?.let { sel ->
            SelectionStrip(
                yPx = with(density) {
                    val gap = 8.dp.toPx()
                    val strip = 48.dp.toPx()
                    val top = sel.top?.let { navigatorTop + it }
                    val bottom = sel.bottom?.let { navigatorTop + it }
                    when {
                        top != null && top - strip - gap > navigatorTop -> top - strip - gap
                        bottom != null -> bottom + gap
                        else -> navigatorTop
                    }
                },
                onAction = { action ->
                    scope.launch {
                        val now = vm.selection() ?: sel
                        val o = now.range.first
                        when (action) {
                            SelectionAction.Highlight -> vm.addHighlight(now.range, now.text)
                            SelectionAction.Explain -> onExplain(o, now.text)
                            SelectionAction.Ask -> onAsk(o, now.text)
                            SelectionAction.Card -> onCard(o, now.text)
                            SelectionAction.Define -> onDefine(o, now.text.trim { !it.isLetterOrDigit() })
                        }
                        vm.clearSelection()
                    }
                },
            )
        }

        // Sheets.
        ReaderSheet(sheet == Sheet.Contents, onDismiss = { sheet = Sheet.None }, reduced = reduced, tall = true) {
            ContentsSheetBody(
                index = ready.index, sections = ready.sections, currentOrdinal = ordinal,
                annotations = annotations, bookmarks = bookmarks,
                onJump = { o, href -> vm.jumpTo(o, href); sheet = Sheet.None; chrome = false },
                onDeleteBookmark = vm::deleteBookmark,
                onSearch = { sheet = Sheet.Search },
            )
        }
        ReaderSheet(sheet == Sheet.Search, onDismiss = { sheet = Sheet.None }, reduced = reduced, tall = true) {
            SearchSheetBody(search, onSearch = vm::runSearch, onOpen = { hit ->
                vm.jumpTo(hit.anchor)
                sheet = Sheet.None
                chrome = false
            })
        }
        ReaderSheet(sheet == Sheet.Typography, onDismiss = { sheet = Sheet.None }, reduced = reduced) {
            TypographySheetBody(
                style = style, local = local,
                onTheme = { t -> vm.updateStyle { it.copy(readerTheme = t.settingsId()) } },
                onFont = { f -> vm.updateStyle { it.copy(fontFamily = f.settingsId()) } },
                onFontScale = { v -> vm.updateStyle { it.copy(fontScale = v) } },
                onLineHeight = { v -> vm.updateStyle { it.copy(lineHeight = v) } },
                onLocal = vm::updateLocal,
            )
        }
        val noteId = (sheet as? Sheet.Note)?.id
        val noteAnnotation = noteId?.let { id -> annotations.firstOrNull { it.id == id } }
        ReaderSheet(noteAnnotation != null, onDismiss = { sheet = Sheet.None }, reduced = reduced) {
            if (noteAnnotation != null) {
                AnnotationSheetBody(
                    annotation = noteAnnotation,
                    onChange = vm::updateAnnotation,
                    onDelete = { vm.deleteAnnotation(noteAnnotation.id); sheet = Sheet.None },
                    onDone = { sheet = Sheet.None },
                )
            }
        }
    }
    }
}

private const val SCROLL_FOLLOW_WINDOW = 6

private enum class SelectionAction { Highlight, Explain, Ask, Card, Define }

/** Selection strip: `key` r4 h48, words split by 1 dp rules, no icons. */
@Composable
private fun SelectionStrip(yPx: Float, onAction: (SelectionAction) -> Unit) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val labels = listOf(
        SelectionAction.Highlight to stringResource(R.string.reader_sel_highlight),
        SelectionAction.Explain to stringResource(R.string.reader_sel_explain),
        SelectionAction.Ask to stringResource(R.string.reader_sel_ask),
        SelectionAction.Card to stringResource(R.string.reader_sel_card),
        SelectionAction.Define to stringResource(R.string.reader_sel_define),
    )
    Box(Modifier.fillMaxWidth().offset { IntOffset(0, yPx.roundToInt()) }, contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .height(48.dp)
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = if (p.isDark) 0.9f else 0.25f), Offset(0f, 2.dp.toPx()), size)
                }
                .clip(RoundedCornerShape(4.dp))
                .background(p.key)
                .drawBehind { drawRect(Color.White.copy(alpha = 0.7f), Offset(4.dp.toPx(), 0f), Size(size.width - 8.dp.toPx(), 1.dp.toPx())) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, (action, label) ->
                if (i > 0) Box(Modifier.width(1.dp).height(24.dp).background(p.onKey.copy(alpha = 0.2f)))
                Box(
                    Modifier
                        .height(48.dp)
                        .clickable(role = Role.Button) {
                            haptics.play(if (action == SelectionAction.Highlight) HapticToken.Confirm else HapticToken.Tap)
                            onAction(action)
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(label, style = ReaderType.keyLabel(p.onKey))
                }
            }
        }
    }
}

/** A 14×56 ribbon hanging top-right; outlined while chrome shows and the page has no bookmark. */
@Composable
private fun Bookmark(marked: Boolean, chrome: Boolean, reduced: Boolean, onToggle: () -> Unit) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val drop by animateDpAsState(if (chrome) 56.dp else 0.dp, ReaderMotion.snap(reduced), label = "ribbon")
    if (!marked && !chrome) return
    val label = stringResource(if (marked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add)
    Box(Modifier.fillMaxWidth().statusBarsPadding().offset(y = drop), contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier
                .size(48.dp, 64.dp)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button) { haptics.play(if (marked) HapticToken.Tap else HapticToken.Confirm); onToggle() }
                // the ribbon hangs in the 26 dp outer margin, never over the text column
                .padding(end = 6.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            if (marked) {
                // A silk ribbon that swings once when it is laid in; nothing loops.
                SilkRibbon(p.materials.silk, rememberRibbonSwing(marked), 14.dp, 56.dp, foldAt = 0.dp)
            } else {
                Canvas(Modifier.size(14.dp, 56.dp)) {
                    val notch = size.width * 0.7f / 2
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(size.width / 2, size.height - notch)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, p.text2, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun ReaderLoading(page: Color, ink: Color, dark: Boolean) {
    // Static loading blocks cut from the page's own paper, not the app's dark surfaces.
    val seed = Kp.palette.seed
    val p = remember(seed, page, ink, dark) { paperPalette(seed, page, ink, dark) }
    Column(Modifier.fillMaxSize().background(page).statusBarsPadding().padding(horizontal = 26.dp, vertical = 72.dp)) {
        repeat(9) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i % 4 == 3) 0.6f else 1f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(p.surface2),
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ReaderFailed(page: Color, ink: Color, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(page).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(stringResource(R.string.reader_open_failed), style = ReaderType.body(ink))
        Spacer(Modifier.height(24.dp))
        SecondaryKey(stringResource(R.string.reader_back), onClick = onClose)
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
