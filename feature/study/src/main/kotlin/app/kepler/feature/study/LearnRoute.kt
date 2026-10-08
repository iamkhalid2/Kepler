package app.kepler.feature.study

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.kepler.core.designsystem.Kp
import app.kepler.core.model.AskAnswer
import app.kepler.core.model.BookId

enum class LearnTab(val label: Int) {
    ASK(R.string.tab_ask), RECAP(R.string.tab_recap), CONCEPTS(R.string.tab_concepts), MAP(R.string.tab_map), TAKEAWAYS(R.string.tab_takeaways),
}

/**
 * Learn for one book: Ask, Recap, Concepts, Map, Takeaways. `onJumpTo(ordinal)` opens the book at a sentence.
 * `onOpenSettings` is offered next to missing or rejected key errors when the host provides it.
 */
@Composable
fun LearnRoute(
    deps: StudyDeps,
    bookId: BookId,
    onJumpTo: (ordinal: Int) -> Unit,
    onBack: () -> Unit,
    initialTab: LearnTab = LearnTab.ASK,
    onOpenSettings: (() -> Unit)? = null,
) {
    val vm = studyViewModel("learn:$bookId") { LearnViewModel(bookId, deps) }
    val s by vm.state.collectAsState()
    val p = Kp.palette
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var cardFor by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
    LaunchedEffect(tab) {
        when (tab) {
            LearnTab.RECAP -> vm.loadRecap()
            LearnTab.CONCEPTS, LearnTab.MAP -> vm.loadConcepts()
            else -> Unit
        }
    }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                BackKey(onBack)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    CapsLine(stringResource(R.string.learn_caps, s.book?.title.orEmpty()))
                }
                s.budget?.let {
                    BasicText(stringResource(R.string.requests_left, it.requestsLeftToday), style = StudyType.numeral(p.text2, 13f))
                }
            }
            TabStrip(tab) { tab = it }
            Hairline()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    LearnTab.ASK -> AskTab(s, vm, onJumpTo, onOpenSettings)
                    LearnTab.RECAP -> RecapTab(s, vm, onOpenSettings)
                    LearnTab.CONCEPTS -> ConceptsTab(s, vm, onJumpTo)
                    LearnTab.MAP -> MapTab(s, vm)
                    LearnTab.TAKEAWAYS -> TakeawaysTab(s, vm)
                }
            }
        }
        s.node?.let { node ->
            ConceptSheet(s, node, vm, onDismiss = { vm.selectNode(null) }, onJumpTo = { vm.selectNode(null); onJumpTo(it) }) { ord, q ->
                vm.selectNode(null)
                cardFor = Triple(ord, node.description, q)
            }
        }
        cardFor?.let { (ord, text, q) ->
            CardFromSelectionSheet(deps, bookId, ord, text, onDismiss = { cardFor = null }, initialQuestion = q)
        }
    }
}

