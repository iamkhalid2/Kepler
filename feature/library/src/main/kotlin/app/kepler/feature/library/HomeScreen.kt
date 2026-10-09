package app.kepler.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.BOOK_W
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpHairline
import app.kepler.core.designsystem.KpIcon
import app.kepler.core.designsystem.KpIconKind
import app.kepler.core.designsystem.KpPrimaryKey
import app.kepler.core.designsystem.KpRing
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpToastHost
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.BookPose
import app.kepler.core.designsystem.BookRibbons
import app.kepler.core.designsystem.BookScene
import app.kepler.core.designsystem.PlankBaseline
import app.kepler.core.designsystem.ShelfPlank
import app.kepler.core.model.Book
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val STAGE_H = 236f
private const val STAGE_PLANK_TOP = 176f
private const val HOME_BOOK_W = 100f

/**
 * Home: the book you are in, standing on a plank, and the one thing to do next. Below it, quiet rows
 * for what else is waiting (previously on, daily review, narration being bound).
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
) {
    val p = Kp.palette
    ScreenRoot {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (onSettings == null) {
                Spacer(Modifier.height(24.dp))
                KpCaps(remember { todayCaps() }, Modifier.padding(horizontal = 24.dp))
            } else {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KpCaps(remember { todayCaps() }, Modifier.weight(1f))
                    KpTextLink(stringResource(R.string.settings_caps), onSettings)
                }
            }
            val book = ui.current
            when {
                !ui.loaded -> Unit
                book == null -> EmptyHome(onAdd, ui.importing)
                else -> {
                    Stage(book, onOpenBook)
                    Resume(ui, book, onRead, onListen)
                    Spacer(Modifier.height(32.dp))
                    Waiting(ui, book, onRecap, onReview, onOpenBook)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
        KpToastHost(
            message = if (ui.importFailed) stringResource(R.string.import_failed) else null,
            onDismiss = onDismissMessage,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

/** Greeting on the left; the current book stands on the plank at the right with its two ribbons. */
@Composable
private fun Stage(book: Book, onOpenBook: (Long) -> Unit) {
    val p = Kp.palette
    val materials = rememberBookMaterials()
    val cover = rememberCover(book.coverPath, 360)
    val spec = remember(book.id, book.title, materials, cover) { bookSpecFor(book, materials, cover, thickness = 18f) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(STAGE_H.dp)) {
        val w = maxWidth.value
        val k = 390f / w
        val s = HOME_BOOK_W * k / BOOK_W
        val bookH = HOME_BOOK_W * 1.5f
        val left = w - 24f - HOME_BOOK_W
        val cy = (STAGE_PLANK_TOP + PlankBaseline.value - bookH / 2f) * k
        Text(
            stringResource(greetingRes()), color = p.text, style = KpType.display,
            modifier = Modifier.padding(start = 24.dp, top = 16.dp).width((left - 24f - 16f).dp),
        )
        ShelfPlank(Modifier.offset(y = STAGE_PLANK_TOP.dp))
        BookScene(
            listOf(spec), listOf(BookPose((left + HOME_BOOK_W / 2f - 4f) * k, cy, s, ry = 16f, rx = -4f)),
            Modifier.fillMaxSize(),
            onTap = { onOpenBook(book.id) },
            stageKeys = listOf(book.id),
            stageTag = "home",
            pressLift = true,
        )
        BookRibbons(
            book.readFraction, book.listenFraction, swingKey = book.id,
            modifier = Modifier.offset(x = (left + 20f).dp, y = (STAGE_PLANK_TOP + 7f).dp),
        )
        // Numerals beside the ribbons, read from left to right like a label on the shelf edge.
        Row(Modifier.offset(x = 24.dp, y = (STAGE_PLANK_TOP + 34f).dp), verticalAlignment = Alignment.Bottom) {
            if (book.listenFraction > 0.005f) {
                Text("${percentOf(book.listenFraction)}%", color = p.accentText, style = KpType.numeral)
                Text(" " + stringResource(R.string.listened) + "   ", color = p.text2, style = KpType.caption)
            }
            if (book.readFraction > 0.005f || book.listenFraction <= 0.005f) {
                Text("${percentOf(book.readFraction)}%", color = p.text, style = KpType.numeral)
                Text(" " + stringResource(R.string.read_share), color = p.text2, style = KpType.caption)
            }
        }
    }
}

