package app.kepler.nav

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.kepler.AppContainer
import app.kepler.R
import app.kepler.core.designsystem.BookStageOverlay
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpMotion
import app.kepler.core.designsystem.LocalBookStage
import app.kepler.core.designsystem.LocalStageOwner
import app.kepler.core.designsystem.rememberBookStage
import app.kepler.core.designsystem.KpToastHost
import app.kepler.core.designsystem.rememberReducedMotion
import app.kepler.core.model.AppSettings
import app.kepler.core.model.BookId
import app.kepler.core.model.ImportResult
import app.kepler.core.model.PositionKind
import app.kepler.core.model.ReaderMode
import app.kepler.feature.library.KeplerTheme
import app.kepler.feature.library.HomeRoute
import app.kepler.feature.library.HubRoute
import app.kepler.feature.library.LibraryRoute
import app.kepler.feature.library.NarrationSetupRoute
import app.kepler.feature.library.OnboardingRoute
import app.kepler.feature.library.ProvideLibraryDeps
import app.kepler.feature.library.SettingsRoute
import app.kepler.feature.player.FullPlayerRoute
import app.kepler.feature.player.NowBar
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private sealed interface SheetReq {
    data class Explain(val bookId: BookId, val ordinal: Int, val text: String) : SheetReq
    data class Define(val bookId: BookId, val ordinal: Int, val term: String) : SheetReq
    data class Card(val bookId: BookId, val ordinal: Int, val text: String) : SheetReq
}

