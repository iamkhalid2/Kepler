package app.kepler.core.designsystem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect

/**
 * Shared-element plumbing (brief v2 section 4). The navigation host wraps everything in a SharedTransitionLayout
 * and provides its scope here, plus the AnimatedVisibilityScope of the back-stack entry being composed. Feature
 * screens never touch these directly: [CoverImage] and [BookObject] apply [kpSharedCover] when asked to.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalKpSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The entry-level animated scope. Nested AnimatedContents (the tab fade) must not override it. */
val LocalKpEntryScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

object KpSharedKeys {
    fun cover(bookId: Long): String = "cover/$bookId"
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val CoverBounds = BoundsTransform { _: Rect, _: Rect -> spring(dampingRatio = 0.9f, stiffness = 380f) }

/**
 * Marks this node as the shared cover of [bookId]. Only one node per screen may carry it for a given book;
 * outside a navigation host (previews, tests) or with reduced motion it does nothing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.kpSharedCover(bookId: Long): Modifier = composed {
    val shared = LocalKpSharedScope.current
    val entry = LocalKpEntryScope.current
    val reduced = rememberReducedMotion()
    if (shared == null || entry == null || reduced) return@composed this
    with(shared) {
        this@composed.sharedElement(
            sharedContentState = rememberSharedContentState(KpSharedKeys.cover(bookId)),
            animatedVisibilityScope = entry,
            boundsTransform = CoverBounds,
        )
    }
}
