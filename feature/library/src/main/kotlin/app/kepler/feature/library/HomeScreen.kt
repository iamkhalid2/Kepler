package app.kepler.feature.library

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.BookObject
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpButton
import app.kepler.core.designsystem.KpButtonStyle
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpChevron
import app.kepler.core.designsystem.KpCoverTile
import app.kepler.core.designsystem.KpEmptyState
import app.kepler.core.designsystem.KpIconButton
import app.kepler.core.designsystem.KpIconButtonStyle
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpLargeTopBar
import app.kepler.core.designsystem.KpProgressBar
import app.kepler.core.designsystem.KpRing
import app.kepler.core.designsystem.KpSectionHeader
import app.kepler.core.designsystem.KpSkeleton
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpStreamingCaret
import app.kepler.core.designsystem.KpToastHost
import app.kepler.core.designsystem.KpType
import app.kepler.core.model.Book
import java.text.SimpleDateFormat
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home (brief v2 section 8): greeting, the Continue card with the book breaking out of its top edge, then
 * whatever is waiting: the recap after a gap, the daily review, narration progress, other books and highlights.
 */
@Composable
fun HomeRoute(
    onOpenBook: (bookId: Long) -> Unit,
    onRead: (bookId: Long) -> Unit,
    onListen: (bookId: Long) -> Unit,
    onRecap: (bookId: Long) -> Unit,
    onReview: () -> Unit,
    deps: LibraryDeps = LocalLibraryDeps.current,
    onSettings: (() -> Unit)? = null,
) {
    val vm = libraryViewModel { HomeViewModel(deps) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri.toString())
    }
    HomeScreen(
        ui = ui,
        onOpenBook = onOpenBook,
        onRead = onRead,
        onListen = onListen,
        onRecap = onRecap,
        onReview = onReview,
        onAdd = { picker.launch(arrayOf("application/epub+zip")) },
        onDismissMessage = vm::dismissMessage,
        onSettings = onSettings,
        onRetryRecap = vm::retryRecap,
    )
}

