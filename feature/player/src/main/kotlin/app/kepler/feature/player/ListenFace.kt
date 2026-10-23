package app.kepler.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.BookObject
import app.kepler.core.designsystem.CoverRequest
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpIconButton
import app.kepler.core.designsystem.KpIconButtonStyle
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpMotion
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpSize
import app.kepler.core.designsystem.KpSkeleton
import app.kepler.core.designsystem.KpType
import app.kepler.core.model.BookId
import app.kepler.core.model.PlaybackController
import kotlinx.coroutines.delay

/**
 * The Listen face of the read + listen screen (#112): the book as a 3D object on light tinted from its cover,
 * title and author, the chapter chip, the chapter scrubber (#109), the transport (previous chapter, replay 15,
 * play/pause, forward 30, next chapter) and a quiet row of sleep, speed, voice and bookmark.
 *
 * [cover], [title] and [chapter] are known before playback reports the book (the chapter from the reader's page),
 * so nothing is an empty hole while narration loads.
 */
@Composable
fun ListenFace(
    controller: PlaybackController,
    bookId: BookId,
    cover: CoverRequest?,
    title: String,
    author: String,
    chapter: String?,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onRead: () -> Unit,
    onLearn: () -> Unit,
    onVoice: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Kp.palette
    val tint = rememberCoverTint(cover)
    var sheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }

    BoxWithConstraints(
        modifier.fillMaxSize().background(p.bg).drawBehind {
            // Ambient light from the cover: its colour at 24% fading into the background.
            drawRect(
                Brush.radialGradient(
                    listOf(tint.copy(alpha = 0.24f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.3f),
                    radius = size.maxDimension * 0.6f,
                ),
            )
        },
    ) {
        val landscape = maxWidth > maxHeight * 1.15f
        val screenHeight = maxHeight
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            ListenTopBar(onClose = onClose, onRead = onRead, onLearn = onLearn)
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (cover != null) Hero(cover, (screenHeight - 120.dp).coerceIn(140.dp, 260.dp))
                    }
                    Column(Modifier.weight(0.58f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Details(controller, bookId, title, author, chapter, bookmarked, onBookmark, onVoice) { sheet = it }
                    }
                }
            } else {
                val bookHeight = when {
                    screenHeight < 640.dp -> 180.dp
                    screenHeight < 700.dp -> 220.dp
                    // Tall phones: the book grows with the screen so it holds the space instead of floating in it.
                    else -> (screenHeight * 0.34f).coerceIn(260.dp, 330.dp)
                }
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                    Box(Modifier.height(bookHeight), contentAlignment = Alignment.Center) {
                        if (cover != null) Hero(cover, bookHeight)
                    }
                    Spacer(Modifier.weight(0.9f).heightIn(min = 16.dp))
                    Details(controller, bookId, title, author, chapter, bookmarked, onBookmark, onVoice) { sheet = it }
                    Spacer(Modifier.weight(0.35f).heightIn(min = 8.dp))
                }
            }
        }
    }

    PlayerSheetHost(controller, sheet) { sheet = null }
}

@Composable
private fun Hero(cover: CoverRequest, height: Dp) {
    BookObject(
        cover, Modifier.height(height), yaw = 16f, turnIn = true, thickness = 30f,
        sharedElement = true, contentDescription = cover.title,
    )
}

@Composable
private fun ListenTopBar(onClose: () -> Unit, onRead: () -> Unit, onLearn: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(KpSize.topBar).padding(horizontal = 8.dp)) {
        KpIconButton(
            KpIcons.ChevronDown, stringResource(R.string.player_close), onClose,
            Modifier.align(Alignment.CenterStart), KpIconButtonStyle.Tonal,
        )
        KpSegmented(
            options = listOf(stringResource(R.string.player_face_listen), stringResource(R.string.player_face_read)),
            selected = 0,
            onSelect = { if (it == 1) onRead() },
            modifier = Modifier.align(Alignment.Center).width(176.dp),
        )
        KpIconButton(
            KpIcons.Sparkle, stringResource(R.string.player_learn), onLearn,
            Modifier.align(Alignment.CenterEnd), KpIconButtonStyle.Tonal,
        )
    }
}

