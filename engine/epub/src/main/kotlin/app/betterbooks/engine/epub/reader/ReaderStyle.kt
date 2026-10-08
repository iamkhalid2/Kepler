package app.betterbooks.engine.epub.reader

/** Page themes (docs/plan/experience.md 3.1). Colours are ARGB page/ink pairs for the book content. */
enum class ReaderThemeId(val page: Int, val ink: Int, val dark: Boolean) {
    DAY(0xFFFFFFFF.toInt(), 0xFF1A1A1A.toInt(), false),
    PAPER(0xFFF7F3EA.toInt(), 0xFF2A2622.toInt(), false),
    SEPIA(0xFFF4ECD8.toInt(), 0xFF3B2F22.toInt(), false),
    NIGHT(0xFF1B1B1F.toInt(), 0xFFD6D3CC.toInt(), true),
    BLACK(0xFF000000.toInt(), 0xFFC9C9C9.toInt(), true),
    CUSTOM(0xFF1B1B1F.toInt(), 0xFFD6D3CC.toInt(), true),
    ;

    companion object {
        /** Accepts the ids stored in AppSettings.readerTheme ("night", "amoled", ...). */
        fun parse(id: String?): ReaderThemeId = when (id?.lowercase()) {
            "day", "light" -> DAY
            "paper" -> PAPER
            "sepia" -> SEPIA
            "black", "amoled" -> BLACK
            "custom" -> CUSTOM
            else -> NIGHT
        }
    }
}

/** Reading fonts. `css` null means the publisher's own font. */
enum class ReaderFont(val css: String?) {
    LITERATA("Literata"),
    SERIF("serif"),
    SANS("sans-serif"),
    DYSLEXIC("OpenDyslexic"),
    PUBLISHER(null),
    ;

    companion object {
        fun parse(name: String?): ReaderFont =
            entries.firstOrNull { it.name.equals(name, true) || it.css.equals(name, true) } ?: LITERATA
    }
}

/** Everything the page rendering depends on, independent of Readium types. */
data class ReaderStyle(
    val theme: ReaderThemeId = ReaderThemeId.NIGHT,
    /** Custom-colour hook: used when [theme] is CUSTOM. */
    val customPage: Int? = null,
    val customInk: Int? = null,
    val font: ReaderFont = ReaderFont.LITERATA,
    val fontScale: Double = 1.0,
    val lineHeight: Double = 1.6,
    val pageMargins: Double = 1.0,
    val publisherStyles: Boolean = false,
    val scroll: Boolean = false,
    val justify: Boolean = false,
    val tapZones: Boolean = true,
) {
    val pageColor: Int get() = if (theme == ReaderThemeId.CUSTOM) customPage ?: theme.page else theme.page
    val inkColor: Int get() = if (theme == ReaderThemeId.CUSTOM) customInk ?: theme.ink else theme.ink

    /** Whether the page is dark; for CUSTOM this follows the luminance of the page colour. */
    val isDark: Boolean
        get() {
            if (theme != ReaderThemeId.CUSTOM) return theme.dark
            val c = pageColor
            val l = 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
            return l < 128
        }
}