@Composable
fun HomeScreen(
    ui: HomeUi,
    onOpenBook: (Long) -> Unit,
    onRead: (Long) -> Unit,
    onListen: (Long) -> Unit,
    onRecap: (Long) -> Unit,
    onReview: () -> Unit,
    onAdd: () -> Unit,
    onDismissMessage: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onRetryRecap: () -> Unit = {},
) {
    val book = ui.current
    PreloadCovers(listOfNotNull(book) + ui.carousel, 104)
    ScreenRoot {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            KpLargeTopBar(
                title = stringResource(R.string.home_title),
                caption = remember { todayCaption() }.let { (day, part) -> stringResource(R.string.home_caption, day, stringResource(part)) },
                actions = {
                    if (onSettings != null) KpIconButton(KpIcons.Tune, stringResource(R.string.settings_caps), onSettings, style = KpIconButtonStyle.Tonal)
                },
            )
            when {
                !ui.loaded -> HomeSkeleton()
                book == null -> Box(Modifier.fillMaxWidth().padding(top = 96.dp)) {
                    KpEmptyState(
                        title = stringResource(R.string.home_empty_title),
                        body = stringResource(R.string.home_empty_body),
                        actionLabel = stringResource(R.string.add_a_book),
                        onAction = { if (!ui.importing) onAdd() },
                        art = {
                            BookObject(
                                app.kepler.core.designsystem.CoverRequest(0L, stringResource(R.string.home_empty_cover), "", null),
                                Modifier.height(168.dp),
                            )
                        },
                    )
                }
                else -> HomeContent(ui, book, onOpenBook, onRead, onListen, onRecap, onReview, onRetryRecap)
            }
            Spacer(Modifier.height(KpSpace.l))
        }
        KpToastHost(
            message = if (ui.importFailed) stringResource(R.string.import_failed) else null,
            onDismiss = onDismissMessage,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun HomeContent(
    ui: HomeUi, book: Book,
    onOpenBook: (Long) -> Unit, onRead: (Long) -> Unit, onListen: (Long) -> Unit, onRecap: (Long) -> Unit,
    onReview: () -> Unit, onRetryRecap: () -> Unit,
) {
    Column(Modifier.padding(horizontal = KpSpace.gutter), verticalArrangement = Arrangement.spacedBy(KpSpace.cardGap)) {
        Spacer(Modifier.height(KpSpace.m))
        ContinueCard(ui, book, onOpenBook, onRead, onListen)
        if (ui.recap != RecapUi.None && ui.daysAway != null) {
            RecapCard(ui.recap, ui.daysAway, ui.current?.title.orEmpty(), { onRecap(book.id) }, onRetryRecap)
        }
        if (ui.dueCount > 0 || ui.reviewedToday > 0) ReviewCard(ui, onReview)
        ui.generating?.let { f -> NarrationCard(f, ui.voiceName, book, onOpenBook) }
    }
    if (ui.carousel.isNotEmpty()) {
        Spacer(Modifier.height(KpSpace.section))
        KpSectionHeader(stringResource(R.string.continue_label), Modifier.padding(horizontal = KpSpace.gutter))
        LazyRow(
            contentPadding = PaddingValues(horizontal = KpSpace.gutter),
            horizontalArrangement = Arrangement.spacedBy(KpSpace.m),
        ) {
            items(ui.carousel, key = { it.id }) { b ->
                Box(Modifier.width(104.dp).clip(KpRadiusCover).clickable { onOpenBook(b.id) }) {
                    KpCoverTile(b.coverRequest(), b.positionFraction)
                }
            }
        }
    }
    if (ui.highlights.isNotEmpty()) {
        Spacer(Modifier.height(KpSpace.section))
        KpSectionHeader(stringResource(R.string.home_highlights), Modifier.padding(horizontal = KpSpace.gutter))
        Column(Modifier.padding(horizontal = KpSpace.gutter), verticalArrangement = Arrangement.spacedBy(KpSpace.cardGap)) {
            ui.highlights.forEach { h -> HighlightCard(h) { onOpenBook(h.bookId) } }
        }
    }
}

private val KpRadiusCover = app.kepler.core.designsystem.KpRadius.cover

@Composable
private fun ContinueCard(ui: HomeUi, book: Book, onOpenBook: (Long) -> Unit, onRead: (Long) -> Unit, onListen: (Long) -> Unit) {
    val p = Kp.palette
    val listening = ui.mode == HubMode.Listen
    // The book rises 24 dp above the card, as in the MidnightReads dashboard shot.
    Box(Modifier.padding(top = 24.dp)) {
        KpCard(onClick = { onOpenBook(book.id) }, padding = PaddingValues(20.dp)) {
            Row {
                Spacer(Modifier.width(88.dp + 16.dp))
                Column(Modifier.weight(1f)) {
                    val mode = stringResource(if (listening) R.string.home_listening else R.string.home_reading)
                    KpCaps(
                        listOfNotNull(mode, ui.chapterTitle).joinToString(" · "),
                        color = p.accentText,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(book.title, color = p.text, style = KpType.title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.authors.isNotBlank()) {
                        Text(book.authors, color = p.text2, style = KpType.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            KpProgressBar(book.positionFraction, Modifier.fillMaxWidth(), height = 4.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (ui.started) {
                    val left = ui.minutesLeft
                    if (left != null) stringResource(R.string.home_position_left, percentOf(book.positionFraction), minutesText(left))
                    else stringResource(R.string.home_through, percentOf(book.positionFraction))
                } else stringResource(R.string.home_not_started),
                color = p.text2, style = KpType.caption,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(KpSpace.s)) {
                val listenButton = @Composable { primary: Boolean ->
                    KpButton(
                        stringResource(R.string.listen), { onListen(book.id) }, Modifier.weight(1f),
                        style = if (primary) KpButtonStyle.Primary else KpButtonStyle.Secondary,
                        small = true, icon = KpIcons.Headphones, fullWidth = true,
                    )
                }
                val readButton = @Composable { primary: Boolean ->
                    KpButton(
                        stringResource(R.string.read), { onRead(book.id) }, Modifier.weight(1f),
                        style = if (primary) KpButtonStyle.Primary else KpButtonStyle.Secondary,
                        small = true, icon = KpIcons.Book, fullWidth = true,
                    )
                }
                // The way you last used the book is the key-filled one; both resume in one tap.
                if (listening) { listenButton(true); readButton(false) } else { readButton(true); listenButton(false) }
            }
        }
        BookObject(
            book.coverRequest(),
            Modifier.offset(x = 20.dp, y = (-24).dp).height(132.dp),
            yaw = 18f,
            ribbon = null,
            sharedElement = true,
            contentDescription = book.title,
        )
    }
}

@Composable
private fun RecapCard(recap: RecapUi, days: Int, bookTitle: String, onFull: () -> Unit, onRetry: () -> Unit) {
    val p = Kp.palette
    KpCard(padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KpCaps(stringResource(R.string.home_previously_on), Modifier.weight(1f), color = p.accentText)
            Text(pluralStringResource(R.plurals.home_days_away_n, days, days), color = p.text2, style = KpType.caption)
        }
        Spacer(Modifier.height(8.dp))
        when (recap) {
            RecapUi.None -> Unit
            RecapUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recap_reading), color = p.text2, style = KpType.prose)
                Spacer(Modifier.width(4.dp))
                KpStreamingCaret()
            }
            is RecapUi.Streaming -> Text(
                recap.text, color = p.text, style = KpType.prose.copy(fontSize = 16.sp, lineHeight = 24.sp),
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            is RecapUi.Done -> Text(
                recap.text, color = p.text, style = KpType.prose.copy(fontSize = 16.sp, lineHeight = 24.sp),
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            RecapUi.Failed -> Text(stringResource(R.string.recap_failed), color = p.text2, style = KpType.body)
        }
        Spacer(Modifier.height(12.dp))
        if (recap == RecapUi.Failed) {
            KpButton(stringResource(R.string.try_again), onRetry, style = KpButtonStyle.Secondary, small = true)
        } else {
            KpButton(stringResource(R.string.full_recap), onFull, style = KpButtonStyle.Secondary, small = true)
        }
    }
}

@Composable
private fun ReviewCard(ui: HomeUi, onReview: () -> Unit) {
    val p = Kp.palette
    KpCard(padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                KpCaps(stringResource(R.string.home_daily_review), color = p.accentText)
                Spacer(Modifier.height(4.dp))
                if (ui.dueCount > 0) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${ui.dueCount}", color = p.text, style = KpType.bigStat)
                        Text(
                            stringResource(R.string.home_due_label), color = p.text2, style = KpType.subhead,
                            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.home_review_about, reviewMinutes(ui.dueCount)) +
                            if (ui.reviewedToday > 0) " · " + stringResource(R.string.home_done_today, ui.reviewedToday) else "",
                        color = p.text2, style = KpType.caption,
                    )
                } else {
                    Text(stringResource(R.string.home_caught_up), color = p.text, style = KpType.title2)
                    Text(stringResource(R.string.home_done_today, ui.reviewedToday), color = p.text2, style = KpType.caption)
                }
            }
            if (ui.dueCount > 0) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(p.key).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onReview),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.home_start), color = p.onKey, style = KpType.button) }
            }
        }
        if (ui.week.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(KpSpace.m)) { ui.week.forEach { WeekDotView(it) } }
        }
    }
}

