package app.kepler.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.BOOK_H
import app.kepler.core.designsystem.BOOK_W
import app.kepler.core.designsystem.BookMaterial
import app.kepler.core.designsystem.BookObject
import app.kepler.core.designsystem.BookPose
import app.kepler.core.designsystem.BookScene
import app.kepler.core.designsystem.CoverImage
import app.kepler.core.designsystem.CoverRequest
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpChip
import app.kepler.core.designsystem.KpCoverTile
import app.kepler.core.designsystem.KpEmptyState
import app.kepler.core.designsystem.KpIconButton
import app.kepler.core.designsystem.KpIconButtonStyle
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpLargeTopBar
import app.kepler.core.designsystem.KpRing
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpSectionHeader
import app.kepler.core.designsystem.KpSheet
import app.kepler.core.designsystem.KpSkeleton
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpToastHost
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.PlankBaseline
import app.kepler.core.designsystem.PlankHeight
import app.kepler.core.designsystem.ShelfPlank
import app.kepler.core.designsystem.rememberCoverImage
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.model.Book

private const val GUTTER_DP = 20f

@Composable
fun LibraryRoute(
    onOpenBook: (bookId: Long) -> Unit,
    deps: LibraryDeps = LocalLibraryDeps.current,
    onRead: ((bookId: Long) -> Unit)? = null,
    onListen: ((bookId: Long) -> Unit)? = null,
) {
    val vm = libraryViewModel { LibraryViewModel(deps) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri.toString())
    }
    LibraryScreen(
        ui = ui,
        onOpenBook = onOpenBook,
        onAdd = { picker.launch(arrayOf("application/epub+zip")) },
        onQuery = vm::setQuery,
        onFilter = vm::setFilter,
        onToggleLayout = vm::toggleLayout,
        onSort = vm::setSort,
        onLongPress = vm::openMenu,
        onCloseMenu = vm::closeMenu,
        onRemove = vm::requestDelete,
        onConfirmDelete = vm::confirmDelete,
        onCancelDelete = vm::cancelDelete,
        onDismissMessage = vm::dismissMessage,
        onRead = onRead,
        onListen = onListen,
    )
}

