package app.kepler.core.designsystem

import android.provider.Settings
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
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
