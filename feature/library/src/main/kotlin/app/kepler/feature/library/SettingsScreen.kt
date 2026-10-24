package app.kepler.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpChevron
import app.kepler.core.designsystem.KpDot
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpInput
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpSeed
import app.kepler.core.designsystem.KpSheet
import app.kepler.core.designsystem.KpSlider
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpSwitch
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpTopBar
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.model.ScrubberScope
import java.util.Locale
import kotlin.math.roundToInt

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

private enum class Sheet { Fish, OpenRouter, Model, Cap, Speed, TextSize, LineSpacing, Typeface, Scope }

/** Grouped production settings: Reading, Listening, AI, Appearance, Data, About, each a card of 56 dp rows. */
@Composable
fun SettingsScreen(ui: SettingsUi, onBack: () -> Unit, vm: SettingsViewModel) {
    val p = Kp.palette
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val uriHandler = LocalUriHandler.current

    ScreenRoot {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            KpTopBar(onBack = onBack, title = stringResource(R.string.settings_caps))
            Column(Modifier.padding(horizontal = KpSpace.gutter).padding(bottom = 48.dp)) {
                Section(R.string.group_reading, first = true) {
                    ValueRow(
                        stringResource(R.string.text_size),
                        listOf(R.string.small, R.string.medium, R.string.large).map { stringResource(it) }.getOrNull(FONT_SCALES.indexOf(ui.app.fontScale))
                            ?: stringResource(R.string.custom),
                    ) { sheet = Sheet.TextSize }
                    Sep()
                    ValueRow(
                        stringResource(R.string.line_spacing),
                        listOf(R.string.tight, R.string.standard, R.string.open).map { stringResource(it) }.getOrNull(LINE_HEIGHTS.indexOf(ui.app.lineHeight))
                            ?: stringResource(R.string.custom),
                    ) { sheet = Sheet.LineSpacing }
                    Sep()
                    ValueRow(stringResource(R.string.typeface), ui.app.fontFamily) { sheet = Sheet.Typeface }
                }

                Section(R.string.group_listening) {
                    KpRow(
                        stringResource(R.string.provider_fish_name), meta = stringResource(R.string.provider_fish_meta), inset = 16.dp,
                        onClick = { vm.clearCheck(KeyKind.Fish); sheet = Sheet.Fish }, trailing = { Status(ui.fishSet) },
                    )
                    Sep()
                    KpRow(
                        stringResource(R.string.voice), meta = stringResource(R.string.voice_meta), inset = 16.dp,
                        trailing = { Value(ui.app.voice?.name ?: stringResource(R.string.voice_none)) },
                    )
                    Sep()
                    ValueRow(stringResource(R.string.default_speed), speedText(ui.app.speed)) { sheet = Sheet.Speed }
                    Sep()
                    ValueRow(
                        stringResource(R.string.scrubber_scope),
                        stringResource(if (ui.app.scrubberScope == ScrubberScope.CHAPTER) R.string.scope_chapter else R.string.scope_book),
                    ) { sheet = Sheet.Scope }
                }

                Section(R.string.group_ai) {
                    KpRow(
                        stringResource(R.string.provider_or_name), meta = stringResource(R.string.provider_or_meta), inset = 16.dp,
                        onClick = { vm.clearCheck(KeyKind.OpenRouter); sheet = Sheet.OpenRouter }, trailing = { Status(ui.openRouterSet) },
                    )
                    Sep()
                    val auto = ui.app.openRouterModel == null
                    KpRow(
                        stringResource(R.string.ai_model), inset = 16.dp,
                        meta = when {
                            !auto -> stringResource(R.string.model_chosen_meta)
                            ui.modelSpeed != null -> stringResource(
                                R.string.model_fastest_meta, shortModelName(ui.modelSpeed.modelId), secondsText(ui.modelSpeed.firstWordsMs),
                            )
                            else -> stringResource(R.string.model_auto_meta)
                        },
                        onClick = if (ui.openRouterSet) ({ sheet = Sheet.Model }) else null,
                        trailing = { Value(if (auto) stringResource(R.string.automatic) else shortModelName(ui.app.openRouterModel.orEmpty()), chevron = ui.openRouterSet) },
                    )
                    Sep()
                    KpRow(
                        stringResource(R.string.requests_today), inset = 16.dp,
                        trailing = {
                            val b = ui.budget
                            Text(
                                if (b != null) stringResource(R.string.library_requests_left, b.requestsLeftToday) else stringResource(R.string.not_set),
                                color = if (b != null) p.text else p.text2, style = KpType.numeral,
                            )
                        },
                    )
                }

                Section(R.string.group_appearance) {
                    KpRow(
                        stringResource(R.string.accent), inset = 16.dp,
                        trailing = { Swatches(ui.seed, vm::setSeed) },
                    )
                    Sep()
                    KpRow(
                        stringResource(R.string.dark_mode), inset = 16.dp,
                        trailing = { KpSwitch(ui.app.dark, vm::setDark, label = stringResource(R.string.dark_mode)) },
                    )
                    Sep()
                    KpRow(
                        stringResource(R.string.haptics), meta = stringResource(R.string.haptics_meta), inset = 16.dp,
                        trailing = { KpSwitch(ui.app.haptics, vm::setHaptics, label = stringResource(R.string.haptics)) },
                    )
                }

                Section(R.string.group_data) {
                    KpRow(stringResource(R.string.fish_spend), inset = 16.dp, trailing = { Text(money(ui.spentCents), color = p.text, style = KpType.numeral) })
                    Sep()
                    ValueRow(
                        stringResource(R.string.monthly_cap),
                        if (ui.app.monthlyCapCents == 0) stringResource(R.string.cap_default, money(ui.paid.monthlyCapCents)) else money(ui.app.monthlyCapCents),
                    ) { sheet = Sheet.Cap }
                    Sep()
                    KpRow(
                        stringResource(R.string.book_cap), meta = stringResource(R.string.book_cap_body), inset = 16.dp,
                        trailing = { Text(money(ui.paid.bookCapCents), color = p.text, style = KpType.numeral) },
                    )
                }
                if (ui.paid.showsNotice) PaidNotice(ui.paid, Modifier.padding(horizontal = 4.dp, vertical = 12.dp))

                Section(R.string.group_about) {
                    KpRow(
                        stringResource(R.string.app_name_row), inset = 16.dp,
                        meta = if (ui.version.isBlank()) null else stringResource(R.string.version_x, ui.version),
                    )
                    Sep()
                    KpRow(stringResource(R.string.about_tagline_label), meta = stringResource(R.string.about_tagline), inset = 16.dp)
                    Sep()
                    KpRow(
                        stringResource(R.string.about_github_label), meta = "iamkhalid2/Kepler", inset = 16.dp,
                        onClick = { uriHandler.openUri("https://github.com/iamkhalid2/Kepler") }, trailing = { KpChevron() },
                    )
                    Sep()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.about_license), color = p.text2, style = KpType.caption)
                        Text(stringResource(R.string.about_fonts), color = p.text2, style = KpType.caption)
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.Fish -> KeySheet(KeyKind.Fish, stringResource(R.string.provider_fish_name), ui.fishSet, ui.fishCheck, vm) { sheet = null }
        Sheet.OpenRouter -> KeySheet(KeyKind.OpenRouter, stringResource(R.string.provider_or_name), ui.openRouterSet, ui.openRouterCheck, vm) { sheet = null }
        Sheet.Model -> KpSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.ai_model))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 480.dp)) {
                ChoiceRow(
                    stringResource(R.string.automatic),
                    ui.modelSpeed?.let { stringResource(R.string.model_fastest_meta, shortModelName(it.modelId), secondsText(it.firstWordsMs)) }
                        ?: stringResource(R.string.model_auto_meta),
                    ui.app.openRouterModel == null,
                ) { vm.setOpenRouterModel(null); sheet = null }
                ui.models.forEach { m ->
                    ChoiceRow(shortModelName(m), if (m == ui.modelSpeed?.modelId) stringResource(R.string.model_fastest_tag) else null, ui.app.openRouterModel == m) {
                        vm.setOpenRouterModel(m); sheet = null
                    }
                }
            }
        }
        Sheet.Cap -> CapSheet(ui.app.monthlyCapCents, ui.paid.monthlyCapCents, vm) { sheet = null }
        Sheet.Speed -> SpeedSheet(ui.app.speed, vm) { sheet = null }
        Sheet.TextSize -> KpSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.text_size))
            listOf(R.string.small, R.string.medium, R.string.large).forEachIndexed { i, r ->
                ChoiceRow(stringResource(r), null, FONT_SCALES[i] == ui.app.fontScale) { vm.setFontScale(FONT_SCALES[i]); sheet = null }
            }
        }
        Sheet.LineSpacing -> KpSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.line_spacing))
            listOf(R.string.tight, R.string.standard, R.string.open).forEachIndexed { i, r ->
                ChoiceRow(stringResource(r), null, LINE_HEIGHTS[i] == ui.app.lineHeight) { vm.setLineHeight(LINE_HEIGHTS[i]); sheet = null }
            }
        }
        Sheet.Typeface -> KpSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.typeface))
            FONT_FAMILIES.forEach { f -> ChoiceRow(f, null, f == ui.app.fontFamily) { vm.setFontFamily(f); sheet = null } }
        }
        Sheet.Scope -> KpSheet(onDismiss = { sheet = null }) {
            SheetTitle(stringResource(R.string.scrubber_scope))
            ChoiceRow(stringResource(R.string.scope_chapter), stringResource(R.string.scope_chapter_meta), ui.app.scrubberScope == ScrubberScope.CHAPTER) {
                vm.setScrubberScope(ScrubberScope.CHAPTER); sheet = null
            }
            ChoiceRow(stringResource(R.string.scope_book), stringResource(R.string.scope_book_meta), ui.app.scrubberScope == ScrubberScope.BOOK) {
                vm.setScrubberScope(ScrubberScope.BOOK); sheet = null
            }
        }
        null -> Unit
    }
}

