package app.kepler.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.model.Annotation
import app.kepler.core.model.Bookmark
import app.kepler.core.model.Section
import app.kepler.engine.epub.reader.ReaderFont
import app.kepler.engine.epub.reader.ReaderStyle
import app.kepler.engine.epub.reader.ReaderThemeId
import app.kepler.engine.epub.reader.SearchHit
import kotlin.math.roundToInt

/** The six highlight colours, all palette-derived; index = Annotation.color. */
@Composable
internal fun highlightTints(): List<Color> {
    val p = Kp.palette
    return listOf(p.accent, p.secondary, p.positive, p.warning, p.negative, p.text2)
}

@Composable
private fun Caps(text: String, modifier: Modifier = Modifier) {
    BasicText(text.uppercase(), modifier, style = ReaderType.caps(Kp.palette.text2))
}

// ---------------------------------------------------------------- contents, highlights, bookmarks

internal enum class ContentsTab { Contents, Highlights, Bookmarks }

/** A search pill that opens the search sheet: the same h44 pill as the Library's search. */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    val p = Kp.palette
    val label = stringResource(R.string.reader_search)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(p.surface1)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KpIconView(KpIcons.Search, p.text2, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        BasicText(label, style = ReaderType.body(p.text2))
    }
}

@Composable
internal fun ContentsSheetBody(
    index: BookIndex,
    sections: List<Section>,
    currentOrdinal: Int?,
    annotations: List<Annotation>,
    bookmarks: List<Bookmark>,
    onJump: (ordinal: Int, href: String?) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onSearch: (() -> Unit)? = null,
) {
    val p = Kp.palette
    var tab by rememberSaveable { mutableStateOf(ContentsTab.Contents) }
    if (onSearch != null) {
        SearchEntry(onSearch)
        Spacer(Modifier.height(12.dp))
    }
    Segmented(
        options = listOf(
            stringResource(R.string.reader_tab_contents),
            stringResource(R.string.reader_tab_highlights),
            stringResource(R.string.reader_tab_bookmarks),
        ),
        selected = tab.ordinal,
        onSelect = { tab = ContentsTab.entries[it] },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    val tints = highlightTints()
    when (tab) {
        ContentsTab.Contents -> {
            val shown = sections.filter { it.included }.sortedBy { it.index }
            val minLevel = shown.minOfOrNull { it.level } ?: 0
            val current = currentOrdinal?.let(index::sectionAt)
            LazyColumn(Modifier.fillMaxWidth()) {
                items(shown, key = { it.id }) { s ->
                    ReaderRow(onClick = { onJump(s.startOrdinal, s.href) }, indent = (16 * (s.level - minLevel)).dp) {
                        BasicText(
                            s.title, Modifier.weight(1f),
                            style = ReaderType.rowTitle(if (s.id == current?.id) p.text else p.text2),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(12.dp))
                        BasicText(
                            stringResource(R.string.reader_percent, (index.fraction(s.startOrdinal) * 100).roundToInt()),
                            style = ReaderType.numeral(p.text2),
                        )
                    }
                }
            }
        }
        ContentsTab.Highlights -> if (annotations.isEmpty()) {
            BasicText(stringResource(R.string.reader_empty_highlights), style = ReaderType.body(p.text2))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(annotations.sortedBy { it.startOrdinal }, key = { it.id }) { a ->
                    ReaderRow(onClick = { onJump(a.startOrdinal, null) }, minHeight = 72.dp) {
                        Box(
                            Modifier.width(2.dp).heightIn(min = 40.dp).fillMaxHeight()
                                .background(tints[a.color.coerceIn(0, tints.lastIndex)]),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(a.quote, style = ReaderType.prose(p.text), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val where = index.chapterAt(a.startOrdinal)?.title
                            if (where != null) BasicText(where, style = ReaderType.caption(p.text2), maxLines = 1)
                        }
                    }
                }
            }
        }
        ContentsTab.Bookmarks -> if (bookmarks.isEmpty()) {
            BasicText(stringResource(R.string.reader_empty_bookmarks), style = ReaderType.body(p.text2))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(bookmarks.sortedBy { it.ordinal }, key = { it.id }) { b ->
                    ReaderRow(onClick = { onJump(b.ordinal, null) }, minHeight = 72.dp) {
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                b.label ?: index.chapterAt(b.ordinal)?.title.orEmpty(),
                                style = ReaderType.rowTitle(p.text), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                stringResource(R.string.reader_percent, (index.fraction(b.ordinal) * 100).roundToInt()),
                                style = ReaderType.numeral(p.text2),
                            )
                        }
                        TextLink(stringResource(R.string.reader_remove), onClick = { onDeleteBookmark(b.id) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- search

@Composable
internal fun SearchSheetBody(state: SearchState, onSearch: (String) -> Unit, onOpen: (SearchHit) -> Unit) {
    val p = Kp.palette
    var query by rememberSaveable { mutableStateOf(state.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ReaderInput(
        value = query,
        onValueChange = { query = it },
        hint = stringResource(R.string.reader_search_hint),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        imeAction = ImeAction.Search,
        onIme = { if (query.isNotBlank()) onSearch(query.trim()) },
    )
    Spacer(Modifier.height(16.dp))
    when {
        state.running -> BasicText(stringResource(R.string.reader_searching), style = ReaderType.body(p.text2))
        state.query.isNotEmpty() && state.hits.isEmpty() ->
            BasicText(stringResource(R.string.reader_search_none), style = ReaderType.body(p.text2))
        state.hits.isNotEmpty() -> {
            Caps(stringResource(R.string.reader_search_count, state.hits.size))
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(state.hits) { hit ->
                    ReaderRow(onClick = { onOpen(hit) }, minHeight = 72.dp) {
                        Column(Modifier.weight(1f)) {
                            val snippet = buildAnnotatedString {
                                withStyle(SpanStyle(color = p.text2)) { append(hit.anchor.before.orEmpty().takeLast(60).trimStart()) }
                                withStyle(SpanStyle(color = p.text, fontWeight = FontWeight.W600)) {
                                    append(hit.anchor.highlight.orEmpty())
                                }
                                withStyle(SpanStyle(color = p.text2)) { append(hit.anchor.after.orEmpty().take(80).trimEnd()) }
                            }
                            BasicText(snippet, style = ReaderType.body(p.text2), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            hit.title?.let { BasicText(it, style = ReaderType.caption(p.text2), maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

/** Input from the brief: 52 high, r4, `surface1`, hairline; focus turns the border accent. */
@Composable
internal fun ReaderInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
) {
    val p = Kp.palette
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(p.surface1)
            .border(1.dp, if (focused) p.accent else p.hairline, shape)
            .onFocusChanged { focused = it.isFocused },
        textStyle = ReaderType.body(p.text),
        singleLine = singleLine,
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText(hint, style = ReaderType.body(p.text2))
                inner()
            }
        },
    )
}

// ---------------------------------------------------------------- text and theme

@Composable
internal fun TypographySheetBody(
    style: ReaderStyle,
    local: ReaderLocalPrefs,
    onTheme: (ReaderThemeId) -> Unit,
    onFont: (ReaderFont) -> Unit,
    onFontScale: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onLocal: ((ReaderLocalPrefs) -> ReaderLocalPrefs) -> Unit,
) {
    val p = Kp.palette
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Caps(stringResource(R.string.reader_theme))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val labels = mapOf(
                ReaderThemeId.DAY to R.string.reader_theme_day,
                ReaderThemeId.PAPER to R.string.reader_theme_paper,
                ReaderThemeId.SEPIA to R.string.reader_theme_sepia,
                ReaderThemeId.NIGHT to R.string.reader_theme_night,
                ReaderThemeId.BLACK to R.string.reader_theme_black,
                ReaderThemeId.CUSTOM to R.string.reader_theme_custom,
            )
            ReaderThemeId.entries.forEach { t ->
                val sample = style.copy(theme = t)
                val selected = style.theme == t
                val label = stringResource(labels.getValue(t))
                Column(
                    Modifier
                        .clickable(role = Role.RadioButton) { onTheme(t) }
                        .semantics { contentDescription = label }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(40.dp, 48.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(sample.pageColor))
                            .border(1.dp, if (selected) p.text else p.hairline, RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("Aa", style = ReaderType.title(Color(sample.inkColor), 15f))
                    }
                    Spacer(Modifier.height(4.dp))
                    BasicText(label, style = ReaderType.caption(if (selected) p.text else p.text2))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Caps(stringResource(R.string.reader_font))
        Spacer(Modifier.height(8.dp))
        val fonts = listOf(ReaderFont.LITERATA, ReaderFont.SERIF, ReaderFont.SANS, ReaderFont.PUBLISHER)
        Segmented(
            options = listOf(
                stringResource(R.string.reader_font_literata),
                stringResource(R.string.reader_font_serif),
                stringResource(R.string.reader_font_sans),
                stringResource(R.string.reader_font_publisher),
            ),
            selected = fonts.indexOf(style.font).coerceAtLeast(0),
            onSelect = { onFont(fonts[it]) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Stepper(
            label = stringResource(R.string.reader_size),
            value = stringResource(R.string.reader_percent, (style.fontScale * 100).roundToInt()),
            onMinus = { onFontScale((style.fontScale - 0.1).toFloat().coerceAtLeast(0.8f)) },
            onPlus = { onFontScale((style.fontScale + 0.1).toFloat().coerceAtMost(1.4f)) },
        )
        Stepper(
            label = stringResource(R.string.reader_line_height),
            value = "%.1f".format(style.lineHeight),
            onMinus = { onLineHeight((style.lineHeight - 0.1).toFloat().coerceAtLeast(1.2f)) },
            onPlus = { onLineHeight((style.lineHeight + 0.1).toFloat().coerceAtMost(2.2f)) },
        )
        Stepper(
            label = stringResource(R.string.reader_margins),
            value = "%.1f".format(local.pageMargins),
            onMinus = { onLocal { it.copy(pageMargins = (it.pageMargins - 0.25).coerceAtLeast(0.5)) } },
            onPlus = { onLocal { it.copy(pageMargins = (it.pageMargins + 0.25).coerceAtMost(2.5)) } },
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.reader_publisher_styles), local.publisherStyles) { v -> onLocal { it.copy(publisherStyles = v) } }
        SwitchRow(stringResource(R.string.reader_scroll), local.scroll) { v -> onLocal { it.copy(scroll = v) } }
        SwitchRow(stringResource(R.string.reader_justify), local.justify) { v -> onLocal { it.copy(justify = v) } }
        SwitchRow(stringResource(R.string.reader_tap_zones), local.tapZones) { v -> onLocal { it.copy(tapZones = v) } }
        SwitchRow(stringResource(R.string.reader_volume_keys), local.volumeKeys) { v -> onLocal { it.copy(volumeKeys = v) } }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val p = Kp.palette
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f), style = ReaderType.body(p.text))
        GlyphKey(stringResource(R.string.reader_decrease), onMinus, Glyph.Minus, p.text)
        BasicText(value, Modifier.width(56.dp), style = ReaderType.numeral(p.text, 15f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        GlyphKey(stringResource(R.string.reader_increase), onPlus, Glyph.Plus, p.text)
    }
}

/** Switch from the brief: 44×24 r4 `surface2` track, 20×20 r3 `key` thumb, accent track when on. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = Kp.palette
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = ReaderType.body(p.text))
        Box(
            Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(4.dp)).background(if (checked) p.accent else p.surface2),
        ) {
            Box(
                Modifier
                    .offset(x = if (checked) 22.dp else 2.dp, y = 2.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(p.key),
            )
        }
    }
}

// ---------------------------------------------------------------- one highlight

@Composable
internal fun AnnotationSheetBody(
    annotation: Annotation,
    onChange: (Annotation) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val p = Kp.palette
    val tints = highlightTints()
    var note by remember(annotation.id) { mutableStateOf(annotation.note.orEmpty()) }
    var color by remember(annotation.id) { mutableStateOf(annotation.color) }
    Caps(stringResource(R.string.reader_highlight))
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(2.dp).heightIn(min = 24.dp).fillMaxHeight().background(tints[color.coerceIn(0, tints.lastIndex)]))
        Spacer(Modifier.width(12.dp))
        BasicText(
            annotation.quote, Modifier.weight(1f),
            style = ReaderType.prose(p.text).copy(fontStyle = FontStyle.Italic), maxLines = 6, overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        tints.forEachIndexed { i, tint ->
            val label = stringResource(R.string.reader_colour, i + 1)
            Box(
                Modifier
                    .size(48.dp)
                    .semantics { contentDescription = label }
                    .clickable(role = Role.RadioButton) { color = i },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint)
                        .border(1.dp, if (i == color) p.text else Color.Transparent, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Caps(stringResource(R.string.reader_note))
    Spacer(Modifier.height(8.dp))
    ReaderInput(
        value = note, onValueChange = { note = it }, hint = stringResource(R.string.reader_note_hint),
        modifier = Modifier.fillMaxWidth(), singleLine = false, imeAction = ImeAction.Default,
    )
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryKey(stringResource(R.string.reader_delete), onClick = onDelete)
        PrimaryKey(
            stringResource(R.string.reader_done),
            onClick = {
                val updated = annotation.copy(note = note.trim().ifEmpty { null }, color = color)
                if (updated != annotation) onChange(updated)
                onDone()
            },
            modifier = Modifier.weight(1f),
        )
    }
}
