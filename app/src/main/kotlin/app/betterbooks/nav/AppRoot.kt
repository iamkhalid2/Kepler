package app.betterbooks.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.betterbooks.AppContainer
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.designsystem.BbMotion
import app.betterbooks.core.designsystem.rememberReducedMotion
import app.betterbooks.core.model.AppSettings
import app.betterbooks.core.model.BookId
import app.betterbooks.core.model.PositionKind
import app.betterbooks.core.model.ReaderMode
import app.betterbooks.feature.library.BetterBooksTheme
import app.betterbooks.feature.library.HomeRoute
import app.betterbooks.feature.library.HubRoute
import app.betterbooks.feature.library.LibraryRoute
import app.betterbooks.feature.library.NarrationSetupRoute
import app.betterbooks.feature.library.OnboardingRoute
import app.betterbooks.feature.library.ProvideLibraryDeps
import app.betterbooks.feature.library.SettingsRoute
import app.betterbooks.feature.player.FullPlayerRoute
import app.betterbooks.feature.player.NowBar
import app.betterbooks.feature.player.ReadAlongTransport
import app.betterbooks.feature.reader.ReaderRoute
import app.betterbooks.feature.study.CardFromSelectionSheet
import app.betterbooks.feature.study.DefineSheet
import app.betterbooks.feature.study.ExplainSheet
import app.betterbooks.feature.study.LearnRoute
import app.betterbooks.feature.study.LearnTab
import app.betterbooks.feature.study.NotebookRoute
import app.betterbooks.feature.study.ReviewRoute
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
    is Route.Reader -> "reader/$bookId"
    is Route.Learn -> "learn/$bookId"
    else -> encode()
}

@Composable
fun AppRoot(container: AppContainer, deepLinks: Flow<DeepLink>, onFinish: () -> Unit) {
    val settings by produceState<AppSettings?>(null) { value = container.data.settings.settings.first() }
    BetterBooksTheme(container.data.settings) {
        ProvideLibraryDeps(container.libraryDeps) {
            val s = settings
            if (s == null) {
                Box(Modifier.fillMaxSize().background(Bb.palette.bg))
            } else {
                val stack = rememberSaveable(saver = BackStack.Saver) {
                    BackStack(listOf(if (s.onboarded) Route.Tabs(Tab.HOME) else Route.Onboarding))
                }
                AppNavHost(container, stack, deepLinks, onFinish)
            }
        }
    }
}

@Composable
private fun AppNavHost(container: AppContainer, stack: BackStack, deepLinks: Flow<DeepLink>, onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = container.playback
    val data = container.data
    var sheet by remember { mutableStateOf<SheetReq?>(null) }

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

    fun read(bookId: BookId, mode: ReaderMode = ReaderMode.READ) = stack.push(Route.Reader(bookId, mode))

    fun jumpTo(bookId: BookId, ordinal: Int, mode: ReaderMode = ReaderMode.READ) {
        scope.launch {
            data.positions.savePosition(bookId, PositionKind.READING, ordinal)
            val top = stack.top
            if (top is Route.Reader && top.bookId == bookId) stack.pop()
            read(bookId, mode)
        }
    }

    LaunchedEffect(deepLinks) { deepLinks.collect { jumpTo(it.bookId, it.ordinal) } }

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

    Box(Modifier.fillMaxSize().background(Bb.palette.bg)) {
        AnimatedContent(
            targetState = entryKey to top,
            contentKey = { it.first },
            transitionSpec = {
                if (reduced) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                else fadeIn(BbMotion.settle()) togetherWith fadeOut(BbMotion.snap())
            },
            label = "nav",
        ) { (key, route) ->
            EntryScope(key, stores, holder) {
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
                    is Route.Setup -> NarrationSetupRoute(
                        bookId = route.bookId,
                        onBack = { stack.pop() },
                        onOpenSettings = { stack.push(Route.Settings) },
                    )
                    Route.Settings -> SettingsRoute(onBack = { stack.pop() })
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
    }
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
    val playingBook by playback.state.collectAsState()

    Box(Modifier.fillMaxSize().background(Bb.palette.bg)) {
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
            if (playingBook.bookId != null) {
                NowBar(
                    controller = playback,
                    onOpen = { playingBook.bookId?.let(onOpenPlayer) },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            BbTabBar(tab, onSelect)
        }
    }
}
