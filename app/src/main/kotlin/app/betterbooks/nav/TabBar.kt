package app.betterbooks.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.betterbooks.R
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.designsystem.BbIcon
import app.betterbooks.core.designsystem.BbIconKind
import app.betterbooks.core.designsystem.BbType
import app.betterbooks.core.designsystem.HapticToken
import app.betterbooks.core.designsystem.rememberHaptics

val TabBarHeight = 64.dp

/** Brief section 1: Home, Library, Notebook; top hairline; active tab gets the 12x2 accent rule above its icon. */
@Composable
fun BbTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val p = Bb.palette
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth().background(p.bg).windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
        Row(Modifier.fillMaxWidth().height(TabBarHeight - 1.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val tint = if (active) p.text else p.text2
                val label = stringResource(
                    when (tab) {
                        Tab.HOME -> R.string.tab_home
                        Tab.LIBRARY -> R.string.tab_library
                        Tab.NOTEBOOK -> R.string.tab_notebook
                    },
                )
                Column(
                    Modifier
                        .weight(1f)
                        .height(TabBarHeight - 1.dp)
                        .semantics { this.selected = active }
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) {
                            haptics.play(HapticToken.Tap)
                            onSelect(tab)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(12.dp).height(2.dp).background(if (active) p.accent else Color.Transparent))
                    Spacer(Modifier.height(6.dp))
                    when (tab) {
                        Tab.LIBRARY -> BbIcon(BbIconKind.Shelf, tint, size = 22.dp)
                        else -> TabGlyph(tab, tint)
                    }
                    Spacer(Modifier.height(4.dp))
                    BasicText(label, style = BbType.caps.copy(color = tint))
                }
            }
        }
    }
}

/** Home and Notebook line glyphs, drawn on the same 24-unit grid and 1.6 stroke as [BbIcon]. */
@Composable
private fun TabGlyph(tab: Tab, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val st = Stroke(1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (tab == Tab.HOME) {
            val roof = Path().apply {
                moveTo(4f * u, 11f * u); lineTo(12f * u, 4f * u); lineTo(20f * u, 11f * u)
            }
            drawPath(roof, tint, style = st)
            val body = Path().apply {
                moveTo(6.5f * u, 9.5f * u); lineTo(6.5f * u, 20f * u); lineTo(17.5f * u, 20f * u); lineTo(17.5f * u, 9.5f * u)
            }
            drawPath(body, tint, style = st)
        } else {
            drawRoundRect(tint, Offset(5f * u, 3.5f * u), Size(14f * u, 17f * u), CornerRadius(1.5f * u), style = st)
            drawLine(tint, Offset(8.5f * u, 3.5f * u), Offset(8.5f * u, 20.5f * u), st.width)
            for (y in listOf(8.5f, 12f, 15.5f)) drawLine(tint, Offset(11f * u, y * u), Offset(16f * u, y * u), st.width, StrokeCap.Round)
        }
    }
}
