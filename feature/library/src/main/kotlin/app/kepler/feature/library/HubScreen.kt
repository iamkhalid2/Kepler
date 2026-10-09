package app.kepler.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.BOOK_W
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpHairline
import app.kepler.core.designsystem.KpPrimaryKey
import app.kepler.core.designsystem.KpRing
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.BookPose
import app.kepler.core.designsystem.BookRibbons
import app.kepler.core.designsystem.BookScene
import app.kepler.core.designsystem.PlankBaseline
import app.kepler.core.designsystem.ShelfPlank
import app.kepler.core.model.Book
import app.kepler.core.model.ContentType

private const val HERO_H = 340f
private const val PLANK_TOP = 228f
private const val HUB_BOOK_W = 128f

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
) {
    val vm = libraryViewModel(key = "hub-$bookId") { HubViewModel(deps, bookId) }
    val ui by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    HubScreen(ui, onRead, onListen, onLearn, onSetup, onBack, onLearnAt)
}

/** Lines of the hub's Learn contents page. Names match `LearnTab` in :feature:study (map with `LearnTab.valueOf(entry.name)`). */
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
) {
    val p = Kp.palette
    val book = ui.book
    ScreenRoot {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(onBack, null)
            if (book == null) {
                if (ui.loaded) Text(
                    stringResource(R.string.book_missing), color = p.text2, style = KpType.body,
                    modifier = Modifier.padding(24.dp),
                )
                return@Column
            }
            Hero(book, ui)
            Column(Modifier.padding(horizontal = 24.dp)) {
                val readLabel = stringResource(if (ui.started) R.string.continue_reading else R.string.start_reading)
                val listenLabel = stringResource(if (ui.started) R.string.resume_listening else R.string.start_listening)
                val primaryIsListen = ui.primary == HubMode.Listen
                KpPrimaryKey(if (primaryIsListen) listenLabel else readLabel, if (primaryIsListen) onListen else onRead)
                Spacer(Modifier.height(12.dp))
                KpSecondaryKey(
                    stringResource(if (primaryIsListen) R.string.read else R.string.listen),
                    if (primaryIsListen) onRead else onListen, Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
            KpHairline()
            NarrationLine(ui, onSetup)
            KpHairline()
            Spacer(Modifier.height(32.dp))
            LearnContents(book, ui.cardCount, onLearnAt)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Learn as a contents page: entries with dotted leaders and right captions that name the fence. */
@Composable
private fun LearnContents(book: Book, cardCount: Int, onLearnAt: (HubLearnEntry) -> Unit) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val reached = maxOf(book.readFraction, book.listenFraction)
    val fence = when {
        book.contentType == ContentType.NONFICTION -> stringResource(R.string.learn_whole_book)
        reached < 0.005f -> stringResource(R.string.learn_not_started)
        else -> stringResource(R.string.learn_up_to, percentOf(reached))
    }
    val recapCaption = if (reached < 0.005f) stringResource(R.string.learn_not_started) else stringResource(R.string.learn_up_to, percentOf(reached))
    val entries = listOf(
        Triple(HubLearnEntry.ASK, R.string.learn_ask, fence),
        Triple(HubLearnEntry.RECAP, R.string.learn_recap, recapCaption),
        // Only Ask may read past the frontier (whole book for non-fiction); the others stop at it.
        Triple(HubLearnEntry.CONCEPTS, R.string.learn_concepts, recapCaption),
        Triple(HubLearnEntry.MAP, R.string.learn_map, recapCaption),
        Triple(HubLearnEntry.TAKEAWAYS, R.string.learn_takeaways, stringResource(R.string.learn_by_chapter)),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.Bottom) {
        KpCaps(stringResource(R.string.hub_learn_caps), Modifier.weight(1f))
        if (cardCount > 0) {
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.learn_cards, cardCount, cardCount),
                color = p.text2, style = KpType.caption,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    entries.forEach { (entry, title, caption) ->
        Row(
            Modifier.fillMaxWidth().height(56.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { haptics.play(HapticToken.Tap); onLearnAt(entry) }
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(title), color = p.text, style = KpType.title, maxLines = 1)
            DottedLeader(Modifier.weight(1f).padding(horizontal = 8.dp).padding(top = 10.dp))
            Text(caption, color = p.text2, style = KpType.caption, maxLines = 1)
        }
    }
}

/** A contents-page leader: 1 dp dots every 5 dp in `hairline`-strength text2. */
@Composable
private fun DottedLeader(modifier: Modifier) {
    val c = Kp.palette.text2.copy(alpha = 0.5f)
    androidx.compose.foundation.Canvas(modifier.height(2.dp)) {
        val step = 5.dp.toPx()
        val r = 0.6.dp.toPx()
        var x = r
        while (x < size.width) { drawCircle(c, r, androidx.compose.ui.geometry.Offset(x, size.height / 2f)); x += step }
    }
}

@Composable
private fun Hero(book: Book, ui: HubUi) {
    val p = Kp.palette
    val materials = rememberBookMaterials()
    val coverLoad = rememberCoverLoad(book.coverPath, 480)
    val spec = rememberHubSpec(book, materials, coverLoad.image)
    BoxWithConstraints(Modifier.fillMaxWidth().height(HERO_H.dp)) {
        val w = maxWidth.value
        val k = 390f / w
        val s = HUB_BOOK_W * k / BOOK_W
        val bookH = HUB_BOOK_W * 1.5f
        val cy = (PLANK_TOP + PlankBaseline.value - bookH / 2f) * k
        ShelfPlank(Modifier.offset(y = PLANK_TOP.dp))
        if (coverLoad.ready) {
            // Spine to cover: the book leaves the shelf edge-on, lifts, and turns to face you (brief 4.1).
            val pose = BookPose((24f + 72f) * k, cy, s, ry = 24f, rx = -4f)
            BookScene(
                listOf(spec), listOf(pose), Modifier.fillMaxSize(),
                enterFrom = listOf(pose.copy(cx = pose.cx - 18f * k, scale = s * 0.94f, ry = 90f, rx = 0f, alpha = 0f, lift = 1f)),
            )
        }
        val ribbonScale = HUB_BOOK_W / 100f
        BookRibbons(
            book.readFraction, book.listenFraction, swingKey = book.id,
            modifier = Modifier.offset(x = (24f + 20f * ribbonScale).dp, y = (PLANK_TOP + 7f).dp),
            scale = ribbonScale,
        )
        // legend beside the ribbons: listened in accent, read in bone
        Column(Modifier.offset(x = 100.dp, y = (PLANK_TOP + 40f).dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${percentOf(book.listenFraction)}%", color = p.accentText, style = KpType.numeral)
                Text(" " + stringResource(R.string.listened), color = p.text2, style = KpType.caption)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${percentOf(book.readFraction)}%", color = p.text, style = KpType.numeral)
                Text(" " + stringResource(R.string.read_share), color = p.text2, style = KpType.caption)
            }
        }
        // text to the right of the book
        Column(Modifier.offset(x = 176.dp, y = 44.dp).width((w - 176f - 24f).dp)) {
            KpCaps(typeCaps(book), maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(book.title, color = p.text, style = KpType.headline, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(book.authors, color = p.text2, style = KpType.author, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun rememberHubSpec(book: Book, materials: List<app.kepler.core.designsystem.BookMaterial>, cover: androidx.compose.ui.graphics.ImageBitmap?) =
    androidx.compose.runtime.remember(book.id, book.title, materials, cover) {
        bookSpecFor(book, materials, cover, thickness = 22f)
    }

@Composable
private fun typeCaps(book: Book): String {
    val type = stringResource(
        when (book.contentType) {
            ContentType.FICTION -> R.string.type_fiction
            ContentType.NONFICTION -> R.string.type_nonfiction
            ContentType.MIXED -> R.string.type_mixed
        },
    )
    val minutes = readMinutes(book.sentenceCount)
    val time = if (minutes >= 60) stringResource(R.string.read_time_hm, minutes / 60, minutes % 60) else stringResource(R.string.read_time_m, minutes)
    return stringResource(R.string.type_and_time, type, time)
}

/** About 20 words a sentence at 230 words a minute. */
fun readMinutes(sentenceCount: Int): Int = (sentenceCount * 20 / 230).coerceAtLeast(1)

@Composable
private fun NarrationLine(ui: HubUi, onSetup: () -> Unit) {
    val p = Kp.palette
    val voice = ui.voiceName
    val (ringValue, text, link) = when (val n = ui.narration) {
        NarrationStatus.None -> Triple(0f, stringResource(R.string.narration_none), stringResource(R.string.set_up))
        is NarrationStatus.Binding ->
            if (n.running) Triple(n.fraction, stringResource(R.string.narration_binding, percentOf(n.fraction)), stringResource(R.string.view))
            else Triple(n.fraction, bound(percentOf(n.fraction), voice), stringResource(R.string.set_up))
        NarrationStatus.Bound -> Triple(
            1f,
            if (voice.isNullOrBlank()) stringResource(R.string.narration_ready) else stringResource(R.string.narration_ready_voice, voice),
            stringResource(R.string.change),
        )
        is NarrationStatus.Paused -> Triple(0f, stringResource(pausedReasonRes(n.reason)), stringResource(R.string.view))
        is NarrationStatus.Failed -> Triple(0f, stringResource(R.string.narration_failed), stringResource(R.string.set_up))
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KpRing(ringValue)
        Text(text, color = p.text, style = KpType.body, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        KpTextLink(link, onSetup)
    }
}

@Composable
private fun bound(percent: Int, voice: String?): String =
    if (voice.isNullOrBlank()) stringResource(R.string.narration_partial, percent) else stringResource(R.string.narration_partial_voice, percent, voice)