@Composable
private fun Resume(ui: HomeUi, book: Book, onRead: (Long) -> Unit, onListen: (Long) -> Unit) {
    val p = Kp.palette
    val listening = ui.mode == HubMode.Listen
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        val caps = if (listening) {
            listOfNotNull(
                stringResource(R.string.home_listening),
                ui.voiceName?.takeIf { it.isNotBlank() },
                "%.1f×".format(Locale.getDefault(), ui.speed),
            ).joinToString(" · ")
        } else {
            stringResource(R.string.home_reading)
        }
        KpCaps(caps)
        Spacer(Modifier.height(8.dp))
        Text(book.title, color = p.text, style = KpType.title.copy(fontSize = 22.sp, lineHeight = 28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.authors.isNotBlank()) {
            Text(book.authors, color = p.text2, style = KpType.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        val through = percentOf(maxOf(book.readFraction, book.listenFraction))
        Text(
            if (ui.started) stringResource(R.string.home_through, through) else stringResource(R.string.home_not_started),
            color = p.text2, style = KpType.caption,
        )
        Spacer(Modifier.height(16.dp))
        val label = when {
            listening && ui.started -> R.string.resume_listening
            listening -> R.string.start_listening
            ui.started -> R.string.continue_reading
            else -> R.string.start_reading
        }
        KpPrimaryKey(stringResource(label), { if (listening) onListen(book.id) else onRead(book.id) })
    }
}

@Composable
private fun Waiting(ui: HomeUi, book: Book, onRecap: (Long) -> Unit, onReview: () -> Unit, onOpenBook: (Long) -> Unit) {
    val p = Kp.palette
    val rows = buildList<@Composable () -> Unit> {
        ui.daysAway?.let { days ->
            add {
                KpRow(
                    stringResource(R.string.home_previously_on),
                    meta = stringResource(R.string.home_days_away, days),
                    onClick = { onRecap(book.id) },
                    trailing = { KpIcon(KpIconKind.Chevron, p.text2) },
                )
            }
        }
        if (ui.dueCount > 0) {
            add {
                KpRow(
                    stringResource(R.string.home_daily_review),
                    meta = stringResource(R.string.home_due, ui.dueCount, reviewMinutes(ui.dueCount)),
                    trailing = { KpSecondaryKey(stringResource(R.string.home_start), onReview) },
                )
            }
        }
        ui.generating?.let { f ->
            add {
                KpRow(
                    stringResource(R.string.home_binding, percentOf(f)),
                    meta = book.title,
                    onClick = { onOpenBook(book.id) },
                    leading = { KpRing(f) },
                )
            }
        }
    }
    if (rows.isEmpty()) return
    KpHairline()
    rows.forEach { row -> row(); KpHairline() }
}

@Composable
private fun EmptyHome(onAdd: () -> Unit, importing: Boolean) {
    val p = Kp.palette
    Spacer(Modifier.height(16.dp))
    Text(stringResource(greetingRes()), color = p.text, style = KpType.display, modifier = Modifier.padding(horizontal = 24.dp))
    Box(Modifier.fillMaxWidth().height(120.dp)) { ShelfPlank(Modifier.align(Alignment.BottomCenter)) }
    Column(Modifier.padding(horizontal = 24.dp).padding(top = 32.dp)) {
        Text(stringResource(R.string.home_empty_title), color = p.text, style = KpType.headline)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_body), color = p.text2, style = KpType.body)
        Spacer(Modifier.height(24.dp))
        KpPrimaryKey(stringResource(R.string.add_a_book), onAdd, enabled = !importing)
    }
}

private fun greetingRes(): Int = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> R.string.home_morning
    in 12..17 -> R.string.home_afternoon
    else -> R.string.home_evening
}

private fun todayCaps(): String = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date())
