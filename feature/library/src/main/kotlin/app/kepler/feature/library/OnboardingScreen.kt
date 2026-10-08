package app.kepler.feature.library

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.BOOK_H
import app.kepler.core.designsystem.BOOK_W
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpDot
import app.kepler.core.designsystem.KpFonts
import app.kepler.core.designsystem.KpInput
import app.kepler.core.designsystem.KpPrimaryKey
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.BookPose
import app.kepler.core.designsystem.BookScene
import app.kepler.core.designsystem.BookSpec
import app.kepler.core.designsystem.PlankBaseline
import app.kepler.core.designsystem.ShelfPlank
import kotlinx.coroutines.delay

@Composable
fun OnboardingRoute(onDone: () -> Unit, deps: LibraryDeps = LocalLibraryDeps.current) {
    val vm = libraryViewModel { OnboardingViewModel(deps) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(ui.finished) { if (ui.finished) onDone() }
    BackHandler(enabled = !ui.isFirst) { vm.back() }
    OnboardingScreen(
        ui = ui,
        onNext = vm::next,
        onFinish = vm::finish,
        onVerify = vm::verify,
        onSaveUnverified = vm::saveUnverified,
        onOpenBatterySettings = { openBatterySettings(context) },
        prefill = deps.devKeyPrefill,
    )
}

@Composable
fun OnboardingScreen(
    ui: OnboardingUi,
    onNext: () -> Unit,
    onFinish: () -> Unit,
    onVerify: (KeyKind, String) -> Unit,
    onSaveUnverified: (KeyKind, String) -> Unit,
    onOpenBatterySettings: () -> Unit,
    prefill: (KeyKind) -> String = { "" },
) {
    val p = Kp.palette
    var fishKey by remember { mutableStateOf(if (ui.fish == KeyCheck.Connected) "" else prefill(KeyKind.Fish)) }
    var orKey by remember { mutableStateOf(if (ui.openRouter == KeyCheck.Connected) "" else prefill(KeyKind.OpenRouter)) }
    // Read, Listen, Remember: the book turns 90 -> 24 -> 0 once, as the three lines are read.
    var turn by remember(ui.step) { mutableIntStateOf(0) }
    LaunchedEffect(ui.step) {
        if (ui.step == OnboardingStep.Pillars) { delay(700); turn = 1; delay(900); turn = 2 }
    }
    val yaw = when (ui.step) {
        OnboardingStep.Welcome -> 90f
        OnboardingStep.Pillars -> floatArrayOf(90f, 24f, 0f)[turn]
        OnboardingStep.Keys -> 24f
        OnboardingStep.Privacy -> 0f
        OnboardingStep.Battery -> 24f
    }
    val materials = rememberBookMaterials()
    val wordmark = stringResource(R.string.wordmark)

    ScreenRoot {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth.value
            val h = maxHeight.value
            val k = 390f / w
            val plankTop = h * 0.34f
            val bookWidthDp = 100f
            val s = bookWidthDp * k / BOOK_W
            val cyUnits = (plankTop + PlankBaseline.value - BOOK_H * s / k / 2f) * k
            // Step segments, then the book on its plank, then the step's content.
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ui.steps.forEachIndexed { i, _ ->
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == ui.index) p.accent else p.hairline))
                }
            }
            ShelfPlank(Modifier.offset(y = plankTop.dp))
            BookScene(
                books = listOf(BookSpec(wordmark, "", 26f, materials[0])),
                targets = listOf(BookPose(w / 2f * k, cyUnits, s, ry = yaw, rx = if (yaw == 0f) 0f else -4f)),
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.fillMaxSize().padding(top = (plankTop + 48f).dp).imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            ) {
                when (ui.step) {
                    OnboardingStep.Welcome -> Welcome(onNext)
                    OnboardingStep.Pillars -> Pillars(onNext)
                    OnboardingStep.Keys -> Keys(
                        ui, fishKey, { fishKey = it }, orKey, { orKey = it }, onVerify,
                        { kind, key -> onSaveUnverified(kind, key) }, onNext,
                    )
                    OnboardingStep.Privacy -> Privacy(if (ui.isLast) onFinish else onNext, ui.isLast)
                    OnboardingStep.Battery -> Battery(onOpenBatterySettings, onFinish)
                }
            }
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val p = Kp.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.wordmark), color = p.text, fontFamily = KpFonts.displaySmall, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.6.sp)
        Spacer(Modifier.size(6.dp))
        KpDot(p.accent)
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.welcome_line), color = p.text, style = KpType.display)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.welcome_sub), color = p.text2, style = KpType.body)
    Spacer(Modifier.height(32.dp))
    KpPrimaryKey(stringResource(R.string.get_started), onNext)
}

