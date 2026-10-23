package app.kepler.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpButton
import app.kepler.core.designsystem.KpButtonStyle
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpDot
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpInput
import app.kepler.core.designsystem.KpMark
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpType

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

/**
 * Three short pages (design brief v2 section 8): an object in the top 45%, a display headline, a body in `text2`,
 * dots with a 18 dp active pill, a Primary Continue and a Ghost Skip docked at the bottom. OPPO-family phones get a
 * fourth page about background activity.
 */
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
    var fishKey by remember { mutableStateOf(if (ui.fish == KeyCheck.Connected) "" else prefill(KeyKind.Fish)) }
    var orKey by remember { mutableStateOf(if (ui.openRouter == KeyCheck.Connected) "" else prefill(KeyKind.OpenRouter)) }

    ScreenRoot {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The object takes 45% of the page; on the keys page it shrinks so both fields and the keyboard fit.
            val artHeight by animateDpAsState(if (ui.step == OnboardingStep.Keys) 112.dp else maxHeight * 0.4f, tween(180), label = "art")
            Column(Modifier.fillMaxSize().imePadding()) {
                Box(Modifier.fillMaxWidth().height(artHeight), contentAlignment = Alignment.Center) {
                    Crossfade(ui.step, animationSpec = tween(180), label = "art-object") { step -> StepArt(step, artHeight) }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = KpSpace.gutter + 4.dp),
                ) {
                    Crossfade(ui.step, animationSpec = tween(180), label = "page") { step ->
                        Column {
                            when (step) {
                                OnboardingStep.Welcome -> Welcome()
                                OnboardingStep.Keys -> Keys(ui, fishKey, { fishKey = it }, orKey, { orKey = it }, onVerify, onSaveUnverified)
                                OnboardingStep.Privacy -> Privacy()
                                OnboardingStep.Battery -> Battery(onOpenBatterySettings)
                            }
                        }
                    }
                }
                // Continue keeps what was typed on the keys page: it verifies any key not yet checked (and stores it
                // once accepted) instead of silently dropping it. "Skip for now" still leaves them out.
                val onContinue = {
                    if (ui.step == OnboardingStep.Keys) {
                        if (fishKey.isNotBlank() && ui.fish != KeyCheck.Connected && ui.fish != KeyCheck.Checking) onVerify(KeyKind.Fish, fishKey)
                        if (orKey.isNotBlank() && ui.openRouter != KeyCheck.Connected && ui.openRouter != KeyCheck.Checking) onVerify(KeyKind.OpenRouter, orKey)
                    }
                    onNext()
                }
                Footer(ui, onContinue, onNext, onFinish)
            }
        }
    }
}

@Composable
private fun Footer(ui: OnboardingUi, onContinue: () -> Unit, onNext: () -> Unit, onFinish: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = KpSpace.gutter).padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Dots(ui.steps.size, ui.index)
        Spacer(Modifier.height(16.dp))
        KpButton(
            stringResource(
                when {
                    ui.step == OnboardingStep.Welcome -> R.string.get_started
                    ui.isLast -> R.string.done
                    else -> R.string.continue_label
                },
            ),
            if (ui.isLast) onFinish else onContinue,
            Modifier.fillMaxWidth(), fullWidth = true,
        )
        // Skip is always one tap away, except on the last page where Done is the way out.
        Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
            if (!ui.isLast) {
                KpButton(
                    stringResource(if (ui.step == OnboardingStep.Keys) R.string.skip_for_now else R.string.skip),
                    if (ui.step == OnboardingStep.Keys) onNext else onFinish,
                    style = KpButtonStyle.Ghost, small = true,
                )
            }
        }
    }
}

/** Page dots: the active one is an 18 x 6 pill, the others 6 dp discs. */
@Composable
private fun Dots(count: Int, active: Int) {
    val p = Kp.palette
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == active) 18.dp else 6.dp, tween(180), label = "dot")
            Box(Modifier.size(w, 6.dp).clip(CircleShape).background(if (i == active) p.accent else p.hairline))
        }
    }
}

/** The page's object: the Kepler mark over a soft accent glow, or a tile of icons for the other pages. */
@Composable
private fun StepArt(step: OnboardingStep, height: androidx.compose.ui.unit.Dp) {
    val p = Kp.palette
    when (step) {
        OnboardingStep.Welcome -> Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(p.accent.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height / 2f), radius = size.height * 0.5f,
                    ),
                )
            },
            contentAlignment = Alignment.Center,
        ) { KpMark(size = (height * 0.62f).coerceAtMost(220.dp), contentDescription = stringResource(R.string.wordmark)) }
        OnboardingStep.Keys -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(KpIcons.Voice)
            IconTile(KpIcons.Sparkle)
        }
        OnboardingStep.Privacy -> IconTile(KpIcons.Book, big = true)
        OnboardingStep.Battery -> IconTile(KpIcons.Headphones, big = true)
    }
}

@Composable
private fun IconTile(icon: ImageVector, big: Boolean = false) {
    val p = Kp.palette
    val s = if (big) 112.dp else 72.dp
    Box(Modifier.size(s).clip(RoundedCornerShape(if (big) 32.dp else 22.dp)).background(p.surface1), contentAlignment = Alignment.Center) {
        KpIconView(icon, p.accentText, size = if (big) 48.dp else 32.dp)
    }
}

@Composable
private fun Headline(text: String) {
    Text(text, color = Kp.palette.text, style = KpType.displayHeadline)
}

