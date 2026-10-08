package app.betterbooks.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * A palette whose neutrals come from a reading page, so the reader's chrome, sheets and transport are
 * made of the same paper as the page instead of floating app-coloured bars over it. Accent rules are
 * re-derived from the seed for the page's lightness and kept readable on it.
 */
fun paperPalette(seed: Color, page: Color, ink: Color, dark: Boolean): BbPalette {
    val base = bbPalette(seed, dark)
    val hairline = mix(page, ink, 0.14f)
    val text2 = ensureContrast(mix(ink, page, 0.40f), page, 4.5f)
    return BbPalette(
        isDark = dark,
        seed = seed,
        bg = page,
        surface1 = mix(page, ink, 0.05f),
        surface2 = mix(page, ink, 0.10f),
        hairline = hairline,
        text = ink,
        text2 = text2,
        accent = ensureContrast(base.accent, page, 3f),
        onAccent = base.onAccent,
        accentText = ensureContrast(base.accentText, page, 4.5f),
        secondary = base.secondary,
        positive = ensureContrast(base.positive, page, 3f),
        warning = ensureContrast(base.warning, page, 3f),
        negative = ensureContrast(base.negative, page, 3f),
        readAlong = base.readAlong,
        body = ink,
        page = page,
        readText = ink,
        pencil = mix(ink, page, 0.45f),
        key = base.key,
        onKey = base.onKey,
        edge = mix(page, ink, 0.22f),
        readAlongLine = base.readAlongLine,
        materials = base.materials,
    )
}
