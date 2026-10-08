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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.SilkRibbon
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.designsystem.rememberRibbonSwing
import app.kepler.core.designsystem.LocalBbPalette
import app.kepler.core.designsystem.paperPalette
import androidx.compose.runtime.CompositionLocalProvider
import app.kepler.core.model.BookId
import app.kepler.core.model.ReaderMode
import app.kepler.engine.epub.reader.ReaderEvent
import app.kepler.engine.epub.reader.ReaderFragmentFactory
import app.kepler.engine.epub.reader.ReadiumReaderEngine
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * The reading screen for [bookId].
 *
 * Host requirements: the activity must be a [FragmentActivity] (the Readium navigator is a fragment),
 * should call [ReaderFragmentFactory.install] before `super.onCreate` so the navigator survives
 * recreation, and should forward volume keys through [ReaderKeys.dispatch].
 *
 * [readAlongOrdinal] is the sentence being spoken; in [ReaderMode.READ_LISTEN] it is banded and
 * followed. [transport] is shown above the bottom bar in that mode.
 */
@Composable
fun ReaderRoute(
    deps: ReaderDeps,
    bookId: BookId,
    readAlongOrdinal: Flow<Int?>,
    mode: ReaderMode,
    onModeChange: (ReaderMode) -> Unit,
    onPlayFromSentence: (Int) -> Unit,
    onExplain: (ordinal: Int, text: String) -> Unit,
    onAsk: (ordinal: Int, text: String) -> Unit,
    onCard: (ordinal: Int, text: String) -> Unit,
    onDefine: (ordinal: Int, term: String) -> Unit,
    onOpenLearn: () -> Unit,
    onClose: () -> Unit,
    transport: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReaderViewModel = viewModel(key = "reader-$bookId") {
        ReaderViewModel(context.applicationContext, deps, bookId)
    }
    val load by vm.load.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val page = Color(style.pageColor)
    when (val l = load) {
        ReaderLoad.Loading -> ReaderLoading(page)
        is ReaderLoad.Failed -> ReaderFailed(page, Color(style.inkColor), onClose)
        is ReaderLoad.Ready -> ReaderScreen(
            vm, l, readAlongOrdinal, mode, onModeChange, onPlayFromSentence,
            onExplain, onAsk, onCard, onDefine, onOpenLearn, onClose, transport,
        )
    }
}

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
    readAlongOrdinal: Flow<Int?>,
    mode: ReaderMode,
    onModeChange: (ReaderMode) -> Unit,
    onPlayFromSentence: (Int) -> Unit,
    onExplain: (Int, String) -> Unit,
    onAsk: (Int, String) -> Unit,
    onCard: (Int, String) -> Unit,
    onDefine: (Int, String) -> Unit,
    onOpenLearn: () -> Unit,
    onClose: () -> Unit,
    transport: @Composable () -> Unit,
) {
    val p = Kp.palette
    val context = LocalContext.current
    val density = LocalDensity.current
    val reduced = rememberReducedMotion()
    val engine = ready.engine
    val scope = rememberCoroutineScope()

    val style by vm.style.collectAsStateWithLifecycle()
    val local by vm.localPrefs.collectAsStateWithLifecycle()
    val ordinal by vm.ordinal.collectAsStateWithLifecycle()
    val frontier by vm.frontier.collectAsStateWithLifecycle()
    val detached by vm.detached.collectAsStateWithLifecycle()
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val location by engine.location.collectAsStateWithLifecycle()
    val spoken by readAlongOrdinal.collectAsStateWithLifecycle(initialValue = null)

    var chrome by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet>(Sheet.None) }
    var selectionActive by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<ReaderSelection?>(null) }
    var navigatorTop by remember { mutableFloatStateOf(0f) }
    val listening = mode == ReaderMode.READ_LISTEN

    // ------------------------------------------------------------- navigator host

    val activity = remember(context) { context.findFragmentActivity() }
    val selectionCallback = remember(engine) {
        object : ActionMode.Callback {
            // The system menu stays empty; the Shelf selection strip is drawn in Compose instead.
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
        engine.fragmentFactory(vm.currentAnchor(), style, selectionCallback)
    }
    DisposableEffect(engine) { onDispose { engine.detach() } }

    LaunchedEffect(engine, style) { engine.submit(style) }
    LaunchedEffect(listening) { vm.setListening(listening) }

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

    // Read-along band and follow.
    val bandTint = p.accent.copy(alpha = if (p.isDark) 0.12f else 0.09f).toArgb()
    LaunchedEffect(engine, spoken, listening, bandTint) {
        val o = spoken.takeIf { listening }
        vm.paintReadAlong(o, bandTint)
        if (o != null && !vm.detached.value) {
            val top = vm.ordinal.value
            val visibleEnough = style.scroll && top != null && o in top..top + SCROLL_FOLLOW_WINDOW
            if (!visibleEnough) vm.follow(o)
        }
    }

    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(engine) {
        engine.events.collect { e ->
            when (e) {
                ReaderEvent.CenterTap -> if (sheet == Sheet.None) chrome = !chrome
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

    // Volume keys, forwarded by the host activity.
    DisposableEffect(engine, local.volumeKeys) {
        val handler: (Boolean) -> Unit = { forward -> if (forward) engine.goForward() else engine.goBackward() }
        if (local.volumeKeys) ReaderKeys.handler = handler
        onDispose { if (ReaderKeys.handler === handler) ReaderKeys.handler = null }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pauseTracking() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (sheet == Sheet.None) vm.resumeTracking() }
    LaunchedEffect(sheet) { if (sheet == Sheet.None) vm.resumeTracking() else vm.pauseTracking() }

    BackHandler(enabled = sheet != Sheet.None) { sheet = Sheet.None }
    BackHandler(enabled = sheet == Sheet.None) { currentOnClose() }

    // ------------------------------------------------------------- layout

    val page = Color(style.pageColor)
    val ink = Color(style.inkColor)
    val chapter = ordinal?.let(ready.index::chapterAt)

    // Chrome, sheets and the transport are cut from the page's own paper.
    val paper = remember(p.seed, page, ink, style.isDark) { paperPalette(p.seed, page, ink, style.isDark) }
    CompositionLocalProvider(LocalBbPalette provides paper) {
    Box(Modifier.fillMaxSize().background(page)) {
        // Running head and folio sit outside the WebView and are never transformed.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 26.dp, end = 26.dp, top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                (chapter?.title ?: ready.book.title).uppercase(), Modifier.weight(1f),
                style = ReaderType.caps(ink.copy(alpha = 0.55f), size = 10.5f, tracking = 0.16f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            location?.position?.let {
                Spacer(Modifier.width(12.dp))
                BasicText(it.toString(), style = ReaderType.numeral(ink.copy(alpha = 0.55f), 12f))
            }
        }

        AndroidFragment<EpubNavigatorFragment>(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 48.dp, bottom = 24.dp)
                .onGloballyPositioned { navigatorTop = it.positionInRoot().y },
            onUpdate = { fragment -> engine.attach(fragment, scope) },
        )

        Bookmark(
            marked = ordinal?.let { o -> bookmarks.any { it.ordinal in o..o + ReaderViewModel.BOOKMARK_SLACK } } == true,
            chrome = chrome,
            reduced = reduced,
            onToggle = { ordinal?.let(vm::toggleBookmark) },
        )

        // Top chrome.
        AnimatedVisibility(
            chrome,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(ReaderMotion.snap(reduced)),
            exit = fadeOut(ReaderMotion.snap(reduced)),
        ) {
            TopBar(
                title = ready.book.title,
                onBack = onClose,
                onTypography = { sheet = Sheet.Typography },
                onSearch = { sheet = Sheet.Search },
                onContents = { sheet = Sheet.Contents },
                onLearn = onOpenLearn,
            )
        }

        // Bottom: detach prompts, transport, chrome.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(
                listening && detached,
                enter = slideInVertically(ReaderMotion.settle(reduced)) { it } + fadeIn(ReaderMotion.settle(reduced)),
                exit = slideOutVertically(ReaderMotion.settle(reduced)) { it } + fadeOut(ReaderMotion.settle(reduced)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextLink(stringResource(R.string.reader_return), onClick = {
                        vm.reattach()
                        spoken?.let(vm::follow)
                    })
                    Spacer(Modifier.width(8.dp))
                    SecondaryKey(stringResource(R.string.reader_play_here), onClick = {
                        ordinal?.let(onPlayFromSentence)
                        vm.reattach()
                    })
                }
            }
            if (listening) {
                Column(Modifier.fillMaxWidth().background(page)) {
                    Hairline()
                    transport()
                    if (!chrome) Spacer(Modifier.navigationBarsPadding())
                }
            }
            AnimatedVisibility(
                chrome,
                enter = fadeIn(ReaderMotion.snap(reduced)),
                exit = fadeOut(ReaderMotion.snap(reduced)),
            ) {
                BottomBar(
                    ready = ready,
                    ordinal = ordinal,
                    frontier = frontier,
                    mode = mode,
                    onModeChange = onModeChange,
                    onSeek = { f -> vm.jumpTo(ready.index.ordinalAt(f)) },
                )
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

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    onTypography: () -> Unit,
    onSearch: () -> Unit,
    onContents: () -> Unit,
    onLearn: () -> Unit,
) {
    val p = Kp.palette
    Column(Modifier.fillMaxWidth().background(p.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphKey(stringResource(R.string.reader_back), onBack, Glyph.Back, p.text)
            // The title opens the contents: the brief's top bar has no separate contents key.
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clickable(role = Role.Button, onClick = onContents)
                    .semantics { contentDescription = title },
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicText(title, style = ReaderType.title(p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GlyphKey(stringResource(R.string.reader_typography), onTypography, Glyph.Typography, p.text)
            GlyphKey(stringResource(R.string.reader_search), onSearch, Glyph.Search, p.text)
            TextLink(stringResource(R.string.reader_learn), onClick = onLearn)
        }
        Hairline()
    }
}

@Composable
private fun BottomBar(
    ready: ReaderLoad.Ready,
    ordinal: Int?,
    frontier: Int,
    mode: ReaderMode,
    onModeChange: (ReaderMode) -> Unit,
    onSeek: (Float) -> Unit,
) {
    val p = Kp.palette
    val index = ready.index
    val o = ordinal ?: index.firstOrdinal
    val fraction = index.fraction(o)
    val ticks = remember(index) { index.chapters.drop(1).map { index.fraction(it.startOrdinal) } }
    val frontierFraction = if (frontier >= 0) index.fraction(frontier) else null
    val chapterName = index.chapterAt(o)?.title ?: stringResource(R.string.reader_this_chapter)
    val minutes = index.minutesLeftInChapter(o)
    val left = if (minutes < 1.0) {
        stringResource(R.string.reader_under_min_in, chapterName)
    } else {
        stringResource(R.string.reader_min_left_in, minutes.roundToInt(), chapterName)
    }
    Column(Modifier.fillMaxWidth().background(p.bg)) {
        Hairline()
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 12.dp)) {
            ProgressTrack(fraction, frontierFraction, ticks, onSeek)
            BasicText(
                stringResource(R.string.reader_progress, (fraction * 100).roundToInt(), left),
                style = ReaderType.caption(p.text2), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Segmented(
                options = listOf(stringResource(R.string.reader_mode_read), stringResource(R.string.reader_mode_read_listen)),
                selected = if (mode == ReaderMode.READ_LISTEN) 1 else 0,
                onSelect = { onModeChange(if (it == 1) ReaderMode.READ_LISTEN else ReaderMode.READ) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 48 dp touch strip; 2 dp track, accent fill, 1×6 chapter ticks, 2×10 frontier mark. */
@Composable
private fun ProgressTrack(fraction: Float, frontier: Float?, ticks: List<Float>, onSeek: (Float) -> Unit) {
    val p = Kp.palette
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: fraction
    val description = stringResource(R.string.reader_progress_track)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { contentDescription = description }
            .pointerInputs(onSeek = onSeek, onDrag = { drag = it }),
    ) {
        val y = size.height / 2
        val track = 2.dp.toPx()
        drawRect(p.hairline, Offset(0f, y - track / 2), Size(size.width, track))
        drawRect(p.accent, Offset(0f, y - track / 2), Size(size.width * shown, track))
        val tickH = 6.dp.toPx()
        val tickW = 1.dp.toPx()
        ticks.forEach { t -> drawRect(p.text2, Offset(size.width * t - tickW / 2, y - tickH / 2), Size(tickW, tickH)) }
        frontier?.let { f ->
            val w = 2.dp.toPx()
            val h = 10.dp.toPx()
            drawRect(p.accent, Offset(size.width * f - w / 2, y - h / 2), Size(w, h))
        }
    }
}

private fun Modifier.pointerInputs(onSeek: (Float) -> Unit, onDrag: (Float?) -> Unit): Modifier = this
    .then(
        Modifier.pointerInput(Unit) {
            detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
        },
    )
    .then(
        Modifier.pointerInput(Unit) {
            var last = 0f
            detectHorizontalDragGestures(
                onDragStart = { last = (it.x / size.width).coerceIn(0f, 1f); onDrag(last) },
                onDragEnd = { onDrag(null); onSeek(last) },
                onDragCancel = { onDrag(null) },
                onHorizontalDrag = { change, _ -> last = (change.position.x / size.width).coerceIn(0f, 1f); onDrag(last) },
            )
        },
    )

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
                .padding(end = 14.dp)
                .size(48.dp, 64.dp)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button) { haptics.play(if (marked) HapticToken.Tap else HapticToken.Confirm); onToggle() },
            contentAlignment = Alignment.TopCenter,
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
private fun ReaderLoading(page: Color) {
    val p = Kp.palette
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
