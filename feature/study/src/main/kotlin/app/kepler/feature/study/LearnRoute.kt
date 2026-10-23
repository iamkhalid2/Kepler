package app.kepler.feature.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kepler.core.designsystem.HapticToken
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpCaps
import app.kepler.core.designsystem.KpChatInput
import app.kepler.core.designsystem.KpChevron
import app.kepler.core.designsystem.KpChip
import app.kepler.core.designsystem.KpIconView
import app.kepler.core.designsystem.KpIcons
import app.kepler.core.designsystem.KpRow
import app.kepler.core.designsystem.KpRowSeparator
import app.kepler.core.designsystem.KpSectionHeader
import app.kepler.core.designsystem.KpSegmented
import app.kepler.core.designsystem.KpSpace
import app.kepler.core.designsystem.KpStreamingCaret
import app.kepler.core.designsystem.KpTopBar
import app.kepler.core.designsystem.KpType
import app.kepler.core.designsystem.rememberHaptics
import app.kepler.core.model.AskAnswer
import app.kepler.core.model.BookId
import app.kepler.core.model.Citation

enum class LearnTab(val label: Int) {
    ASK(R.string.tab_ask), RECAP(R.string.tab_recap), CONCEPTS(R.string.tab_concepts), MAP(R.string.tab_map), TAKEAWAYS(R.string.tab_takeaways),
}

/** The four segments, in the brief's order. Takeaways live under Recap. */
private val SEGMENTS = listOf(LearnTab.ASK, LearnTab.RECAP, LearnTab.MAP, LearnTab.CONCEPTS)

