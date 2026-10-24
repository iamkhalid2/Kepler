package app.kepler.feature.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.CoverImage
import app.kepler.core.designsystem.CoverRequest
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpChevron
import app.kepler.core.designsystem.KpCovers
import app.kepler.core.designsystem.KpIconButton
import app.kepler.core.designsystem.KpIconButtonStyle
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpInput
import app.kepler.core.designsystem.KpLargeTopBar
import app.kepler.core.designsystem.KpRadius
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSkeleton
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpStatTile
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberCoverStyle
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.model.Annotation
import app.kepler.core.model.Book
import app.kepler.core.model.Card

private fun Book.coverRequest() = CoverRequest(id, title, authors, coverPath)

/** Warms the cover cache the moment the books are known, so a header draws its cover on the first frame. */
@Composable
private fun PreloadCovers(books: List<Book>) {
    val style = rememberCoverStyle()
    val density = LocalDensity.current.density
    val ids = books.map { it.id to it.coverPath }
    LaunchedEffect(ids, style) { KpCovers.preload(books.map { it.coverRequest() }, style, (32 * density).toInt()) }
}

/**
 * Notebook: Highlights / Cards / Words / Stats, a review banner when cards are due, search and export.
 * `onJumpTo(bookId, ordinal)` opens a book at a sentence; `onStartReview` opens [ReviewRoute].
 */
@Composable
fun NotebookRoute(
    deps: StudyDeps,
    onJumpTo: (bookId: Long, ordinal: Int) -> Unit,
    onStartReview: () -> Unit,
) {
    val vm = studyViewModel("notebook") { NotebookViewModel(deps) }
    val d by vm.data.collectAsState()
    val p = Kp.palette
    var tab by rememberSaveable { mutableStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Annotation?>(null) }
    var card by remember { mutableStateOf<Card?>(null) }
    val now = remember(d) { deps.clock() }
    val books = remember(d) { (d.highlights.map { it.book } + d.cards.map { it.book } + d.words.map { it.book }).distinctBy { it.id } }
    PreloadCovers(books)

    val highlights = remember(d.highlights, query) { filterHighlights(d.highlights, query) }
    val cards = remember(d.cards, query) { filterCards(d.cards, query) }
    val words = remember(d.words, query) { filterCards(d.words, query) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            KpLargeTopBar(
                title = stringResource(R.string.notebook_caps),
                caption = if (d.loaded) pluralStringResource(R.plurals.n_highlights, highlightCount(d.highlights), highlightCount(d.highlights)) + " · " + pluralStringResource(R.plurals.n_cards, d.stats.cards, d.stats.cards) else null,
                actions = {
                    KpIconButton(
                        if (searching) KpIcons.Close else KpIcons.Search, stringResource(R.string.search),
                        { searching = !searching; if (!searching) query = "" }, style = KpIconButtonStyle.Tonal,
                    )
                    if (d.highlights.isNotEmpty()) {
                        KpIconButton(KpIcons.Download, stringResource(R.string.export), { vm.resetExport(); exporting = true }, style = KpIconButtonStyle.Tonal)
                    }
                },
            )
            if (searching) {
                KpInput(
                    query, { query = it }, Modifier.padding(horizontal = KpSpace.gutter).padding(bottom = 8.dp),
                    placeholder = stringResource(R.string.search_notebook),
                )
            }
            if (!d.loaded) NotebookSkeleton() else LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = KpSpace.gutter, end = KpSpace.gutter, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(KpSpace.cardGap),
            ) {
                if (d.dueCount > 0 || d.week != null) item(key = "banner") { ReviewBanner(d, onStartReview) }
                item(key = "tabs") {
                    Segmented(
                        listOf(R.string.nb_highlights, R.string.nb_cards, R.string.nb_words, R.string.nb_stats).map { stringResource(it) },
                        tab, { tab = it },
                    )
                }
                when (tab) {
                    0 -> highlightItems(highlights, query.isNotBlank(), now, deps, onOpen = { a -> onJumpTo(a.bookId, a.startOrdinal) }, onMenu = { highlight = it })
                    1 -> cardItems(cards, R.string.cards_empty, query.isNotBlank(), now) { card = it }
                    2 -> cardItems(words, R.string.words_empty, query.isNotBlank(), now) { card = it }
                    else -> statsItems(d)
                }
            }
        }
        highlight?.let { a ->
            HighlightSheet(a, vm, onDismiss = { highlight = null }, onOpen = { highlight = null; onJumpTo(a.bookId, a.startOrdinal) })
        }
        card?.let { c -> CardSheet(c, onDismiss = { card = null }, onDelete = { vm.deleteCard(c.id); card = null }, onOpen = { card = null; onJumpTo(c.bookId, c.sourceStart) }) }
        if (exporting) ExportSheet(d.highlights.map { it.book to it.count }, vm, onDismiss = { exporting = false })
    }
}

