package app.kepler.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A colour in OKLCH. Hue is in degrees. Pure Kotlin, so it runs in JVM tests. */
@Immutable
data class Lch(val l: Float, val c: Float, val h: Float) {
    fun shade(k: Float) = Lch(l * k, c * k, h)
    fun toColor(alpha: Float = 1f): Color = oklch(l, c, h, alpha)
}

private fun srgbToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
private fun linearToSrgb(v: Float): Float = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

fun Color.toLch(): Lch {
    val r = srgbToLinear(red); val g = srgbToLinear(green); val b = srgbToLinear(blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    val bigL = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
    val a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
    val bb = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    val chroma = sqrt(a * a + bb * bb)
    var hue = Math.toDegrees(atan2(bb.toDouble(), a.toDouble())).toFloat()
    if (hue < 0f) hue += 360f
    return Lch(bigL, chroma, if (chroma < 1e-4f) 0f else hue)
}

/** Returns false when the colour lies outside sRGB. */
private fun lchToLinear(l: Float, c: Float, h: Float, out: FloatArray): Boolean {
    val hr = Math.toRadians(h.toDouble())
    val a = (c * cos(hr)).toFloat()
    val b = (c * sin(hr)).toFloat()
    val l_ = l + 0.3963377774f * a + 0.2158037573f * b
    val m_ = l - 0.1055613458f * a - 0.0638541728f * b
    val s_ = l - 0.0894841775f * a - 1.2914855480f * b
    val lc = l_ * l_ * l_; val mc = m_ * m_ * m_; val sc = s_ * s_ * s_
    out[0] = 4.0767416621f * lc - 3.3077115913f * mc + 0.2309699292f * sc
    out[1] = -1.2684380046f * lc + 2.6097574011f * mc - 0.3413193965f * sc
    out[2] = -0.0041960863f * lc - 0.7034186147f * mc + 1.7076147010f * sc
    val eps = 0.0005f
    return out.all { it >= -eps && it <= 1f + eps }
}

/** OKLCH to sRGB. Out-of-gamut colours keep their lightness and hue and lose chroma. */
fun oklch(l: Float, c: Float, h: Float, alpha: Float = 1f): Color {
    val ll = l.coerceIn(0f, 1f)
    val out = FloatArray(3)
    var chroma = max(0f, c)
    if (!lchToLinear(ll, chroma, h, out)) {
        var lo = 0f; var hi = chroma
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (lchToLinear(ll, mid, h, out)) lo = mid else hi = mid
        }
        chroma = lo
        lchToLinear(ll, chroma, h, out)
    }
    return Color(
        linearToSrgb(out[0].coerceIn(0f, 1f)), linearToSrgb(out[1].coerceIn(0f, 1f)), linearToSrgb(out[2].coerceIn(0f, 1f)), alpha,
    )
}

/** The one darkening operation: (L k, C k, H). */
fun shade(c: Color, k: Float): Color = c.toLch().shade(k).toColor(c.alpha)

fun Color.withLch(l: Float = Float.NaN, c: Float = Float.NaN, h: Float = Float.NaN): Color {
    val x = toLch()
    return oklch(if (l.isNaN()) x.l else l, if (c.isNaN()) x.c else c, if (h.isNaN()) x.h else h, alpha)
}

private fun relLuminance(c: Color): Float =
    0.2126f * srgbToLinear(c.red) + 0.7152f * srgbToLinear(c.green) + 0.0722f * srgbToLinear(c.blue)

/** WCAG 2 contrast ratio between two opaque colours. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = relLuminance(a); val lb = relLuminance(b)
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * Moves [fg] along OKLCH lightness (lighter when [lighten], else darker) until it reaches [min]
 * contrast against [bg]. Hue and chroma are kept, so hue doesn't drift.
 */
fun guardContrast(fg: Color, bg: Color, min: Float, lighten: Boolean): Color {
    val x = fg.toLch()
    var l = x.l
    var out = fg
    var guard = 0
    while (contrastRatio(out, bg) < min && guard++ < 120) {
        l = if (lighten) min(1f, l + 0.01f) else max(0f, l - 0.01f)
        out = oklch(l, x.c, x.h, fg.alpha)
        if (l <= 0f || l >= 1f) break
    }
    return out
}

fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}
