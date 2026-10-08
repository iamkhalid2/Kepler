package app.betterbooks.feature.study

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.betterbooks.core.designsystem.Bb
import app.betterbooks.core.model.BookId
import kotlinx.coroutines.delay

@Composable
internal inline fun <reified VM : ViewModel> studyViewModel(key: String, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

/** The quoted passage: italic, with the pencil sideline. */
@Composable
internal fun QuoteBlock(text: String, maxLines: Int = 5, modifier: Modifier = Modifier) {
    val p = Bb.palette
    Box(
        modifier.fillMaxWidth().drawBehind { drawRect(p.pencil, size = Size(2.dp.toPx(), size.height)) }.padding(start = 14.dp),
    ) { BasicText(text, style = StudyType.quote(p.text), maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
}

// ----------------------------------------------------------------------------------------- Explain

@Composable
fun ExplainSheet(
    deps: StudyDeps, bookId: BookId, ordinal: Int, text: String,
    onDismiss: () -> Unit, onMakeCard: () -> Unit, onAskMore: () -> Unit,
) {
    val vm = studyViewModel("explain:$bookId:$ordinal:${text.hashCode()}") { ExplainViewModel(bookId, ordinal, text, deps) }
    val s by vm.state.collectAsState()
    val p = Bb.palette
    StudySheet(onDismiss) {
        Caps(stringResource(R.string.explain_caps))
        Spacer(Modifier.height(12.dp))
        QuoteBlock(text)
        Spacer(Modifier.height(24.dp))
        when (s.phase) {
            ExplainPhase.LOADING_MODE -> LoadingBlocks(2)
            ExplainPhase.PREDICT -> {
                BasicText(stringResource(R.string.explain_why), style = StudyType.title(p.text))
                Spacer(Modifier.height(12.dp))
                StudyInput(s.prediction, vm::setPrediction, placeholder = stringResource(R.string.explain_guess_hint), minLines = 3)
                Spacer(Modifier.height(16.dp))
                PrimaryKey(stringResource(R.string.reveal_explanation), vm::reveal, Modifier.fillMaxWidth())
                TextLink(stringResource(R.string.skip), vm::reveal)
            }
            ExplainPhase.REVEALED -> {
                if (s.prediction.isNotBlank()) {
                    Caps(stringResource(R.string.your_guess)); Spacer(Modifier.height(4.dp))
                    BasicText(s.prediction, style = StudyType.body(p.text2)); Spacer(Modifier.height(16.dp))
                }
                LoadView(s.explanation, vm::load, lines = 4) { BasicText(it, style = StudyType.prose(p.text)) }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryKey(stringResource(R.string.make_a_card), onMakeCard)
                    Spacer(Modifier.width(8.dp))
                    TextLink(stringResource(R.string.ask_more), onAskMore)
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
    val p = Bb.palette
    StudySheet(onDismiss) {
        Caps(stringResource(R.string.define_caps))
        Spacer(Modifier.height(8.dp))
        BasicText(term.trim(), style = StudyType.display(p.text))
        Spacer(Modifier.height(16.dp))
        LoadView(s.definition, vm::load, lines = 3) { def ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                def.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    val m = Regex("^(\\d+)[.)]\\s*(.*)$").matchEntire(line)
                    if (m != null) {
                        Row {
                            BasicText(m.groupValues[1], Modifier.width(24.dp), style = StudyType.numeral(p.text2, 14f))
                            BasicText(m.groupValues[2], style = StudyType.prose(p.text))
                        }
                    } else BasicText(line, style = StudyType.prose(p.text))
                }
            }
        }
        if (s.savedAsWord) {
            Spacer(Modifier.height(16.dp))
            BasicText(stringResource(R.string.saved_to_words), style = StudyType.caption(p.text2))
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
    val p = Bb.palette
    StudySheet(onDismiss, tall = true) {
        Caps(stringResource(R.string.card_caps))
        Spacer(Modifier.height(12.dp))
        QuoteBlock(text, maxLines = 3)
        Spacer(Modifier.height(24.dp))
        if (s.capReached) {
            BasicText(stringResource(R.string.cap_reached, Study.DAILY_NEW_CARD_CAP), style = StudyType.body(p.text2))
            Spacer(Modifier.height(16.dp))
        } else {
            Caps(stringResource(R.string.suggested_questions)); Spacer(Modifier.height(4.dp))
            when (val sg = s.suggestions) {
                Load.Idle -> Unit
                Load.Loading -> LoadingBlocks(2, Modifier.padding(vertical = 8.dp))
                is Load.Failed -> ErrorNote(sg.error, vm::suggest)
                is Load.Ready -> if (sg.value.isEmpty()) BasicText(stringResource(R.string.no_suggestions), style = StudyType.caption(p.text2), modifier = Modifier.padding(vertical = 12.dp))
                else sg.value.forEach { sug ->
                    ListRow({ vm.choose(sug) }, minHeight = 56.dp) {
                        BasicText(sug.question, Modifier.weight(1f), style = StudyType.quote(if (s.chosen == sug) p.accentText else p.text))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            StudyInput(s.question, vm::setQuestion, label = stringResource(R.string.front_label), placeholder = stringResource(R.string.front_hint), minLines = 2)
            Spacer(Modifier.height(16.dp))
            StudyInput(s.answer, vm::setAnswer, label = stringResource(R.string.your_answer_label), placeholder = stringResource(R.string.your_answer_hint), minLines = 3)
            Spacer(Modifier.height(16.dp))
            when (val c = s.check) {
                Load.Loading -> LoadingBlocks(2)
                is Load.Failed -> ErrorNote(c.error, null)
                is Load.Ready -> Column {
                    BasicText(verdictText(c.value.verdict), style = StudyType.title(p.text))
                    c.value.covered.forEach { CheckLine(it, true) }
                    c.value.missing.forEach { CheckLine(it, false) }
                }
                Load.Idle -> Unit
            }
            if (s.question.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Caps(stringResource(R.string.preview_caps)); Spacer(Modifier.height(8.dp))
                IndexCard(Modifier.fillMaxWidth().heightIn(min = 120.dp), scroll = false) {
                    BasicText(s.question, style = StudyType.cardProse(p.readText))
                }
            }
            Spacer(Modifier.height(24.dp))
            val checked = s.check is Load.Ready || s.check is Load.Failed
            if (!checked) {
                SecondaryKey(
                    stringResource(R.string.check_my_answer), vm::check, Modifier.fillMaxWidth(), enabled = s.canCheck,
                )
                Spacer(Modifier.height(8.dp))
                TextLink(stringResource(R.string.save_without_checking), { vm.save(onSaved) })
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
    val p = Bb.palette
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp).padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Tick(hit, Modifier.padding(top = 3.dp))
        Spacer(Modifier.width(12.dp))
        BasicText(text, Modifier.weight(1f), style = StudyType.body(if (hit) p.text else p.text2))
    }
}

/** Rubric tick: a filled check in `positive` when hit, an empty hairline box when missed. */
@Composable
internal fun Tick(hit: Boolean, modifier: Modifier = Modifier) {
    val p = Bb.palette
    Canvas(modifier.size(18.dp).semantics { contentDescription = if (hit) "covered" else "missed" }) {
        val r = 2.dp.toPx()
        drawRoundRect(if (hit) p.positive else p.hairline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = Stroke(1.5.dp.toPx()))
        if (hit) {
            val w = 1.8.dp.toPx()
            drawLine(p.positive, Offset(size.width * .25f, size.height * .52f), Offset(size.width * .43f, size.height * .70f), w, StrokeCap.Round)
            drawLine(p.positive, Offset(size.width * .43f, size.height * .70f), Offset(size.width * .76f, size.height * .30f), w, StrokeCap.Round)
        }
    }
}

// ----------------------------------------------------------------------------------------- Check-in

@Composable
fun CheckInSheet(deps: StudyDeps, bookId: BookId, sectionId: Long, onDismiss: () -> Unit) {
    val vm = studyViewModel("checkin:$bookId:$sectionId") { CheckInViewModel(bookId, sectionId, deps) }
    val s by vm.state.collectAsState()
    val p = Bb.palette
    StudySheet(onDismiss) {
        Caps(stringResource(R.string.checkin_caps))
        Spacer(Modifier.height(12.dp))
        when (val ci = s.checkIn) {
            Load.Idle, Load.Loading -> LoadingBlocks(2)
            is Load.Failed -> ErrorNote(ci.error, vm::load)
            is Load.Ready -> {
                Slip {
                    BasicText(ci.value.prompt, style = StudyType.cardProse(p.readText))
                }
                Spacer(Modifier.height(16.dp))
                if (s.phase == CheckInPhase.ANSWER) {
                    StudyInput(s.answer, vm::setAnswer, placeholder = stringResource(R.string.checkin_hint), minLines = 3)
                    Spacer(Modifier.height(16.dp))
                    PrimaryKey(stringResource(R.string.reveal), vm::reveal, Modifier.fillMaxWidth(), enabled = !s.checking)
                } else {
                    Caps(stringResource(R.string.the_gist)); Spacer(Modifier.height(8.dp))
                    BasicText(ci.value.gist, style = StudyType.prose(p.text))
                    Spacer(Modifier.height(16.dp))
                    RubricList(ci.value.rubric, s.ticks)
                }
            }
        }
        // Skipping is always one tap, whatever state the sheet is in.
        TextLink(
            stringResource(if (s.phase == CheckInPhase.REVEALED) R.string.done else R.string.skip),
            { onDismiss() }, Modifier.padding(top = 8.dp),
        )
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
    val p = Bb.palette
    StudySheet(onDismiss, tall = true) {
        Caps(stringResource(R.string.recall_caps))
        Spacer(Modifier.height(12.dp))
        when (val ci = s.checkIn) {
            Load.Idle, Load.Loading -> LoadingBlocks(2)
            is Load.Failed -> ErrorNote(ci.error, vm::load)
            is Load.Ready -> when (s.phase) {
                RecallPhase.PREDICT -> {
                    BasicText(stringResource(R.string.recall_predict), style = StudyType.title(p.text))
                    Spacer(Modifier.height(24.dp))
                    BasicText("${s.predicted}%", style = StudyType.bigNumeral(p.text))
                    StudySlider(s.predicted, vm::setPredicted, stringResource(R.string.recall_predict_a11y))
                    Spacer(Modifier.height(24.dp))
                    PrimaryKey(stringResource(R.string.recall_start), vm::startRecall, Modifier.fillMaxWidth())
                }
                RecallPhase.RECALL -> {
                    BasicText(stringResource(R.string.recall_write), style = StudyType.title(p.text))
                    Spacer(Modifier.height(12.dp))
                    StudyInput(s.answer, vm::setAnswer, placeholder = stringResource(R.string.recall_write_hint), minLines = 6, maxLines = 12)
                    if (s.checkFailed) ErrorNote(UiError(UiErrorKind.UNAVAILABLE, true), null)
                    Spacer(Modifier.height(16.dp))
                    PrimaryKey(stringResource(R.string.reveal), vm::finish, Modifier.fillMaxWidth(), enabled = !s.checking)
                }
                RecallPhase.RESULT -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column {
                            Caps(stringResource(R.string.predicted)); BasicText("${s.predicted}%", style = StudyType.bigNumeral(p.text2))
                        }
                        Column {
                            Caps(stringResource(R.string.recalled)); BasicText("${s.actual}%", style = StudyType.bigNumeral(p.text))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Hairline()
                    Spacer(Modifier.height(16.dp))
                    BasicText(ci.value.gist, style = StudyType.prose(p.text))
                    Spacer(Modifier.height(16.dp))
                    RubricList(ci.value.rubric, s.ticks)
                    Spacer(Modifier.height(24.dp))
                    PrimaryKey(stringResource(R.string.done), onDismiss, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 48 dp touch height, 2 dp track, accent fill, 4x20 `key` thumb (the Scrubber of the brief). */
@Composable
internal fun StudySlider(value: Int, onChange: (Int) -> Unit, description: String, modifier: Modifier = Modifier) {
    val p = Bb.palette
    var width by remember { mutableStateOf(1) }
    Box(
        modifier.fillMaxWidth().height(48.dp)
            .semantics { contentDescription = description }
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / width * 100).toInt().coerceIn(0, 100)) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> onChange((change.position.x / width * 100).toInt().coerceIn(0, 100)) } }
            .drawBehind {
                val cy = size.height / 2
                drawRect(p.hairline, Offset(0f, cy - 1.dp.toPx()), Size(size.width, 2.dp.toPx()))
                val x = size.width * value / 100f
                drawRect(p.accent, Offset(0f, cy - 1.dp.toPx()), Size(x, 2.dp.toPx()))
                drawRoundRect(p.key, Offset(x - 2.dp.toPx(), cy - 10.dp.toPx()), Size(4.dp.toPx(), 20.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
            },
    )
}