@Composable
private fun CapsLine(text: String, modifier: Modifier = Modifier) {
    BasicText(text.uppercase(), modifier, style = StudyType.caps(Kp.palette.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun BackKey(onClick: () -> Unit) =
    app.kepler.core.designsystem.KpIconKey(app.kepler.core.designsystem.KpIconKind.Back, stringResource(R.string.back), onClick)

/** Scrolling caps tabs; the active one reads `text` with a 12x2 accent rule above. */
@Composable
private fun TabStrip(selected: LearnTab, onSelect: (LearnTab) -> Unit) {
    val p = Kp.palette
    val haptics = app.kepler.core.designsystem.rememberHaptics()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        LearnTab.entries.forEach { t ->
            val active = t == selected
            Box(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Tab) {
                    if (!active) haptics.play(app.kepler.core.designsystem.HapticToken.Tap)
                    onSelect(t)
                }.padding(horizontal = 12.dp)
                    .drawBehind { if (active) drawRect(p.accent, Offset(0f, 6.dp.toPx()), Size(12.dp.toPx(), 2.dp.toPx())) },
                contentAlignment = Alignment.Center,
            ) { BasicText(stringResource(t.label).uppercase(), style = StudyType.caps(if (active) p.text else p.text2)) }
        }
    }
}

// ----------------------------------------------------------------------------------------- Ask

@Composable
private fun AskTab(s: LearnState, vm: LearnViewModel, onJumpTo: (Int) -> Unit, onOpenSettings: (() -> Unit)?) {
    val p = Kp.palette
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(s.transcript.size, s.asking) {
        val n = s.transcript.size + if (s.asking) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }
    val suggestionTexts = vm.suggestions().map { stringResource(suggestionRes(it)) }
    val sendLabel = stringResource(R.string.send)
    val haptics = app.kepler.core.designsystem.rememberHaptics()
    Column(Modifier.fillMaxSize().imePadding()) {
        FenceRow(s.fence, Modifier.padding(horizontal = 24.dp))
        Hairline(Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (s.transcript.isEmpty() && !s.asking) {
                item {
                    Column {
                        BasicText(stringResource(R.string.ask_intro), style = StudyType.prose(p.text2))
                        Spacer(Modifier.height(8.dp))
                        suggestionTexts.forEach { q ->
                            ListRow({ vm.ask(q) }) { BasicText(q, Modifier.weight(1f), style = StudyType.quote(p.text)) }
                        }
                    }
                }
            }
            items(s.transcript) { item ->
                when (item) {
                    is AskItem.Question -> BasicText(item.text, Modifier.fillMaxWidth(), style = StudyType.body(p.text2).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
                    is AskItem.Answer -> AnswerBlock(item.answer, onJumpTo)
                    is AskItem.Failure -> Column {
                        ErrorNote(item.error, { vm.retry(item) })
                        if ((item.error.kind == UiErrorKind.NO_KEY || item.error.kind == UiErrorKind.BAD_KEY) && onOpenSettings != null) {
                            TextLink(stringResource(R.string.open_settings), onOpenSettings)
                        }
                    }
                }
            }
            if (s.asking) item { WorkingRule(s.fence) }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudyInput(draft, { draft = it }, Modifier.weight(1f), placeholder = stringResource(R.string.ask_hint), maxLines = 4)
            val canSend = draft.isNotBlank() && !s.asking
            Box(
                Modifier.size(52.dp).drawBehind {
                    drawRoundRect(if (canSend) p.key else p.surface2, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    if (canSend) drawRect(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), Offset(4.dp.toPx(), 0f), Size(size.width - 8.dp.toPx(), 1.dp.toPx()))
                    val c = if (canSend) p.onKey else p.text2
                    val w = 1.8.dp.toPx(); val cx = size.width / 2; val cy = size.height / 2
                    drawLine(c, Offset(cx, cy + 8.dp.toPx()), Offset(cx, cy - 8.dp.toPx()), w, StrokeCap.Round)
                    drawLine(c, Offset(cx, cy - 8.dp.toPx()), Offset(cx - 6.dp.toPx(), cy - 2.dp.toPx()), w, StrokeCap.Round)
                    drawLine(c, Offset(cx, cy - 8.dp.toPx()), Offset(cx + 6.dp.toPx(), cy - 2.dp.toPx()), w, StrokeCap.Round)
                }.semantics { contentDescription = sendLabel }.clickable(enabled = canSend, role = Role.Button) {
                    haptics.play(app.kepler.core.designsystem.HapticToken.Confirm); vm.ask(draft); draft = ""
                },
            )
        }
    }
}

private fun suggestionRes(q: SuggestedQuestion) = when (q) {
    SuggestedQuestion.MainArgument -> R.string.sugg_main_argument
    SuggestedQuestion.KeyTerms -> R.string.sugg_key_terms
    SuggestedQuestion.LastChapter -> R.string.sugg_last_chapter
    SuggestedQuestion.WhoIsWho -> R.string.sugg_who
    SuggestedQuestion.WhatHappened -> R.string.sugg_what_happened
}

@Composable
private fun FenceRow(f: FenceInfo, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val text = when {
        f.wholeBook -> stringResource(R.string.fence_whole)
        f.sectionTitle != null -> stringResource(R.string.fence_upto, f.sectionTitle, f.sectionIndex ?: 0)
        else -> stringResource(R.string.fence_none)
    }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Caps(stringResource(R.string.fence_label), color = p.text2)
        Spacer(Modifier.width(12.dp))
        BasicText(text, Modifier.weight(1f), style = StudyType.body(p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Reading..." over an accent rule that grows once towards 90%; it never loops. */
@Composable
private fun WorkingRule(f: FenceInfo) {
    val p = Kp.palette
    val reduced = rememberReducedMotion()
    val frac = remember { Animatable(0f) }
    LaunchedEffect(Unit) { frac.animateTo(0.9f, tween(if (reduced) 120 else 20_000)) }
    Column {
        BasicText(
            stringResource(if (f.sectionTitle != null && !f.wholeBook) R.string.working_reading_section else R.string.working_reading, f.sectionTitle.orEmpty()),
            style = StudyType.caption(p.text2),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline).drawBehind { drawRect(p.accent, size = Size(size.width * frac.value, size.height)) })
    }
}

@Composable
private fun AnswerBlock(a: AskAnswer, onJumpTo: (Int) -> Unit) {
    val p = Kp.palette
    val accent = p.accentText
    val styled: AnnotatedString = remember(a.text, accent) {
        buildAnnotatedString {
            var last = 0
            Regex("\\[(\\d{1,2})]").findAll(a.text).forEach { m ->
                append(a.text.substring(last, m.range.first))
                withStyle(SpanStyle(color = accent, baselineShift = BaselineShift.Superscript, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))) { append(m.groupValues[1]) }
                last = m.range.last + 1
            }
            append(a.text.substring(last))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        BasicText(styled, style = StudyType.prose(p.text))
        if (a.citations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            a.citations.forEachIndexed { i, c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onJumpTo(c.ordinal) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText("${i + 1}", Modifier.width(28.dp), style = StudyType.numeral(p.accentText, 14f))
                    BasicText(c.label, Modifier.weight(1f), style = StudyType.caption(p.accentText), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- Recap

@Composable
private fun RecapTab(s: LearnState, vm: LearnViewModel, onOpenSettings: (() -> Unit)?) {
    val p = Kp.palette
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Caps(stringResource(R.string.recap_caps))
        Spacer(Modifier.height(12.dp))
        LoadView(s.recap, { vm.loadRecap(force = true) }, lines = 5) { text ->
            if (text.isBlank()) EmptyNote(stringResource(R.string.recap_empty)) else {
                BasicText(text, style = StudyType.prose(p.text), maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                TextLink(stringResource(if (expanded) R.string.less else R.string.more), { expanded = !expanded })
            }
        }
        val f = s.recap
        if (f is Load.Failed && (f.error.kind == UiErrorKind.NO_KEY || f.error.kind == UiErrorKind.BAD_KEY) && onOpenSettings != null) {
            TextLink(stringResource(R.string.open_settings), onOpenSettings)
        }
    }
}

// ----------------------------------------------------------------------------------------- Concepts

@Composable
private fun ConceptsTab(s: LearnState, vm: LearnViewModel, onJumpTo: (Int) -> Unit) {
    val p = Kp.palette
    LoadView(s.concepts, { vm.loadConcepts(force = true) }, lines = 6) { map ->
        if (map.nodes.isEmpty()) Box(Modifier.padding(24.dp)) { EmptyNote(stringResource(R.string.concepts_empty)) }
        else {
            val grouped = map.nodes.sortedBy { it.label.lowercase() }.groupBy { indexLetter(it.label) }.toSortedMap()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
                grouped.forEach { (letter, nodes) ->
                    item(key = "h$letter") { BasicText(letter.toString(), Modifier.padding(top = 24.dp, bottom = 4.dp), style = StudyType.display(p.text)) }
                    items(nodes, key = { it.id }) { n ->
                        val where = sectionFor(s.sections, n.firstOrdinal)?.title
                        ListRow({ vm.selectNode(n.id) }) {
                            Column(Modifier.weight(1f)) {
                                BasicText(n.label, style = StudyType.rowTitle(p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (where != null) BasicText(stringResource(R.string.first_in, where), style = StudyType.caption(p.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConceptSheet(
    s: LearnState, node: app.kepler.core.model.ConceptNode, vm: LearnViewModel,
    onDismiss: () -> Unit, onJumpTo: (Int) -> Unit, onMakeCard: (Int, String) -> Unit,
) {
    val p = Kp.palette
    val map = (s.concepts as? Load.Ready)?.value
    val labels = map?.nodes?.associate { it.id to it.label }.orEmpty()
    val edges = map?.edges?.filter { it.from == node.id || it.to == node.id }.orEmpty()
    StudySheet(onDismiss) {
        Caps(node.kind)
        Spacer(Modifier.height(8.dp))
        BasicText(node.label, style = StudyType.display(p.text))
        Spacer(Modifier.height(16.dp))
        BasicText(node.description, style = StudyType.prose(p.text))
        if (edges.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Caps(stringResource(R.string.connections))
            edges.forEach { e ->
                val other = if (e.from == node.id) e.to else e.from
                ListRow({ onJumpTo(e.evidenceOrdinal) }, minHeight = 48.dp) {
                    BasicText(e.relation, Modifier.width(112.dp), style = StudyType.caption(p.text2), maxLines = 2)
                    BasicText(labels[other] ?: other, Modifier.weight(1f), style = StudyType.rowTitle(p.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryKey(stringResource(R.string.first_mention), { onJumpTo(node.firstOrdinal) })
            Spacer(Modifier.width(8.dp))
            SecondaryKey(stringResource(R.string.make_a_card), { onMakeCard(node.firstOrdinal, stringResource0(node.label)) })
        }
        if (node.id in s.positions) {
            TextLink(stringResource(if (node.id in s.pinned) R.string.unpin else R.string.pin), { vm.togglePin(node.id) })
        }
    }
}

/** The question a concept card starts with. */
private fun stringResource0(label: String) = "What is $label?"

// ----------------------------------------------------------------------------------------- Map

@Composable
private fun MapTab(s: LearnState, vm: LearnViewModel) {
    LoadView(s.concepts, { vm.loadConcepts(force = true) }, lines = 6) { map ->
        if (map.nodes.isEmpty()) Box(Modifier.padding(24.dp)) { EmptyNote(stringResource(R.string.concepts_empty)) }
        else Column(Modifier.fillMaxSize()) {
            ConceptMapView(map, s.positions, s.pinned, s.selectedNode, vm::moveNode, vm::selectNode, Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(stringResource(R.string.map_hint), Modifier.weight(1f).padding(horizontal = 12.dp), style = StudyType.caption(Kp.palette.text2))
                TextLink(stringResource(R.string.relax), vm::relayout)
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- Takeaways

@Composable
private fun TakeawaysTab(s: LearnState, vm: LearnViewModel) {
    val p = Kp.palette
    val reached = s.reached
    if (reached.isEmpty()) { Box(Modifier.padding(24.dp)) { EmptyNote(stringResource(R.string.takeaways_empty)) }; return }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
        items(reached, key = { it.id }) { sec ->
            Column {
                ListRow({
                    open = if (open == sec.id) null else sec.id
                    if (open == sec.id) vm.loadTakeaways(sec.id)
                }, minHeight = 56.dp, separator = open != sec.id) {
                    BasicText(sec.title.ifBlank { "—" }, Modifier.weight(1f), style = StudyType.rowTitle(p.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (open == sec.id) {
                    Column(Modifier.padding(bottom = 16.dp)) {
                        LoadView(s.takeaways[sec.id] ?: Load.Loading, { vm.loadTakeaways(sec.id, force = true) }, lines = 3) { list ->
                            if (list.isEmpty()) EmptyNote(stringResource(R.string.takeaways_none))
                            else list.forEachIndexed { i, t ->
                                Row(Modifier.padding(top = 12.dp)) {
                                    BasicText("${i + 1}", Modifier.width(28.dp), style = StudyType.numeral(p.text2, 14f))
                                    BasicText(t, Modifier.weight(1f), style = StudyType.prose(p.text))
                                }
                            }
                        }
                    }
                    Hairline()
                }
            }
        }
    }
}
