package app.kepler.feature.library

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpHairline
import app.kepler.core.designsystem.KpIcon
import app.kepler.core.designsystem.KpIconKind
import app.kepler.core.designsystem.KpPrimaryKey
import app.kepler.core.designsystem.KpRing
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSecondaryKey
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpTextLink
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
        vm::start, vm::stop, vm::useSystemVoice,
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
) {
    val p = Kp.palette
    ScreenRoot {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(onBack, stringResource(R.string.narration_caps))
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(ui.book?.title.orEmpty(), color = p.text, style = KpType.headline)
                val authors = ui.book?.authors.orEmpty()
                if (authors.isNotBlank()) Text(authors, color = p.text2, style = KpType.author)
            }
            Spacer(Modifier.height(24.dp))

            Section(R.string.setup_type) {
                KpSegmented(
                    listOf(stringResource(R.string.provider_fish), stringResource(R.string.provider_system)),
                    if (ui.provider == PROVIDER_FISH) 0 else 1,
                    { onProvider(if (it == 0) PROVIDER_FISH else PROVIDER_SYSTEM) },
                    Modifier.padding(horizontal = 24.dp),
                )
            }

            Section(R.string.setup_voice) {
                when (val v = ui.voices) {
                    VoicesUi.Loading -> Text(stringResource(R.string.loading), color = p.text2, style = KpType.caption, modifier = Modifier.padding(horizontal = 24.dp))
                    is VoicesUi.Unavailable -> Column(Modifier.padding(horizontal = 24.dp)) {
                        Text(
                            stringResource(if (v.problem == VoiceProblem.NoFishKey) R.string.voices_no_key else R.string.voices_offline),
                            color = p.text2, style = KpType.body,
                        )
                        if (v.problem == VoiceProblem.NoFishKey && onOpenSettings != null) KpTextLink(stringResource(R.string.open_settings), onOpenSettings)
                        KpSecondaryKey(stringResource(R.string.use_system_voice), { onProvider(PROVIDER_SYSTEM) })
                    }
                    is VoicesUi.Ready -> v.voices.forEach { opt ->
                        KpRow(
                            title = opt.ref.name,
                            meta = opt.description.takeIf { it.isNotBlank() },
                            onClick = { onSelect(opt.ref) },
                            leading = { if (ui.selected?.id == opt.ref.id) KpIcon(KpIconKind.Check, p.accentText) },
                            trailing = {
                                KpTextLink(
                                    stringResource(if (ui.previewing == opt.ref.id) R.string.preview_loading else R.string.preview),
                                    { onPreview(opt) },
                                )
                            },
                        )
                        KpRowSeparator(hasLeading = true)
                    }
                }
                if (ui.previewFailed) Text(stringResource(R.string.preview_failed), color = p.negative, style = KpType.caption, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }

            Section(R.string.setup_speed) {
                KpSegmented(
                    SPEED_CHOICES.map { stringResource(R.string.speed_x, it) },
                    SPEED_CHOICES.indexOfFirst { it == ui.speed },
                    { onSpeed(SPEED_CHOICES[it]) },
                    Modifier.padding(horizontal = 24.dp),
                )
            }

            Section(R.string.setup_cost) {
                CostSlip(ui.estimate)
            }

            Column(Modifier.padding(horizontal = 24.dp)) {
                Progress(ui, onStop, onUseSystem)
                Spacer(Modifier.height(12.dp))
                if (ui.narration !is NarrationState.Running) {
                    KpPrimaryKey(
                        stringResource(if (ui.narration is NarrationState.Done) R.string.bind_again else R.string.bind_narration),
                        onStart, enabled = ui.canStart,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    KpCaps(stringResource(title), Modifier.padding(horizontal = 24.dp))
    Spacer(Modifier.height(8.dp))
    content()
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun CostSlip(estimate: EstimateUi) {
    val p = Kp.palette
    Column(
        Modifier.padding(horizontal = 24.dp).fillMaxWidth().background(p.page, RoundedCornerShape(2.dp)).padding(16.dp),
    ) {
        when (estimate) {
            EstimateUi.None -> Text(stringResource(R.string.estimate_pick_voice), color = p.readText, style = KpType.caption)
            EstimateUi.Loading -> Text(stringResource(R.string.estimating), color = p.readText, style = KpType.caption)
            EstimateUi.Unavailable -> Text(stringResource(R.string.estimate_unavailable), color = p.readText, style = KpType.caption)
            is EstimateUi.Ready -> {
                val e = estimate.estimate
                SlipLine(stringResource(R.string.slip_characters), "%,d".format(e.characters))
                SlipLine(stringResource(R.string.slip_cost), costText(e))
                SlipLine(stringResource(R.string.slip_audio), durationText(e.audioSeconds))
                SlipLine(stringResource(R.string.slip_time), stringResource(R.string.about_x, durationText(e.etaSeconds)))
            }
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
private fun SlipLine(label: String, value: String) {
    val p = Kp.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.readText, style = KpType.caption, modifier = Modifier.weight(1f))
        Text(value, color = p.readText, style = KpType.numeral.copy(color = p.readText), textAlign = TextAlign.End)
    }
}

@Composable
private fun Progress(ui: SetupUi, onStop: () -> Unit, onUseSystem: () -> Unit) {
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
        is NarrationState.Paused -> Column {
            Text(stringResource(R.string.narration_paused, n.reason), color = p.text2, style = KpType.body)
            if (ui.provider == PROVIDER_FISH) {
                Spacer(Modifier.height(8.dp))
                KpSecondaryKey(stringResource(R.string.use_system_voice), onUseSystem)
            }
        }
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
