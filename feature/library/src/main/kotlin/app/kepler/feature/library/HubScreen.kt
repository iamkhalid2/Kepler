package app.kepler.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.BookObject
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpButton
import app.kepler.core.designsystem.KpButtonStyle
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpChevron
import app.kepler.core.designsystem.KpChip
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpRing
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSectionHeader
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpStatTile
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpTopBar
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberCoverImage
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.model.Book
import app.kepler.core.model.ContentType

@Composable
fun HubRoute(
    bookId: Long,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onLearn: () -> Unit,
    onSetup: () -> Unit,
    onBack: () -> Unit,
    deps: LibraryDeps = LocalLibraryDeps.current,
    onLearnAt: (HubLearnEntry) -> Unit = { onLearn() },
    onOpenChapter: ((sectionId: Long) -> Unit)? = null,
) {
    val vm = libraryViewModel(key = "hub-$bookId") { HubViewModel(deps, bookId) }
    val ui by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    HubScreen(ui, onRead, onListen, onLearn, onSetup, onBack, onLearnAt, onRemove = { vm.remove(onBack) }, onOpenChapter = onOpenChapter)
}

/** Lines of the hub's Learn list. Names match `LearnTab` in :feature:study (map with `LearnTab.valueOf(entry.name)`). */
enum class HubLearnEntry { ASK, RECAP, CONCEPTS, MAP, TAKEAWAYS }

@Composable
fun HubScreen(
    ui: HubUi,
    onRead: () -> Unit,
    onListen: () -> Unit,
    onLearn: () -> Unit,
    onSetup: () -> Unit,
    onBack: () -> Unit,
    onLearnAt: (HubLearnEntry) -> Unit = { onLearn() },
    onRemove: (() -> Unit)? = null,
    onOpenChapter: ((Long) -> Unit)? = null,
) {
    val p = Kp.palette
    val book = ui.book
    var confirmRemove by remember { mutableStateOf(false) }
    // Edge to edge at the top: the hero's cover light runs up under the status bar instead of stopping at a band.
    Box(
        Modifier.fillMaxSize().background(p.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (book == null) {
                KpTopBar(onBack = onBack, modifier = Modifier.statusBarsPadding())
                if (ui.loaded) Text(
                    stringResource(R.string.book_missing), color = p.text2, style = KpType.body,
                    modifier = Modifier.padding(KpSpace.xxl),
                )
                return@Column
            }
            Hero(book, ui, onBack)
            Column(Modifier.padding(horizontal = KpSpace.gutter)) {
                Spacer(Modifier.height(20.dp))
                StatRow(book, ui)
                Spacer(Modifier.height(KpSpace.cardGap))
                ActionTiles(ui, onRead, onListen, onLearn)
                Spacer(Modifier.height(KpSpace.cardGap))
                NarrationCard(ui, onSetup)
                Spacer(Modifier.height(KpSpace.section))
                LearnList(book, ui.cardCount, onLearnAt)
                if (ui.chapters.isNotEmpty()) {
                    Spacer(Modifier.height(KpSpace.section))
                    ChapterList(ui.chapters, onOpenChapter)
                }
                if (onRemove != null) {
                    Spacer(Modifier.height(KpSpace.l))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        KpTextLink(stringResource(R.string.remove_from_library), { confirmRemove = true }, color = p.negative)
                    }
                }
                Spacer(Modifier.height(KpSpace.x3))
            }
        }
    }
    if (confirmRemove && book != null && onRemove != null) {
        RemoveBookSheet(book.title, { confirmRemove = false; onRemove() }, { confirmRemove = false })
    }
}

/** Top bar with the genre chip, the cover's tint behind it, the book object, title and author. */
@Composable
private fun Hero(book: Book, ui: HubUi, onBack: () -> Unit) {
    val p = Kp.palette
    val bg = p.bg
    val request = remember(book.id, book.title, book.authors, book.coverPath) { book.coverRequest() }
    val tint = rememberCoverTint(request)
    Column(
        Modifier.fillMaxWidth().drawBehind {
            // Ambient light from the cover: its colour at 24% fading to the background.
            drawRect(
                Brush.radialGradient(
                    listOf(tint.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(size.width / 2f, 150.dp.toPx() + 56.dp.toPx()),
                    radius = size.width * 0.85f,
                ),
            )
            // The light dies out before the hero ends, so the stat tiles never sit under a hard edge.
            drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to bg))
        }.statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KpTopBar(onBack = onBack, actions = { KpChip(typeLabel(book)); Spacer(Modifier.width(12.dp)) })
        Spacer(Modifier.height(8.dp))
        BookObject(
            request, Modifier.height(200.dp), yaw = 18f, turnIn = true, ribbon = book.positionFraction,
            sharedElement = true, contentDescription = book.title,
        )
        // room for the ribbon hanging under the book
        Spacer(Modifier.height(20.dp + (80f * book.positionFraction).dp))
        Text(
            book.title, color = p.text, style = KpType.displayTitle, textAlign = TextAlign.Center,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = KpSpace.xxl),
        )
        if (book.authors.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                book.authors, color = p.text2, style = KpType.subhead, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = KpSpace.xxl),
            )
        }
    }
}

