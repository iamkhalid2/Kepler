package app.kepler.nav

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.kepler.AppContainer
import app.kepler.R
import app.kepler.core.designsystem.CoverRequest
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpMiniPlayer
import app.kepler.core.designsystem.KpMotion
import app.kepler.core.designsystem.KpToastHost
import app.kepler.core.designsystem.LocalKpEntryScope
import app.kepler.core.designsystem.LocalKpSharedScope
import app.kepler.core.designsystem.rememberReducedMotion
import app.kepler.core.model.AppSettings
import app.kepler.core.model.BookId
import app.kepler.core.model.ImportResult
import app.kepler.core.model.PlaybackInfo
import app.kepler.core.model.PlaybackInfoSource
import app.kepler.core.model.PositionKind
import app.kepler.core.model.ReaderMode
import app.kepler.feature.library.HomeRoute
import app.kepler.feature.library.HubRoute
import app.kepler.feature.library.KeplerTheme
import app.kepler.feature.library.LibraryRoute
import app.kepler.feature.library.NarrationSetupRoute
import app.kepler.feature.library.OnboardingRoute
import app.kepler.feature.library.ProvideLibraryDeps
import app.kepler.feature.library.SettingsRoute
import app.kepler.feature.player.FullPlayerRoute
import app.kepler.feature.player.ReadAlongTransport
import app.kepler.feature.reader.ReaderRoute
import app.kepler.feature.study.CardFromSelectionSheet
import app.kepler.feature.study.DefineSheet
import app.kepler.feature.study.ExplainSheet
import app.kepler.feature.study.LearnRoute
import app.kepler.feature.study.LearnTab
import app.kepler.feature.study.NotebookRoute
import app.kepler.feature.study.ReviewRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private sealed interface SheetReq {
    data class Explain(val bookId: BookId, val ordinal: Int, val text: String) : SheetReq
    data class Define(val bookId: BookId, val ordinal: Int, val term: String) : SheetReq
    data class Card(val bookId: BookId, val ordinal: Int, val text: String) : SheetReq
}

/** Key of a back-stack entry's state: changing the reader mode or face, or the Learn tab, keeps the same screen state. */
private fun Route.stateKey(): String = when (this) {
    is Route.Tabs -> "tabs"
    is Route.Reader -> "reader/$bookId/$nonce"
    is Route.Learn -> "learn/$bookId"
    else -> encode()
}

/** One composed back-stack entry: [key] owns its saved state and ViewModels, [route] is what it shows now. */
private data class NavEntry(val key: String, val route: Route) {
    val index: Int get() = key.substringBefore(':').toInt()
}

