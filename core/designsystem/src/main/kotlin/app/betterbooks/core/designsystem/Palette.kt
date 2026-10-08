package app.betterbooks.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Theme-agnostic palette. Every colour in the app is derived from ONE seed (the accent) plus neutral
 * ramps, so any seed works in dark and light. Nothing in the UI hardcodes a hue.
 */
@Immutable
class BbPalette(
    val isDark: Boolean,
    val seed: Color,
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val hairline: Color,
    val text: Color,
    val text2: Color,
    /** Accent used for fills (buttons, ribbons). */
    val accent: Color,
    /** Readable text on [accent]. */
    val onAccent: Color,
    /** Accent tuned to be readable as TEXT on [bg] (contrast >= 4.5). */
    val accentText: Color,
    val secondary: Color,
    val positive: Color,
    val warning: Color,
    val negative: Color,
    /** Read-along sentence highlight. */
    val readAlong: Color,
)

/** Accent presets. Cobalt is the default; anything else is a one-line change. */
enum class BbSeed(val label: String, val color: Color) {
    Cobalt("Cobalt", Color(0xFF4D8BFF)),
    Ember("Ember", Color(0xFFFF7A45)),
    Moss("Moss", Color(0xFF4FD69C)),
    Orchid("Orchid", Color(0xFFB58CFF)),
    Rose("Rose", Color(0xFFFF6F91)),
}

fun bbPalette(seed: Color, dark: Boolean): BbPalette {
    val bg = if (dark) Color(0xFF000000) else Color(0xFFF6F2EA)
    val text = if (dark) Color(0xFFFFFFFF) else Color(0xFF14130F)
    val accent = seed
    return BbPalette(
        isDark = dark,
        seed = seed,
        bg = bg,
        surface1 = if (dark) Color(0xFF101012) else Color(0xFFFFFFFF),
        surface2 = if (dark) Color(0xFF1A1A1E) else Color(0xFFEFE9DE),
        hairline = if (dark) Color(0xFF2A2A30) else Color(0xFFE2DBCD),
        text = text,
        text2 = if (dark) Color(0xFF9B9BA3) else Color(0xFF5E584F),
        accent = accent,
        onAccent = readableOn(accent),
        accentText = ensureContrast(accent, bg, 4.5f),
        secondary = hueShift(seed, -45f),
        positive = if (dark) Color(0xFF34D399) else Color(0xFF1F9D6A),
        warning = if (dark) Color(0xFFFBBF24) else Color(0xFFB7791F),
        negative = if (dark) Color(0xFFF87171) else Color(0xFFD64545),
        readAlong = accent.copy(alpha = if (dark) 0.30f else 0.18f),
    )
}

/** Black or white, whichever reads better on [bg]. */
fun readableOn(bg: Color): Color {
    val onDark = ColorUtils.calculateContrast(Color.White.toArgb(), bg.toArgb())
    val onLight = ColorUtils.calculateContrast(Color(0xFF05070D).toArgb(), bg.toArgb())
    return if (onDark >= onLight) Color.White else Color(0xFF05070D)
}

/** Nudge [fg] lighter (on dark) or darker (on light) until it reaches [min] contrast against [bg]. */
fun ensureContrast(fg: Color, bg: Color, min: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(fg.toArgb(), hsl)
    val bgLum = ColorUtils.calculateLuminance(bg.toArgb())
    val step = if (bgLum < 0.5) 0.02f else -0.02f
    var c = fg
    var guard = 0
    while (ColorUtils.calculateContrast(c.toArgb(), bg.toArgb()) < min && guard++ < 60) {
        hsl[2] = (hsl[2] + step).coerceIn(0f, 1f)
        c = Color(ColorUtils.HSLToColor(hsl))
    }
    return c
}

fun hueShift(c: Color, degrees: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(c.toArgb(), hsl)
    hsl[0] = (hsl[0] + degrees + 360f) % 360f
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Mix of [a] and [b]: 0 = a, 1 = b. */
fun mix(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t)

fun withLightness(c: Color, l: Float, satScale: Float = 1f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(c.toArgb(), hsl)
    hsl[1] = (hsl[1] * satScale).coerceIn(0f, 1f)
    hsl[2] = l.coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Cloth + ink pairs for book covers, all derived from the seed (plus two neutrals). */
@Immutable
class BookMaterial(val cloth: Color, val ink: Color)

fun bookMaterials(p: BbPalette): List<BookMaterial> {
    val s = p.seed
    val bone = Color(0xFFE6DECD)
    val graphite = Color(0xFF1E1F24)
    fun m(cloth: Color) = BookMaterial(cloth, readableOn(cloth))
    return listOf(
        m(withLightness(s, 0.30, 0.95f)),            // deep bookcloth in the accent
        m(bone),                                     // bone
        m(graphite),                                 // graphite
        m(withLightness(s, 0.62, 1f)),               // bright accent
        m(withLightness(hueShift(s, -18f), 0.17f, 0.9f)), // near-black, slightly cooler
        m(mix(bone, s, 0.12f)),                      // warm paper tinted by the accent
    )
}

private fun withLightness(c: Color, l: Double, satScale: Float) = withLightness(c, l.toFloat(), satScale)
