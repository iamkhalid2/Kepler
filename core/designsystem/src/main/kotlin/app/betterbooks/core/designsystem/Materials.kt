package app.betterbooks.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.max

/** Bookcloth for one book, derived from a base colour by shading in OKLCH. */
@Immutable
class Cloth(
    val face: Color, val head: Color, val board: Color, val groove: Color,
    val ink: Color, val inkMuted: Color, val bounce: Color,
)

/** Three-stop silk: sheen sits at .45 of the ribbon's length. */
@Immutable
class Silk(val edgeA: Color, val sheen: Color, val edgeB: Color)

/** Bone paper tones. Never tinted by the accent. */
@Immutable
class Paper(
    val slip: Color, val lit: Color, val head: Color, val fore: Color,
    val thumbed: Color, val shaded: Color, val line: Color, val grainAlpha: Float,
)

/** The environment a book stands in. Only this changes with the mode; object lighting is the same. */
@Immutable
class Env(
    val plankTopFrom: Color, val plankTopTo: Color, val plankFront: Color,
    val lipBase: Color, val lipA: Float, val lipB: Float, val lipC: Float,
    /** Hairline seam under the lip (light mode only); transparent in dark. */
    val seam: Color,
    val contactAlpha: Float, val bounceAlpha: Float,
)

@Immutable
class Marks(val line: Color, val sentence: Color, val selection: Color, val pencil: Color)

@Immutable
class BbMaterials(
    val cloth: Cloth, val silk: Silk, val boneRibbon: Silk,
    val paper: Paper, val env: Env, val marks: Marks,
)

private const val BONE_H = 86f
private const val BONE_C = 0.02f
private fun bone(l: Float) = oklch(l, BONE_C, BONE_H)

/** Ink on [face]: near-white if it reads (4.5:1), else near-black. */
fun inkFor(face: Color): Color {
    val h = face.toLch().h
    val light = oklch(0.973f, 0.008f, h)
    return if (contrastRatio(light, face) >= 4.5f) light else oklch(0.20f, 0.01f, h)
}

fun clothFor(base: Color, dark: Boolean): Cloth {
    val x = base.toLch()
    // greys floor at C .03 and L .26 so a grey cover seed still reads as cloth
    val face = oklch((x.l - 0.30f).coerceIn(0.26f, 0.55f), max(x.c * 0.71f, 0.03f), (x.h + 3.5f) % 360f)
    val f = face.toLch()
    val ink = inkFor(face)
    var muted = oklch(0.85f, 0.054f, f.h)
    var t = 0f
    while (contrastRatio(muted, face) < 4.5f && t < 1f) {
        t += 0.1f
        muted = mix(oklch(0.85f, 0.054f, f.h), ink, t)
    }
    return Cloth(
        face = face,
        head = shade(face, 0.924f),
        board = shade(face, 0.77f),
        groove = shade(face, 0.627f),
        ink = ink,
        inkMuted = muted,
        bounce = oklch(f.l + 0.106f, f.c * 1.24f, f.h, alpha = if (dark) 0.32f else 0.16f),
    )
}

private fun silkFor(seed: Color, bg: Color, dark: Boolean): Silk {
    val x = seed.toLch()
    val r = x.l.coerceIn(0.55f, 0.80f)
    val h = (x.h + 2f) % 360f
    var dl = 0f
    fun build(d: Float) = Silk(
        oklch(r - 0.12f + d, x.c * 1.02f, h),
        oklch(r + 0.11f + d, x.c * 0.66f, h),
        oklch(r - 0.083f + d, x.c, h),
    )
    var s = build(dl)
    var guard = 0
    while (contrastRatio(s.edgeB, bg) < 3f && guard++ < 40) {
        dl += if (dark) 0.01f else -0.01f
        s = build(dl)
    }
    return s
}

fun bbMaterials(seed: Color, accent: Color, bg: Color, dark: Boolean): BbMaterials {
    val boneRibbon = if (dark) Silk(Color(0xFFB9B19F), Color(0xFFF4EEE2), Color(0xFFC6BEAC))
    else Silk(Color(0xFF5F594C), Color(0xFFA69E8C), Color(0xFF7D7667))
    val paper = Paper(
        slip = bone(0.951f), lit = bone(0.926f), head = bone(0.902f), fore = bone(0.76f),
        thumbed = bone(0.70f), shaded = bone(0.568f), line = bone(0.466f), grainAlpha = 0.5f,
    )
    val env = if (dark) Env(
        plankTopFrom = Color(0xFF000000), plankTopTo = Color(0xFF0C0C0E), plankFront = Color(0xFF070708),
        lipBase = Color.White, lipA = 0.22f, lipB = 0.12f, lipC = 0.02f,
        seam = Color.Transparent, contactAlpha = 1f, bounceAlpha = 0.32f,
    ) else Env(
        plankTopFrom = Color(0xFFF4F2EE), plankTopTo = Color(0xFFEAE8E2), plankFront = Color(0xFFE0DED8),
        lipBase = Color.White, lipA = 0.9f, lipB = 0.55f, lipC = 0.15f,
        seam = Color.Black.copy(alpha = 0.12f), contactAlpha = 0.30f, bounceAlpha = 0.16f,
    )
    val marks = Marks(
        line = accent.copy(alpha = if (dark) 0.30f else 0.22f),
        sentence = accent.copy(alpha = if (dark) 0.12f else 0.09f),
        selection = accent.copy(alpha = if (dark) 0.35f else 0.25f),
        pencil = if (dark) Color(0xFFA39B8A) else Color(0xFF787161),
    )
    return BbMaterials(
        cloth = clothFor(seed, dark), silk = silkFor(seed, bg, dark), boneRibbon = boneRibbon,
        paper = paper, env = env, marks = marks,
    )
}
