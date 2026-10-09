package app.kepler.engine.ai

import app.kepler.core.model.AiBudget
import app.kepler.core.model.AiCacheKeys
import app.kepler.core.model.AiService
import app.kepler.core.model.AiStore
import app.kepler.core.model.AnswerCheck
import app.kepler.core.model.AskAnswer
import app.kepler.core.model.Book
import app.kepler.core.model.BookId
import app.kepler.core.model.CardSuggestion
import app.kepler.core.model.CheckIn
import app.kepler.core.model.Citation
import app.kepler.core.model.ConceptEdge
import app.kepler.core.model.ConceptMap
import app.kepler.core.model.ConceptNode
import app.kepler.core.model.LibraryRepository
import app.kepler.core.model.PositionRepository
import app.kepler.core.model.Section
import app.kepler.core.model.SectionKind
import app.kepler.core.model.Sentence
import app.kepler.core.model.SettingsStore
import app.kepler.core.model.SpineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * OpenRouter-backed [AiService]. Every method asks [FenceService] for the highest allowed ordinal first and only
 * then fetches text through [ContextBuilder], so nothing past the fence can reach a prompt. Failures surface as
 * [AiError] subclasses, never raw exceptions.
 *
 * Besides [AiService], the UI/workers use [buildPack] (prefetch), [freeModels] (model picker) and [keyInfo].
 */