@Composable
private fun NotebookSkeleton() {
    Column(Modifier.padding(horizontal = KpSpace.gutter), verticalArrangement = Arrangement.spacedBy(KpSpace.cardGap)) {
        KpSkeleton(Modifier.fillMaxWidth().height(96.dp), KpRadius.card)
        KpSkeleton(Modifier.fillMaxWidth().height(40.dp), KpRadius.segmentTrack)
        KpSkeleton(Modifier.fillMaxWidth().height(120.dp), KpRadius.card)
        KpSkeleton(Modifier.fillMaxWidth().height(120.dp), KpRadius.card)
    }
}

/** Same card as Home's daily review: due count, minutes, the week's dots and a 56 dp Start. */
@Composable
private fun ReviewBanner(d: NotebookData, onStart: () -> Unit) {
    val p = Kp.palette
    KpCard(padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                KpCaps(stringResource(R.string.daily_review), color = p.accentText)
                Spacer(Modifier.height(4.dp))
                if (d.dueCount > 0) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${d.dueCount}", color = p.text, style = KpType.bigStat)
                        Text(pluralStringResource(R.plurals.cards_due, d.dueCount), color = p.text2, style = KpType.subhead, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                    }
                    Text(stringResource(R.string.about_minutes, ((d.dueCount + 1) / 2).coerceAtLeast(1)), color = p.text2, style = KpType.caption)
                } else {
                    Text(stringResource(R.string.caught_up), color = p.text, style = KpType.title2)
                    Text(stringResource(R.string.nothing_due), color = p.text2, style = KpType.caption)
                }
            }
            if (d.dueCount > 0) {
                val haptics = rememberHaptics()
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(p.key).clickable(role = Role.Button) { haptics.play(HapticToken.Confirm); onStart() },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.start), color = p.onKey, style = KpType.button) }
            }
        }
        d.week?.let { w ->
            Spacer(Modifier.height(12.dp))
            WeekDots(w, Modifier.fillMaxWidth())
        }
    }
}

// ----------------------------------------------------------------------------------------- Highlights

@Composable
private fun BookHeader(book: Book, count: String, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Row(modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(book.coverRequest(), Modifier.size(32.dp, 48.dp), KpRadius.coverSmall)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, color = p.text, style = KpType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count, color = p.text2, style = KpType.caption, maxLines = 1)
        }
    }
}

private fun LazyListScope.highlightItems(
    groups: List<BookHighlights>, searching: Boolean, now: Long, deps: StudyDeps,
    onOpen: (Annotation) -> Unit, onMenu: (Annotation) -> Unit,
) {
    if (groups.isEmpty()) {
        item(key = "empty-h") { EmptyNote(stringResource(if (searching) R.string.nothing_found else R.string.highlights_empty)) }
        return
    }
    groups.forEach { g ->
        item(key = "b${g.book.id}") { BookHeader(g.book, pluralStringResource(R.plurals.n_highlights, g.count, g.count)) }
        g.sections.forEach { sh ->
            val title = sh.section?.title?.takeIf { it.isNotBlank() }
            item(key = "s${g.book.id}-${sh.section?.id}") {
                Caps(title ?: stringResource(R.string.in_book), Modifier.padding(top = 4.dp))
            }
            items(sh.items, key = { "a${it.id}" }) { a -> HighlightCard(a, now, deps, onOpen, onMenu) }
        }
    }
}