@Composable
private fun WeekDotView(d: WeekDot) {
    val p = Kp.palette
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(20.dp).clip(CircleShape)
                .background(if (d.reviewed) p.accent else p.surface2)
                .then(if (d.today && !d.reviewed) Modifier.border(1.5.dp, p.accent, CircleShape) else Modifier),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            d.day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            color = if (d.today) p.text else p.text2, style = KpType.caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NarrationCard(fraction: Float, voice: String?, book: Book, onOpenBook: (Long) -> Unit) {
    val p = Kp.palette
    KpCard(onClick = { onOpenBook(book.id) }, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KpRing(fraction, size = 40.dp, stroke = 3.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                val pct = percentOf(fraction)
                Text(
                    if (voice.isNullOrBlank()) stringResource(R.string.home_narrating, pct) else stringResource(R.string.home_narrating_voice, pct, voice),
                    color = p.text, style = KpType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(book.title, color = p.text2, style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            KpChevron()
        }
    }
}

@Composable
private fun HighlightCard(h: HomeHighlight, onClick: () -> Unit) {
    val p = Kp.palette
    KpCard(onClick = onClick, padding = PaddingValues(16.dp)) {
        Row {
            Box(Modifier.width(3.dp).height(72.dp).clip(CircleShape).background(p.accent))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(h.quote, color = p.text, style = KpType.prose.copy(fontSize = 15.sp, lineHeight = 24.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val ago = remember(h.createdAt) {
                    DateUtils.getRelativeTimeSpanString(h.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                }
                Text("${h.bookTitle} · $ago", color = p.text2, style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.padding(horizontal = KpSpace.gutter), verticalArrangement = Arrangement.spacedBy(KpSpace.cardGap)) {
        Spacer(Modifier.height(KpSpace.m))
        KpSkeleton(Modifier.fillMaxWidth().height(200.dp), app.kepler.core.designsystem.KpRadius.card)
        KpSkeleton(Modifier.fillMaxWidth().height(140.dp), app.kepler.core.designsystem.KpRadius.card)
        KpSkeleton(Modifier.fillMaxWidth().height(72.dp), app.kepler.core.designsystem.KpRadius.card)
    }
}

private fun todayCaption(): Pair<String, Int> {
    val part = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> R.string.part_morning
        in 12..17 -> R.string.part_afternoon
        else -> R.string.part_evening
    }
    return SimpleDateFormat("EEEE", Locale.getDefault()).format(Date()) to part
}
