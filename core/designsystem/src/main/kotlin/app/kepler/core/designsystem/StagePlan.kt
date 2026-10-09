package app.kepler.core.designsystem

import androidx.compose.runtime.Immutable

/**
 * Pure rules of the shared book stage, kept free of Compose so they can be unit tested.
 * Coordinates are root pixels; [u] is pixels per BookScene design unit.
 */
@Immutable
data class StagePose(
    val cx: Float, val cy: Float, val u: Float, val scale: Float,
    val ry: Float, val rx: Float, val thickness: Float,
) {
    /** On-screen cover height in px. */
    val heightPx: Float get() = BOOK_H * scale * u
}

enum class StageRole {
    /** A 3D book drawn by a screen's BookScene (shelf spine, hub cover, player, now-bar). */
    Book,
    /** The reader's page: a flight here opens the book and grows its first page to fill this rect. */
    Page,
}

/** What the stage needs to know about one published slot when choosing where a book flies. */
interface SlotFacts {
    val key: Any
    val owner: String
    val role: StageRole
    val tag: String
    val pose: StagePose
}

object StagePlan {
    /** Share of the page width the closed book takes just before its board opens. */
    const val FACING_WIDTH = 0.84f
    /** The facing book never exceeds this share of the page height. */
    const val FACING_HEIGHT = 0.80f
    /** A tapped slot is preferred as the source of the next flight for this long. */
    const val PREFER_MS = 3_000L
    /** How long a route change waits for the new screen to publish its slot. */
    const val PENDING_MS = 700L
    /** The opening cover never holds the reader's first page back by more than this once it is ready. */
    const val PAGE_GRACE_MS = 150L
    /** Upper bound for the whole cover-to-page choreography; past it the snapshot fades regardless. */
    const val PAGE_CEILING_MS = 1_400L

    /**
     * The closed book squarely facing the viewer in the middle of a page rect, sized to [FACING_WIDTH]
     * of its width (or [FACING_HEIGHT] of its height on a wide page). [stageU] is the stage's px per unit.
     */
    fun facing(left: Float, top: Float, right: Float, bottom: Float, stageU: Float, thickness: Float): StagePose {
        val w = (right - left).coerceAtLeast(1f)
        val h = (bottom - top).coerceAtLeast(1f)
        val byWidth = FACING_WIDTH * w / (BOOK_W * stageU)
        val byHeight = FACING_HEIGHT * h / (BOOK_H * stageU)
        return StagePose((left + right) / 2f, (top + bottom) / 2f, stageU, minOf(byWidth, byHeight), 0f, 0f, thickness)
    }

    /**
     * Picks the slot of [key] a book leaves from among the slots the previous screen published: the one the
     * person touched (its [preferredTag], if recent), otherwise the largest on screen.
     */
    fun <S : SlotFacts> pickSource(key: Any, sources: Collection<S>, preferredTag: String?): S? {
        val mine = sources.filter { it.key == key }
        if (mine.isEmpty()) return null
        return mine.firstOrNull { preferredTag != null && it.tag == preferredTag } ?: mine.maxBy { it.pose.heightPx }
    }

    /**
     * Picks where a book of [key] lands on [owner]'s screen: a slot with the tag it left from (so a book that
     * came from the now-bar returns to it), otherwise the reader page, otherwise the largest.
     */
    fun <S : SlotFacts> pickTarget(key: Any, owner: String, slots: Collection<S>, originTag: String?): S? {
        val mine = slots.filter { it.key == key && it.owner == owner }
        if (mine.isEmpty()) return null
        return mine.firstOrNull { originTag != null && it.tag == originTag }
            ?: mine.firstOrNull { it.role == StageRole.Page }
            ?: mine.maxBy { it.pose.heightPx }
    }

    /** True when two poses differ enough that a running flight should retarget (half a pixel or a tenth of a degree). */
    fun moved(a: StagePose, b: StagePose): Boolean =
        kotlin.math.abs(a.cx - b.cx) > 0.5f || kotlin.math.abs(a.cy - b.cy) > 0.5f ||
            kotlin.math.abs(a.heightPx - b.heightPx) > 0.5f || kotlin.math.abs(a.ry - b.ry) > 0.1f ||
            kotlin.math.abs(a.rx - b.rx) > 0.1f
}