@Composable
fun LibraryScreen(
    ui: LibraryUi,
    onOpenBook: (Long) -> Unit,
    onAdd: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onToggleLayout: () -> Unit,
    onSort: (LibrarySort) -> Unit,
    onLongPress: (Book) -> Unit,
    onCloseMenu: () -> Unit,
    onRemove: (Book) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismissMessage: () -> Unit,
    onRead: ((Long) -> Unit)? = null,
    onListen: ((Long) -> Unit)? = null,
) {
    val p = Kp.palette
    var sortSheet by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    val header: @Composable () -> Unit = {
        Column {
            KpLargeTopBar(
                title = stringResource(R.string.library_title),
                actions = {
                    val bookcase = ui.layout == LibraryLayout.Bookcase
                    KpIconButton(
                        if (bookcase) KpIcons.Grid else KpIcons.Bookcase,
                        stringResource(if (bookcase) R.string.show_grid else R.string.show_bookcase),
                        onToggleLayout, style = KpIconButtonStyle.Tonal,
                    )
                    KpIconButton(KpIcons.Add, stringResource(R.string.add_book), onAdd, style = KpIconButtonStyle.Tonal)
                },
            )
            if (!ui.isEmpty && ui.loaded) {
                SearchPill(ui.query, onQuery, stringResource(R.string.search_hint), Modifier.padding(horizontal = KpSpace.gutter))
                Spacer(Modifier.height(12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = KpSpace.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LibraryFilter.entries) { f ->
                        KpChip(stringResource(f.label()), selected = f == ui.filter, onClick = { onFilter(f) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.padding(horizontal = KpSpace.gutter), verticalAlignment = Alignment.CenterVertically) {
                    KpSectionHeader(
                        pluralStringResource(R.plurals.library_books, ui.books.size, ui.books.size),
                        Modifier.weight(1f),
                        actionLabel = stringResource(R.string.sort_link, stringResource(ui.sort.label())),
                        onAction = { sortSheet = true },
                    )
                }
            }
        }
    }

    ScreenRoot {
        Box(Modifier.fillMaxSize()) {
            when {
                !ui.loaded -> Column { header(); LibrarySkeleton() }
                ui.isEmpty -> Column {
                    header()
                    Box(Modifier.fillMaxWidth().padding(top = 72.dp)) {
                        KpEmptyState(
                            title = stringResource(R.string.empty_title),
                            body = stringResource(R.string.empty_body),
                            actionLabel = stringResource(R.string.add_a_book),
                            onAction = { if (!ui.importing) onAdd() },
                            art = { BookObject(CoverRequest(0L, stringResource(R.string.home_empty_cover), "", null), Modifier.height(150.dp)) },
                        )
                    }
                }
                ui.layout == LibraryLayout.Bookcase -> BookcaseView(ui, header, onOpenBook, onLongPress, onAdd)
                else -> GridView(ui, header, onOpenBook, onLongPress)
            }
            KpToastHost(
                message = ui.message?.let { m ->
                    when (m) {
                        LibraryMessage.ImportFailed -> stringResource(R.string.import_failed)
                        is LibraryMessage.Duplicate -> stringResource(R.string.import_duplicate, m.title)
                        is LibraryMessage.Imported -> stringResource(R.string.import_done, m.title)
                    }
                },
                onDismiss = onDismissMessage,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }

    if (sortSheet) {
        KpSheet(onDismiss = { sortSheet = false }) {
            Text(stringResource(R.string.sort_by), color = p.text, style = KpType.title2)
            Spacer(Modifier.height(8.dp))
            LibrarySort.entries.forEach { s ->
                SelectableRow(stringResource(s.label()), selected = s == ui.sort) {
                    haptics.play(HapticToken.Tap); onSort(s); sortSheet = false
                }
            }
        }
    }
    ui.menuFor?.let { b ->
        BookActionsSheet(
            b, onDismiss = onCloseMenu,
            onOpen = { onCloseMenu(); onOpenBook(b.id) },
            onRead = onRead?.let { r -> { onCloseMenu(); r(b.id) } },
            onListen = onListen?.let { l -> { onCloseMenu(); l(b.id) } },
            onRemove = { onRemove(b) },
        )
    }
    ui.pendingDelete?.let { b -> RemoveBookSheet(b.title, onConfirmDelete, onCancelDelete) }
}

private fun LibrarySort.label(): Int = when (this) {
    LibrarySort.Recent -> R.string.sort_recent
    LibrarySort.Title -> R.string.sort_title
    LibrarySort.Author -> R.string.sort_author
}

private fun LibraryFilter.label(): Int = when (this) {
    LibraryFilter.All -> R.string.filter_all
    LibraryFilter.Reading -> R.string.filter_reading
    LibraryFilter.Listening -> R.string.filter_listening
    LibraryFilter.Finished -> R.string.filter_finished
    LibraryFilter.NonFiction -> R.string.filter_nonfiction
}

/** The 44 dp search pill (brief v2 section 8). */
@Composable
internal fun SearchPill(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(p.surface1)
            .then(if (!p.isDark) Modifier.border(1.dp, p.hairline, CircleShape) else Modifier)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KpIconView(KpIcons.Search, p.text2, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = KpType.body.copy(color = p.text), cursorBrush = SolidColor(p.accent),
            )
            if (value.isEmpty()) Text(placeholder, color = p.text2, style = KpType.body, maxLines = 1)
        }
        if (value.isNotEmpty()) {
            val clear = stringResource(R.string.clear)
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { onChange("") }.semantics { contentDescription = clear },
                contentAlignment = Alignment.Center,
            ) { KpIconView(KpIcons.Close, p.text2, size = 18.dp) }
        }
    }
}

@Composable
internal fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = Kp.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        KpRow(
            label, Modifier.weight(1f), onClick = onClick,
            leading = { if (selected) KpIconView(KpIcons.Check, p.accentText, size = 20.dp) },
        )
    }
}

