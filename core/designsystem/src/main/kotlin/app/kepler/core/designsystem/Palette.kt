package app.kepler.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Theme-agnostic palette. Neutrals are fixed per mode and never tinted; the accent is the one user
 * seed; materials are derived from the accent in OKLCH. Nothing in the UI hardcodes a hue.
 */
@Immutable
class KpPalette(
    val isDark: Boolean,
    val seed: Color,
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val hairline: Color,
    val text: Color,
    val text2: Color,
    /** Accent used for fills (ribbons, rules, switch tracks). Never a filled button. */
    val accent: Color,
    /** Readable text on [accent]. */
    val onAccent: Color,
    /** Accent tuned to be readable as TEXT on [bg]. */
    val accentText: Color,
    val secondary: Color,
    val positive: Color,
    val warning: Color,
    val negative: Color,
    /** Read-along sentence band (accent at low alpha). */
    val readAlong: Color,
    // ---- added with the Shelf language (docs/design/shelf-language.md section 2) ----
    val body: Color,
    val page: Color,
    val readText: Color,
    val pencil: Color,
    /** Primary key fill and its label. */
    val key: Color,
    val onKey: Color,
    /** Key and secondary-key border: hairline +8% lightness. */
    val edge: Color,
    /** Read-along current line (stronger than [readAlong]). */
    val readAlongLine: Color,
    val materials: KpMaterials,
    // ---- v2 (brief section 1): elevation by tone ----
    /** Sheets, menus, the mini player and toasts. */
    val surface3: Color = surface2,
)

/** Accent presets. Cobalt is the default. The last four are older presets kept for stored settings. */
enum class KpSeed(val label: String, val color: Color) {
    Cobalt("Cobalt", Color(0xFF4D8BFF)),
    Vermilion("Vermilion", Color(0xFFF76046)),
    Verdigris("Verdigris", Color(0xFF3FCBA8)),
    Saffron("Saffron", Color(0xFFFEBF32)),
    Ember("Ember", Color(0xFFFF7A45)),
    Moss("Moss", Color(0xFF4FD69C)),
    Orchid("Orchid", Color(0xFFB58CFF)),
    Rose("Rose", Color(0xFFFF6F91));

    companion object {
        /** The presets offered in Settings. */
        val presets: List<KpSeed> = listOf(Cobalt, Vermilion, Verdigris, Saffron)

        /** Maps a stored name back to a seed; unknown names fall back to Cobalt. */
        fun fromName(name: String?): KpSeed = entries.firstOrNull { it.name == name } ?: Cobalt
    }
}

private const val LIGHT_BG = 0xFFF4F2EE
private fun hex(v: Long) = Color(v)

