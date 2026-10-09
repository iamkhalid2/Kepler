package app.kepler.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.BOOK_H
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.LocalBookStage
import app.kepler.core.designsystem.KpIcon
import app.kepler.core.designsystem.KpIconKey
import app.kepler.core.designsystem.KpIconKind
import app.kepler.core.designsystem.KpInput
import app.kepler.core.designsystem.KpPrimaryKey
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpSheet
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpToastHost
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.BookMaterial
import app.kepler.core.designsystem.BookPose
import app.kepler.core.designsystem.BookScene
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.PlankBaseline
import app.kepler.core.designsystem.PlankHeight
import app.kepler.core.designsystem.ShelfPlank
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.model.Book

private const val GUTTER_DP = 24f

@Composable
fun LibraryRoute(onOpenBook: (bookId: Long) -> Unit, deps: LibraryDeps = LocalLibraryDeps.current) {
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
        onToggleSearch = vm::toggleSearch,
        onToggleLayout = vm::toggleLayout,
        onSort = vm::setSort,
        onLongPress = vm::requestDelete,
        onConfirmDelete = vm::confirmDelete,
        onCancelDelete = vm::cancelDelete,
        onDismissMessage = vm::dismissMessage,
    )
}

@Composable
fun LibraryScreen(
    ui: LibraryUi,
    onOpenBook: (Long) -> Unit,
    onAdd: () -> Unit,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleLayout: () -> Unit,
    onSort: (LibrarySort) -> Unit,
    onLongPress: (Book) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val p = Kp.palette
    var sortSheet by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    ScreenRoot {
        Column(Modifier.fillMaxSize()) {
            // header: caps count, then the three icon keys
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                KpCaps(stringResource(R.string.library_caps, ui.total), Modifier.weight(1f))
                KpIconKey(KpIconKind.Search, stringResource(R.string.search), onToggleSearch)
                KpIconKey(
                    if (ui.layout == LibraryLayout.Shelf) KpIconKind.List else KpIconKind.Shelf,
                    stringResource(if (ui.layout == LibraryLayout.Shelf) R.string.show_list else R.string.show_shelf),
                    onToggleLayout,
                )
                KpIconKey(KpIconKind.Add, stringResource(R.string.add_book), onAdd)
            }
            if (ui.searching) {
                KpInput(
                    ui.query, onQuery, Modifier.padding(horizontal = 24.dp),
                    placeholder = stringResource(R.string.search_hint),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (!ui.isEmpty) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    KpTextLink(stringResource(R.string.sort_link, stringResource(ui.sort.label())), { sortSheet = true })
                    if (ui.importing) {
                        Spacer(Modifier.weight(1f))
                        KpCaps(stringResource(R.string.adding), Modifier.padding(end = 4.dp), color = p.accentText)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !ui.loaded -> Unit
                    ui.isEmpty -> EmptyShelf(onAdd, ui.importing)
                    ui.noMatches -> Text(
                        stringResource(R.string.no_matches, ui.query), color = p.text2, style = KpType.body,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                    ui.layout == LibraryLayout.Shelf -> ShelfView(ui.books, onOpenBook, onLongPress, onAdd, ui.importing)
                    else -> ListView(ui.books, onOpenBook, onLongPress)
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
    }

    if (sortSheet) {
        KpSheet(onDismiss = { sortSheet = false }) {
            KpCaps(stringResource(R.string.sort_by))
            Spacer(Modifier.height(8.dp))
            LibrarySort.entries.forEach { s ->
                SelectableRow(stringResource(s.label()), selected = s == ui.sort) {
                    haptics.play(HapticToken.Tap); onSort(s); sortSheet = false
                }
            }
        }
    }
    ui.pendingDelete?.let { b -> RemoveBookSheet(b.title, onConfirmDelete, onCancelDelete) }
}

private fun LibrarySort.label(): Int = when (this) {
    LibrarySort.Recent -> R.string.sort_recent
    LibrarySort.Title -> R.string.sort_title
    LibrarySort.Author -> R.string.sort_author
}

@Composable
internal fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = Kp.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        KpRow(
            label, Modifier.weight(1f), onClick = onClick,
            leading = { if (selected) KpIcon(KpIconKind.Check, p.accentText) },
        )
    }
}

@Composable
private fun EmptyShelf(onAdd: () -> Unit, importing: Boolean) {
    val p = Kp.palette
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val plankTop = maxHeight * 0.34f
        ShelfPlank(Modifier.offset(y = plankTop))
        Column(Modifier.fillMaxWidth().padding(top = plankTop + 56.dp).padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.empty_title), color = p.text, style = KpType.headline)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.empty_body), color = p.text2, style = KpType.body)
            Spacer(Modifier.height(24.dp))
            KpPrimaryKey(stringResource(R.string.add_a_book), onAdd, enabled = !importing)
        }
    }
}