/** Section label over a card of rows. */
@Composable
private fun Section(title: Int, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(if (first) 8.dp else KpSpace.section))
    KpCaps(stringResource(title), Modifier.padding(start = 4.dp, bottom = 8.dp))
    KpCard(padding = PaddingValues(0.dp), content = content)
}

@Composable
private fun Sep() = KpRowSeparator(inset = 16.dp)

@Composable
private fun SheetTitle(text: String) {
    Text(text, color = Kp.palette.text, style = KpType.title2, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Value(text: String, chevron: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Kp.palette.text2, style = KpType.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = if (chevron) 4.dp else 0.dp))
        if (chevron) KpChevron()
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    KpRow(title, inset = 16.dp, onClick = onClick, trailing = { Value(value) })
}

@Composable
private fun Status(connected: Boolean) {
    val p = Kp.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (connected) { KpDot(p.positive); Spacer(Modifier.size(8.dp)) }
        Text(stringResource(if (connected) R.string.connected else R.string.not_set), color = p.text2, style = KpType.subhead)
        Spacer(Modifier.size(4.dp))
        KpChevron()
    }
}

/** One option in a choice sheet: a check on the chosen one, an optional line of explanation. */
@Composable
private fun ChoiceRow(title: String, meta: String?, selected: Boolean, onClick: () -> Unit) {
    val p = Kp.palette
    KpRow(
        title, meta = meta, onClick = onClick, inset = 0.dp,
        leading = { Box(Modifier.size(24.dp)) { if (selected) KpIconView(KpIcons.Check, p.accentText, size = 24.dp) } },
    )
}

