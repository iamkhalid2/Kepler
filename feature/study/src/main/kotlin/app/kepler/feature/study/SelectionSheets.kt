package app.kepler.feature.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.kepler.core.designsystem.Kp
import app.kepler.core.designsystem.KpCard
import app.kepler.core.designsystem.KpSlider
import app.kepler.core.designsystem.KpStreamingCaret
import app.kepler.core.designsystem.KpType
import app.kepler.core.model.BookId
import kotlinx.coroutines.delay

@Composable
internal inline fun <reified VM : ViewModel> studyViewModel(key: String, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/** The quoted passage: italic, with a 3 dp accent bar. */
@Composable
internal fun QuoteBlock(text: String, maxLines: Int = 5, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(p.accent))
        Spacer(Modifier.width(12.dp))
        Text(text, Modifier.weight(1f), color = p.text, style = StudyType.quote(p.text), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Streamed prose: "Reading the book…" with the caret until the first words, then the text with the caret until done. */
@Composable
internal fun StreamingProse(load: Load<String>, streaming: Boolean, onRetry: () -> Unit, content: @Composable (String) -> Unit) {
    val p = Kp.palette
    when (load) {
        Load.Idle, Load.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.working_reading), color = p.text2, style = KpType.subhead)
            Spacer(Modifier.size(6.dp))
            KpStreamingCaret()
        }
        is Load.Failed -> ErrorNote(load.error, onRetry)
        is Load.Ready -> Column {
            content(load.value)
            if (streaming) {
                Spacer(Modifier.height(4.dp))
                KpStreamingCaret()
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- Explain

@Composable
fun ExplainSheet(
    deps: StudyDeps, bookId: BookId, ordinal: Int, text: String,
    onDismiss: () -> Unit, onMakeCard: () -> Unit, onAskMore: () -> Unit,
) {
    val vm = studyViewModel("explain:$bookId:$ordinal:${text.hashCode()}") { ExplainViewModel(bookId, ordinal, text, deps) }
    val s by vm.state.collectAsState()
    val p = Kp.palette
    StudySheet(onDismiss) {
        SheetTitle(stringResource(R.string.explain_caps))
        Spacer(Modifier.height(12.dp))
        QuoteBlock(text)
        Spacer(Modifier.height(20.dp))
        when (s.phase) {
            ExplainPhase.LOADING_MODE -> LoadingBlocks(2)
            ExplainPhase.PREDICT -> {
                Text(stringResource(R.string.explain_why), color = p.text, style = KpType.rowTitle)
                Spacer(Modifier.height(12.dp))
                StudyInput(s.prediction, vm::setPrediction, placeholder = stringResource(R.string.explain_guess_hint), minLines = 3, inCard = true)
                Spacer(Modifier.height(16.dp))
                PrimaryKey(stringResource(R.string.reveal_explanation), vm::reveal, Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                GhostKey(stringResource(R.string.skip), vm::reveal)
            }
            ExplainPhase.REVEALED -> {
                if (s.prediction.isNotBlank()) {
                    Caps(stringResource(R.string.your_guess)); Spacer(Modifier.height(4.dp))
                    Text(s.prediction, color = p.text2, style = KpType.body); Spacer(Modifier.height(16.dp))
                }
                StreamingProse(s.explanation, s.streaming, vm::load) { Text(it, color = p.text, style = KpType.prose) }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryKey(stringResource(R.string.make_a_card), onMakeCard, small = true)
                    GhostKey(stringResource(R.string.ask_more), onAskMore)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- Define

@Composable
fun DefineSheet(deps: StudyDeps, bookId: BookId, ordinal: Int, term: String, onDismiss: () -> Unit) {
    val vm = studyViewModel("define:$bookId:$ordinal:$term") { DefineViewModel(bookId, ordinal, term, deps) }
    val s by vm.state.collectAsState()
    val p = Kp.palette
    StudySheet(onDismiss) {
        Caps(stringResource(R.string.define_caps))
        Spacer(Modifier.height(8.dp))
        Text(term.trim(), color = p.text, style = KpType.displayTitle)
        Spacer(Modifier.height(16.dp))
        StreamingProse(s.definition, s.streaming, vm::load) { def ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                def.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    val m = Regex("^(\\d+)[.)]\\s*(.*)$").matchEntire(line)
                    if (m != null) {
                        Row {
                            Text(m.groupValues[1], Modifier.widthIn(min = 24.dp), color = p.accentText, style = KpType.numeral)
                            Text(m.groupValues[2], color = p.text, style = KpType.prose)
                        }
                    } else Text(line, color = p.text, style = KpType.prose)
                }
            }
        }
        if (s.savedAsWord) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.saved_to_words), color = p.text2, style = KpType.caption)
        }
    }
}

// ----------------------------------------------------------------------------------------- Card

@Composable
fun CardFromSelectionSheet(
    deps: StudyDeps, bookId: BookId, ordinal: Int, text: String, onDismiss: () -> Unit,
    initialQuestion: String? = null, onSaved: () -> Unit = {},
) {
    val vm = studyViewModel("card:$bookId:$ordinal:${text.hashCode()}:${initialQuestion.hashCode()}") {
        CardDraftViewModel(bookId, ordinal, text, initialQuestion, deps)
    }
    val s by vm.state.collectAsState()
    val p = Kp.palette
    StudySheet(onDismiss, tall = true) {
        SheetTitle(stringResource(R.string.card_caps))
        Spacer(Modifier.height(12.dp))
        QuoteBlock(text, maxLines = 3)
        Spacer(Modifier.height(20.dp))
        if (s.capReached) {
            Text(stringResource(R.string.cap_reached, Study.DAILY_NEW_CARD_CAP), color = p.text2, style = KpType.body)
            Spacer(Modifier.height(16.dp))
        } else {
            Caps(stringResource(R.string.suggested_questions)); Spacer(Modifier.height(4.dp))
            when (val sg = s.suggestions) {
                Load.Idle -> Unit
                Load.Loading -> LoadingBlocks(2, Modifier.padding(vertical = 8.dp))
                is Load.Failed -> ErrorNote(sg.error, vm::suggest)
                is Load.Ready -> if (sg.value.isEmpty()) Text(stringResource(R.string.no_suggestions), color = p.text2, style = KpType.caption, modifier = Modifier.padding(vertical = 12.dp))
                else sg.value.forEach { sug ->
                    ListRow({ vm.choose(sug) }, minHeight = 56.dp) {
                        Text(sug.question, Modifier.weight(1f), color = if (s.chosen == sug) p.accentText else p.text, style = KpType.body)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            StudyInput(s.question, vm::setQuestion, label = stringResource(R.string.front_label), placeholder = stringResource(R.string.front_hint), minLines = 2, inCard = true)
            Spacer(Modifier.height(16.dp))
            StudyInput(s.answer, vm::setAnswer, label = stringResource(R.string.your_answer_label), placeholder = stringResource(R.string.your_answer_hint), minLines = 3, inCard = true)
            Spacer(Modifier.height(16.dp))
            when (val c = s.check) {
                Load.Loading -> LoadingBlocks(2)
                is Load.Failed -> ErrorNote(c.error, null)
                is Load.Ready -> Column {
                    Text(verdictText(c.value.verdict), color = p.text, style = KpType.title2)
                    c.value.covered.forEach { CheckLine(it, true) }
                    c.value.missing.forEach { CheckLine(it, false) }
                }
                Load.Idle -> Unit
            }
            if (s.question.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Caps(stringResource(R.string.preview_caps)); Spacer(Modifier.height(8.dp))
                IndexCard(Modifier.fillMaxWidth().heightIn(min = 120.dp), scroll = false) {
                    Text(s.question, color = p.readText, style = KpType.title2)
                }
            }
            Spacer(Modifier.height(24.dp))
            val checked = s.check is Load.Ready || s.check is Load.Failed
            if (!checked) {
                SecondaryKey(stringResource(R.string.check_my_answer), vm::check, Modifier.fillMaxWidth(), enabled = s.canCheck)
                Spacer(Modifier.height(4.dp))
                GhostKey(stringResource(R.string.save_without_checking), { vm.save(onSaved) })
            } else {
                PrimaryKey(stringResource(R.string.save_card), { vm.save(onSaved) }, Modifier.fillMaxWidth(), enabled = s.canSave)
            }
        }
    }
}

@Composable
private fun verdictText(verdict: String): String = when (verdict.lowercase().trim()) {
    "correct", "right", "good", "full" -> stringResource(R.string.verdict_correct)
    "partial", "partially", "partly" -> stringResource(R.string.verdict_partial)
    "incorrect", "wrong", "missed", "none" -> stringResource(R.string.verdict_incorrect)
    "" -> stringResource(R.string.verdict_compared)
    else -> verdict.replaceFirstChar { it.uppercase() }
}

@Composable
internal fun CheckLine(text: String, hit: Boolean, modifier: Modifier = Modifier) {
    val p = Kp.palette
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp).padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Tick(hit, Modifier.padding(top = 3.dp))
        Spacer(Modifier.size(12.dp))
        Text(text, Modifier.weight(1f), color = if (hit) p.text else p.text2, style = KpType.body)
    }
}

/** Rubric tick: a filled check in `positive` when hit, an empty hairline circle when missed. */
@Composable
internal fun Tick(hit: Boolean, modifier: Modifier = Modifier) {
    val p = Kp.palette
    val hitLabel = stringResource(R.string.covered)
    val missLabel = stringResource(R.string.missed)
    Canvas(modifier.size(18.dp).semantics { contentDescription = if (hit) hitLabel else missLabel }) {
        drawCircle(if (hit) p.positive else p.hairline, radius = size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        if (hit) {
            val w = 1.8.dp.toPx()
            drawLine(p.positive, Offset(size.width * .28f, size.height * .52f), Offset(size.width * .44f, size.height * .68f), w, StrokeCap.Round)
            drawLine(p.positive, Offset(size.width * .44f, size.height * .68f), Offset(size.width * .74f, size.height * .32f), w, StrokeCap.Round)
        }
    }
}

// ----------------------------------------------------------------------------------------- Check-in

@Composable
fun CheckInSheet(deps: StudyDeps, bookId: BookId, sectionId: Long, onDismiss: () -> Unit) {
    val vm = studyViewModel("checkin:$bookId:$sectionId") { CheckInViewModel(bookId, sectionId, deps) }
    val s by vm.state.collectAsState()
    val p = Kp.palette
    StudySheet(onDismiss) {
        SheetTitle(stringResource(R.string.checkin_caps))
        Spacer(Modifier.height(12.dp))
        when (val ci = s.checkIn) {
            Load.Idle, Load.Loading -> LoadingBlocks(2)
            is Load.Failed -> ErrorNote(ci.error, vm::load)
            is Load.Ready -> {
                KpCard(color = p.surface2, padding = PaddingValues(16.dp)) {
                    Text(ci.value.prompt, color = p.text, style = KpType.prose)
                }
                Spacer(Modifier.height(16.dp))
                if (s.phase == CheckInPhase.ANSWER) {
                    StudyInput(s.answer, vm::setAnswer, placeholder = stringResource(R.string.checkin_hint), minLines = 3, inCard = true)
                    Spacer(Modifier.height(16.dp))
                    PrimaryKey(stringResource(R.string.reveal), vm::reveal, Modifier.fillMaxWidth(), enabled = !s.checking)
                } else {
                    Caps(stringResource(R.string.the_gist)); Spacer(Modifier.height(8.dp))
                    Text(ci.value.gist, color = p.text, style = KpType.prose)
                    Spacer(Modifier.height(16.dp))
                    RubricList(ci.value.rubric, s.ticks)
                }
            }
        }
        // Skipping is always one tap, whatever state the sheet is in.
        GhostKey(stringResource(if (s.phase == CheckInPhase.REVEALED) R.string.done else R.string.skip), { onDismiss() }, Modifier.padding(top = 8.dp))
    }
}

/** Rubric lines tick one after another, 60 ms apart (all at once under reduced motion). */
@Composable
internal fun RubricList(rubric: List<String>, ticks: List<Boolean>) {
    val reduced = rememberReducedMotion()
    var shown by remember(rubric) { mutableStateOf(if (reduced) rubric.size else 0) }
    LaunchedEffect(rubric, reduced) {
        if (reduced) return@LaunchedEffect
        for (i in 1..rubric.size) { delay(60); shown = i }
    }
    rubric.forEachIndexed { i, line -> CheckLine(line, hit = i < shown && ticks.getOrNull(i) == true) }
}

// ----------------------------------------------------------------------------------------- Recall

@Composable
fun RecallSheet(deps: StudyDeps, bookId: BookId, sectionId: Long, onDismiss: () -> Unit) {
    val vm = studyViewModel("recall:$bookId:$sectionId") { RecallViewModel(bookId, sectionId, deps) }
    val s by vm.state.collectAsState()
    val p = Kp.palette
    StudySheet(onDismiss, tall = true) {
        SheetTitle(stringResource(R.string.recall_caps))
        Spacer(Modifier.height(12.dp))
        when (val ci = s.checkIn) {
            Load.Idle, Load.Loading -> LoadingBlocks(2)
            is Load.Failed -> ErrorNote(ci.error, vm::load)
            is Load.Ready -> when (s.phase) {
                RecallPhase.PREDICT -> {
                    Text(stringResource(R.string.recall_predict), color = p.text, style = KpType.rowTitle)
                    Spacer(Modifier.height(24.dp))
                    Text("${s.predicted}%", color = p.text, style = KpType.bigStat)
                    KpSlider(
                        s.predicted / 100f, { vm.setPredicted((it * 100).toInt()) },
                        contentDescription = stringResource(R.string.recall_predict_a11y),
                    )
                    Spacer(Modifier.height(24.dp))
                    PrimaryKey(stringResource(R.string.recall_start), vm::startRecall, Modifier.fillMaxWidth())
                }
                RecallPhase.RECALL -> {
                    Text(stringResource(R.string.recall_write), color = p.text, style = KpType.rowTitle)
                    Spacer(Modifier.height(12.dp))
                    StudyInput(s.answer, vm::setAnswer, placeholder = stringResource(R.string.recall_write_hint), minLines = 6, maxLines = 12, inCard = true)
                    if (s.checkFailed) {
                        Spacer(Modifier.height(8.dp))
                        ErrorNote(UiError(UiErrorKind.UNAVAILABLE, true), null)
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryKey(stringResource(R.string.reveal), vm::finish, Modifier.fillMaxWidth(), enabled = !s.checking)
                }
                RecallPhase.RESULT -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column {
                            Caps(stringResource(R.string.predicted)); Text("${s.predicted}%", color = p.text2, style = KpType.bigStat)
                        }
                        Column {
                            Caps(stringResource(R.string.recalled)); Text("${s.actual}%", color = p.text, style = KpType.bigStat)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Hairline()
                    Spacer(Modifier.height(16.dp))
                    Text(ci.value.gist, color = p.text, style = KpType.prose)
                    Spacer(Modifier.height(16.dp))
                    RubricList(ci.value.rubric, s.ticks)
                    Spacer(Modifier.height(24.dp))
                    PrimaryKey(stringResource(R.string.done), onDismiss, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
