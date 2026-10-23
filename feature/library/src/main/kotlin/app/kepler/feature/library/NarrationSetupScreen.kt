package app.kepler.feature.library

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpButton
import app.kepler.core.designsystem.KpButtonStyle
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpIcon
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpRing
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpSkeleton
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpStatTile
import app.kepler.core.designsystem.KpTextLink
import app.kepler.core.designsystem.KpTopBar
import app.kepler.core.designsystem.KpType
import app.kepler.core.model.NarrationEstimate
import app.kepler.core.model.NarrationState
import app.kepler.core.model.VoiceOption

@Composable
fun NarrationSetupRoute(
    bookId: Long,
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    deps: LibraryDeps = LocalLibraryDeps.current,
) {
    val sample = stringResource(R.string.voice_sample)
    val vm = libraryViewModel(key = "setup-$bookId") { NarrationSetupViewModel(deps, bookId, sample) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val player = remember { PreviewPlayer() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(vm) { vm.previews.collect { player.play(it) } }
    BackHandler(onBack = onBack)
    NarrationSetupScreen(
        ui, onBack, onOpenSettings, vm::setProvider, vm::select, vm::setSpeed, vm::preview,
        vm::start, vm::stop, vm::useSystemVoice, vm::confirmPaid, vm::resume,
    )
}

/** Plays one preview file at a time. */
internal class PreviewPlayer {
    private var player: MediaPlayer? = null

    fun play(path: String) {
        release()
        player = MediaPlayer().apply {
            try {
                setDataSource(path)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { release() }
                setOnErrorListener { _, _, _ -> release(); true }
                prepareAsync()
            } catch (_: Exception) {
                release()
            }
        }
    }

    fun release() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}

@Composable
fun NarrationSetupScreen(
    ui: SetupUi,
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onProvider: (String) -> Unit,
    onSelect: (app.kepler.core.model.VoiceRef) -> Unit,
    onSpeed: (Float) -> Unit,
    onPreview: (VoiceOption) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUseSystem: () -> Unit,
    onConfirmPaid: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    val p = Kp.palette
    ScreenRoot {
        // Brief v2, narration setup: detail top bar, provider segmented, the voices as one grouped card, speed,
        // the estimate as stat tiles, and the start action docked at the bottom so it is never a scroll away.
        Column(Modifier.fillMaxSize()) {
            KpTopBar(title = stringResource(R.string.narration_caps), caption = ui.book?.title, onBack = onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = KpSpace.gutter).padding(top = 8.dp, bottom = 16.dp),
            ) {
                KpSegmented(
                    listOf(stringResource(R.string.provider_fish), stringResource(R.string.provider_system)),
                    if (ui.provider == PROVIDER_FISH) 0 else 1,
                    { onProvider(if (it == 0) PROVIDER_FISH else PROVIDER_SYSTEM) },
                )

                Section(R.string.setup_voice) {
                    when (val v = ui.voices) {
                        VoicesUi.Loading -> KpCard(padding = PaddingValues(16.dp)) {
                            repeat(3) { i ->
                                if (i > 0) Spacer(Modifier.height(12.dp))
                                KpSkeleton(Modifier.fillMaxWidth().height(44.dp))
                            }
                        }
                        is VoicesUi.Unavailable -> KpCard(padding = PaddingValues(20.dp)) {
                            Text(
                                stringResource(if (v.problem == VoiceProblem.NoFishKey) R.string.voices_no_key else R.string.voices_offline),
                                color = p.text2, style = KpType.body,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(KpSpace.s)) {
                                KpButton(stringResource(R.string.use_system_voice), { onProvider(PROVIDER_SYSTEM) }, style = KpButtonStyle.Secondary, small = true)
                                if (v.problem == VoiceProblem.NoFishKey && onOpenSettings != null) {
                                    KpButton(stringResource(R.string.open_settings), onOpenSettings, style = KpButtonStyle.Ghost, small = true)
                                }
                            }
                        }
                        is VoicesUi.Ready -> KpCard(padding = PaddingValues(vertical = 4.dp)) {
                            v.voices.forEachIndexed { i, opt ->
                                if (i > 0) KpRowSeparator(hasLeading = true, inset = 16.dp)
                                VoiceRow(
                                    opt, voiceMeta(opt, ui.provider != PROVIDER_FISH), avatarText(opt, ui.provider != PROVIDER_FISH),
                                    selected = ui.selected?.id == opt.ref.id,
                                    previewing = ui.previewing == opt.ref.id,
                                    onSelect = { onSelect(opt.ref) }, onPreview = { onPreview(opt) },
                                )
                            }
                        }
                    }
                    if (ui.previewFailed) {
                        Text(stringResource(R.string.preview_failed), color = p.negative, style = KpType.caption, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                Section(R.string.setup_speed) {
                    KpSegmented(
                        SPEED_CHOICES.map { stringResource(R.string.speed_x, it) },
                        SPEED_CHOICES.indexOfFirst { it == ui.speed },
                        { onSpeed(SPEED_CHOICES[it]) },
                    )
                }

                Section(R.string.setup_cost) {
                    Estimate(ui.estimate)
                    if (ui.paid.showsNotice && ui.narration !is NarrationState.Paused) {
                        PaidNotice(ui.paid, Modifier.padding(top = 12.dp))
                    }
                }
            }

            // Docked: progress (while narrating) and the one Primary action.
            Column(Modifier.fillMaxWidth().background(p.bg)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                Column(Modifier.padding(horizontal = KpSpace.gutter, vertical = 12.dp)) {
                    Progress(ui, onStop, onUseSystem, onConfirmPaid, onResume, onOpenSettings)
                    if (ui.narration !is NarrationState.Running && ui.narration !is NarrationState.Paused) {
                        if (ui.narration !is NarrationState.Idle) Spacer(Modifier.height(12.dp))
                        KpButton(
                            stringResource(if (ui.narration is NarrationState.Done) R.string.bind_again else R.string.bind_narration),
                            onStart, Modifier.fillMaxWidth(), enabled = ui.canStart, fullWidth = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Spacer(Modifier.height(28.dp))
    KpCaps(stringResource(title))
    Spacer(Modifier.height(8.dp))
    content()
}

/** One voice: initial avatar (accent once chosen), name, a plain descriptor, Preview; the chosen one is checked. */
@Composable
private fun VoiceRow(
    opt: VoiceOption, meta: String?, avatar: String, selected: Boolean, previewing: Boolean, onSelect: () -> Unit, onPreview: () -> Unit,
) {
    val p = Kp.palette
    KpRow(
        title = opt.ref.name,
        meta = meta,
        onClick = onSelect,
        inset = 16.dp,
        titleColor = if (selected) p.accentText else p.text,
        leading = {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (selected) p.accent else p.surface2),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) KpIconView(KpIcons.Check, p.onAccent, size = 20.dp)
                else Text(avatar, color = p.text, style = if (avatar.length > 1) KpType.subhead else KpType.rowTitle)
            }
        },
        trailing = {
            KpTextLink(stringResource(if (previewing) R.string.preview_loading else R.string.preview), onPreview)
        },
    )
}

/** The avatar text: a system voice's region ("AU", "GB"), since every English voice would otherwise read "E". */
private fun avatarText(opt: VoiceOption, system: Boolean): String {
    val region = opt.description.split('-').getOrNull(1)?.takeIf { system && it.length == 2 && it.all(Char::isLetter) }
    return region?.uppercase() ?: opt.ref.name.trim().take(1).uppercase()
}

/**
 * Fish voices come with a written description. System voices only have an engine id ("en-au-x-aua-local"),
 * which means nothing to a listener: show which variant it is and whether it runs on the phone instead.
 */
@Composable
private fun voiceMeta(opt: VoiceOption, system: Boolean): String? {
    val d = opt.description.trim()
    if (!system) return d.takeIf { it.isNotBlank() }
    return when {
        d.endsWith("-language") -> stringResource(R.string.voice_standard)
        "-x-" in d -> {
            val variant = d.substringAfter("-x-").substringBefore('-').lastOrNull()?.uppercaseChar()
            val where = stringResource(if (d.endsWith("-network")) R.string.voice_online else R.string.voice_on_device)
            if (variant != null) stringResource(R.string.voice_variant, variant.toString(), where) else where
        }
        else -> d.takeIf { it.isNotBlank() }
    }
}

/** The estimate as stat tiles (cost, audio length, time to narrate), with a quiet line while it is worked out. */
@Composable
private fun Estimate(estimate: EstimateUi) {
    val p = Kp.palette
    when (estimate) {
        is EstimateUi.Ready -> {
            val e = estimate.estimate
            Row(horizontalArrangement = Arrangement.spacedBy(KpSpace.s)) {
                KpStatTile(costText(e), stringResource(R.string.slip_cost), Modifier.weight(1f))
                KpStatTile(durationText(e.audioSeconds), stringResource(R.string.slip_audio), Modifier.weight(1f))
                KpStatTile(durationText(e.etaSeconds), stringResource(R.string.slip_time), Modifier.weight(1f))
            }
            Text(
                stringResource(R.string.slip_characters_line, "%,d".format(e.characters)),
                color = p.text2, style = KpType.caption, modifier = Modifier.padding(top = 8.dp),
            )
        }
        EstimateUi.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(KpSpace.s)) {
            repeat(3) { KpSkeleton(Modifier.weight(1f).height(72.dp)) }
        }
        EstimateUi.None, EstimateUi.Unavailable -> KpCard(padding = PaddingValues(16.dp)) {
            Text(
                stringResource(if (estimate == EstimateUi.None) R.string.estimate_pick_voice else R.string.estimate_unavailable),
                color = p.text2, style = KpType.body,
            )
        }
    }
}

@Composable
private fun costText(e: NarrationEstimate): String =
    if (e.freeTier) stringResource(R.string.cost_free_until) else "$" + "%.2f".format(e.costCents / 100.0)

@Composable
internal fun durationText(seconds: Long): String {
    val (h, m) = splitHoursMinutes(seconds)
    return if (h > 0) stringResource(R.string.duration_hm, h, m) else stringResource(R.string.duration_m, m.coerceAtLeast(1))
}

@Composable
private fun Progress(
    ui: SetupUi, onStop: () -> Unit, onUseSystem: () -> Unit, onConfirmPaid: () -> Unit, onResume: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val p = Kp.palette
    when (val n = ui.narration) {
        is NarrationState.Running -> {
            val f = if (n.totalSentences > 0) n.doneSentences.toFloat() / n.totalSentences else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                KpRing(f)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.narration_binding, percentOf(f)), color = p.text, style = KpType.body)
                    Text(stringResource(R.string.eta_left, durationText(n.etaSeconds)), color = p.text2, style = KpType.caption)
                }
                KpSecondaryKey(stringResource(R.string.stop), onStop)
            }
        }
        is NarrationState.Paused -> PausedPanel(
            pausedKind(n.reason), ui.paid,
            (ui.estimate as? EstimateUi.Ready)?.estimate?.takeIf { !it.freeTier }?.costCents,
            ui.provider == PROVIDER_FISH, onConfirmPaid, onResume, onUseSystem, onOpenSettings,
        )
        is NarrationState.Failed -> Column {
            Text(stringResource(R.string.narration_stopped_with, n.message), color = p.negative, style = KpType.body)
            if (ui.provider == PROVIDER_FISH) {
                Spacer(Modifier.height(8.dp))
                KpSecondaryKey(stringResource(R.string.use_system_voice), onUseSystem)
            }
        }
        is NarrationState.Done -> Text(stringResource(R.string.narration_done), color = p.positive, style = KpType.body)
        NarrationState.Idle -> Unit
    }
}