/**
 * Learn for one book: Ask, Recap, Map, Concepts. `onJumpTo(ordinal)` opens the book at a sentence.
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
    var tab by rememberSaveable { mutableStateOf(if (initialTab == LearnTab.TAKEAWAYS) LearnTab.RECAP else initialTab) }
    var cardFor by remember { mutableStateOf<Triple<Int, String, String>?>(null) }
    LaunchedEffect(tab) {
        when (tab) {
            LearnTab.RECAP, LearnTab.TAKEAWAYS -> vm.loadRecap()
            LearnTab.CONCEPTS, LearnTab.MAP -> vm.loadConcepts()
            else -> Unit
        }
    }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            KpTopBar(onBack = onBack, title = stringResource(R.string.learn_title), caption = s.book?.title)
            KpSegmented(
                SEGMENTS.map { stringResource(it.label) }, SEGMENTS.indexOf(tab).coerceAtLeast(0),
                { tab = SEGMENTS[it] }, Modifier.padding(horizontal = KpSpace.gutter),
            )
            ScopeRow(s, Modifier.padding(horizontal = KpSpace.gutter))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    LearnTab.ASK -> AskTab(s, vm, onJumpTo, onOpenSettings) { ord, text, q -> cardFor = Triple(ord, text, q) }
                    LearnTab.RECAP, LearnTab.TAKEAWAYS -> RecapTab(s, vm, onOpenSettings)
                    LearnTab.CONCEPTS -> ConceptsTab(s, vm)
                    LearnTab.MAP -> MapTab(s, vm)
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

/** Scope chip ("Up to here" / "Whole book") with where that is, and the day's requests, quietly. */
@Composable
private fun ScopeRow(s: LearnState, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val f = s.fence
    Row(modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        KpChip(
            stringResource(if (f.wholeBook) R.string.scope_whole else R.string.scope_here),
            icon = if (f.wholeBook) KpIcons.Book else KpIcons.Bookmark,
        )
        Spacer(Modifier.padding(start = 8.dp))
        Text(
            when {
                f.wholeBook -> ""
                f.sectionTitle != null -> stringResource(R.string.fence_upto, f.sectionTitle, f.sectionIndex ?: 0)
                else -> stringResource(R.string.fence_none)
            },
            Modifier.weight(1f), color = p.text2, style = KpType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        s.budget?.let {
            Text(stringResource(R.string.requests_left, it.requestsLeftToday), color = p.text2, style = KpType.caption, maxLines = 1)
        }
    }
}

// ----------------------------------------------------------------------------------------- Ask

@Composable
private fun AskTab(
    s: LearnState, vm: LearnViewModel, onJumpTo: (Int) -> Unit, onOpenSettings: (() -> Unit)?,
    onMakeCard: (ordinal: Int, text: String, question: String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val haptics = rememberHaptics()
    // When a question is sent, bring it to the top so the answer grows into the screen below it.
    LaunchedEffect(s.transcript.size, s.asking) {
        val last = s.transcript.lastIndex + if (s.asking) 1 else 0
        val questionIndex = s.transcript.indexOfLast { it is AskItem.Question }
        if (s.asking && questionIndex >= 0) listState.animateScrollToItem(questionIndex)
        else if (last >= 0) listState.animateScrollToItem(last)
    }
    val suggestions = vm.suggestions().map { stringResource(suggestionRes(it)) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = KpSpace.gutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.transcript.isEmpty() && !s.asking) {
                item { AskIntro(s.fence.wholeBook, suggestions) { vm.ask(it) } }
            }
            items(s.transcript) { item ->
                when (item) {
                    is AskItem.Question -> QuestionBubble(item.text)
                    is AskItem.Answer -> AnswerCard(
                        item.answer.text, item.answer.citations, streaming = false, onJumpTo = onJumpTo, onStop = null,
                        onMakeCard = { onMakeCard(item.answer.citations.firstOrNull()?.ordinal ?: s.frontier.coerceAtLeast(0), plainAnswer(item.answer.text), item.question) },
                    )
                    is AskItem.Failure -> {
                        val settings = onOpenSettings.takeIf { item.error.needsSettings() }
                        ErrorNote(
                            item.error, { vm.retry(item) },
                            actionLabel = settings?.let { stringResource(R.string.open_settings) }, onAction = settings,
                        )
                    }
                }
            }
            s.live?.let { live ->
                item {
                    if (!live.started) WaitingCard(s.fence, vm::stop)
                    else AnswerCard(live.text, live.citations, streaming = true, onJumpTo = onJumpTo, onStop = vm::stop, onMakeCard = {})
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = KpSpace.gutter, vertical = 12.dp)) {
            KpChatInput(
                draft, { draft = it },
                onSend = { haptics.play(HapticToken.Confirm); vm.ask(draft); draft = "" },
                placeholder = stringResource(R.string.ask_hint), enabled = !s.asking,
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
private fun AskIntro(wholeBook: Boolean, suggestions: List<String>, onPick: (String) -> Unit) {
    val p = Kp.palette
    KpCard(padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KpIconView(KpIcons.Sparkle, p.accentText, size = 20.dp)
                Spacer(Modifier.padding(start = 8.dp))
                KpCaps(stringResource(R.string.ask_suggested), color = p.accentText)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(if (wholeBook) R.string.ask_intro_whole else R.string.ask_intro), color = p.text2, style = KpType.body)
        }
        suggestions.forEachIndexed { i, q ->
            KpRowSeparator(inset = 16.dp)
            KpRow(q, onClick = { onPick(q) }, trailing = { KpChevron() }, inset = 16.dp)
            if (i == suggestions.lastIndex) Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun QuestionBubble(text: String) {
    val p = Kp.palette
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text, Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp)).background(p.surface2).padding(horizontal = 16.dp, vertical = 12.dp),
            color = p.text, style = KpType.body,
        )
    }
}

/** "Reading the book…" with the caret, from the moment the question is sent until the first words arrive. */
@Composable
private fun WaitingCard(f: FenceInfo, onStop: () -> Unit) {
    val p = Kp.palette
    KpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (f.sectionTitle != null && !f.wholeBook) R.string.working_reading_section else R.string.working_reading, f.sectionTitle.orEmpty()),
                Modifier.weight(1f, fill = false), color = p.text2, style = KpType.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(start = 6.dp))
            KpStreamingCaret()
        }
        Spacer(Modifier.height(4.dp))
        GhostKey(stringResource(R.string.stop), onStop)
    }
}

