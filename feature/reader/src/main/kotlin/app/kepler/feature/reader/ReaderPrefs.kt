package app.kepler.feature.reader

import android.content.Context
import app.kepler.core.model.AppSettings
import app.kepler.engine.epub.reader.ReaderFont
import app.kepler.engine.epub.reader.ReaderStyle
import app.kepler.engine.epub.reader.ReaderThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reader options that AppSettings does not hold. Per-device conveniences, kept in SharedPreferences. */
data class ReaderLocalPrefs(
    val pageMargins: Double = 1.0,
    val publisherStyles: Boolean = false,
    val scroll: Boolean = false,
    val justify: Boolean = false,
    val tapZones: Boolean = true,
    val volumeKeys: Boolean = true,
    val customPage: Int? = null,
    val customInk: Int? = null,
    val lastHighlightColor: Int = 0,
    val doubleTapHintShown: Boolean = false,
)

internal class ReaderPrefsStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<ReaderLocalPrefs> = _prefs.asStateFlow()

    private fun read() = ReaderLocalPrefs(
        pageMargins = sp.getFloat("margins", 1f).toDouble(),
        publisherStyles = sp.getBoolean("publisher", false),
        scroll = sp.getBoolean("scroll", false),
        justify = sp.getBoolean("justify", false),
        tapZones = sp.getBoolean("tapZones", true),
        volumeKeys = sp.getBoolean("volumeKeys", true),
        customPage = if (sp.contains("customPage")) sp.getInt("customPage", 0) else null,
        customInk = if (sp.contains("customInk")) sp.getInt("customInk", 0) else null,
        lastHighlightColor = sp.getInt("hlColor", 0),
        doubleTapHintShown = sp.getBoolean("doubleTapHint", false),
    )

    fun update(transform: (ReaderLocalPrefs) -> ReaderLocalPrefs) {
        val p = transform(_prefs.value)
        _prefs.value = p
        sp.edit().apply {
            putFloat("margins", p.pageMargins.toFloat())
            putBoolean("publisher", p.publisherStyles)
            putBoolean("scroll", p.scroll)
            putBoolean("justify", p.justify)
            putBoolean("tapZones", p.tapZones)
            putBoolean("volumeKeys", p.volumeKeys)
            if (p.customPage != null) putInt("customPage", p.customPage) else remove("customPage")
            if (p.customInk != null) putInt("customInk", p.customInk) else remove("customInk")
            putInt("hlColor", p.lastHighlightColor)
            putBoolean("doubleTapHint", p.doubleTapHintShown)
        }.apply()
    }
}

internal fun readerStyle(app: AppSettings, local: ReaderLocalPrefs) = ReaderStyle(
    theme = ReaderThemeId.parse(app.readerTheme),
    customPage = local.customPage,
    customInk = local.customInk,
    font = ReaderFont.parse(app.fontFamily),
    fontScale = app.fontScale.toDouble(),
    lineHeight = app.lineHeight.toDouble(),
    pageMargins = local.pageMargins,
    publisherStyles = local.publisherStyles,
    scroll = local.scroll,
    justify = local.justify,
    tapZones = local.tapZones,
)

internal fun ReaderThemeId.settingsId(): String = name.lowercase()

internal fun ReaderFont.settingsId(): String = css ?: "publisher"