// ------------------------------------------------------------------ shelf

/**
 * The bookcase: lit planks every 196 dp fill the viewport whatever the count, books stand at natural heights,
 * the first stands face-out, the last spine leans, and a ghost spine waits where the next book goes.
 */
@Composable
private fun ShelfView(books: List<Book>, onOpen: (Long) -> Unit, onLongPress: (Book) -> Unit, onAdd: () -> Unit, importing: Boolean) {
    val materials = rememberBookMaterials()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth.value
        val facts = remember(books) { books.map { ShelfBookFacts(it.id, spineThicknessUnits(it.sentenceCount)) } }
        val plans = remember(facts, width) { ShelfLayout.plan(facts, width - 2 * GUTTER_DP) }
        val planks = ShelfLayout.plankCount(plans.size, maxHeight.value)
        LazyColumn(Modifier.fillMaxSize()) {
            items(planks) { r ->
                val plan = plans.getOrNull(r)
                if (plan == null) {
                    Box(Modifier.fillMaxWidth().height(ShelfLayout.ROW.dp)) { ShelfPlank(Modifier.align(Alignment.BottomCenter)) }
                } else {
                    ShelfRow(plan, books, materials, width, onOpen, onLongPress, onAdd, importing)
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
    // small jackets so each spine can wear its jacket's colour; the face-out book shows its jacket
    // While a jacket decodes (or after the order changed), wear the look the stage last saw for that book,
    // never the previous occupant's jacket and never a typographic flash.
    val stage = LocalBookStage.current
    val covers = plan.books.map {
        val b = all[it.index]
        val load = rememberCoverLoad(b.coverPath, if (it.faceOut) 240 else 96)
        if (load.ready && (load.image != null || b.coverPath == null)) load.image else stage?.knownSpec(b.id)?.cover ?: load.image
    }
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
                stageKeys = books.map { it.id },
                stageTag = "shelf",
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

// ------------------------------------------------------------------ list

@Composable
private fun ListView(books: List<Book>, onOpen: (Long) -> Unit, onLongPress: (Book) -> Unit) {
    val materials = rememberBookMaterials()
    LazyColumn(Modifier.fillMaxSize()) {
        items(books, key = { it.id }) { b ->
            val cover = rememberCover(b.coverPath, 120)
            KpRow(
                title = b.title,
                meta = listOfNotNull(b.authors.takeIf { it.isNotBlank() }, progressMeta(b)).joinToString(" · "),
                onClick = { onOpen(b.id) },
                onLongClick = { onLongPress(b) },
                leading = { MiniCover(materialFor(b, materials), cover) },
            )
            KpRowSeparator(hasLeading = true)
        }
    }
}

@Composable
private fun progressMeta(b: Book): String? = when {
    b.listenFraction > 0.005f && b.listenFraction >= b.readFraction -> stringResource(R.string.pct_listened, percentOf(b.listenFraction))
    b.readFraction > 0.005f -> stringResource(R.string.pct_read, percentOf(b.readFraction))
    else -> null
}

/** 28 x 42 flat cover: the jacket if there is one, otherwise cloth with a title bar. */
@Composable
internal fun MiniCover(material: BookMaterial, cover: ImageBitmap?) {
    Canvas(Modifier.size(28.dp, 42.dp)) {
        val r = CornerRadius(1.5.dp.toPx())
        drawRoundRect(material.cloth, Offset.Zero, size, r)
        if (cover != null) {
            val wantW = minOf(cover.width, (cover.height * 2f / 3f).toInt())
            val wantH = minOf(cover.height, (cover.width * 3f / 2f).toInt())
            drawImage(
                cover, IntOffset((cover.width - wantW) / 2, (cover.height - wantH) / 2), IntSize(wantW, wantH),
                IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()),
            )
        } else {
            drawRect(material.ink.copy(alpha = 0.85f), Offset(size.width * 0.2f, size.height * 0.22f), Size(size.width * 0.6f, 1.5.dp.toPx()))
            drawCircle(material.ink.copy(alpha = 0.8f), size.width * 0.16f, Offset(size.width / 2f, size.height * 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
        }
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.18f), 0.42f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f),
                start = Offset.Zero, end = Offset(size.width, size.height),
            ),
        )
    }
}