/** The answer: prose with numbered markers, the caret while it streams, citation chips as they resolve. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerCard(
    text: String, citations: List<Citation>, streaming: Boolean,
    onJumpTo: (Int) -> Unit, onStop: (() -> Unit)?, onMakeCard: () -> Unit,
) {
    val p = Kp.palette
    val accent = p.accentText
    val styled: AnnotatedString = remember(text, citations, accent) { styleAnswer(text, citations, accent) }
    KpCard(padding = PaddingValues(20.dp)) {
        Text(styled, color = p.text, style = KpType.prose)
        if (streaming) {
            Spacer(Modifier.height(4.dp))
            KpStreamingCaret()
        }
        if (citations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                citations.forEachIndexed { i, c -> KpChip("${i + 1}  ${c.label}", onClick = { onJumpTo(c.ordinal) }) }
            }
        }
        if (streaming && onStop != null) {
            Spacer(Modifier.height(8.dp))
            GhostKey(stringResource(R.string.stop), onStop)
        } else if (!streaming) {
            Spacer(Modifier.height(12.dp))
            SecondaryKey(stringResource(R.string.make_review_card), onMakeCard, small = true)
        }
    }
}

/**
 * The service marks citations with sentence ordinals ("[3358]"); show them as 1-based superscript numerals matching the
 * chips below, and drop any marker that is not in that list (also one still arriving at the end of a stream).
 */
internal fun styleAnswer(text: String, citations: List<Citation>, accent: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        val a = AskAnswer(trimPartialMarker(text).replace(RAW_MARKER_LIST, ""), citations)
        var last = 0
        var prevMarkerEnd = -1
        Regex("\\s*\\[(\\d+)]").findAll(a.text).forEach { m ->
            append(a.text.substring(last, m.range.first))
            val n = citationNumber(a, m.groupValues[1].toIntOrNull())
            if (n != null) {
                withStyle(SpanStyle(color = accent, baselineShift = BaselineShift.Superscript, fontSize = 11.sp)) {
                    append(if (prevMarkerEnd == m.range.first) ",$n" else "$n")
                }
                prevMarkerEnd = m.range.last + 1
            }
            last = m.range.last + 1
        }
        append(a.text.substring(last))
    }

/** A citation marker that has only half arrived ("... as shown [33", "[556-558, 18") is hidden until it completes. */
internal fun trimPartialMarker(text: String): String = text.replace(Regex("\\s*\\[[\\d\\s,;\\-–s]*$"), "")

/**
 * A list of passage numbers the service passed through unresolved ("[556-558, 1827-1830]"): raw sentence
 * ordinals mean nothing to a reader, so they are hidden rather than printed in the answer.
 */
/** The answer without passage markers, for text that leaves the answer card (a review card's back). */
internal fun plainAnswer(text: String): String =
    text.replace(RAW_MARKER_LIST, "").replace(Regex("\\s*\\[\\d+]"), "").replace(Regex("[ \\t]+([.,;:])"), "$1").trim()

private val RAW_MARKER_LIST = Regex("\\s*\\[\\s*s?\\d+(?:\\s*[,;\\-–]\\s*s?\\d+)+\\s*]")

/** 1-based position of the citation for [ordinal] in [a]'s list, or null when it is not cited. */
internal fun citationNumber(a: AskAnswer, ordinal: Int?): Int? =
    ordinal?.let { o -> a.citations.indexOfFirst { it.ordinal == o }.takeIf { it >= 0 }?.plus(1) }

// ----------------------------------------------------------------------------------------- Recap

