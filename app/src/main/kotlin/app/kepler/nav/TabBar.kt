package app.kepler.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kepler.R
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpSize
import app.kepler.core.designsystem.KpType

val TabBarHeight = KpSize.tabBar

/**
 * Brief v2 section 3: Home, Library, Notebook. Top hairline; the selected tab sits on a 56x32 surface2 pill
 * that fades in (never slides). No haptic on tab switch.
 */
@Composable
fun KpTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Column(modifier.fillMaxWidth().background(p.bg).windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
        Row(Modifier.fillMaxWidth().height(TabBarHeight - 1.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val tint by animateColorAsState(if (active) p.text else p.text2, tween(120), label = "tabTint")
                val pill by animateColorAsState(if (active) p.surface2 else Color.Transparent, tween(120), label = "tabPill")
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
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(56.dp, 32.dp).background(pill, CircleShape), contentAlignment = Alignment.Center) {
                        KpIconView(
                            when (tab) {
                                Tab.HOME -> KpIcons.Home
                                Tab.LIBRARY -> KpIcons.Library
                                Tab.NOTEBOOK -> KpIcons.Notebook
                            },
                            tint,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    BasicText(label, style = KpType.tabLabel.copy(color = tint))
                }
            }
        }
    }
}