@Composable
private fun HighlightCard(a: Annotation, now: Long, deps: StudyDeps, onOpen: (Annotation) -> Unit, onMenu: (Annotation) -> Unit) {
    val p = Kp.palette
    val tints = listOf(p.accent, p.secondary, p.positive, p.warning, p.negative, p.text2)
    val tint = tints[a.color.coerceIn(0, tints.lastIndex)]
    KpCard(onClick = { onOpen(a) }, padding = PaddingValues(0.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(tint))
            Column(Modifier.weight(1f).padding(start = 16.dp, top = 14.dp, bottom = 0.dp)) {
                Text(a.quote, color = p.text, style = StudyType.highlight(p.text), maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (!a.note.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        app.kepler.core.designsystem.KpIconView(KpIcons.Edit, p.text2, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(a.note!!, color = p.text2, style = KpType.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ageText(ageOf(a.createdAt, now, deps.zone)),
                        Modifier.weight(1f), color = p.text2, style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    KpIconButton(KpIcons.MoreHoriz, stringResource(R.string.more_actions), { onMenu(a) })
                }
            }
        }
    }
}

@Composable
internal fun ageText(age: Age): String = when (age.unit) {
    AgeUnit.TODAY -> stringResource(R.string.age_today)
    AgeUnit.YESTERDAY -> stringResource(R.string.age_yesterday)
    AgeUnit.DAYS -> pluralStringResource(R.plurals.age_days, age.value, age.value)
    AgeUnit.WEEKS -> pluralStringResource(R.plurals.age_weeks, age.value, age.value)
    AgeUnit.MONTHS -> pluralStringResource(R.plurals.age_months, age.value, age.value)
    AgeUnit.YEARS -> pluralStringResource(R.plurals.age_years, age.value, age.value)
}

// ----------------------------------------------------------------------------------------- Cards and words

private fun LazyListScope.cardItems(groups: List<BookCards>, empty: Int, searching: Boolean, now: Long, onOpen: (Card) -> Unit) {
    if (groups.isEmpty()) {
        item(key = "empty-c") { EmptyNote(stringResource(if (searching) R.string.nothing_found else empty)) }
        return
    }
    groups.forEach { g ->
        item(key = "b${g.book.id}") { BookHeader(g.book, pluralStringResource(R.plurals.n_cards, g.cards.size, g.cards.size)) }
        item(key = "cc${g.book.id}") {
            KpCard(padding = PaddingValues(0.dp)) {
                g.cards.forEachIndexed { i, c ->
                    if (i > 0) KpRowSeparator(inset = 16.dp)
                    CardRow(c, now, onOpen)
                }
            }
        }
    }
}

@Composable
private fun CardRow(c: Card, now: Long, onOpen: (Card) -> Unit) {
    val p = Kp.palette
    val due = dueIn(c, now)
    KpRow(
        c.front, meta = c.back.lineSequence().first(), onClick = { onOpen(c) }, inset = 16.dp,
        leading = {
            Box(Modifier.size(8.dp).clip(CircleShape).background(when (c.state) { 0 -> p.text2; 1, 3 -> p.warning; else -> p.positive }))
        },
        trailing = {
            Text(
                if (due == null) stringResource(R.string.due_now) else intervalText(due),
                color = if (due == null) p.accentText else p.text2, style = KpType.time,
            )
        },
    )
}

// ----------------------------------------------------------------------------------------- Stats

private fun LazyListScope.statsItems(d: NotebookData) {
    val st = d.stats
    item(key = "stat1") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KpSpace.cardGap)) {
            KpStatTile("${st.reviewedToday}", stringResource(R.string.stat_reviewed_today), Modifier.weight(1f))
            KpStatTile("${d.dueCount}", stringResource(R.string.stat_due_now), Modifier.weight(1f))
        }
    }
    item(key = "stat2") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KpSpace.cardGap)) {
            KpStatTile("${st.cards}", stringResource(R.string.nb_cards), Modifier.weight(1f))
            KpStatTile("${st.words}", stringResource(R.string.nb_words), Modifier.weight(1f))
        }
    }
    item(key = "maturity") { MaturityCard(st) }
    if (st.predictedRecall != null && st.actualRecall != null) item(key = "recall") { RecallCard(st) }
    d.week?.let { w ->
        item(key = "week") {
            KpCard(padding = PaddingValues(20.dp)) {
                KpCaps(stringResource(R.string.this_week))
                Spacer(Modifier.height(12.dp))
                WeekDots(w, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                WeekCaption(w)
            }
        }
    }
}

@Composable
private fun MaturityCard(st: ReviewStats) {
    val p = Kp.palette
    val (n, l, m) = maturityShares(st)
    KpCard(padding = PaddingValues(20.dp)) {
        KpCaps(stringResource(R.string.stat_maturity))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.surface2)) {
            if (n > 0f) Box(Modifier.weight(n).fillMaxHeight().background(p.text2))
            if (l > 0f) Box(Modifier.weight(l).fillMaxHeight().background(p.warning))
            if (m > 0f) Box(Modifier.weight(m).fillMaxHeight().background(p.positive))
        }
        Spacer(Modifier.height(12.dp))
        LegendRow(p.text2, stringResource(R.string.stat_new), st.newCards)
        LegendRow(p.warning, stringResource(R.string.stat_learning), st.learning)
        LegendRow(p.positive, stringResource(R.string.stat_mature), st.mature)
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: Int) {
    val p = Kp.palette
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = p.text, style = KpType.body)
        Text("$value", color = p.text2, style = KpType.numeral)
    }
}