fun kpPalette(seed: Color, dark: Boolean): KpPalette {
    val bg = if (dark) hex(0xFF0A0A0C) else hex(LIGHT_BG)
    val hairline = if (dark) hex(0xFF2A2A30) else hex(0xFFDEDBD4)
    val s = seed.toLch()

    // accent: the seed, guarded to 3:1 against bg (lifted in dark, lowered in light)
    val accent = guardContrast(seed, bg, 3f, lighten = dark)
    // accentText: lift toward text, then guard to 7:1 (dark) / 4.5:1 (light)
    val accentText = if (dark) {
        val base = if (contrastRatio(accent, bg) < 7f) oklch(s.l + 0.08f, s.c * 0.74f, s.h) else accent
        guardContrast(base, bg, 7f, lighten = true)
    } else {
        guardContrast(accent, bg, 4.5f, lighten = false)
    }
    val ah = accent.toLch().h
    val onAccentDark = oklch(0.15f, 0.04f, ah)
    val onAccent = if (contrastRatio(onAccentDark, accent) >= 4.5f) onAccentDark else hex(0xFFFBFAF7)
    val secondary = oklch(0.785f, 0.133f, (s.h - 80f + 360f) % 360f).let {
        if (dark) it else guardContrast(it, bg, 3f, lighten = false)
    }

    // status colours; rotate away from the accent when they would be confused with it
    fun unclash(c: Color): Color {
        val l = c.toLch()
        if (hueDistance(l.h, ah) >= 20f) return c
        val delta = ((l.h - ah + 540f) % 360f) - 180f
        return oklch(l.l, l.c, (l.h + if (delta >= 0f) 30f else -30f + 360f) % 360f)
    }
    val positive = unclash(if (dark) hex(0xFF34D399) else hex(0xFF007D57))
    val warning = unclash(if (dark) hex(0xFFFBBF24) else hex(0xFF8D6900))
    val negative = unclash(if (dark) hex(0xFFF87171) else hex(0xFFC34146))

    return KpPalette(
        isDark = dark,
        seed = seed,
        bg = bg,
        surface1 = if (dark) hex(0xFF16161A) else hex(0xFFFFFFFF),
        surface2 = if (dark) hex(0xFF1F1F24) else hex(0xFFEFEDE8),
        hairline = hairline,
        text = if (dark) hex(0xFFFFFFFF) else hex(0xFF1A1814),
        text2 = if (dark) hex(0xFF9B9BA3) else hex(0xFF66635C),
        accent = accent,
        onAccent = onAccent,
        accentText = accentText,
        secondary = secondary,
        positive = positive,
        warning = warning,
        negative = negative,
        readAlong = accent.copy(alpha = if (dark) 0.12f else 0.09f),
        body = if (dark) hex(0xFFD9D9DE) else hex(0xFF2B2924),
        page = if (dark) hex(0xFF111114) else hex(0xFFFEFCF7),
        readText = if (dark) hex(0xFFECE3D0) else hex(0xFF4C473C),
        pencil = if (dark) hex(0xFFA39B8A) else hex(0xFF787161),
        key = if (dark) hex(0xFFECE6D9) else hex(0xFF201E1A),
        onKey = if (dark) hex(0xFF0E0E10) else hex(0xFFECE6D9),
        // a key's edge must read against its surroundings: lighter than the hairline on black, darker on linen
        edge = hairline.withLch(l = hairline.toLch().l + if (dark) 0.08f else -0.10f),
        readAlongLine = accent.copy(alpha = if (dark) 0.30f else 0.22f),
        materials = kpMaterials(seed, accent, bg, dark),
        surface3 = if (dark) hex(0xFF26262C) else hex(0xFFFFFFFF),
    )
}

/** Black or white, whichever reads better on [bg]. */
fun readableOn(bg: Color): Color {
    val onDark = ColorUtils.calculateContrast(Color.White.toArgb(), bg.toArgb())
    val onLight = ColorUtils.calculateContrast(Color(0xFF05070D).toArgb(), bg.toArgb())
    return if (onDark >= onLight) Color.White else Color(0xFF05070D)
}

/** Nudge [fg] lighter (on dark) or darker (on light) until it reaches [min] contrast against [bg]. */
fun ensureContrast(fg: Color, bg: Color, min: Float): Color =
    guardContrast(fg, bg, min, lighten = contrastRatio(Color.Black, bg) < contrastRatio(Color.White, bg))

fun hueShift(c: Color, degrees: Float): Color {
    val x = c.toLch()
    return oklch(x.l, x.c, (x.h + degrees + 720f) % 360f, c.alpha)
}

/** Mix of [a] and [b]: 0 = a, 1 = b. */
fun mix(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t)

/** Sets the OKLCH lightness of [c]; [satScale] scales its chroma. */
fun withLightness(c: Color, l: Float, satScale: Float = 1f): Color {
    val x = c.toLch()
    return oklch(l, x.c * satScale, x.h, c.alpha)
}

/** Cloth + ink pairs for book covers, all derived from the seed (plus two neutrals). */
@Immutable
class BookMaterial(val cloth: Color, val ink: Color)

/** Six cover materials. A book picks one by a stable hash of its id. */
fun bookMaterials(p: KpPalette): List<BookMaterial> {
    val s = p.seed.toLch()
    val bone = oklch(0.902f, 0.02f, 86f)
    val graphite = oklch(0.255f, 0.01f, 270f)
    fun m(cloth: Color) = BookMaterial(cloth, inkFor(cloth))
    return listOf(
        m(clothFor(p.seed, p).face),
        m(bone),
        m(graphite),
        m(oklch(0.62f, s.c, s.h)),
        m(oklch(0.20f, s.c * 0.3f, (s.h - 18f + 360f) % 360f)),
        m(mix(bone, p.seed, 0.12f)),
    )
}

/** Per-book cloth: the same rule used for the accent cloth, usable on any base colour. */
fun clothFor(base: Color, p: KpPalette): Cloth = clothFor(base, p.isDark)

/** The cloth a book wears: a stable index from its id, shared by every screen that draws the book. */
fun bookMaterialIndex(id: Long, count: Int): Int {
    var h = id * -0x61c8864680b583ebL
    h = h xor (h ushr 32)
    return ((h and 0x7fffffffL) % count).toInt()
}
