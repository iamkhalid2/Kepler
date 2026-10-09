package app.kepler.core.designsystem

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Named springs (damping ratio / stiffness) from the design brief section 4. */
object KpMotion {
    fun <T> snap(): SpringSpec<T> = spring(1.0f, 1600f)
    fun <T> settle(): SpringSpec<T> = spring(0.90f, 400f)
    fun <T> move(): SpringSpec<T> = spring(0.82f, 320f)
    fun <T> turn(): SpringSpec<T> = spring(0.72f, 260f)
    fun <T> lift(): SpringSpec<T> = spring(1.0f, 800f)
    fun <T> open(): SpringSpec<T> = spring(0.85f, 220f)
    fun <T> reveal(): SpringSpec<T> = spring(0.90f, 380f)
    fun <T> flip(): SpringSpec<T> = spring(0.86f, 380f)
    const val STAGGER_MS = 60L

    // ---- v2 (brief section 4) ----
    const val FADE_OUT_MS = 60
    const val FADE_IN_MS = 120
    const val PUSH_IN_MS = 220
    const val PUSH_OUT_MS = 120
    const val REDUCED_MS = 120
    const val FACE_SWITCH_MS = 180

    /** Tab switch: 60 ms out, then 120 ms in. No slide, no scale. */
    fun fadeThrough(): ContentTransform =
        fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS, easing = FastOutSlowInEasing)) togetherWith
            fadeOut(tween(FADE_OUT_MS, easing = LinearEasing))

    /** Plain crossfade used everywhere under reduced motion. */
    fun crossfade(ms: Int = REDUCED_MS): ContentTransform = fadeIn(tween(ms)) togetherWith fadeOut(tween(ms))
}

/** App-level reduced-motion switch. The system animator scale of 0 also counts (see [rememberReducedMotion]). */
val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    val app = LocalReducedMotion.current
    val system = remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    return app || system
}