@Composable
private fun RecallCard(st: ReviewStats) {
    val p = Kp.palette
    KpCard(padding = PaddingValues(20.dp)) {
        KpCaps(stringResource(R.string.stat_recall))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column {
                Text("${st.predictedRecall}%", color = p.text2, style = KpType.stat)
                Text(stringResource(R.string.predicted), color = p.text2, style = KpType.caption)
            }
            Column {
                Text("${st.actualRecall}%", color = p.text, style = KpType.stat)
                Text(stringResource(R.string.recalled), color = p.text2, style = KpType.caption)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(pluralStringResource(R.plurals.stat_recall_attempts, st.recallAttempts, st.recallAttempts), color = p.text2, style = KpType.caption)
    }
}

// ----------------------------------------------------------------------------------------- Sheets

@Composable
private fun HighlightSheet(a: Annotation, vm: NotebookViewModel, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val p = Kp.palette
    var note by remember(a.id) { mutableStateOf(a.note.orEmpty()) }
    StudySheet(onDismiss) {
        QuoteBlock(a.quote, maxLines = 8)
        Spacer(Modifier.height(20.dp))
        StudyInput(note, { note = it }, label = stringResource(R.string.note_label), placeholder = stringResource(R.string.note_hint), minLines = 3, inCard = true)
        Spacer(Modifier.height(16.dp))
        PrimaryKey(stringResource(R.string.save_note), { vm.saveNote(a, note); onDismiss() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryKey(stringResource(R.string.open_in_book), onOpen, small = true)
            KpTextLink(stringResource(R.string.delete), { vm.deleteHighlight(a.id); onDismiss() }, color = p.negative)
        }
    }
}

@Composable
private fun CardSheet(c: Card, onDismiss: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit) {
    val p = Kp.palette
    StudySheet(onDismiss) {
        Caps(stringResource(R.string.card_caps))
        Spacer(Modifier.height(12.dp))
        Text(c.front, color = p.text, style = KpType.title2)
        Spacer(Modifier.height(16.dp))
        Text(c.back, color = p.text, style = KpType.prose)
        if (!c.userAnswer.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Caps(stringResource(R.string.in_your_words)); Spacer(Modifier.height(4.dp))
            Text(c.userAnswer!!, color = p.text2, style = KpType.body)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryKey(stringResource(R.string.open_in_book), onOpen, small = true)
            KpTextLink(stringResource(R.string.delete), onDelete, color = p.negative)
        }
    }
}

@Composable
private fun ExportSheet(books: List<Pair<Book, Int>>, vm: NotebookViewModel, onDismiss: () -> Unit) {
    val p = Kp.palette
    val st by vm.export.collectAsState()
    var selected by remember { mutableStateOf(0) }
    var format by remember { mutableStateOf(0) }
    StudySheet(onDismiss) {
        SheetTitle(stringResource(R.string.export_caps))
        Spacer(Modifier.height(16.dp))
        Segmented(listOf(stringResource(R.string.fmt_markdown), stringResource(R.string.fmt_json)), format, { format = it })
        Text(stringResource(if (format == 0) R.string.fmt_markdown_note else R.string.fmt_json_note), Modifier.padding(top = 8.dp), color = p.text2, style = KpType.caption)
        Spacer(Modifier.height(8.dp))
        books.forEachIndexed { i, (b, n) ->
            ListRow({ selected = i }) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .then(if (i == selected) Modifier.background(p.accent) else Modifier.border(1.5.dp, p.hairline, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) { if (i == selected) app.kepler.core.designsystem.KpIconView(KpIcons.Check, p.onAccent, size = 14.dp) }
                Spacer(Modifier.width(16.dp))
                Text(b.title, Modifier.weight(1f), color = p.text, style = KpType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$n", color = p.text2, style = KpType.numeral)
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryKey(
            stringResource(R.string.export_action),
            { books.getOrNull(selected)?.let { vm.export(it.first, if (format == 0) ExportFormat.MARKDOWN else ExportFormat.WEB_ANNOTATION) } },
            Modifier.fillMaxWidth(), enabled = st.status != ExportStatus.WORKING,
        )
        when (st.status) {
            ExportStatus.DONE -> Text(stringResource(R.string.export_saved, st.fileName.orEmpty()), Modifier.padding(top = 12.dp), color = p.positive, style = KpType.caption)
            ExportStatus.FAILED -> Text(stringResource(R.string.export_failed), Modifier.padding(top = 12.dp), color = p.negative, style = KpType.caption)
            else -> Unit
        }
    }
}