@Composable
private fun Pillars(onNext: () -> Unit) {
    val p = Kp.palette
    listOf(R.string.pillar_read to R.string.pillar_read_sub, R.string.pillar_listen to R.string.pillar_listen_sub, R.string.pillar_remember to R.string.pillar_remember_sub)
        .forEach { (a, b) ->
            KpCaps(stringResource(a), color = p.text)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(b), color = p.text2, style = KpType.body)
            Spacer(Modifier.height(16.dp))
        }
    Spacer(Modifier.height(16.dp))
    KpPrimaryKey(stringResource(R.string.continue_label), onNext)
}

@Composable
private fun Keys(
    ui: OnboardingUi,
    fishKey: String, onFishKey: (String) -> Unit,
    orKey: String, onOrKey: (String) -> Unit,
    onVerify: (KeyKind, String) -> Unit,
    onSaveUnverified: (KeyKind, String) -> Unit,
    onNext: () -> Unit,
) {
    val p = Kp.palette
    Text(stringResource(R.string.keys_title), color = p.text, style = KpType.headline)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.keys_sub), color = p.text2, style = KpType.body)
    Spacer(Modifier.height(24.dp))
    KeyField(KeyKind.Fish, stringResource(R.string.fish_key_label), ui.fish, fishKey, onFishKey, onVerify, onSaveUnverified)
    Spacer(Modifier.height(24.dp))
    KeyField(KeyKind.OpenRouter, stringResource(R.string.openrouter_key_label), ui.openRouter, orKey, onOrKey, onVerify, onSaveUnverified)
    Spacer(Modifier.height(24.dp))
    KpPrimaryKey(stringResource(R.string.continue_label), onNext)
    Spacer(Modifier.height(4.dp))
    KpTextLink(stringResource(R.string.skip_for_now), onNext)
}

@Composable
internal fun KeyField(
    kind: KeyKind, label: String, check: KeyCheck, value: String, onValue: (String) -> Unit,
    onVerify: (KeyKind, String) -> Unit, onSaveUnverified: (KeyKind, String) -> Unit,
) {
    val p = Kp.palette
    KpInput(
        value = value, onValueChange = onValue, label = label, secret = true,
        placeholder = if (check == KeyCheck.Connected && value.isEmpty()) stringResource(R.string.key_saved_placeholder) else null,
        error = when (check) {
            KeyCheck.Rejected -> stringResource(R.string.key_rejected)
            KeyCheck.Unreachable -> stringResource(R.string.key_unreachable)
            else -> null
        },
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpSecondaryKey(
            stringResource(if (check == KeyCheck.Checking) R.string.checking else R.string.verify),
            { onVerify(kind, value) }, enabled = value.isNotBlank() && check != KeyCheck.Checking,
        )
        if (check == KeyCheck.Connected) {
            KpDot(p.positive)
            Text(stringResource(R.string.connected), color = p.text2, style = KpType.caption)
        }
        if (check == KeyCheck.Unreachable) KpTextLink(stringResource(R.string.save_without_checking), { onSaveUnverified(kind, value) })
    }
}

@Composable
private fun Privacy(onNext: () -> Unit, last: Boolean) {
    val p = Kp.palette
    Text(stringResource(R.string.privacy_title), color = p.text, style = KpType.headline)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.privacy_body), color = p.body, style = KpType.body)
    Spacer(Modifier.height(32.dp))
    KpPrimaryKey(stringResource(if (last) R.string.done else R.string.continue_label), onNext)
}

@Composable
private fun Battery(onOpen: () -> Unit, onFinish: () -> Unit) {
    val p = Kp.palette
    Text(stringResource(R.string.battery_title), color = p.text, style = KpType.headline)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.battery_sub), color = p.text2, style = KpType.body)
    Spacer(Modifier.height(16.dp))
    listOf(R.string.battery_step_1, R.string.battery_step_2, R.string.battery_step_3).forEachIndexed { i, r ->
        Row(Modifier.padding(vertical = 4.dp)) {
            Text("${i + 1}", color = p.accentText, style = KpType.numeral, modifier = Modifier.size(24.dp))
            Text(stringResource(r), color = p.body, style = KpType.body)
        }
    }
    Spacer(Modifier.height(24.dp))
    KpSecondaryKey(stringResource(R.string.open_battery_settings), onOpen, Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    KpPrimaryKey(stringResource(R.string.done), onFinish)
}
