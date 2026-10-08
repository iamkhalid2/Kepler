package app.betterbooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.designsystem.BbSeed
import app.betterbooks.core.designsystem.BbTheme
import app.betterbooks.core.designsystem.BookPose
import app.betterbooks.core.designsystem.BookScene
import app.betterbooks.core.designsystem.BookSpec
import app.betterbooks.core.designsystem.bbPalette
import app.betterbooks.core.designsystem.bookMaterials

/**
 * Phase-0 vertical slice: a shelf of real 3D spines. Tap a spine -> the same object turns, rises and
 * grows into a cover. Tap again -> it returns. The accent swatches re-theme everything live.
 */
@Composable
fun LibraryDemo() {
    var seed by remember { mutableStateOf(BbSeed.Cobalt) }
    var dark by remember { mutableStateOf(true) }
    var picked by remember { mutableIntStateOf(-1) }
    val palette = bbPalette(seed.color, dark)
    val haptic = LocalHapticFeedback.current

    BbTheme(palette) {
        val p = Bb.palette
        val mats = bookMaterials(p)
        val books = remember(seed, dark) {
            listOf(
                BookSpec("Meditations", "Marcus Aurelius", 46f, mats[0]),
                BookSpec("Origin of Species", "Charles Darwin", 58f, mats[1]),
                BookSpec("Walden", "H. D. Thoreau", 40f, mats[2]),
                BookSpec("Pride and Prejudice", "Jane Austen", 52f, mats[3]),
                BookSpec("The Art of War", "Sun Tzu", 36f, mats[4]),
                BookSpec("Frankenstein", "Mary Shelley", 50f, mats[5]),
            )
        }
        val scales = floatArrayOf(0.64f, 0.70f, 0.60f, 0.66f, 0.58f, 0.68f)
        val k = 1.35f
        val total = books.indices.sumOf { (books[it].thickness * scales[it] * k).toDouble() }.toFloat() + 5f * (books.size - 1)
        var acc = (390f - total) / 2f
        val targets = books.indices.map { i ->
            val s = scales[i] * k
            val w = books[i].thickness * s
            val cx = acc + w / 2f
            acc += w + 5f
            val shelfCy = 470f - BOOK_H_UNITS * s / 2f
            when {
                picked == i -> BookPose(195f, 330f, 1.12f, ry = 24f, rx = -4f)
                picked >= 0 -> BookPose(cx, shelfCy + 40f, s, ry = 90f, alpha = 0f)
                else -> BookPose(cx, shelfCy, s, ry = 90f)
            }
        }

        Box(Modifier.fillMaxSize().background(p.bg)) {
            // lacquer plank: a single lit edge, fades away when a book is taken down
            val plankAlpha by androidx.compose.animation.core.animateFloatAsState(if (picked < 0) 1f else 0f, label = "plank")
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val u = size.width / 390f
                val y = 470f * u
                val a = plankAlpha
                drawRect(Color.Black, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 14f * u))
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.White.copy(alpha = 0.28f * a), Color.White.copy(alpha = 0.12f * a), Color.White.copy(alpha = 0.03f * a)),
                    ),
                    androidx.compose.ui.geometry.Offset(0f, y + 14f * u), androidx.compose.ui.geometry.Size(size.width, 1.2f * u),
                )
            }
            BookScene(books, targets, onTap = { i ->
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                picked = if (picked == i) -1 else i
            })

            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 22.dp, vertical = 14.dp)) {
                Text(
                    "Your shelf.", color = p.text, fontSize = 36.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light,
                )
                Text(
                    if (picked < 0) "tap a spine to take it down" else books[picked].title + " · " + books[picked].author,
                    color = p.text2, fontSize = 14.sp,
                )
                Box(Modifier.weight(1f))
                if (picked >= 0) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("Read", p.surface1, p.text, Modifier.weight(1f)) {}
                        Pill("Listen", p.accent, p.onAccent, Modifier.weight(1f)) {}
                    }
                }
                Text("THEME", color = p.text2, fontSize = 11.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    BbSeed.entries.forEach { s ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(s.color)
                                .clickable { seed = s; haptic.performHapticFeedback(HapticFeedbackType.ContextClick) },
                        )
                    }
                    Box(Modifier.weight(1f))
                    Text(
                        if (dark) "Light" else "Dark", color = p.accentText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { dark = !dark }.padding(8.dp),
                    )
                }
            }
        }
    }
}

private const val BOOK_H_UNITS = 225f

@Composable
private fun Pill(label: String, bg: Color, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(54.dp).clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}