/** The cover's average colour, or the accent while it is unknown. */
@Composable
private fun rememberCoverTint(request: app.kepler.core.designsystem.CoverRequest): Color {
    val image = rememberCoverImage(request, 192)
    val fallback = Kp.palette.accent
    return remember(image) { image?.let(::averageColor) ?: fallback }
}

private fun averageColor(image: androidx.compose.ui.graphics.ImageBitmap): Color? = runCatching {
    val small = android.graphics.Bitmap.createScaledBitmap(image.asAndroidBitmap(), 6, 9, true)
    var r = 0L; var g = 0L; var b = 0L
    val n = small.width * small.height
    for (y in 0 until small.height) for (x in 0 until small.width) {
        val c = small.getPixel(x, y)
        r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c)
    }
    Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}.getOrNull()

@Composable
private fun typeLabel(book: Book): String = stringResource(
    when (book.contentType) {
        ContentType.FICTION -> R.string.type_fiction
        ContentType.NONFICTION -> R.string.type_nonfiction
        ContentType.MIXED -> R.string.type_mixed
    },
)

/** About 20 words a sentence at 230 words a minute. */
fun readMinutes(sentenceCount: Int): Int = (sentenceCount * 20 / 230).coerceAtLeast(1)

@Composable
private fun StatRow(book: Book, ui: HubUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(KpSpace.s)) {
        KpStatTile("${percentOf(book.positionFraction)}%", stringResource(R.string.stat_where_you_are), Modifier.weight(1f))
        KpStatTile(
            ui.minutesToListen?.let { minutesText(it) } ?: "–", stringResource(R.string.stat_left_to_listen), Modifier.weight(1f),
        )
        KpStatTile("${ui.cardCount}", stringResource(R.string.cards), Modifier.weight(1f))
    }
}

@Composable
private fun ActionTiles(ui: HubUi, onRead: () -> Unit, onListen: () -> Unit, onLearn: () -> Unit) {
    // The key fill follows how you used the book last; a book you have not opened yet leads with Listen.
    val listenPrimary = !ui.started || ui.primary == HubMode.Listen
    Row(horizontalArrangement = Arrangement.spacedBy(KpSpace.s)) {
        ActionTile(KpIcons.Book, stringResource(R.string.read), onRead, !listenPrimary, Modifier.weight(1f))
        ActionTile(KpIcons.Headphones, stringResource(R.string.listen), onListen, listenPrimary, Modifier.weight(1f))
        ActionTile(KpIcons.Sparkle, stringResource(R.string.learn), onLearn, false, Modifier.weight(1f))
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, primary: Boolean, modifier: Modifier) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(18.dp)
    val content = if (primary) p.onKey else p.text
    Column(
        modifier.height(88.dp).clip(shape).background(if (primary) p.key else p.surface1)
            .then(if (!p.isDark && !primary) Modifier.border(1.dp, p.hairline, shape) else Modifier)
            .clickable(role = Role.Button) { haptics.play(HapticToken.Tap); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KpIconView(icon, content, size = 24.dp)
        Spacer(Modifier.height(8.dp))
        Text(label, color = content, style = KpType.button)
    }
}

@Composable
private fun NarrationCard(ui: HubUi, onSetup: () -> Unit) {
    val p = Kp.palette
    val voice = ui.voiceName
    val (ringValue, text, link) = when (val n = ui.narration) {
        NarrationStatus.None -> Triple(0f, stringResource(R.string.narration_none), stringResource(R.string.set_up))
        is NarrationStatus.Binding ->
            if (n.running) Triple(n.fraction, stringResource(R.string.narration_binding, percentOf(n.fraction)), stringResource(R.string.view))
            else Triple(n.fraction, partial(percentOf(n.fraction), voice), stringResource(R.string.set_up))
        NarrationStatus.Bound -> Triple(
            1f,
            if (voice.isNullOrBlank()) stringResource(R.string.narration_ready) else stringResource(R.string.narration_ready_voice, voice),
            stringResource(R.string.change),
        )
        is NarrationStatus.Paused -> Triple(0f, stringResource(pausedReasonRes(n.reason)), stringResource(R.string.view))
        is NarrationStatus.Failed -> Triple(0f, stringResource(R.string.narration_failed), stringResource(R.string.set_up))
    }
    KpCard(padding = PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KpRing(ringValue, size = 40.dp, stroke = 3.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.narration_caps), color = p.text2, style = KpType.caption)
                Text(text, color = p.text, style = KpType.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            KpButton(link, onSetup, style = KpButtonStyle.Secondary, small = true)
        }
    }
}