@Composable
private fun Body(text: String) {
    Text(text, color = Kp.palette.text2, style = KpType.body.copy(fontSize = 16.sp, lineHeight = 24.sp), modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Welcome() {
    Headline(stringResource(R.string.welcome_line))
    Body(stringResource(R.string.welcome_sub))
    // What the three words mean in practice, so the page explains the app instead of ending in empty space.
    Spacer(Modifier.height(24.dp))
    KpCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        FeatureRow(KpIcons.Book, stringResource(R.string.welcome_read_title), stringResource(R.string.welcome_read_body))
        FeatureRow(KpIcons.Headphones, stringResource(R.string.welcome_listen_title), stringResource(R.string.welcome_listen_body))
        FeatureRow(KpIcons.Sparkle, stringResource(R.string.welcome_remember_title), stringResource(R.string.welcome_remember_body))
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    val p = Kp.palette
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        KpIconView(icon, p.accentText, size = 22.dp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = p.text, style = KpType.rowTitle)
            Text(body, color = p.text2, style = KpType.subhead)
        }
    }
}

@Composable
private fun Keys(
    ui: OnboardingUi,
    fishKey: String, onFishKey: (String) -> Unit,
    orKey: String, onOrKey: (String) -> Unit,
    onVerify: (KeyKind, String) -> Unit,
    onSaveUnverified: (KeyKind, String) -> Unit,
) {
    Headline(stringResource(R.string.keys_title))
    Body(stringResource(R.string.keys_sub))
    Spacer(Modifier.height(20.dp))
    KeyCard(
        stringResource(R.string.provider_fish_name), stringResource(R.string.keys_fish_blurb),
        KeyKind.Fish, stringResource(R.string.fish_key_label), ui.fish, fishKey, onFishKey, onVerify, onSaveUnverified,
    )
    Spacer(Modifier.height(KpSpace.cardGap))
    KeyCard(
        stringResource(R.string.provider_or_name), stringResource(R.string.keys_or_blurb),
        KeyKind.OpenRouter, stringResource(R.string.openrouter_key_label), ui.openRouter, orKey, onOrKey, onVerify, onSaveUnverified,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun KeyCard(
    name: String, blurb: String, kind: KeyKind, label: String, check: KeyCheck, value: String, onValue: (String) -> Unit,
    onVerify: (KeyKind, String) -> Unit, onSaveUnverified: (KeyKind, String) -> Unit,
) {
    val p = Kp.palette
    KpCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), color = p.text, style = KpType.rowTitle)
            if (check == KeyCheck.Connected) {
                KpDot(p.positive)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.connected), color = p.text2, style = KpType.caption)
            }
        }
        Text(blurb, color = p.text2, style = KpType.caption, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        KeyField(kind, label, check, value, onValue, onVerify, onSaveUnverified, inCard = true)
    }
}

@Composable
internal fun KeyField(
    kind: KeyKind, label: String, check: KeyCheck, value: String, onValue: (String) -> Unit,
    onVerify: (KeyKind, String) -> Unit, onSaveUnverified: (KeyKind, String) -> Unit,
    inCard: Boolean = false,
) {
    val p = Kp.palette
    KpInput(
        value = value, onValueChange = onValue, label = label, secret = true, keyboardOptions = SecretKeyboard, inCard = inCard,
        placeholder = if (check == KeyCheck.Connected && value.isEmpty()) stringResource(R.string.key_saved_placeholder) else null,
        error = when (check) {
            KeyCheck.Rejected -> stringResource(R.string.key_rejected)
            KeyCheck.Unreachable -> stringResource(R.string.key_unreachable)
            else -> null
        },
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpButton(
            stringResource(if (check == KeyCheck.Checking) R.string.checking else R.string.verify),
            { onVerify(kind, value) }, style = KpButtonStyle.Secondary, small = true, onBg = !inCard,
            enabled = value.isNotBlank() && check != KeyCheck.Checking,
        )
        if (check == KeyCheck.Connected && !inCard) {
            KpDot(p.positive)
            Text(stringResource(R.string.connected), color = p.text2, style = KpType.caption)
        }
        if (check == KeyCheck.Unreachable) KpTextLink(stringResource(R.string.save_without_checking), { onSaveUnverified(kind, value) })
    }
}

@Composable
private fun Privacy() {
    Headline(stringResource(R.string.privacy_title))
    Spacer(Modifier.height(16.dp))
    KpCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        listOf(R.string.privacy_1, R.string.privacy_2, R.string.privacy_3, R.string.privacy_4).forEach { PrivacyRow(stringResource(it)) }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun PrivacyRow(text: String) {
    val p = Kp.palette
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        KpIconView(KpIcons.Check, p.accentText, size = 20.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = p.text, style = KpType.body.copy(fontSize = 16.sp, lineHeight = 24.sp))
    }
}

@Composable
private fun Battery(onOpen: () -> Unit) {
    val p = Kp.palette
    Headline(stringResource(R.string.battery_title))
    Body(stringResource(R.string.battery_sub))
    Spacer(Modifier.height(16.dp))
    KpCard(padding = PaddingValues(16.dp)) {
        listOf(R.string.battery_step_1, R.string.battery_step_2, R.string.battery_step_3).forEachIndexed { i, r ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}", Modifier.width(24.dp), color = p.accentText, style = KpType.numeral)
                Text(stringResource(r), color = p.text, style = KpType.body)
            }
        }
        Spacer(Modifier.height(12.dp))
        KpButton(stringResource(R.string.open_battery_settings), onOpen, style = KpButtonStyle.Secondary, small = true)
    }
    Spacer(Modifier.height(16.dp))
}
