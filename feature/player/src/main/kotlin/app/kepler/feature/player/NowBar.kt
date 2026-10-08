package app.kepler.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.BookPose
import app.kepler.core.designsystem.BookScene
import app.kepler.core.model.PlaybackController

private val BarHeight = 64.dp
private val Overhang = 12.dp

/**
 * The now-bar: 64 dp, `surface1`, r4, with a plank lip. A 32x48 book stands on it and overhangs the top by
 * 8 dp. Tap or swipe up opens the player; swipe down hides it (playback is paused). One accent line along
 * the bottom shows progress through the book.
 */
@Composable
fun NowBar(
    controller: PlaybackController,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    val p = Kp.palette
    val bookId by controller.select { it.bookId }
    val playing by controller.select { it.playing }
    val buffering by controller.select { it.buffering }
    val error by controller.select { it.error }
    val title by controller.selectInfo { it.bookTitle }
    val section by controller.selectInfo { it.sectionTitle }
    val coverPath by controller.selectInfo { it.coverPath }
    val author by controller.selectInfo { it.author }
    val sentenceCount by controller.selectInfo { it.sentenceCount }
    val ordinal by controller.select { it.ordinal }

    var hiddenFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(playing) { if (playing) hiddenFor = null }
    val id = bookId
    if (id == null || hiddenFor == id) return

    val cover = rememberCover(coverPath)
    val openLabel = stringResource(R.string.nowbar_open)
    val caption = when {
        error != null -> error.orEmpty()
        buffering && !playing -> stringResource(R.string.nowbar_preparing)
        else -> section ?: stringResource(R.string.nowbar_fallback_caption)
    }

    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp).height(BarHeight + Overhang)) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BarHeight)
                .background(p.surface1, SmallKeyShape)
                .drawBehind {
                    // plank lip: a hairline highlight that fades away from the key light
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.White.copy(alpha = if (p.isDark) 0.22f else 0.9f), Color.White.copy(alpha = 0.02f)),
                        ),
                        Offset.Zero, Size(size.width, 0.9.dp.toPx()),
                    )
                }
                .pointerInput(id) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            when {
                                total < -48.dp.toPx() -> onOpen()
                                total > 48.dp.toPx() -> { controller.pause(); hiddenFor = id; onDismiss() }
                            }
                        },
                        onVerticalDrag = { change, delta -> total += delta; change.consume() },
                    )
                }
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen),
        ) {
            Row(Modifier.fillMaxHeight().padding(start = 60.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        title.orEmpty(), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = KpType.rowTitle.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    )
                    Text(
                        caption, color = if (error != null) p.negative else p.text2, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = KpType.caption,
                    )
                }
                PlayKey(playing = playing, size = 48.dp, onClick = { if (playing) controller.pause() else controller.resume() })
            }
            // progress through the book: the one accent line of the bar
            Box(
                Modifier.align(Alignment.BottomStart).height(1.dp)
                    .drawBehind {
                        val f = PlayerFormat.fractionOf(ordinal, sentenceCount)
                        drawRect(p.accent, Offset.Zero, Size(this.size.width * f, this.size.height))
                    }
                    .fillMaxWidth(),
            )
        }
        // the book stands on the bar and overhangs it by 8 dp
        Box(Modifier.offset(x = 4.dp, y = 0.dp).width(48.dp).height(56.dp)) {
            val spec = remember(id, title, author, cover, p) { bookSpec(id, title, author, p, cover) }
            // 48 dp wide scene: 1 design unit = 48/390 dp, so a 32 dp cover is scale 1.73
            val u = 48f / 390f
            BookScene(
                books = listOf(spec),
                targets = listOf(BookPose(cx = 195f, cy = 28f / u, scale = 32f / (150f * u), ry = 0f, rx = 0f)),
                modifier = Modifier.size(48.dp, 56.dp),
            )
        }
    }
}
