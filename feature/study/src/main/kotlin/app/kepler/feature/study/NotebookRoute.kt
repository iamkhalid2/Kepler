package app.kepler.feature.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.bookMaterials
import app.kepler.core.model.Annotation
import app.kepler.core.model.Book
import app.kepler.core.model.Card

/**
 * Notebook: Review / Highlights / Cards / Words, plus export.
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
    var exporting by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Annotation?>(null) }
    var card by remember { mutableStateOf<Card?>(null) }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Caps(stringResource(R.string.notebook_caps), Modifier.weight(1f))
                if (d.highlights.isNotEmpty()) TextLink(stringResource(R.string.export), { vm.resetExport(); exporting = true })
            }
            Segmented(
                listOf(R.string.nb_review, R.string.nb_highlights, R.string.nb_cards, R.string.nb_words).map { stringResource(it) },
                tab, { tab = it }, Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!d.loaded) Box(Modifier.padding(24.dp)) { LoadingBlocks(4) } else when (tab) {
                    0 -> ReviewPane(d, onStartReview)
                    1 -> HighlightsPane(d.highlights, onOpen = { highlight = it })
                    2 -> CardsPane(d.cards, stringResource(R.string.cards_empty)) { card = it }
                    else -> CardsPane(d.words, stringResource(R.string.words_empty)) { card = it }
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
private fun ReviewPane(d: NotebookData, onStartReview: () -> Unit) {
    val p = Kp.palette
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        if (d.dueCount > 0) {
            Caps(stringResource(R.string.due_today))
            BasicText("${d.dueCount}", style = StudyType.bigNumeral(p.text))
            BasicText(stringResource(R.string.about_minutes, ((d.dueCount + 1) / 2).coerceAtLeast(1)), style = StudyType.caption(p.text2))
            Spacer(Modifier.height(24.dp))
            PrimaryKey(stringResource(R.string.start_review), onStartReview, Modifier.fillMaxWidth())
        } else {
            BasicText(stringResource(R.string.nothing_due), style = StudyType.headline(p.text))
        }
        d.week?.let { w ->
            Spacer(Modifier.height(32.dp))
            Hairline()
            Spacer(Modifier.height(24.dp))
            Caps(stringResource(R.string.this_week))
            Spacer(Modifier.height(12.dp))
            WeekSquares(w)
            Spacer(Modifier.height(12.dp))
            WeekCaption(w)
        }
    }
}

/** 12x40 spine tinted from the shared book materials, one per book. */
@Composable
private fun MiniSpine(book: Book) {
    val p = Kp.palette
    val mats = remember(p) { bookMaterials(p) }
    val m = mats.getOrNull(((book.id % mats.size) + mats.size).toInt() % mats.size.coerceAtLeast(1))
    Box(Modifier.size(12.dp, 40.dp).clip(RoundedCornerShape(1.5.dp)).background(m?.cloth ?: p.surface2))
}

