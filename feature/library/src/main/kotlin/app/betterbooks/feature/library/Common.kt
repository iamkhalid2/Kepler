package app.betterbooks.feature.library

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.designsystem.BbCaps
import app.betterbooks.core.designsystem.BbIconKey
import app.betterbooks.core.designsystem.BbIconKind

@Composable
internal fun ScreenRoot(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.fillMaxSize().background(Bb.palette.bg).windowInsetsPadding(WindowInsets.safeDrawing),
        content = content,
    )
}

/** 56 dp top bar: back key, caps title, optional trailing keys. */
@Composable
internal fun TopBar(onBack: (() -> Unit)?, title: String?, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) BbIconKey(BbIconKind.Back, androidx.compose.ui.res.stringResource(R.string.back), onBack)
        if (title != null) BbCaps(title, Modifier.weight(1f).padding(start = if (onBack == null) 16.dp else 4.dp))
        else Box(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
internal inline fun <reified VM : ViewModel> libraryViewModel(key: String? = null, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/** Opens the battery page where background activity can be allowed. Returns false if nothing could be opened. */
fun openBatterySettings(context: Context): Boolean {
    val colorOs = listOf(
        ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"),
        ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
    )
    val attempts = colorOs.map { Intent().setComponent(it) } + listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}