/** The four accent presets as 28 dp discs; the chosen one carries a ring. */
@Composable
private fun Swatches(selected: KpSeed, onSelect: (KpSeed) -> Unit) {
    val p = Kp.palette
    val haptics = rememberHaptics()
    Row {
        KpSeed.presets.forEach { seed ->
            Box(
                Modifier.size(40.dp, 48.dp)
                    .clickable(role = Role.RadioButton) { haptics.play(HapticToken.Tap); onSelect(seed) }
                    .semantics { contentDescription = seed.label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(seed.color)
                        .then(if (seed == selected) Modifier.border(2.dp, p.text, CircleShape) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun SpeedSheet(current: Float, vm: SettingsViewModel, onClose: () -> Unit) {
    val p = Kp.palette
    var v by remember { mutableFloatStateOf(current.coerceIn(0.75f, 2f)) }
    KpSheet(onDismiss = onClose) {
        SheetTitle(stringResource(R.string.default_speed))
        Text(speedText(v), color = p.text, style = KpType.bigStat)
        Text(stringResource(R.string.default_speed_body), color = p.text2, style = KpType.caption)
        Spacer(Modifier.height(8.dp))
        KpSlider(
            v, { v = (it / 0.05f).roundToInt() * 0.05f }, valueRange = 0.75f..2f, steps = 24,
            onValueChangeFinished = { vm.setSpeed(v) },
            contentDescription = stringResource(R.string.default_speed),
        )
    }
}

private fun speedText(s: Float): String = String.format(Locale.US, "%.2f", s).trimEnd('0').trimEnd('.') + "x"

@Composable
private fun secondsText(ms: Long): String = stringResource(R.string.seconds_short, String.format(Locale.getDefault(), "%.1f", ms / 1000.0))

private fun money(cents: Int): String = moneyText(cents)

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

@Composable
private fun CapSheet(current: Int, effective: Int, vm: SettingsViewModel, onClose: () -> Unit) {
    val p = Kp.palette
    var text by remember { mutableStateOf(if (current == 0) "" else "%.2f".format(java.util.Locale.US, current / 100.0).removeSuffix(".00")) }
    var invalid by remember { mutableStateOf(false) }
    KpSheet(onDismiss = onClose) {
        KpCaps(stringResource(R.string.monthly_cap))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.cap_body), color = p.text2, style = KpType.caption)
        Spacer(Modifier.height(16.dp))
        KpInput(
            value = text, onValueChange = { text = it; invalid = false },
            label = stringResource(R.string.cap_field_label),
            placeholder = stringResource(R.string.cap_default, money(effective)),
            error = if (invalid) stringResource(R.string.cap_invalid) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(16.dp))
        KpSecondaryKey(stringResource(R.string.cap_save), { if (vm.setMonthlyCapText(text)) onClose() else invalid = true }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        KpTextLink(stringResource(R.string.cap_use_default), { vm.setMonthlyCapCents(0); onClose() })
    }
}
