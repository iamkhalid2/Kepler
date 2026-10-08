package app.kepler.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.BOOK_H
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpDot
import app.kepler.core.designsystem.KpIconKey
import app.kepler.core.designsystem.KpIconKind
import app.kepler.core.designsystem.KpNumeral
import app.kepler.core.designsystem.KpRing
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpToastHost
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.BookPose
import app.kepler.core.designsystem.BookScene
import app.kepler.core.designsystem.PlankBaseline
import app.kepler.core.designsystem.PlankHeight
import app.kepler.core.designsystem.ShelfPlank
import app.kepler.core.model.AnnotationRepository
import app.kepler.core.model.BookId
import app.kepler.core.model.Bookmark
import app.kepler.core.model.NarrationService
import app.kepler.core.model.NarrationState
import app.kepler.core.model.PlaybackController
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private enum class PlayerSheet { Speed, Sleep, Chapters }

private const val HERO_WIDTH = 220f
private const val HERO_PITCH = -6f

/**
 * The full player: the book as a 3D object on a plank, a chapter scrubber, the transport, and the
 * secondary keys (speed, sleep, bookmark, chapters). [onReadAlong] receives the sentence being spoken so
 * the reader can open on it.
 */
@Composable
fun FullPlayerRoute(
    controller: PlaybackController,
    narration: NarrationService,
    annotations: AnnotationRepository,
    bookId: BookId,
    onReadAlong: (ordinal: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Kp.palette
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    LaunchedEffect(bookId) {
        if (controller.state.value.bookId != bookId) controller.play(bookId)
    }

    val loadedId by controller.select { it.bookId }
    val playing by controller.select { it.playing }
    val error by controller.select { it.error }
    val speed by controller.select { it.speed }
    val sleepRemaining by controller.select { it.sleepRemainingMs }
    val title by controller.selectInfo { it.bookTitle }
    val author by controller.selectInfo { it.author }
    val section by controller.selectInfo { it.sectionTitle }
    val voice by controller.selectInfo { it.voiceName }
    val sleepAtSectionEnd by controller.selectInfo { it.sleepAtSectionEnd }
    val cover = rememberCover(controller.selectInfo { it.coverPath }.value)

    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val savedLabel = stringResource(R.string.player_bookmark_saved)

    Box(modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                KpIconKey(KpIconKind.Close, stringResource(R.string.player_close), onClose)
                Spacer(Modifier.width(4.dp))
                KpCaps(
                    if (voice != null) stringResource(R.string.player_caps_listening_voice, voice.orEmpty())
                    else stringResource(R.string.player_caps_listening),
                    Modifier.weight(1f),
                )
                KpTextLink(stringResource(R.string.player_read_along), { onReadAlong(controller.state.value.ordinal) })
            }

            Hero(bookId = loadedId ?: bookId, title = title, author = author, cover = cover)

            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                if (title == null) LoadingBlocks() else {
                    Text(title.orEmpty(), color = p.text, style = KpType.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!author.isNullOrBlank()) {
                        Text(author.orEmpty(), color = p.text2, style = KpType.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!section.isNullOrBlank()) {
                        Text(
                            section.orEmpty(), color = p.text2, style = KpType.caption, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            ScrubberSection(controller)

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipKey(15, SkipDirection.Back, stringResource(R.string.player_back_15), { controller.seekByMs(-15_000) })
                PlayKey(
                    playing = playing, size = 72.dp, enabled = loadedId != null,
                    onClick = { if (playing) controller.pause() else controller.resume() },
                )
                SkipKey(30, SkipDirection.Forward, stringResource(R.string.player_forward_30), { controller.seekByMs(30_000) })
            }

            StatusLine(controller, narration, bookId, error)

            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpSecondaryKey(
                        PlayerFormat.speed(speed),
                        { sheet = PlayerSheet.Speed }, Modifier.weight(1f),
                    )
                    KpSecondaryKey(
                        sleepRemaining?.takeIf { !sleepAtSectionEnd }?.let { PlayerFormat.clock(it) }
                            ?: if (sleepAtSectionEnd) stringResource(R.string.player_sleep_section_end)
                            else stringResource(R.string.player_sleep),
                        { sheet = PlayerSheet.Sleep }, Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KpSecondaryKey(
                        stringResource(R.string.player_bookmark),
                        {
                            val st = controller.state.value
                            scope.launch {
                                annotations.addBookmark(
                                    Bookmark(0, bookId, st.ordinal, section, System.currentTimeMillis()),
                                )
                                toast = savedLabel
                            }
                        },
                        Modifier.weight(1f), enabled = loadedId != null,
                    )
                    KpSecondaryKey(stringResource(R.string.player_chapters), { sheet = PlayerSheet.Chapters }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        KpToastHost(toast, { toast = null }, Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 16.dp))
    }

    when (sheet) {
        PlayerSheet.Speed -> SpeedSheet(speed, controller::setSpeed) { sheet = null }
        PlayerSheet.Sleep -> SleepSheet(sleepRemaining, sleepAtSectionEnd, { minutes -> controller.setSleepTimer(minutes); sheet = null }) { sheet = null }
        PlayerSheet.Chapters -> {
            val marks by controller.selectInfo { it.marks }
            val ordinal = controller.state.value.ordinal
            val current = marks.lastOrNull { it.startOrdinal <= ordinal }?.sectionId
            ChaptersSheet(marks, current, { controller.seekToSentence(it.startOrdinal); sheet = null }) { sheet = null }
        }
        null -> Unit
    }
}

/** The book standing on a plank. Its width is 220 dp at a 390 dp screen and scales with the screen. */
@Composable
private fun Hero(bookId: BookId, title: String?, author: String?, cover: androidx.compose.ui.graphics.ImageBitmap?) {
    val p = Kp.palette
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val unit: Dp = maxWidth / 390f
        val scale = HERO_WIDTH / 150f
        val bookHeight = unit * (BOOK_H * scale)
        val top = 8.dp
        val height = top + bookHeight - PlankBaseline + PlankHeight
        val spec = remember(bookId, title, author, cover, p) { bookSpec(bookId, title, author, p, cover, thickness = 26f) }
        Box(Modifier.fillMaxWidth().height(height)) {
            ShelfPlank(Modifier.align(Alignment.BottomCenter))
            BookScene(
                books = listOf(spec),
                targets = listOf(BookPose(cx = 195f, cy = (top + bookHeight / 2f) / unit, scale = scale, ry = 0f, rx = HERO_PITCH)),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoadingBlocks() {
    val p = Kp.palette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(0.7f).height(26.dp).background(p.surface2))
        Box(Modifier.fillMaxWidth(0.4f).height(16.dp).background(p.surface2))
    }
}

@Composable
private fun ScrubberSection(controller: PlaybackController) {
    val p = Kp.palette
    val ordinal by controller.select { it.ordinal }
    val speed by controller.select { it.speed }
    val count by controller.selectInfo { it.sentenceCount }
    val marks by controller.selectInfo { it.marks }
    val elapsed by controller.selectInfo { it.bookElapsedMs }
    val total by controller.selectInfo { it.bookTotalMs }
    val ticks = remember(marks, count) { PlayerFormat.tickFractions(marks.map { it.startOrdinal }, count) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        BookScrubber(
            fraction = PlayerFormat.fractionOf(ordinal, count),
            ticks = ticks,
            description = stringResource(R.string.player_scrubber_description),
            stateText = stringResource(R.string.player_scrubber_percent, PlayerFormat.percent(PlayerFormat.fractionOf(ordinal, count))),
            onSeek = { f -> controller.seekToSentence(PlayerFormat.ordinalAt(f, count)) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            KpNumeral(PlayerFormat.clock((elapsed / speed).toLong()), color = p.text2)
            KpNumeral("−" + PlayerFormat.clock(((total - elapsed).coerceAtLeast(0) / speed).toLong()), color = p.text2)
        }
    }
}

/** One line under the transport: narration progress, how far ahead the audio is, or what went wrong. */
@Composable
private fun StatusLine(controller: PlaybackController, narration: NarrationService, bookId: BookId, error: String?) {
    val p = Kp.palette
    val narrationState by remember(narration, bookId) { narration.state(bookId) }.collectAsStateWithLifecycle(NarrationState.Idle)
    val buffering by controller.select { it.buffering }
    val speed by controller.select { it.speed }
    val ahead by controller.selectInfo { it.aheadMs }
    val minutes = PlayerFormat.minutesAhead(ahead, speed)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            error != null -> {
                KpDot(p.negative)
                Text(error, Modifier.weight(1f), color = p.text2, style = KpType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                KpTextLink(stringResource(R.string.player_try_again), { controller.resume() })
            }
            narrationState is NarrationState.Running -> {
                val st = narrationState as NarrationState.Running
                val fraction = if (st.totalSentences > 0) st.doneSentences.toFloat() / st.totalSentences else 0f
                KpRing(fraction)
                Text(
                    stringResource(R.string.player_binding, PlayerFormat.percent(fraction)) +
                        if (minutes > 0) " · " + pluralStringResource(R.plurals.player_ahead_minutes, minutes, minutes) else "",
                    color = p.text2, style = KpType.caption,
                )
            }
            buffering -> {
                KpDot(p.warning)
                Text(stringResource(R.string.player_buffering), color = p.text2, style = KpType.caption)
            }
            narrationState is NarrationState.Paused -> {
                KpDot(p.warning)
                Text(stringResource(R.string.player_narration_paused), color = p.text2, style = KpType.caption)
            }
            narrationState is NarrationState.Done -> {
                KpDot(p.positive)
                Text(stringResource(R.string.player_narration_done), color = p.text2, style = KpType.caption)
            }
            minutes > 0 -> {
                KpDot(p.positive)
                Text(pluralStringResource(R.plurals.player_ahead_minutes, minutes, minutes), color = p.text2, style = KpType.caption)
            }
        }
    }
}