/** Key of a back-stack entry's state: changing the reader mode or Learn tab keeps the same screen state. */
private fun Route.stateKey(): String = when (this) {
    is Route.Tabs -> "tabs"
    is Route.Reader -> "reader/$bookId/$nonce"
    is Route.Learn -> "learn/$bookId"
    else -> encode()
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
        stack.push(Route.Player(bookId))
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

    // Pressing back on a non-home tab at the root returns to Home, as on every tabbed Android app.
    val top = stack.top
    val canGoHome = stack.entries.size == 1 && top is Route.Tabs && top.tab != Tab.HOME
    BackHandler(enabled = stack.entries.size > 1 || canGoHome) {
        if (canGoHome) stack.replaceTop(Route.Tabs(Tab.HOME)) else stack.pop()
    }
    if (stack.entries.size == 1 && top is Route.Tabs && top.tab == Tab.HOME) {
        BackHandler { onFinish() }
    }

    // One ViewModelStore per back-stack entry, cleared shortly after the entry leaves (once its exit fade is over).
    val stores = remember { mutableMapOf<String, ViewModelStore>() }
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(stack) {
        snapshotFlow { stack.entries.mapIndexed { i, r -> "$i:${r.stateKey()}" }.toSet() }
            .distinctUntilChanged()
            .collect { live ->
                delay(800)
                val current = stack.entries.mapIndexed { i, r -> "$i:${r.stateKey()}" }.toSet()
                stores.keys.filter { it !in live && it !in current }.forEach { k ->
                    stores.remove(k)?.clear()
                    holder.removeState(k)
                }
            }
    }
    val reduced = rememberReducedMotion()
    val index = stack.entries.lastIndex
    val entryKey = "$index:${top.stateKey()}"
    // The shared book layer: screens publish where their book stands; one book flies between entries.
    val stage = rememberBookStage()
    SideEffect { stage.setTop(entryKey) }

    CompositionLocalProvider(LocalBookStage provides stage) {
    Box(Modifier.fillMaxSize().background(Kp.palette.bg)) {
        AnimatedContent(
            targetState = entryKey to top,
            contentKey = { it.first },
            transitionSpec = {
                if (reduced) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                else fadeIn(KpMotion.settle()) togetherWith fadeOut(KpMotion.snap())
            },
            label = "nav",
        ) { (key, route) ->
            EntryScope(key, stores, holder) {
              CompositionLocalProvider(LocalStageOwner provides key) {
                when (route) {
                    Route.Onboarding -> OnboardingRoute(onDone = { stack.resetTo(Route.Tabs(Tab.HOME)) })
                    is Route.Tabs -> TabsHost(
                        container = container,
                        tab = route.tab,
                        onSelect = { stack.replaceTop(Route.Tabs(it)) },
                        onOpenBook = { stack.push(Route.Hub(it)) },
                        onRead = { read(it) },
                        onListen = { listen(it) },
                        onRecap = { stack.push(Route.Learn(it, LearnTab.RECAP)) },
                        onReview = { stack.push(Route.Review) },
                        onSettings = { stack.push(Route.Settings) },
                        onJumpTo = { b, o -> jumpTo(b, o) },
                        onOpenPlayer = { stack.push(Route.Player(it)) },
                    )
                    is Route.Hub -> HubRoute(
                        bookId = route.bookId,
                        onRead = { read(route.bookId) },
                        onListen = { listen(route.bookId) },
                        onLearn = { stack.push(Route.Learn(route.bookId)) },
                        onSetup = { stack.push(Route.Setup(route.bookId)) },
                        onBack = { stack.pop() },
                        onLearnAt = { stack.push(Route.Learn(route.bookId, LearnTab.valueOf(it.name))) },
                    )
                    is Route.Reader -> ReaderHost(container, route, stack, onListenPermission = ::askNotificationsOnce) { sheet = it }
                    is Route.Player -> FullPlayerRoute(
                        controller = playback,
                        narration = container.narration,
                        annotations = data.annotations,
                        bookId = route.bookId,
                        onReadAlong = { ordinal ->
                            scope.launch {
                                data.positions.savePosition(route.bookId, PositionKind.READING, ordinal)
                                stack.replaceTop(Route.Reader(route.bookId, ReaderMode.READ_LISTEN))
                            }
                        },
                        onClose = { stack.pop() },
                    )
                    is Route.Learn -> LearnRoute(
                        deps = container.studyDeps,
                        bookId = route.bookId,
                        onJumpTo = { jumpTo(route.bookId, it) },
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
            }
        }

        // Above every screen, below sheets and toasts.
        BookStageOverlay(stage)

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

        val chrome = if (top is Route.Tabs) TabBarHeight else 0.dp
        KpToastHost(
            message = toast,
            onDismiss = { toast = null },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = chrome + 16.dp),
        )
    }
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
    val owner = remember(key) {
        val store = stores.getOrPut(key) { ViewModelStore() }
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        holder.SaveableStateProvider(key, content)
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
    fun setMode(mode: ReaderMode) {
        val top = stack.top
        if (top is Route.Reader && top.bookId == bookId) stack.replaceTop(top.copy(mode = mode))
    }
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

@Composable
private fun TabsHost(
    container: AppContainer,
    tab: Tab,
    onSelect: (Tab) -> Unit,
    onOpenBook: (BookId) -> Unit,
    onRead: (BookId) -> Unit,
    onListen: (BookId) -> Unit,
    onRecap: (BookId) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    onJumpTo: (BookId, Int) -> Unit,
    onOpenPlayer: (BookId) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    var bottomPx by remember { mutableIntStateOf(0) }
    val bottom = with(density) { bottomPx.toDp() }
    val playback = container.playback
    val hasBook by remember(playback) { playback.state.map { it.bookId != null }.distinctUntilChanged() }.collectAsState(false)

    Box(Modifier.fillMaxSize().background(Kp.palette.bg)) {
        Box(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(WindowInsets.navigationBars)
                .padding(bottom = bottom),
        ) {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
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
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height },
        ) {
            if (hasBook) {
                NowBar(
                    controller = playback,
                    onOpen = { playback.state.value.bookId?.let(onOpenPlayer) },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            KpTabBar(tab, onSelect)
        }
    }
}