@Composable
private fun LibrarySkeleton() {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = KpSpace.gutter)) {
        val w = (maxWidth - 24.dp) / 3
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) { KpSkeleton(Modifier.width(w).height(w * 1.5f), app.kepler.core.designsystem.KpRadius.cover) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ grid

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridView(ui: LibraryUi, header: @Composable () -> Unit, onOpen: (Long) -> Unit, onLongPress: (Book) -> Unit) {
    val haptics = rememberHaptics()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 600.dp) 5 else 3
        val tileDp = ((maxWidth.value - 2 * GUTTER_DP - (columns - 1) * 12f) / columns).toInt()
        PreloadCovers(ui.books, tileDp)
        LazyVerticalGrid(
            GridCells.Fixed(columns), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = KpSpace.gutter, end = KpSpace.gutter, bottom = KpSpace.l),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                // The header is laid out edge to edge, so undo the grid's gutter around it.
                Box(Modifier.layout(outsetGutter)) { header() }
            }
            if (ui.noMatches) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "none") {
                    Text(
                        stringResource(R.string.no_matches, ui.query), color = Kp.palette.text2, style = KpType.body,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(ui.books, key = { it.id }) { b ->
                Box(
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onLongClick = { haptics.play(HapticToken.Confirm); onLongPress(b) },
                        onClick = { haptics.play(HapticToken.Tap); onOpen(b.id) },
                    ),
                ) {
                    KpCoverTile(
                        b.coverRequest(), b.positionFraction, sharedElement = true,
                        badge = when (val badge = ui.badges[b.id]) {
                            is BookBadge.Narrated -> ({ KpIconView(KpIcons.Headphones, Kp.palette.text, size = 14.dp) })
                            is BookBadge.Generating -> ({ KpRing(badge.fraction, size = 16.dp, stroke = 2.dp) })
                            else -> null
                        },
                    )
                }
            }
        }
    }
}

/** Lets a full-width child ignore the horizontal content padding of its lazy container. */
private val outsetGutter: androidx.compose.ui.layout.MeasureScope.(androidx.compose.ui.layout.Measurable, androidx.compose.ui.unit.Constraints) -> androidx.compose.ui.layout.MeasureResult =
    { measurable, constraints ->
        val extra = (GUTTER_DP * 2).dp.roundToPx()
        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.maxWidth + extra))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    }