class OpenRouterAiService(
    private val settings: SettingsStore,
    private val spine: SpineRepository,
    private val positions: PositionRepository,
    private val aiStore: AiStore,
    private val library: LibraryRepository,
    client: OpenRouterClient = OpenRouterClient(),
    clock: () -> Long = System::currentTimeMillis,
    modeFor: suspend (BookId) -> FenceMode? = { null },
    /** Strict mode: title and author are left out of prompts to reduce leakage from the model's own knowledge. */
    private val strict: Boolean = false,
    private val budgeter: RequestBudgeter = RequestBudgeter(aiStore, clock = clock),
    private val catalog: ModelCatalog = ModelCatalog(client, aiStore, clock),
) : AiService {
    private val fence = FenceService(positions, library, spine, modeFor)
    private val context = ContextBuilder(spine)
    private val runner = LlmRunner(client, catalog, budgeter, settings)
    private val packs = ChapterPackService(runner, aiStore, includeBookMeta = !strict)

    // ------------------------------------------------------------------ public extras

    /** The model that served the most recent successful request (for a "answered by" line in the UI). */
    val lastModelUsed: String? get() = runner.lastModelUsed

    /** Free models eligible for the model picker, best first. */
    suspend fun freeModels(): List<ModelInfo> = guard {
        ModelRanker.rank(catalog.all(requireKey()))
    }

    suspend fun keyInfo(): KeyInfo? = guard { catalog.keyInfo(requireKey()) }

    /** Builds (or returns the cached) chapter pack for a section at the current fence. Used for prefetching. */
    suspend fun buildPack(bookId: BookId, sectionId: Long, priority: Priority = Priority.PREFETCH): PackResult = guard {
        val book = book(bookId)
        packFor(book, section(bookId, sectionId), FenceFeature.TAKEAWAYS, priority)
    }

    // ------------------------------------------------------------------ AiService

    override suspend fun budget(): AiBudget = guard { runner.budget() }

    override suspend fun explain(bookId: BookId, ordinal: Int, selection: String): String = guard {
        val book = book(bookId)
        val ctx = passageContext(bookId, ordinal, FenceFeature.EXPLAIN, before = 12, after = 3)
        val sel = selection.trim().take(2000)
        val prompt = PromptRegistry.EXPLAIN
        stripOrdinalMarkers(cachedText(bookId, "explain", prompt, ctx.cacheKey, sel) {
            runner.text(Priority.INTERACTIVE, prompt.system, buildString {
                bookLine(book)
                appendLine("Passages:"); appendLine(ctx.rendered)
                appendLine(); appendLine("Selection to explain: \"$sel\"")
            })
        })
    }

    override suspend fun define(bookId: BookId, ordinal: Int, term: String): String = guard {
        val book = book(bookId)
        val ctx = passageContext(bookId, ordinal, FenceFeature.DEFINE, before = 6, after = 1)
        val t = term.trim().take(200)
        val prompt = PromptRegistry.DEFINE
        stripOrdinalMarkers(cachedText(bookId, "define", prompt, ctx.cacheKey, t.lowercase()) {
            runner.text(Priority.INTERACTIVE, prompt.system, buildString {
                bookLine(book)
                appendLine("Passages:"); appendLine(ctx.rendered)
                appendLine(); appendLine("Term to define: \"$t\"")
            }, maxTokens = 400)
        })
    }

    override suspend fun ask(bookId: BookId, question: String): AskAnswer = guard {
        val book = book(bookId)
        val q = question.trim().take(1000)
        val max = fence.maxOrdinal(bookId, FenceFeature.ASK)
        if (max < 0) throw AiError.NothingRead()
        val prompt = PromptRegistry.ASK
        val cacheKey = AiCacheKeys.scope(bookId, sha256("ask", prompt.tag, bookId.toString(), q.lowercase(), max.toString()))
        aiStore.get("ask", cacheKey)?.let { raw ->
            runCatching { AiJson.decodeFromString(StoredAnswer.serializer(), raw) }.getOrNull()?.let { return@guard it.toAnswer() }
        }
        val hits = retrieve(bookId, q, max)
        val sections = spine.sections(bookId)
        val sectionsByOrdinal = { o: Int -> sections.firstOrNull { o in it.startOrdinal..it.endOrdinal } }
        val user = buildString {
            bookLine(book)
            // Cached pack gists of the sections that were hit: free context, already fence-clipped.
            val seen = LinkedHashSet<Long>()
            for (s in hits.map { it.sectionId }.distinct()) {
                val sec = sections.firstOrNull { it.id == s } ?: continue
                val end = minOf(sec.endOrdinal, max)
                packs.cachedFor(book, sec, end)?.let {
                    if (seen.add(sec.id) && it.pack.gist.isNotBlank()) appendLine("Summary of \"${sec.title}\": ${it.pack.gist}")
                }
            }
            appendLine("Passages:")
            appendLine(context.render(hits))
            appendLine(); appendLine("Question: $q")
        }
        val allowed = hits.map { it.ordinal }.toSet()
        val raw = runner.text(Priority.INTERACTIVE, prompt.system, user, maxTokens = 900)
        val answer = buildAnswer(raw, allowed, max) { o -> sectionsByOrdinal(o)?.title.orEmpty() }
        aiStore.put("ask", cacheKey, AiJson.encodeToString(StoredAnswer.serializer(), StoredAnswer.of(answer)))
        answer
    }

    override suspend fun recap(bookId: BookId): String = guard {
        val book = book(bookId)
        val max = fence.maxOrdinal(bookId, FenceFeature.RECAP)
        if (max < 0) throw AiError.NothingRead()
        val body = spine.sections(bookId)
            .filter { it.included && it.kind == SectionKind.BODY && it.startOrdinal <= max }
            .sortedBy { it.startOrdinal }
        if (body.isEmpty()) throw AiError.NothingRead()
        val recent = body.takeLast(RECAP_SECTIONS)
        val lines = ArrayList<String>()
        var builds = 0
        for (sec in recent) {
            val end = minOf(sec.endOrdinal, max)
            var result = packs.cachedFor(book, sec, end)
            val partial = end < sec.endOrdinal
            // Missing summaries are built for the current partial section and, sparingly, for finished ones.
            if (result == null && (partial || builds < MAX_RECAP_BUILDS)) {
                val text = context.sectionText(bookId, sec, max)
                if (text != null) {
                    result = packs.obtain(book, text, Priority.INTERACTIVE)
                    if (!partial) builds++
                }
            }
            val summary = result?.pack?.recapSummary?.trim().orEmpty()
            if (summary.isNotEmpty()) lines += "${sec.title.ifBlank { "Section ${sec.index + 1}" }}: $summary"
        }
        if (lines.isEmpty()) throw AiError.InvalidResponse("no summaries available yet")
        lines.joinToString("\n\n")
    }

    override suspend fun takeaways(bookId: BookId, sectionId: Long): List<String> = guard {
        val book = book(bookId)
        packFor(book, section(bookId, sectionId), FenceFeature.TAKEAWAYS, Priority.INTERACTIVE).pack.takeaways
    }

    override suspend fun concepts(bookId: BookId): ConceptMap = guard {
        val book = book(bookId)
        val max = fence.maxOrdinal(bookId, FenceFeature.CONCEPTS)
        if (max < 0) throw AiError.NothingRead()
        val sections = spine.sections(bookId).filter { it.included && it.startOrdinal <= max }.sortedBy { it.startOrdinal }
        var found = sections.mapNotNull { packs.cachedFor(book, it, minOf(it.endOrdinal, max)) }
        if (found.isEmpty()) {
            // Nothing cached yet: build the pack for the section being read so the map is never empty for no reason.
            val current = sections.lastOrNull { it.kind == SectionKind.BODY } ?: sections.lastOrNull()
            val text = current?.let { context.sectionText(bookId, it, max) }
            if (text != null) found = listOf(packs.obtain(book, text, Priority.INTERACTIVE))
        }
        buildConceptMap(found.map { it.pack }, max)
    }

    override suspend fun suggestCards(bookId: BookId, sectionId: Long): List<CardSuggestion> = guard {
        val book = book(bookId)
        val pack = packFor(book, section(bookId, sectionId), FenceFeature.CARDS, Priority.INTERACTIVE).pack
        pack.cardCandidates.map {
            CardSuggestion(it.q.trim(), it.sourceOrdinals.min(), it.sourceOrdinals.max(), it.hint.trim())
        }
    }

    override suspend fun checkAnswer(question: String, userAnswer: String, sourceText: String): AnswerCheck = guard {
        val spec = JsonSpec(
            "answer_check", Schemas.answerCheck, AnswerCheckDto.serializer(),
            validate = { if (it.verdict !in VERDICTS) "verdict must be correct, partial or incorrect" else null },
        )
        val user = "Reference text:\n${sourceText.trim().take(6000)}\n\nQuestion: ${question.trim().take(500)}\n\n" +
            "Learner's answer: ${userAnswer.trim().take(1500)}"
        val dto = runner.json(Priority.INTERACTIVE, spec, PromptRegistry.CHECK_ANSWER.system, user, maxTokens = 600)
        AnswerCheck(dto.verdict, dto.covered.filter { it.isNotBlank() }, dto.missing.filter { it.isNotBlank() })
    }

    override suspend fun checkIn(bookId: BookId, sectionId: Long): CheckIn = guard {
        val book = book(bookId)
        val pack = packFor(book, section(bookId, sectionId), FenceFeature.CHECK_IN, Priority.INTERACTIVE).pack
        val c = pack.checkIn
        CheckIn(
            prompt = c.prompt.ifBlank { "In a sentence or two, what was this section about?" },
            gist = c.gist.ifBlank { pack.gist },
            rubric = c.rubric.ifEmpty { pack.keyIdeas.flatMap { it.rubric }.take(4) },
        )
    }

    // ------------------------------------------------------------------ internals

    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: AiError) {
        throw e
    } catch (e: Exception) {
        throw AiError.Unexpected(e)
    }

    private suspend fun requireKey(): String =
        settings.openRouterKey()?.takeIf { it.isNotBlank() } ?: throw AiError.MissingKey()

    private suspend fun book(id: BookId): Book = library.book(id).first() ?: throw AiError.BookNotFound()

    private suspend fun section(bookId: BookId, sectionId: Long): Section =
        spine.sections(bookId).firstOrNull { it.id == sectionId && it.included } ?: throw AiError.SectionUnavailable()

    private suspend fun packFor(book: Book, section: Section, feature: FenceFeature, priority: Priority): PackResult {
        val max = fence.maxOrdinal(book.id, feature)
        if (max < 0) throw AiError.NothingRead()
        val text = context.sectionText(book.id, section, max) ?: throw AiError.SectionNotReached()
        return packs.obtain(book, text, priority)
    }

    private class PassageContext(val rendered: String, val cacheKey: String)

    /** Sentences around `ordinal`, never beyond the fence. With nothing readable yet the context is simply empty. */
    private suspend fun passageContext(bookId: BookId, ordinal: Int, feature: FenceFeature, before: Int, after: Int): PassageContext {
        // The selected sentence is on the reader's screen, so it is never a spoiler even when the
        // consumed frontier lags behind (a jump, or pages turned faster than the reading dwell).
        // Nothing after it is let through.
        val max = maxOf(fence.maxOrdinal(bookId, feature), ordinal)
        val window = context.window(bookId, ordinal, before, after, max)
        val rendered = context.render(window)
        return PassageContext(rendered, sha256(bookId.toString(), ordinal.toString(), rendered))
    }

    private suspend fun cachedText(bookId: BookId, kind: String, prompt: PromptRegistry.Prompt, ctxKey: String, subject: String, produce: suspend () -> String): String {
        val key = AiCacheKeys.scope(bookId, sha256(kind, prompt.tag, ctxKey, subject))
        aiStore.get(kind, key)?.let { return it }
        return produce().also { aiStore.put(kind, key, it) }
    }

    private fun StringBuilder.bookLine(book: Book) {
        if (!strict) appendLine("Book: ${book.title} by ${book.authors}")
    }

    /** Fenced retrieval: keyword searches (the FTS is AND-only, so also per keyword), merged and ranked, each hit with a little context. */
    private suspend fun retrieve(bookId: BookId, question: String, max: Int): List<Sentence> {
        val words = keywords(question)
        val scored = LinkedHashMap<Int, Pair<Sentence, Int>>()
        fun add(list: List<Sentence>, bonus: Int) {
            for (s in list) {
                val lower = s.text.lowercase()
                val score = words.count { lower.contains(it) } * 2 + bonus
                val prev = scored[s.ordinal]
                if (prev == null || prev.second < score) scored[s.ordinal] = s to score
            }
        }
        if (words.size > 1) add(context.search(bookId, words.joinToString(" "), max, 10), 3)
        for (w in words.take(6)) add(context.search(bookId, w, max, 10), 0)
        val top = scored.values.sortedWith(compareByDescending<Pair<Sentence, Int>> { it.second }.thenBy { it.first.ordinal })
            .take(MAX_HITS).map { it.first }
        // Pull one neighbour either side, then re-clamp: the ContextBuilder drops anything past the fence.
        val picked = sortedMapOf<Int, Sentence>()
        for (h in top) {
            for (s in context.window(bookId, h.ordinal, 1, 1, max)) picked[s.ordinal] = s
        }
        if (picked.isEmpty()) {
            // No keyword hit (e.g. a general question): fall back to the most recent text read.
            for (s in context.window(bookId, max, 14, 0, max)) picked[s.ordinal] = s
        }
        var chars = 0
        return picked.values.takeWhile { chars += it.text.length; chars <= MAX_CONTEXT_CHARS }
    }

    private fun keywords(q: String): List<String> =
        q.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }.distinct()

    private fun buildAnswer(raw: String, allowed: Set<Int>, max: Int, label: (Int) -> String): AskAnswer {
        val cited = LinkedHashSet<Int>()
        // Models sometimes write [s123], [123, 124] or [123-125]; normalise, keep only what was really provided.
        val text = CITATION.replace(raw) { m ->
            val nums = Regex("\\d+").findAll(m.groupValues[1]).mapNotNull { it.value.toIntOrNull() }.toList()
            val ok = nums.filter { it in allowed && it <= max }
            cited += ok
            ok.joinToString("") { "[$it]" }
        }.replace(Regex("[ \\t]{2,}"), " ").trim()
        return AskAnswer(text, cited.map { Citation(it, label(it)) })
    }

    private fun buildConceptMap(packs: List<ChapterPack>, max: Int): ConceptMap {
        val nodes = LinkedHashMap<String, ConceptNode>()
        val byName = HashMap<String, String>()
        fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        for (e in packs.flatMap { it.entities }) {
            if (e.firstOrdinal !in 0..max || e.name.isBlank()) continue
            val names = (listOf(e.name) + e.aliases).map(::norm).filter { it.isNotEmpty() }
            val existing = names.firstNotNullOfOrNull { byName[it] }
            if (existing != null) {
                val old = nodes.getValue(existing)
                nodes[existing] = old.copy(
                    firstOrdinal = minOf(old.firstOrdinal, e.firstOrdinal),
                    description = old.description.ifBlank { e.definition.trim() },
                )
                names.forEach { byName.putIfAbsent(it, existing) }
            } else {
                val id = norm(e.name).replace(' ', '-').ifEmpty { "node-${nodes.size}" }.let { if (it in nodes) "$it-${nodes.size}" else it }
                nodes[id] = ConceptNode(id, e.name.trim(), e.kind.trim().lowercase(), e.definition.trim(), e.firstOrdinal)
                names.forEach { byName.putIfAbsent(it, id) }
            }
        }
        val edges = LinkedHashMap<Triple<String, String, String>, ConceptEdge>()
        for (e in packs.flatMap { it.edges }) {
            if (e.evidenceOrdinal !in 0..max) continue
            val from = byName[norm(e.from)] ?: continue
            val to = byName[norm(e.to)] ?: continue
            if (from == to) continue
            val rel = e.relation.trim().lowercase()
            edges.putIfAbsent(Triple(from, to, rel), ConceptEdge(from, to, rel, e.evidenceOrdinal))
        }
        return ConceptMap(nodes.values.toList(), edges.values.toList())
    }

    @Serializable
    private class StoredAnswer(val text: String, val ordinals: List<Int>, val labels: List<String>) {
        fun toAnswer() = AskAnswer(text, ordinals.zip(labels).map { Citation(it.first, it.second) })
        companion object {
            fun of(a: AskAnswer) = StoredAnswer(a.text, a.citations.map { it.ordinal }, a.citations.map { it.label })
        }
    }

    @Serializable
    internal class AnswerCheckDto(
        val verdict: String = "", val covered: List<String> = emptyList(), val missing: List<String> = emptyList(),
    )

    private companion object {
        const val RECAP_SECTIONS = 6
        const val MAX_RECAP_BUILDS = 2
        const val MAX_HITS = 8
        const val MAX_CONTEXT_CHARS = 14_000
        val VERDICTS = setOf("correct", "partial", "incorrect")
        val CITATION = Regex("\\[\\s*s?(\\d+(?:\\s*[,;\\-–]\\s*s?\\d+)*)\\s*]")
        val STOPWORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "her", "was", "one", "our", "out",
            "what", "when", "where", "which", "who", "whom", "why", "how", "does", "did", "this", "that", "these",
            "those", "with", "from", "about", "into", "than", "then", "there", "their", "they", "have", "has", "had",
            "will", "would", "could", "should", "his", "she", "him", "its", "tell", "explain", "mean", "means",
        )
    }
}

/**
 * Explain and Define show prose, not citations, but models still echo the passage tags
 * ("(see [145]-[147])", "[148]"). Remove them and tidy the gaps they leave.
 */
internal fun stripOrdinalMarkers(text: String): String =
    text.replace(Regex("""\s*\((?:see\s+)?\[\d+](?:\s*[-–,]\s*\[\d+])*\)"""), "")
        .replace(Regex("""\s*\[\d+](?:\s*[-–,]\s*\[\d+])*"""), "")
        .replace(Regex("[ \\t]+([.,;:])"), "$1")
        .trim()
