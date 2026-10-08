package app.kepler.core.designsystem

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Haptic vocabulary from the design brief section 5. */
enum class HapticToken { Tick, Tap, Confirm, Settle, Success, Threshold, Reject, Heartbeat }

/** App setting for haptics (AppSettings.haptics). The system touch-feedback switch is always respected. */
val LocalHapticsEnabled = compositionLocalOf { true }

/**
 * Plays [HapticToken]s through `View.performHapticFeedback`, which already follows the system
 * touch-feedback setting. Composition primitives are not used yet; every token has a constants fallback.
 */
class Haptics(private val view: View, private val enabled: Boolean) {
    fun play(token: HapticToken) {
        if (!enabled) return
        when (token) {
            HapticToken.Tick -> fire(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
            )
            HapticToken.Tap -> fire(HapticFeedbackConstants.KEYBOARD_TAP)
            HapticToken.Confirm -> fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            HapticToken.Settle -> fire(HapticFeedbackConstants.CONTEXT_CLICK)
            HapticToken.Success -> {
                fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            }
            HapticToken.Threshold -> fire(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK,
            )
            HapticToken.Reject -> fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
            HapticToken.Heartbeat -> {
                fire(HapticFeedbackConstants.KEYBOARD_TAP)
                Handler(Looper.getMainLooper()).postDelayed({ fire(HapticFeedbackConstants.KEYBOARD_TAP) }, 120)
            }
        }
    }

    private fun fire(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}