@Composable
fun AppRoot(container: AppContainer, incoming: Flow<Incoming>, onFinish: () -> Unit) {
    val settings by produceState<AppSettings?>(null) { value = container.data.settings.settings.first() }
    KeplerTheme(container.data.settings) {
        SystemBarsFollowTheme()
        ProvideLibraryDeps(container.libraryDeps) {
            val s = settings
            if (s == null) {
                Box(Modifier.fillMaxSize().background(Kp.palette.bg))
            } else {
                val stack = rememberSaveable(saver = BackStack.Saver) {
                    BackStack(listOf(if (s.onboarded) Route.Tabs(Tab.HOME) else Route.Onboarding))
                }
                AppNavHost(container, stack, incoming, onFinish)
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppNavHost(container: AppContainer, stack: BackStack, incoming: Flow<Incoming>, onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = container.playback
    val data = container.data
    var sheet by remember { mutableStateOf<SheetReq?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun listen(bookId: BookId) {
        askNotificationsOnce()
        stack.showListen(bookId)
    }

    fun read(bookId: BookId, mode: ReaderMode = ReaderMode.READ) = stack.showReader(Route.Reader(bookId, mode))

    fun jumpTo(bookId: BookId, ordinal: Int, mode: ReaderMode = ReaderMode.READ) {
        scope.launch {
            data.positions.savePosition(bookId, PositionKind.READING, ordinal)
            read(bookId, mode)
        }
    }

    val bookMissing = stringResource(R.string.toast_book_missing)
    val cantOpen = stringResource(R.string.toast_cant_open)
    val addedFormat = stringResource(R.string.toast_added)
    LaunchedEffect(incoming) {
        incoming.collect { item ->
            when (item) {
                is Incoming.Link -> {
                    val link = item.link
                    if (data.library.book(link.bookId).first() == null) toast = bookMissing
                    else {
                        data.positions.savePosition(link.bookId, PositionKind.READING, link.ordinal)
                        stack.showReader(Route.Reader(link.bookId))
                    }
                }
                is Incoming.EpubFile -> {
                    val result = try {
                        data.library.importBook(item.uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ImportResult.Failed(e.message.orEmpty())
                    }
                    val book = when (result) {
                        is ImportResult.Imported -> result.book
                        is ImportResult.Duplicate -> result.book
                        is ImportResult.Failed -> { toast = cantOpen; null }
                    }
                    if (book != null) {
                        if (stack.top == Route.Onboarding) toast = addedFormat.format(book.title)
                        else {
                            stack.resetTo(Route.Tabs(Tab.LIBRARY))
                            stack.push(Route.Hub(book.id))
                        }
                    }
                }
            }
        }
    }

    val entries = stack.entries.mapIndexed { i, r -> NavEntry("$i:${r.stateKey()}", r) }
    val topEntry = entries.last()
    val top = topEntry.route

    // ---- back: predictive pop for stacked entries; at the root a non-home tab returns home, home finishes.
    var backProgress by remember { mutableStateOf<Float?>(null) }
    PredictiveBackHandler(enabled = entries.size > 1) { events ->
        try {
            events.collect { backProgress = it.progress }
            backProgress = null
            stack.pop()
        } catch (e: CancellationException) {
            backProgress = null
            throw e
        }
    }
    val atRoot = entries.size == 1
    BackHandler(enabled = atRoot && top is Route.Tabs && top.tab != Tab.HOME) { stack.replaceTop(Route.Tabs(Tab.HOME)) }
    BackHandler(enabled = atRoot && top is Route.Tabs && top.tab == Tab.HOME) { onFinish() }

    // ---- one ViewModelStore per entry (and per tab inside the tabs entry), cleared once the entry has left.
    val stores = remember { mutableMapOf<String, ViewModelStore>() }
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(stack) {
        snapshotFlow { stack.entries.mapIndexed { i, r -> "$i:${r.stateKey()}" }.toSet() }
            .distinctUntilChanged()
            .collect { live ->
                delay(800)
                val current = stack.entries.mapIndexed { i, r -> "$i:${r.stateKey()}" }.toSet()
                stores.keys.filter { k -> k.substringBefore('#').let { it !in live && it !in current } }.forEach { k ->
                    stores.remove(k)?.clear()
                    if ('#' !in k) holder.removeState(k)
                }
            }
    }

    // ---- the entry transition: seekable so predictive back can scrub it.
    val reduced = rememberReducedMotion()
    val transitionState = remember { SeekableTransitionState(topEntry) }
    val progress = backProgress
    if (progress != null && entries.size > 1) {
        val previous = entries[entries.size - 2]
        LaunchedEffect(progress) { transitionState.seekTo(progress, previous) }
    } else {
        LaunchedEffect(topEntry) {
            if (transitionState.currentState != topEntry) {
                transitionState.animateTo(topEntry)
            } else if (transitionState.targetState != topEntry) {
                // A cancelled back gesture: run the scrubbed fraction back to zero, then settle.
                val from = transitionState.fraction
                animate(from, 0f, animationSpec = tween((from * KpMotion.PUSH_IN_MS).toInt().coerceAtLeast(1))) { v, _ ->
                    launch { transitionState.seekTo(v) }
                }
                transitionState.snapTo(topEntry)
            }
        }
    }
    val transition = rememberTransition(transitionState, label = "nav")
    val density = LocalDensity.current
    val pushPx = with(density) { 24.dp.roundToPx() }

    val hasBook by remember(playback) { playback.state.map { it.bookId != null }.distinctUntilChanged() }.collectAsState(false)

    SharedTransitionLayout(Modifier.fillMaxSize().background(Kp.palette.bg)) {
        CompositionLocalProvider(LocalKpSharedScope provides this) {
            Box(Modifier.fillMaxSize()) {
                transition.AnimatedContent(
                    contentKey = { it.key },
                    transitionSpec = {
                        when {
                            reduced -> KpMotion.crossfade()
                            targetState.index >= initialState.index ->
                                (fadeIn(tween(KpMotion.PUSH_IN_MS)) + slideInHorizontally(tween(KpMotion.PUSH_IN_MS)) { pushPx }) togetherWith
                                    fadeOut(tween(KpMotion.PUSH_OUT_MS))
                            else -> (fadeIn(tween(KpMotion.PUSH_IN_MS)) togetherWith
                                (fadeOut(tween(KpMotion.PUSH_IN_MS)) + scaleOut(tween(KpMotion.PUSH_IN_MS), targetScale = 0.92f) +
                                    slideOutHorizontally(tween(KpMotion.PUSH_IN_MS)) { pushPx / 2 }))
                                .apply { targetContentZIndex = -1f }
                        }
                    },
                ) { entry ->
                    CompositionLocalProvider(LocalKpEntryScope provides this) {
                        EntryScope(entry.key, stores, holder) {
                            Destination(
                                container = container,
                                entry = entry,
                                stack = stack,
                                stores = stores,
                                onListen = ::listen,
                                onRead = { read(it) },
                                onJumpTo = { b, o -> jumpTo(b, o) },
                                askNotificationsOnce = ::askNotificationsOnce,
                                openSheet = { sheet = it },
                            )
                        }
                    }
                }

                when (val req = sheet) {
                    null -> Unit
                    is SheetReq.Explain -> ExplainSheet(
                        container.studyDeps, req.bookId, req.ordinal, req.text,
                        onDismiss = { sheet = null },
                        onMakeCard = { sheet = SheetReq.Card(req.bookId, req.ordinal, req.text) },
                        onAskMore = { sheet = null; stack.push(Route.Learn(req.bookId, LearnTab.ASK)) },
                    )
                    is SheetReq.Define -> DefineSheet(container.studyDeps, req.bookId, req.ordinal, req.term, onDismiss = { sheet = null })
                    is SheetReq.Card -> CardFromSelectionSheet(
                        container.studyDeps, req.bookId, req.ordinal, req.text,
                        onDismiss = { sheet = null },
                        onSaved = { sheet = null },
                    )
                }

                val chrome = if (top is Route.Tabs) TabBarHeight + (if (hasBook) 72.dp else 0.dp) else 0.dp
                KpToastHost(
                    message = toast,
                    onDismiss = { toast = null },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = chrome + 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Destination(
    container: AppContainer,
    entry: NavEntry,
    stack: BackStack,
    stores: MutableMap<String, ViewModelStore>,
    onListen: (BookId) -> Unit,
    onRead: (BookId) -> Unit,
    onJumpTo: (BookId, Int) -> Unit,
    askNotificationsOnce: () -> Unit,
    openSheet: (SheetReq) -> Unit,
) {
    when (val route = entry.route) {
        Route.Onboarding -> OnboardingRoute(onDone = { stack.resetTo(Route.Tabs(Tab.HOME)) })
        is Route.Tabs -> TabsHost(
            container = container,
            tab = route.tab,
            storeFor = { tab -> stores.getOrPut("${entry.key}#${tab.name}") { ViewModelStore() } },
            onSelect = { if (stack.top == route) stack.replaceTop(Route.Tabs(it)) },
            onOpenBook = { stack.push(Route.Hub(it)) },
            onRead = onRead,
            onListen = onListen,
            onRecap = { stack.push(Route.Learn(it, LearnTab.RECAP)) },
            onReview = { stack.push(Route.Review) },
            onSettings = { stack.push(Route.Settings) },
            onJumpTo = onJumpTo,
        )
        is Route.Hub -> HubRoute(
            bookId = route.bookId,
            onRead = { onRead(route.bookId) },
            onListen = { onListen(route.bookId) },
            onLearn = { stack.push(Route.Learn(route.bookId)) },
            onSetup = { stack.push(Route.Setup(route.bookId)) },
            onBack = { stack.pop() },
            onLearnAt = { stack.push(Route.Learn(route.bookId, LearnTab.valueOf(it.name))) },
        )
        is Route.Reader -> ReadListenHost(container, route, stack, askNotificationsOnce, openSheet)
        is Route.Learn -> LearnRoute(
            deps = container.studyDeps,
            bookId = route.bookId,
            onJumpTo = { onJumpTo(route.bookId, it) },
            onBack = { stack.pop() },
            initialTab = route.tab,
            onOpenSettings = { stack.push(Route.Settings) },
        )
        Route.Review -> ReviewRoute(container.studyDeps, onClose = { stack.pop() })
        is Route.Setup -> {
            // Narration runs as a foreground service with a notification, so this is where we ask.
            LaunchedEffect(Unit) { askNotificationsOnce() }
            NarrationSetupRoute(
                bookId = route.bookId,
                onBack = { stack.pop() },
                onOpenSettings = { stack.push(Route.Settings) },
            )
        }
        Route.Settings -> SettingsRoute(onBack = { stack.pop() })
    }
}

/** Status and navigation bar icons follow the app's own light/dark theme, not the system's. */
@Composable
private fun SystemBarsFollowTheme() {
    val view = LocalView.current
    val palette = Kp.palette
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !palette.isDark
        controller.isAppearanceLightNavigationBars = !palette.isDark
        window.setBackgroundDrawable(ColorDrawable(palette.bg.toArgb()))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun EntryScope(
    key: String,
    stores: MutableMap<String, ViewModelStore>,
    holder: SaveableStateHolder,
    content: @Composable () -> Unit,
) {
    val owner = remember(key) { storeOwner(stores.getOrPut(key) { ViewModelStore() }) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        holder.SaveableStateProvider(key, content)
    }
}

private fun storeOwner(store: ViewModelStore): ViewModelStoreOwner = object : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = store
}

/**
 * The one read + listen destination (#112). The Listen face is the full player for now and the Read face the
 * reader with its docked transport; both live in the same back-stack entry and crossfade into each other.
 * The unified screen replaces both bodies next stage without touching navigation.
 */
@Composable
private fun ReadListenHost(
    container: AppContainer,
    route: Route.Reader,
    stack: BackStack,
    onListenPermission: () -> Unit,
    openSheet: (SheetReq) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    AnimatedContent(
        targetState = route.face,
        transitionSpec = { KpMotion.crossfade(if (reduced) KpMotion.REDUCED_MS else KpMotion.FACE_SWITCH_MS) },
        label = "face",
    ) { face ->
        when (face) {
            ReaderFace.LISTEN -> FullPlayerRoute(
                controller = container.playback,
                narration = container.narration,
                annotations = container.data.annotations,
                bookId = route.bookId,
                onReadAlong = { ordinal ->
                    scope.launch {
                        container.data.positions.savePosition(route.bookId, PositionKind.READING, ordinal)
                        stack.setFace(route.bookId, ReaderFace.READ, ReaderMode.READ_LISTEN)
                    }
                },
                onClose = { stack.pop() },
            )
            ReaderFace.READ -> ReaderHost(container, route, stack, onListenPermission, openSheet)
        }
    }
}

@Composable
private fun ReaderHost(
    container: AppContainer,
    route: Route.Reader,
    stack: BackStack,
    onListenPermission: () -> Unit,
    openSheet: (SheetReq) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playback = container.playback
    val bookId = route.bookId
    val readAlong = remember(bookId) {
        playback.state.map { if (it.bookId == bookId) it.ordinal else null }.distinctUntilChanged()
    }
    fun setMode(mode: ReaderMode) = stack.setFace(bookId, ReaderFace.READ, mode)
    ReaderRoute(
        deps = container.readerDeps,
        bookId = bookId,
        readAlongOrdinal = readAlong,
        mode = route.mode,
        onModeChange = { mode ->
            setMode(mode)
            if (mode == ReaderMode.READ_LISTEN) {
                onListenPermission()
                val st = playback.state.value
                if (st.bookId != bookId) {
                    scope.launch {
                        val from = container.data.positions.position(bookId, PositionKind.READING).first()?.ordinal
                        playback.play(bookId, from)
                    }
                } else if (!st.playing) {
                    playback.resume()
                }
            } else if (playback.state.value.bookId == bookId && playback.state.value.playing) {
                playback.pause()
            }
        },
        onPlayFromSentence = { ordinal ->
            onListenPermission()
            setMode(ReaderMode.READ_LISTEN)
            playback.play(bookId, ordinal)
        },
        onExplain = { o, t -> openSheet(SheetReq.Explain(bookId, o, t)) },
        onAsk = { _, _ -> stack.push(Route.Learn(bookId, LearnTab.ASK)) },
        onCard = { o, t -> openSheet(SheetReq.Card(bookId, o, t)) },
        onDefine = { o, t -> openSheet(SheetReq.Define(bookId, o, t)) },
        onOpenLearn = { stack.push(Route.Learn(bookId)) },
        onClose = { stack.pop() },
        transport = { ReadAlongTransport(playback) },
    )
}

/**
 * Tabs keep their state: each tab has its own saved-state slot and ViewModelStore for as long as the tabs entry
 * lives, so switching never recreates a ViewModel or loses scroll. The switch is a 180 ms fade-through; the mini
 * player and tab bar sit outside it and never fade.
 */
@Composable
private fun TabsHost(
    container: AppContainer,
    tab: Tab,
    storeFor: (Tab) -> ViewModelStore,
    onSelect: (Tab) -> Unit,
    onOpenBook: (BookId) -> Unit,
    onRead: (BookId) -> Unit,
    onListen: (BookId) -> Unit,
    onRecap: (BookId) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    onJumpTo: (BookId, Int) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    var bottomPx by remember { mutableIntStateOf(0) }
    val bottom = with(density) { bottomPx.toDp() }
    val reduced = rememberReducedMotion()
    val owners = remember { mutableMapOf<Tab, ViewModelStoreOwner>() }

    Box(Modifier.fillMaxSize().background(Kp.palette.bg)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { if (reduced) KpMotion.crossfade() else KpMotion.fadeThrough() },
            label = "tab",
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(WindowInsets.navigationBars)
                .padding(bottom = bottom),
        ) { t ->
            val owner = owners.getOrPut(t) { storeOwner(storeFor(t)) }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                holder.SaveableStateProvider(t.name) {
                    when (t) {
                        Tab.HOME -> HomeRoute(
                            onOpenBook = onOpenBook,
                            onRead = onRead,
                            onListen = onListen,
                            onRecap = onRecap,
                            onReview = onReview,
                            onSettings = onSettings,
                        )
                        Tab.LIBRARY -> LibraryRoute(onOpenBook = onOpenBook)
                        Tab.NOTEBOOK -> NotebookRoute(container.studyDeps, onJumpTo = onJumpTo, onStartReview = onReview)
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height },
        ) {
            ShellMiniPlayer(container, onOpen = onListen, modifier = Modifier.padding(bottom = 8.dp))
            KpTabBar(tab, onSelect)
        }
    }
}

/**
 * Feeds [KpMiniPlayer] from the playback controller. It collects the 10 Hz playback state itself so only the
 * bar recomposes, never the tab underneath. Swipe down pauses and hides the bar until playback resumes.
 */
@Composable
private fun ShellMiniPlayer(container: AppContainer, onOpen: (BookId) -> Unit, modifier: Modifier = Modifier) {
    val playback = container.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val infoFlow = remember(playback) { (playback as? PlaybackInfoSource)?.info ?: MutableStateFlow(PlaybackInfo()) }
    val info by infoFlow.collectAsStateWithLifecycle()
    var hiddenFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val playing = state.playing
    LaunchedEffect(playing) { if (playing) hiddenFor = null }
    val bookId = state.bookId ?: return
    if (hiddenFor == bookId) return

    val cover = remember(bookId, info.bookId, info.bookTitle, info.author, info.coverPath) {
        if (info.bookId == bookId) CoverRequest(bookId, info.bookTitle.orEmpty(), info.author.orEmpty(), info.coverPath) else null
    }
    val leftMs = ((state.sectionDurationMs - state.sectionElapsedMs).coerceAtLeast(0) / state.speed.coerceAtLeast(0.25f)).toLong()
    val left = formatDuration(leftMs)
    val chapter = info.sectionTitle
    val caption = when {
        state.error != null -> state.error.orEmpty()
        state.buffering && !playing -> stringResource(R.string.mini_preparing)
        chapter != null && state.sectionDurationMs > 0 -> stringResource(R.string.mini_caption, chapter, left)
        chapter != null -> chapter
        else -> stringResource(R.string.mini_fallback)
    }
    val progress = if (state.sectionDurationMs > 0) state.sectionElapsedMs.toFloat() / state.sectionDurationMs else 0f
    KpMiniPlayer(
        cover = cover,
        title = info.bookTitle ?: state.title.orEmpty(),
        caption = caption,
        playing = playing,
        progress = progress,
        onPlayPause = { if (playing) playback.pause() else playback.resume() },
        onOpen = { onOpen(bookId) },
        onDismiss = { playback.pause(); hiddenFor = bookId },
        modifier = modifier,
    )
}

@Composable
private fun formatDuration(ms: Long): String {
    val totalMin = ((ms + 59_999) / 60_000).toInt()
    return if (totalMin < 60) stringResource(R.string.duration_minutes, totalMin)
    else stringResource(R.string.duration_hours_minutes, totalMin / 60, totalMin % 60)
}