@Composable
private fun RecapTab(s: LearnState, vm: LearnViewModel, onOpenSettings: (() -> Unit)?) {
    val p = Kp.palette
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KpSpace.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(KpSpace.cardGap),
    ) {
        KpCard(padding = PaddingValues(20.dp)) {
            KpCaps(stringResource(R.string.recap_caps), color = p.accentText)
            Spacer(Modifier.height(12.dp))
            when (val r = s.recap) {
                Load.Idle -> LoadingBlocks(4)
                Load.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.working_reading), color = p.text2, style = KpType.subhead)
                    Spacer(Modifier.padding(start = 6.dp))
                    KpStreamingCaret()
                }
                is Load.Failed -> {
                    val settings = onOpenSettings.takeIf { r.error.needsSettings() }
                    ErrorNote(
                        r.error, { vm.loadRecap(force = true) },
                        actionLabel = settings?.let { stringResource(R.string.open_settings) }, onAction = settings,
                    )
                }
                is Load.Ready -> if (r.value.isBlank()) EmptyNote(stringResource(R.string.recap_empty), Modifier.padding(vertical = 0.dp)) else {
                    Text(r.value.trim(), color = p.text, style = KpType.prose)
                    if (s.recapStreaming) {
                        Spacer(Modifier.height(4.dp))
                        KpStreamingCaret()
                    }
                }
            }
            if (s.recapStreaming) {
                Spacer(Modifier.height(8.dp))
                GhostKey(stringResource(R.string.stop), vm::stopRecap)
            } else if (s.recap is Load.Ready && (s.recap as Load.Ready<String>).value.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                GhostKey(stringResource(R.string.refresh), { vm.loadRecap(force = true) })
            }
        }
        val reached = s.reached
        if (reached.isNotEmpty()) {
            KpSectionHeader(stringResource(R.string.tab_takeaways))
            KpCard(padding = PaddingValues(0.dp)) {
                reached.forEachIndexed { i, sec ->
                    if (i > 0) KpRowSeparator(inset = 16.dp)
                    KpRow(
                        sec.title.ifBlank { "—" },
                        onClick = {
                            open = if (open == sec.id) null else sec.id
                            if (open == sec.id) vm.loadTakeaways(sec.id)
                        },
                        trailing = { KpIconView(if (open == sec.id) KpIcons.ChevronDown else KpIcons.ChevronRight, p.text2, size = 20.dp) },
                        inset = 16.dp,
                    )
                    if (open == sec.id) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            LoadView(s.takeaways[sec.id] ?: Load.Loading, { vm.loadTakeaways(sec.id, force = true) }, lines = 3) { list ->
                                if (list.isEmpty()) Text(stringResource(R.string.takeaways_none), color = p.text2, style = KpType.body)
                                else list.forEachIndexed { n, t ->
                                    Row(Modifier.padding(top = if (n == 0) 0.dp else 12.dp)) {
                                        Text("${n + 1}", Modifier.widthIn(min = 28.dp), color = p.accentText, style = KpType.numeral)
                                        Text(t, Modifier.weight(1f), color = p.text, style = KpType.prose.copy(fontSize = 15.sp, lineHeight = 24.sp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ----------------------------------------------------------------------------------------- Concepts

/** The quiet state while the packs behind Concepts and Map are prepared: a line of copy over skeleton rows. */
@Composable
private fun PreparingCard(text: String, modifier: Modifier = Modifier) {
    val p = Kp.palette
    KpCard(modifier.padding(horizontal = KpSpace.gutter, vertical = 12.dp), padding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KpIconView(KpIcons.Sparkle, p.accentText, size = 20.dp)
            Spacer(Modifier.padding(start = 8.dp))
            Text(text, color = p.text2, style = KpType.subhead)
        }
        Spacer(Modifier.height(16.dp))
        LoadingBlocks(4)
    }
}

@Composable
private fun ConceptsTab(s: LearnState, vm: LearnViewModel) {
    val p = Kp.palette
    when (val c = s.concepts) {
        Load.Idle, Load.Loading -> PreparingCard(stringResource(R.string.concepts_preparing))
        is Load.Failed -> Box(Modifier.padding(horizontal = KpSpace.gutter, vertical = 12.dp)) { ErrorNote(c.error, { vm.loadConcepts(force = true) }) }
        is Load.Ready -> {
            val map = c.value
            if (map.nodes.isEmpty()) Box(Modifier.padding(horizontal = KpSpace.gutter)) { EmptyNote(stringResource(R.string.concepts_empty)) }
            else {
                val grouped = map.nodes.sortedBy { it.label.lowercase() }.groupBy { indexLetter(it.label) }.toSortedMap()
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = KpSpace.gutter, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(KpSpace.cardGap),
                ) {
                    grouped.forEach { (letter, nodes) ->
                        item(key = "g$letter") {
                            Column {
                                KpCaps(letter.toString(), Modifier.padding(top = 8.dp, bottom = 8.dp))
                                KpCard(padding = PaddingValues(0.dp)) {
                                    nodes.forEachIndexed { i, n ->
                                        if (i > 0) KpRowSeparator(inset = 16.dp)
                                        val where = sectionFor(s.sections, n.firstOrdinal)?.title?.takeIf { it.isNotBlank() }
                                        KpRow(
                                            n.label, meta = where?.let { stringResource(R.string.first_in, it) },
                                            onClick = { vm.selectNode(n.id) }, trailing = { KpChevron() }, inset = 16.dp,
                                        )
                                    }
                                }
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
    val question = stringResource(R.string.what_is, node.label)
    StudySheet(onDismiss) {
        Caps(node.kind)
        Spacer(Modifier.height(8.dp))
        Text(node.label, color = p.text, style = KpType.displayTitle)
        Spacer(Modifier.height(16.dp))
        Text(node.description, color = p.text, style = KpType.prose)
        if (edges.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Caps(stringResource(R.string.connections))
            Spacer(Modifier.height(4.dp))
            edges.forEach { e ->
                val other = if (e.from == node.id) e.to else e.from
                ListRow({ onJumpTo(e.evidenceOrdinal) }, minHeight = 48.dp) {
                    Text(e.relation, Modifier.widthIn(min = 112.dp, max = 112.dp), color = p.text2, style = KpType.caption, maxLines = 2)
                    Text(labels[other] ?: other, Modifier.weight(1f), color = p.text, style = KpType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryKey(stringResource(R.string.first_mention), { onJumpTo(node.firstOrdinal) }, small = true)
            SecondaryKey(stringResource(R.string.make_a_card), { onMakeCard(node.firstOrdinal, question) }, small = true)
            if (node.id in s.positions) GhostKey(stringResource(if (node.id in s.pinned) R.string.unpin else R.string.pin), { vm.togglePin(node.id) })
        }
    }
}

// ----------------------------------------------------------------------------------------- Map

@Composable
private fun MapTab(s: LearnState, vm: LearnViewModel) {
    val p = Kp.palette
    when (val c = s.concepts) {
        Load.Idle, Load.Loading -> PreparingCard(stringResource(R.string.map_preparing))
        is Load.Failed -> Box(Modifier.padding(horizontal = KpSpace.gutter, vertical = 12.dp)) { ErrorNote(c.error, { vm.loadConcepts(force = true) }) }
        is Load.Ready -> {
            val map = c.value
            if (map.nodes.isEmpty()) Box(Modifier.padding(horizontal = KpSpace.gutter)) { EmptyNote(stringResource(R.string.concepts_empty)) }
            else Column(Modifier.fillMaxSize().padding(horizontal = KpSpace.gutter, vertical = 12.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface1)) {
                    ConceptMapView(map, s.positions, s.pinned, s.selectedNode, vm::moveNode, vm::selectNode, Modifier.fillMaxSize())
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.map_hint), Modifier.weight(1f), color = p.text2, style = KpType.caption)
                    GhostKey(stringResource(R.string.relax), vm::relayout)
                }
            }
        }
    }
}