@Composable
private fun GroupHeader(book: Book, count: Int) {
    val p = Kp.palette
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MiniSpine(book)
        Spacer(Modifier.width(12.dp))
        BasicText(book.title, Modifier.weight(1f), style = StudyType.title(p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("$count", style = StudyType.numeral(p.text2, 14f))
    }
}

@Composable
private fun HighlightsPane(groups: List<BookHighlights>, onOpen: (Annotation) -> Unit) {
    val p = Kp.palette
    if (groups.isEmpty()) { Box(Modifier.padding(24.dp)) { EmptyNote(stringResource(R.string.highlights_empty)) }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 0.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        groups.forEach { g ->
            item(key = "b${g.book.id}") { GroupHeader(g.book, g.count) }
            g.sections.forEach { sh ->
                items(sh.items, key = { "a${it.id}" }) { a ->
                    Column(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(a) }
                            .drawBehind { drawRect(p.pencil, size = Size(2.dp.toPx(), size.height)) }
                            .padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        BasicText(a.quote, style = StudyType.prose(p.text).copy(lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)), maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (!a.note.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            BasicText(a.note!!, style = StudyType.body(p.text2), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(4.dp))
                        BasicText(sh.section?.title?.ifBlank { null } ?: stringResource(R.string.in_book), style = StudyType.caption(p.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CardsPane(groups: List<BookCards>, empty: String, onOpen: (Card) -> Unit) {
    val p = Kp.palette
    if (groups.isEmpty()) { Box(Modifier.padding(24.dp)) { EmptyNote(empty) }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp)) {
        groups.forEach { g ->
            item(key = "b${g.book.id}") { GroupHeader(g.book, g.cards.size) }
            items(g.cards, key = { "c${it.id}" }) { c ->
                ListRow({ onOpen(c) }, minHeight = 72.dp) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(
                        when (c.state) { 0 -> p.text2; 1, 3 -> p.secondary; else -> p.positive },
                    ))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(c.front, style = StudyType.rowTitle(p.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        BasicText(c.back.lineSequence().first(), style = StudyType.caption(p.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightSheet(a: Annotation, vm: NotebookViewModel, onDismiss: () -> Unit, onOpen: () -> Unit) {
    var note by remember(a.id) { mutableStateOf(a.note.orEmpty()) }
    StudySheet(onDismiss) {
        QuoteBlock(a.quote, maxLines = 8)
        Spacer(Modifier.height(24.dp))
        StudyInput(note, { note = it }, label = stringResource(R.string.note_label), placeholder = stringResource(R.string.note_hint), minLines = 3)
        Spacer(Modifier.height(16.dp))
        PrimaryKey(stringResource(R.string.save_note), { vm.saveNote(a, note); onDismiss() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryKey(stringResource(R.string.open_in_book), onOpen)
            Spacer(Modifier.width(8.dp))
            TextLink(stringResource(R.string.delete), { vm.deleteHighlight(a.id); onDismiss() })
        }
    }
}

@Composable
private fun CardSheet(c: Card, onDismiss: () -> Unit, onDelete: () -> Unit, onOpen: () -> Unit) {
    val p = Kp.palette
    StudySheet(onDismiss) {
        Caps(stringResource(R.string.card_caps))
        Spacer(Modifier.height(12.dp))
        BasicText(c.front, style = StudyType.title(p.text))
        Spacer(Modifier.height(16.dp))
        BasicText(c.back, style = StudyType.prose(p.text))
        if (!c.userAnswer.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Caps(stringResource(R.string.in_your_words)); Spacer(Modifier.height(4.dp))
            BasicText(c.userAnswer!!, style = StudyType.body(p.text2))
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryKey(stringResource(R.string.open_in_book), onOpen)
            Spacer(Modifier.width(8.dp))
            TextLink(stringResource(R.string.delete), onDelete)
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
        Caps(stringResource(R.string.export_caps))
        Spacer(Modifier.height(16.dp))
        Segmented(listOf(stringResource(R.string.fmt_markdown), stringResource(R.string.fmt_json)), format, { format = it })
        Spacer(Modifier.height(4.dp))
        BasicText(stringResource(if (format == 0) R.string.fmt_markdown_note else R.string.fmt_json_note), Modifier.padding(top = 8.dp), style = StudyType.caption(p.text2))
        Spacer(Modifier.height(16.dp))
        books.forEachIndexed { i, (b, n) ->
            ListRow({ selected = i }) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == selected) p.accent else p.hairline))
                Spacer(Modifier.width(16.dp))
                BasicText(b.title, Modifier.weight(1f), style = StudyType.rowTitle(p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("$n", style = StudyType.numeral(p.text2, 14f))
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryKey(
            stringResource(R.string.export_action),
            { books.getOrNull(selected)?.let { vm.export(it.first, if (format == 0) ExportFormat.MARKDOWN else ExportFormat.WEB_ANNOTATION) } },
            Modifier.fillMaxWidth(), enabled = st.status != ExportStatus.WORKING,
        )
        when (st.status) {
            ExportStatus.DONE -> BasicText(stringResource(R.string.export_saved, st.fileName.orEmpty()), Modifier.padding(top = 12.dp), style = StudyType.caption(p.positive))
            ExportStatus.FAILED -> BasicText(stringResource(R.string.export_failed), Modifier.padding(top = 12.dp), style = StudyType.caption(p.negative))
            else -> Unit
        }
    }
}
