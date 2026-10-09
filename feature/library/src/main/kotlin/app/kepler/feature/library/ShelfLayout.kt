package app.kepler.feature.library

import app.kepler.core.designsystem.BOOK_H
import app.kepler.core.designsystem.BOOK_W
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** What the shelf needs to know about a book to place it. */
data class ShelfBookFacts(val id: Long, val thicknessUnits: Float)

/** A book placed on a plank. [x] is its left edge in dp from the row's gutter; [width] is its footprint. */
data class PlacedBook(
    val index: Int,
    val x: Float,
    val width: Float,
    val height: Float,
    val faceOut: Boolean,
    /** Degrees the book tips to the left, resting on its neighbour; 0 stands upright. */
    val lean: Float = 0f,
)

/** The dashed outline that stands where the next book would go; tapping it adds a book. */
data class GhostSpine(val x: Float, val width: Float, val height: Float)

data class ShelfRowPlan(val books: List<PlacedBook>, val ghost: GhostSpine? = null)

/**
 * The bookcase composition (design lead, round 2). Spines stand at natural heights (a stable 88 to 100% of
 * 150 dp per book); the first book in sort order stands face-out on the top plank like a shop display; the
 * last spine leans on its neighbour when there is room; a ghost spine marks where the next book goes.
 * The whole viewport is planks, so one book still reads as a bookcase rather than an empty screen.
 */
object ShelfLayout {
    const val ROW = 196f
    const val SPINE_H = 150f
    /** Face-out books are 2:3 covers this tall. */
    const val FACE_H = 150f
    const val FACE_YAW = 20f
    const val GAP = 2f
    const val FACE_GAP = 10f
    const val GHOST_GAP = 12f
    const val LEAN = 9f
    private val HEIGHTS = floatArrayOf(1f, 0.94f, 0.88f, 0.97f, 0.91f)

    /** A stable height share for a book: the same book always stands at the same height. */
    fun heightFactor(id: Long): Float {
        var h = id * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return HEIGHTS[((h and 0x7fffffffL) % HEIGHTS.size).toInt()]
    }

    fun spineHeight(id: Long): Float = SPINE_H * heightFactor(id)

    /** Spine footprint in dp: thickness scales with the book's height so proportions hold. */
    fun spineWidth(b: ShelfBookFacts): Float = b.thicknessUnits * spineHeight(b.id) / BOOK_H

    /** Projected width of a face-out book turned [FACE_YAW] degrees. */
    fun faceWidth(b: ShelfBookFacts): Float {
        val a = Math.toRadians(FACE_YAW.toDouble())
        val k = FACE_H / BOOK_H
        return ((cos(a) * BOOK_W + sin(a) * b.thicknessUnits) * k).toFloat()
    }

    /** Gap a spine of height [h] leaves at its foot when it leans [LEAN] degrees on a neighbour of height [hn]. */
    fun leanGap(h: Float, hn: Float): Float {
        val a = Math.toRadians(LEAN.toDouble())
        return min(hn * tan(a), h * sin(a)).toFloat()
    }

    /** Packs books into rows [rowWidth] dp wide and places the ghost spine after the last book. */
    fun plan(books: List<ShelfBookFacts>, rowWidth: Float, ghostWidth: Float = BOOK_W * 0.20f * SPINE_H / BOOK_H): List<ShelfRowPlan> {
        val rows = mutableListOf<MutableList<PlacedBook>>()
        var row = mutableListOf<PlacedBook>()
        var x = 0f
        books.forEachIndexed { i, b ->
            val faceOut = i == 0
            val w = if (faceOut) faceWidth(b) else spineWidth(b)
            val h = if (faceOut) FACE_H else spineHeight(b.id)
            if (row.isNotEmpty() && x + w > rowWidth) {
                rows += row
                row = mutableListOf()
                x = 0f
            }
            row += PlacedBook(i, x, w, h, faceOut)
            x += w + if (faceOut) FACE_GAP else GAP
        }
        if (row.isNotEmpty()) rows += row

        // One lean in the whole case: the last spine, resting on the spine before it, if the plank has room.
        val last = rows.lastOrNull()
        if (last != null && last.size >= 2) {
            val b = last.last()
            val n = last[last.size - 2]
            if (!b.faceOut && !n.faceOut) {
                val a = Math.toRadians(LEAN.toDouble())
                val gap = leanGap(b.height, n.height)
                val footprint = (b.width * cos(a)).toFloat()
                val left = n.x + n.width + gap
                // Lean whenever it fits, unless leaning is what pushes the ghost off this plank.
                val ghostFitsUpright = b.x + b.width + GHOST_GAP + ghostWidth <= rowWidth
                val ghostFitsLeaning = left + footprint + GHOST_GAP + ghostWidth <= rowWidth
                if (left + footprint <= rowWidth && (ghostFitsLeaning || !ghostFitsUpright)) {
                    last[last.size - 1] = b.copy(x = left, width = footprint, lean = LEAN)
                }
            }
        }

        val plans = rows.map { ShelfRowPlan(it) }.toMutableList()
        val ghostH = SPINE_H * 0.94f
        val end = rows.lastOrNull()?.lastOrNull()?.let { it.x + it.width } ?: -GHOST_GAP
        if (plans.isNotEmpty() && end + GHOST_GAP + ghostWidth <= rowWidth) {
            plans[plans.lastIndex] = plans.last().copy(ghost = GhostSpine(end + GHOST_GAP, ghostWidth, ghostH))
        } else {
            plans += ShelfRowPlan(emptyList(), GhostSpine(0f, ghostWidth, ghostH))
        }
        return plans
    }

    /** Planks needed to fill a viewport [viewport] dp tall, never fewer than the rows that hold books. */
    fun plankCount(rows: Int, viewport: Float): Int = max(rows, ceil(viewport / ROW).toInt())
}