@Composable
private fun partial(percent: Int, voice: String?): String =
    if (voice.isNullOrBlank()) stringResource(R.string.narration_partial, percent) else stringResource(R.string.narration_partial_voice, percent, voice)

/** Learn entries in one card; each right caption names the spoiler fence. */
@Composable
private fun LearnList(book: Book, cardCount: Int, onLearnAt: (HubLearnEntry) -> Unit) {
    val p = Kp.palette
    val reached = maxOf(book.positionFraction, book.readFraction, book.listenFraction)
    val upTo = if (reached < 0.005f) stringResource(R.string.learn_not_started) else stringResource(R.string.learn_up_to, percentOf(reached))
    val fence = if (book.contentType == ContentType.NONFICTION) stringResource(R.string.learn_whole_book) else upTo
    val entries = listOf(
        Triple(HubLearnEntry.ASK, R.string.learn_ask, fence) to KpIcons.Sparkle,
        // Only Ask may read past the frontier (whole book for non-fiction); the others stop at it.
        Triple(HubLearnEntry.RECAP, R.string.learn_recap, upTo) to KpIcons.ReplayRing,
        Triple(HubLearnEntry.CONCEPTS, R.string.learn_concepts, upTo) to KpIcons.Highlight,
        Triple(HubLearnEntry.MAP, R.string.learn_map, upTo) to KpIcons.Chapters,
        Triple(HubLearnEntry.TAKEAWAYS, R.string.learn_takeaways, stringResource(R.string.learn_by_chapter)) to KpIcons.Cards,
    )
    KpSectionHeader(
        stringResource(R.string.hub_learn_caps),
        actionLabel = null,
    )
    KpCard(padding = PaddingValues(0.dp)) {
        entries.forEachIndexed { i, (e, icon) ->
            val (entry, title, caption) = e
            KpRow(
                stringResource(title), meta = caption, onClick = { onLearnAt(entry) }, inset = 16.dp,
                leading = { KpIconView(icon, p.accentText, size = 22.dp) },
                trailing = { KpChevron() },
            )
            if (i < entries.lastIndex) KpRowSeparator(hasLeading = true, inset = 16.dp)
        }
    }
    if (cardCount > 0) {
        Spacer(Modifier.height(8.dp))
        Text(
            pluralStringResource(R.plurals.learn_cards, cardCount, cardCount),
            color = p.text2, style = KpType.caption, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Chapters with where you are: finished ones checked, the current one marked, a window of six until expanded. */
@Composable
private fun ChapterList(chapters: List<HubChapter>, onOpen: ((Long) -> Unit)?) {
    val p = Kp.palette
    var expanded by remember { mutableStateOf(false) }
    val currentIndex = chapters.indexOfFirst { it.state == ChapterState.Current }.coerceAtLeast(0)
    val start = (currentIndex - 1).coerceAtLeast(0)
    val shown = if (expanded || chapters.size <= 7) chapters else chapters.subList(start, minOf(chapters.size, start + 6))
    KpSectionHeader(
        pluralStringResource(R.plurals.hub_chapters, chapters.size, chapters.size),
        actionLabel = if (!expanded && chapters.size > 7) stringResource(R.string.show_all) else null,
        onAction = { expanded = true },
    )
    KpCard(padding = PaddingValues(0.dp)) {
        shown.forEachIndexed { i, c ->
            KpRow(
                c.title, inset = 16.dp,
                onClick = onOpen?.let { open -> { open(c.id) } },
                titleColor = if (c.state == ChapterState.Upcoming) p.text2 else p.text,
                leading = { ChapterMark(c.state) },
                trailing = if (c.state == ChapterState.Current) ({ Text(stringResource(R.string.you_are_here), color = p.accentText, style = KpType.caption) }) else null,
            )
            if (i < shown.lastIndex) KpRowSeparator(hasLeading = true, inset = 16.dp)
        }
    }
}

@Composable
private fun ChapterMark(state: ChapterState) {
    val p = Kp.palette
    when (state) {
        ChapterState.Done -> KpIconView(KpIcons.Check, p.accentText, size = 20.dp)
        ChapterState.Current -> Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(p.accent, CircleShape))
        }
        ChapterState.Upcoming -> Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).border(1.5.dp, p.text2.copy(alpha = 0.5f), CircleShape))
        }
    }
}
