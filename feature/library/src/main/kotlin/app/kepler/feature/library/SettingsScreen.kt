package app.kepler.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpDot
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSeed
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpSheet
import app.kepler.core.designsystem.KpSwitch
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.clothFor
import app.kepler.core.designsystem.drawShelfPlank
import app.kepler.core.designsystem.rememberHaptics

private val CAP_CHOICES = listOf(0, 500, 1000, 2000)
private val FONT_SCALES = listOf(0.9f, 1.0f, 1.15f)
private val LINE_HEIGHTS = listOf(1.4f, 1.6f, 1.8f)
private val FONT_FAMILIES = listOf("Literata", "Albert Sans")

@Composable
fun SettingsRoute(onBack: () -> Unit, deps: LibraryDeps = LocalLibraryDeps.current) {
    val vm = libraryViewModel { SettingsViewModel(deps) }
    val ui by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    SettingsScreen(ui, onBack, vm)
}

private enum class Sheet { Fish, OpenRouter, Model, Cap }

@Composable
fun SettingsScreen(ui: SettingsUi, onBack: () -> Unit, vm: SettingsViewModel) {
    val p = Kp.palette
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    ScreenRoot {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(onBack, stringResource(R.string.settings_caps))

            Group(R.string.group_providers)
            KpRow(
                stringResource(R.string.provider_fish_name), meta = stringResource(R.string.provider_fish_meta),
                onClick = { vm.clearCheck(KeyKind.Fish); sheet = Sheet.Fish }, trailing = { Status(ui.fishSet) },
            )
            KpRowSeparator()
            KpRow(
                stringResource(R.string.provider_or_name), meta = stringResource(R.string.provider_or_meta),
                onClick = { vm.clearCheck(KeyKind.OpenRouter); sheet = Sheet.OpenRouter }, trailing = { Status(ui.openRouterSet) },
            )

            Group(R.string.group_budget)
            KpRow(
                stringResource(R.string.requests_today),
                trailing = {
                    val b = ui.budget
                    Text(
                        if (b != null) stringResource(R.string.library_requests_left, b.requestsLeftToday) else stringResource(R.string.not_set),
                        color = if (b != null) p.text else p.text2, style = KpType.numeral,
                    )
                },
            )
            KpRowSeparator()
            KpRow(
                stringResource(R.string.ai_model), onClick = if (ui.openRouterSet) ({ sheet = Sheet.Model }) else null,
                trailing = { Text(ui.app.openRouterModel ?: stringResource(R.string.automatic), color = p.text2, style = KpType.caption, maxLines = 1) },
            )
            KpRowSeparator()
            KpRow(
                stringResource(R.string.fish_spend),
                trailing = { Text(money(ui.spentCents), color = p.text, style = KpType.numeral) },
            )
            KpRowSeparator()
            KpRow(
                stringResource(R.string.monthly_cap), onClick = { sheet = Sheet.Cap },
                trailing = {
                    Text(
                        if (ui.app.monthlyCapCents == 0) stringResource(R.string.no_cap) else money(ui.app.monthlyCapCents),
                        color = p.text, style = KpType.numeral,
                    )
                },
            )

            Group(R.string.group_appearance)
            Column(Modifier.padding(horizontal = 24.dp)) {
                KpCaps(stringResource(R.string.accent), color = p.text2)
                SeedSpines(ui.seed, ui.app.dark, vm::setSeed)
                Spacer(Modifier.height(16.dp))
                KpSegmented(
                    listOf(stringResource(R.string.dark), stringResource(R.string.light)),
                    if (ui.app.dark) 0 else 1, { vm.setDark(it == 0) },
                )
            }
            Spacer(Modifier.height(8.dp))
            KpRow(
                stringResource(R.string.haptics), meta = stringResource(R.string.haptics_meta),
                trailing = { KpSwitch(ui.app.haptics, vm::setHaptics, label = stringResource(R.string.haptics)) },
            )

            Group(R.string.group_reading)
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledSegmented(
                    R.string.text_size, listOf(R.string.small, R.string.medium, R.string.large).map { stringResource(it) },
                    FONT_SCALES.indexOfFirst { it == ui.app.fontScale }, { vm.setFontScale(FONT_SCALES[it]) },
                )
                LabeledSegmented(
                    R.string.line_spacing, listOf(R.string.tight, R.string.standard, R.string.open).map { stringResource(it) },
                    LINE_HEIGHTS.indexOfFirst { it == ui.app.lineHeight }, { vm.setLineHeight(LINE_HEIGHTS[it]) },
                )
                LabeledSegmented(
                    R.string.typeface, FONT_FAMILIES,
                    FONT_FAMILIES.indexOf(ui.app.fontFamily), { vm.setFontFamily(FONT_FAMILIES[it]) },
                )
            }

            Group(R.string.group_about)
            KpRow(
                stringResource(R.string.app_name_row),
                meta = if (ui.version.isBlank()) null else stringResource(R.string.version_x, ui.version),
            )
            Text(stringResource(R.string.about_license), color = p.text2, style = KpType.caption, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Text(stringResource(R.string.about_fonts), color = p.text2, style = KpType.caption, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Spacer(Modifier.height(48.dp))
        }
    }

    when (sheet) {
        Sheet.Fish -> KeySheet(KeyKind.Fish, stringResource(R.string.provider_fish_name), ui.fishSet, ui.fishCheck, vm) { sheet = null }
        Sheet.OpenRouter -> KeySheet(KeyKind.OpenRouter, stringResource(R.string.provider_or_name), ui.openRouterSet, ui.openRouterCheck, vm) { sheet = null }
        Sheet.Model -> KpSheet(onDismiss = { sheet = null }) {
            KpCaps(stringResource(R.string.ai_model))
            Spacer(Modifier.height(8.dp))
            SelectableRow(stringResource(R.string.automatic), ui.app.openRouterModel == null) { vm.setOpenRouterModel(null); sheet = null }
            ui.models.forEach { m ->
                SelectableRow(m, ui.app.openRouterModel == m) { vm.setOpenRouterModel(m); sheet = null }
            }
        }
        Sheet.Cap -> KpSheet(onDismiss = { sheet = null }) {
            KpCaps(stringResource(R.string.monthly_cap))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cap_body), color = p.text2, style = KpType.caption)
            Spacer(Modifier.height(8.dp))
            CAP_CHOICES.forEach { c ->
                SelectableRow(if (c == 0) stringResource(R.string.no_cap) else money(c), ui.app.monthlyCapCents == c) {
                    vm.setMonthlyCapCents(c); sheet = null
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun Group(title: Int) {
    Spacer(Modifier.height(32.dp))
    KpCaps(stringResource(title), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

@Composable
private fun Status(connected: Boolean) {
    val p = Kp.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (connected) { KpDot(p.positive); Spacer(Modifier.size(8.dp)) }
        Text(stringResource(if (connected) R.string.connected else R.string.not_set), color = p.text2, style = KpType.caption)
    }
}

@Composable
private fun LabeledSegmented(label: Int, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        KpCaps(stringResource(label))
        Spacer(Modifier.height(8.dp))
        KpSegmented(options, selected, onSelect)
    }
}

private fun money(cents: Int): String = "$" + "%.2f".format(cents / 100.0)

@Composable
private fun KeySheet(kind: KeyKind, name: String, isSet: Boolean, check: KeyCheck, vm: SettingsViewModel, onClose: () -> Unit) {
    val p = Kp.palette
    var value by remember { mutableStateOf("") }
    KpSheet(onDismiss = onClose) {
        KpCaps(name)
        Spacer(Modifier.height(16.dp))
        KeyField(
            kind, stringResource(R.string.api_key), if (isSet && check == KeyCheck.Idle) KeyCheck.Connected else check, value, { value = it },
            onVerify = { k, v -> vm.verifyAndSave(k, v) },
            onSaveUnverified = { k, v -> vm.saveUnverified(k, v) },
        )
        if (isSet) {
            Spacer(Modifier.height(8.dp))
            KpTextLink(stringResource(R.string.remove_key), { vm.removeKey(kind); value = "" }, color = p.negative)
        }
    }
}

/** Four 24 x 40 spines on a mini plank; the chosen one is lifted. */
@Composable
private fun SeedSpines(selected: KpSeed, dark: Boolean, onSelect: (KpSeed) -> Unit) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    val env = p.materials.env
    Box(Modifier.fillMaxWidth().height(88.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawShelfPlank(env, size.height - 26.dp.toPx(), size.width)
        }
        Row {
            KpSeed.presets.forEach { seed ->
                val cloth = remember(seed, dark) { clothFor(seed.color, dark) }
                val lifted = seed == selected
                Box(
                    Modifier.size(48.dp, 88.dp)
                        .clickable(role = Role.RadioButton) { haptics.play(HapticToken.Tap); onSelect(seed) }
                        .semantics { contentDescription = seed.label },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Canvas(Modifier.size(24.dp, 40.dp).offset(y = (-18f - if (lifted) 8f else 0f).dp)) {
                        drawRoundRect(cloth.face, Offset.Zero, size, CornerRadius(1.5.dp.toPx()))
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.White.copy(alpha = 0.18f), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f),
                            ),
                        )
                        drawCircle(cloth.ink.copy(alpha = 0.9f), 2.dp.toPx(), Offset(size.width / 2f, 6.dp.toPx()))
                        if (lifted) drawRect(p.accent, Offset(0f, -4.dp.toPx()), Size(size.width, 1.dp.toPx()))
                    }
                }
            }
        }
    }
}