@Composable
private fun BookActionsSheet(
    book: Book, onDismiss: () -> Unit, onOpen: () -> Unit, onRead: (() -> Unit)?, onListen: (() -> Unit)?, onRemove: () -> Unit,
) {
    val p = Kp.palette
    KpSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(book.coverRequest(), Modifier.size(40.dp, 60.dp), shape = app.kepler.core.designsystem.KpRadius.coverSmall)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(book.title, color = p.text, style = KpType.title2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.authors.isNotBlank()) Text(book.authors, color = p.text2, style = KpType.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        SheetRow(KpIcons.Library, stringResource(R.string.open_book), onOpen)
        if (onRead != null) SheetRow(KpIcons.Book, stringResource(R.string.read), onRead)
        if (onListen != null) SheetRow(KpIcons.Headphones, stringResource(R.string.listen), onListen)
        SheetRow(KpIcons.Trash, stringResource(R.string.remove_from_library), onRemove, color = p.negative)
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color = Kp.palette.text) {
    KpRow(label, onClick = onClick, titleColor = color, inset = 0.dp, leading = { KpIconView(icon, color, size = 22.dp) })
}

// ------------------------------------------------------------------ bookcase

/**
 * The bookcase (optional view): lit planks every 196 dp fill the viewport whatever the count, books stand at
 * natural heights, the first stands face-out, the last spine leans, and a ghost spine waits where the next book goes.
 */
@Composable
private fun BookcaseView(ui: LibraryUi, header: @Composable () -> Unit, onOpen: (Long) -> Unit, onLongPress: (Book) -> Unit, onAdd: () -> Unit) {
    val books = ui.books
    val materials = rememberBookMaterials()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth.value
        val facts = remember(books) { books.map { ShelfBookFacts(it.id, spineThicknessUnits(it.sentenceCount)) } }
        val plans = remember(facts, width) { ShelfLayout.plan(facts, width - 2 * GUTTER_DP) }
        val planks = ShelfLayout.plankCount(plans.size, maxHeight.value)
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") { header() }
            if (ui.noMatches) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.no_matches, ui.query), color = Kp.palette.text2, style = KpType.body,
                        modifier = Modifier.padding(horizontal = KpSpace.gutter, vertical = 16.dp),
                    )
                }
            }
            items(if (ui.noMatches) 0 else planks) { r ->
                val plan = plans.getOrNull(r)
                if (plan == null) {
                    Box(Modifier.fillMaxWidth().height(ShelfLayout.ROW.dp)) { ShelfPlank(Modifier.align(Alignment.BottomCenter)) }
                } else {
                    ShelfRow(plan, books, materials, width, onOpen, onLongPress, onAdd, ui.importing)
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    plan: ShelfRowPlan, all: List<Book>, materials: List<BookMaterial>, widthDp: Float,
    onOpen: (Long) -> Unit, onLongPress: (Book) -> Unit, onAdd: () -> Unit, importing: Boolean,
) {
    val haptics = rememberHaptics()
    val k = 390f / widthDp                // design units per dp
    val baselineDp = ShelfLayout.ROW - PlankHeight.value + PlankBaseline.value
    val books = plan.books.map { all[it.index] }
    // Covers come from the shared cache: a real jacket never shows the generated cover first.
    val covers = plan.books.map { rememberCoverImage(all[it.index].coverRequest(), if (it.faceOut) 384 else 192) }
    val specs = remember(books, materials, covers) { books.mapIndexed { i, b -> bookSpecFor(b, materials, covers[i]) } }
    val targets = remember(plan, widthDp) {
        plan.books.map { pb ->
            val b = all[pb.index]
            val s = pb.height * k / BOOK_H
            val cy = (baselineDp - pb.height / 2f) * k
            if (pb.faceOut) {
                BookPose((GUTTER_DP + pb.x + pb.width / 2f) * k, cy, s, ry = ShelfLayout.FACE_YAW, rx = -3f)
            } else {
                // a leaning spine pivots on its bottom-left corner, so its centre is laid out upright
                val t = spineThicknessUnits(b.sentenceCount) * pb.height / BOOK_H
                BookPose((GUTTER_DP + pb.x + t / 2f) * k, cy, s, ry = 90f, rz = pb.lean)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(ShelfLayout.ROW.dp)) {
        ShelfPlank(Modifier.align(Alignment.BottomCenter))
        if (books.isNotEmpty()) {
            BookScene(
                specs, targets, Modifier.fillMaxSize(),
                onTap = { i -> haptics.play(HapticToken.Settle); onOpen(books[i].id) },
                onLongPress = { i -> haptics.play(HapticToken.Confirm); onLongPress(books[i]) },
                hitPad = 10f,
                labels = books.map { listOf(it.title, it.authors).filter { s -> s.isNotBlank() }.joinToString(", ") },
                keys = books.map { it.id },
                pressLift = true,
            )
        }
        plan.ghost?.let { g ->
            GhostSpine(
                Modifier.offset(x = (GUTTER_DP + g.x + g.width / 2f - 24f).dp, y = (baselineDp - g.height).dp),
                g.width, g.height, onAdd, enabled = !importing,
            )
        }
    }
}

/** A dashed outline where the next book will stand, with a small plus at its foot. 48 dp wide touch target. */
@Composable
private fun GhostSpine(modifier: Modifier, widthDp: Float, heightDp: Float, onAdd: () -> Unit, enabled: Boolean) {
    val p = Kp.palette
    val label = stringResource(R.string.add_a_book)
    val haptics = rememberHaptics()
    Box(
        modifier
            .size(48.dp, heightDp.dp)
            .clickable(enabled = enabled, onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button) {
                haptics.play(HapticToken.Tap); onAdd()
            }
            .semantics { contentDescription = label },
    ) {
        Canvas(Modifier.align(Alignment.Center).size(widthDp.dp, heightDp.dp)) {
            val stroke = 1.5.dp.toPx()
            val c = p.text2.copy(alpha = if (enabled) 0.6f else 0.3f)
            drawRoundRect(
                c, Offset(stroke / 2f, stroke / 2f), Size(size.width - stroke, size.height - stroke), CornerRadius(1.5.dp.toPx()),
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
            val arm = 4.dp.toPx()
            val mid = Offset(size.width / 2f, size.height - 18.dp.toPx())
            drawLine(c, mid.copy(x = mid.x - arm), mid.copy(x = mid.x + arm), stroke)
            drawLine(c, mid.copy(y = mid.y - arm), mid.copy(y = mid.y + arm), stroke)
        }
    }
}