/** Title block, chapter chip, scrubber, status, transport and the secondary row. */
@Composable
private fun ColumnScope.Details(
    controller: PlaybackController,
    bookId: BookId,
    title: String,
    author: String,
    chapter: String?,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onVoice: () -> Unit,
    openSheet: (PlayerSheet) -> Unit,
) {
    val p = Kp.palette
    val current by controller.select { it.bookId == bookId }
    val section by controller.selectInfo { it.sectionTitle }
    val voice by controller.selectInfo { it.voiceName }
    val speed by controller.select { it.speed }
    val sleepRemaining by controller.select { it.sleepRemainingMs?.let { ms -> ms / 1000 * 1000 } }
    val sleepAtSectionEnd by controller.selectInfo { it.sleepAtSectionEnd }
    val marks by controller.selectInfo { it.marks }
    val ordinal by controller.select { it.ordinal }

    // While narration for this book is still being prepared, the engine reports interim chapters, times, speed and
    // voice. They stay invisible and fade in once, when playback has settled (or failed, or after a grace period),
    // so the chip never reads "Chapters", then the page's chapter, then the spoken one.
    val settled by controller.select {
        it.bookId == bookId && (it.error != null || (it.currentSectionId != null && !(it.buffering && it.playing)))
    }
    var waited by remember(bookId) { mutableStateOf(false) }
    LaunchedEffect(bookId) { delay(REVEAL_GRACE_MS); waited = true }
    val reveal by animateFloatAsState(
        if (settled || waited) 1f else 0f, tween(KpMotion.FACE_SWITCH_MS), label = "reveal",
    )
    val revealed = Modifier.graphicsLayer { alpha = reveal }

    Text(
        title, color = p.text, style = KpType.displayTitle, textAlign = TextAlign.Center,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
    if (author.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            author, color = p.text2, style = KpType.subhead, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    // Skeletons hold the chip's and the voice pill's places while they are hidden (brief section 9).
    val waiting = Modifier.graphicsLayer { alpha = 1f - reveal }
    Box(contentAlignment = Alignment.Center) {
        if (reveal < 1f) KpSkeleton(waiting.size(168.dp, 36.dp), CircleShape)
        ChapterChip(
            title = section.takeIf { current } ?: chapter ?: stringResource(R.string.player_chapters),
            onClick = { openSheet(PlayerSheet.Chapters) },
            modifier = revealed,
        )
    }
    Spacer(Modifier.height(8.dp))
    PlaybackScrubber(controller, revealed.padding(horizontal = 4.dp))
    StatusLine(controller)
    TransportRow(
        controller,
        playSize = 72.dp,
        leading = {
            ChapterSkipButton(next = false, enabled = current && marks.isNotEmpty(), onClick = {
                val st = controller.state.value
                PlayerFormat.previousChapterTarget(marks, st.ordinal, st.sectionElapsedMs)?.let(controller::seekToSentence)
            })
        },
        trailing = {
            ChapterSkipButton(next = true, enabled = current && PlayerFormat.nextChapterTarget(marks, ordinal) != null, onClick = {
                PlayerFormat.nextChapterTarget(marks, controller.state.value.ordinal)?.let(controller::seekToSentence)
            })
        },
    )
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SleepButton(sleepRemaining, sleepAtSectionEnd, { openSheet(PlayerSheet.Sleep) })
        Box(revealed) { SpeedButton(speed, { openSheet(PlayerSheet.Speed) }) }
        Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
            if (reveal < 1f) KpSkeleton(waiting.size(144.dp, 40.dp), CircleShape)
            VoicePill(voice.takeIf { current }, onVoice, revealed)
        }
        BookmarkButton(bookmarked, onBookmark, enabled = current)
    }
}

/** One quiet line between scrubber and transport: what is being prepared, or what went wrong. */
@Composable
private fun StatusLine(controller: PlaybackController) {
    val p = Kp.palette
    val error by controller.select { it.error }
    val preparing by controller.select { it.buffering && it.playing }
    Box(Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
        val e = error
        when {
            e != null -> Text(
                e + " · " + stringResource(R.string.player_try_again),
                color = p.text2, style = KpType.caption, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(role = Role.Button) { controller.resume() }.padding(vertical = 4.dp),
            )
            preparing -> Text(stringResource(R.string.player_preparing), color = p.text2, style = KpType.caption)
        }
    }
}

internal enum class PlayerSheet { Speed, Sleep, Chapters }

private const val REVEAL_GRACE_MS = 2_500L

/** Speed, sleep and chapter sheets for one controller. */
@Composable
internal fun PlayerSheetHost(controller: PlaybackController, sheet: PlayerSheet?, onDismiss: () -> Unit) {
    when (sheet) {
        PlayerSheet.Speed -> {
            val speed by controller.select { it.speed }
            SpeedSheet(speed, controller::setSpeed, onDismiss)
        }
        PlayerSheet.Sleep -> {
            val remaining by controller.select { it.sleepRemainingMs?.let { ms -> ms / 1000 * 1000 } }
            val atEnd by controller.selectInfo { it.sleepAtSectionEnd }
            SleepSheet(remaining, atEnd, { minutes -> controller.setSleepTimer(minutes); onDismiss() }, onDismiss)
        }
        PlayerSheet.Chapters -> {
            val marks by controller.selectInfo { it.marks }
            val ordinal = controller.state.value.ordinal
            ChaptersSheet(marks, PlayerFormat.chapterIndex(marks, ordinal), { controller.seekToSentence(it.startOrdinal); onDismiss() }, onDismiss)
        }
        null -> Unit
    }
}
