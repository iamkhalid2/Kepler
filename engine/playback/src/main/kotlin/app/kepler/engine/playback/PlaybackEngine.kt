@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.kepler.engine.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import app.kepler.core.model.AudioSegment
import app.kepler.core.model.Book
import app.kepler.core.model.BookId
import app.kepler.core.model.NarrationConfig
import app.kepler.core.model.NarrationBlockedException
import app.kepler.core.model.NarrationState
import app.kepler.core.model.PlaybackController
import app.kepler.core.model.PlaybackInfo
import app.kepler.core.model.PlaybackInfoSource
import app.kepler.core.model.PlaybackState
import app.kepler.core.model.PositionKind
import app.kepler.core.model.VoiceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.roundToInt

/**
 * The playback brain. One ExoPlayer plays the book's cached narration segments in order with silence
 * between paragraphs; this class maps its position to a sentence ordinal, keeps audio coming
 * (just-in-time narration), and records what was listened to. All calls happen on the application looper.
 */
class PlaybackEngine(
    context: Context,
    private val deps: PlaybackDependencies,
    looper: Looper = Looper.getMainLooper(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val gaps: GapPolicy = GapPolicy(),
    persistContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO.limitedParallelism(1),
) : ReadAlongHost, PlaybackController, PlaybackInfoSource {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistScope = CoroutineScope(SupervisorJob() + persistContext)
    private val hub = PlaybackHub

    private val inner: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLooper(looper)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
    private val sourceFactory = ProgressiveMediaSource.Factory(
        FileDataSource.Factory(), DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
    )

    override val state: StateFlow<PlaybackState> get() = hub.stateFlow
    override val info: StateFlow<PlaybackInfo> get() = hub.infoFlow

    /** Set by the service: tells the Media3 player that notification-visible state changed. */
    var onExternalChange: () -> Unit = {}

    // ---- session ----
    private var bookId: BookId? = null
    private var book: Book? = null
    private var sections = SectionIndex(emptyList(), 0, "")
    private var plan = PlaybackPlan.EMPTY
    private val lookups = HashMap<Long, SyncLookup>()
    private val paragraphs = HashMap<Int, Pair<Int, Long>>()
    private var voice: VoiceRef = VoiceRef("system", "default", "System voice")
    private var activeSegments: List<AudioSegment> = emptyList()
    private var msPerSentence = BookTimeline.DEFAULT_MS_PER_SENTENCE
    private var sentenceCount = 0
    private var generation = 0

    private var ordinal = 0
    private var intendedPlay = false
    private var buffering = false
    private var ended = false
    private var error: String? = null
    private var narrationRunning = false
    private var ensuring = false
    private var nextEnsureAt = 0L
    private var pausedAt = 0L
    private var errorCount = 0
    private var speed = 1f

    private var chapterId: Long? = null
    private var chapterTitle: String? = null
    private var spanIndex = -1
    private var span: LongRange = 0L..0L
    private var aheadMs = 0L
    private var lastAheadCheck = 0L

    private val consume = ConsumeTracker()
    private var lastSavedOrdinal = -1
    private var lastSaveAt = 0L

    private val sleep = SleepTimer()
    private var sleepChapterId: Long? = null
    private var sleepRemainingMs: Long? = null
    private var expiredAt = 0L
    private val shake = ShakeListener(appContext) { onShake() }

    private var released = false
    private var sessionJob: Job? = null
    private var observeJob: Job? = null
    private var ticker: Job? = null
    private val extendMutex = Mutex()

    init {
        inner.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onInnerEnded()
                if (playbackState == Player.STATE_READY) errorCount = 0
                publish(true)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker() else flushProgress()
                publish(true)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && plan.size > 0) {
                    intendedPlay = false
                    pausedAt = clock()
                    flushProgress()
                } else if (playWhenReady) intendedPlay = true
                publish(true)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                tick()
                if (plan.size - inner.currentMediaItemIndex <= EXTEND_BELOW) scope.launch { extend() }
            }

            override fun onPlayerError(e: PlaybackException) = onInnerError()
        })
        scope.launch {
            val s = runCatching { deps.settings.settings.first() }.getOrNull() ?: return@launch
            applySpeed(s.speed)
            publish(false)
        }
    }

    // ================= PlaybackController =================

    override fun play(bookId: BookId, fromOrdinal: Int?) {
        if (bookId == this.bookId && plan.size > 0 && error == null && !ended) {
            if (fromOrdinal != null) seekTo(fromOrdinal, play = true) else resume()
            return
        }
        sessionJob?.cancel()
        sessionJob = scope.launch { open(bookId, fromOrdinal) }
    }

    override fun pause() {
        intendedPlay = false
        inner.pause()
        flushProgress()
        publish(true)
    }

    override fun resume() {
        val id = bookId ?: return
        intendedPlay = true
        when {
            plan.size == 0 -> play(id, ordinal)
            ended -> {
                val from = sections.chapterAt(ordinal)?.startOrdinal ?: sections.firstNarrated
                play(id, from)
            }
            else -> {
                smartRewind()
                pausedAt = 0
                inner.play()
                startTicker()
            }
        }
        publish(true)
    }

    override fun seekToSentence(ordinal: Int) = seekTo(ordinal, intendedPlay)

    private fun seekTo(ordinal: Int, play: Boolean) {
        val id = bookId ?: return
        val target = sections.firstNarratedFrom(ordinal.coerceIn(0, maxOf(sections.lastNarrated, 0))) ?: sections.lastNarrated
        if (seekInPlan(target)) {
            if (play) { intendedPlay = true; inner.play(); startTicker(); publish(true) }
        } else {
            inner.pause()
            sessionJob?.cancel()
            sessionJob = scope.launch { loadAt(id, target, play) }
        }
    }

    override fun skipSentences(delta: Int) {
        if (bookId == null || plan.size == 0) return
        var d = delta
        val item = plan.items.getOrNull(inner.currentMediaItemIndex)
        if (d == -1) {
            // "Previous" replays the current sentence unless we are right at its start.
            if (item is PlanItem.Silence || positionInSentenceMs() > RESTART_SENTENCE_AFTER_MS) d = 0
        }
        seekToSentence(sections.step(ordinal, d) ?: return)
    }

    override fun seekByMs(deltaMs: Long) {
        if (bookId == null || plan.size == 0) return
        val cur = plan.globalMs(inner.currentMediaItemIndex, inner.currentPosition)
        val target = cur + deltaMs
        val allLoaded = plan.lastOrdinal >= sections.lastNarrated
        when {
            target in 0..plan.totalMs -> seekGlobal(target)
            target > plan.totalMs && allLoaded -> seekGlobal(plan.totalMs)
            target < 0 && plan.items.firstOrNull().let { it is PlanItem.Audio && it.segment.startOrdinal <= sections.firstNarrated } ->
                seekGlobal(0)
            else -> seekToSentence(sections.step(ordinal, (deltaMs / msPerSentence).roundToInt()) ?: return)
        }
    }

    override fun setSpeed(speed: Float) {
        applySpeed(speed)
        val chosen = this.speed
        persistScope.launch { runCatching { deps.settings.update { it.copy(speed = chosen) } } }
        publish(true)
    }

    override fun setSleepTimer(minutes: Int) {
        sleep.set(minutes, clock())
        inner.volume = 1f
        sleepChapterId = chapterId
        sleepRemainingMs = null
        if (minutes != 0) shake.start() else shake.stop()
        tick()
        publish(true)
    }

    // ================= ReadAlongHost =================

    override fun externalState(): ExternalState {
        val id = bookId ?: return ExternalState()
        val b = book
        val playbackState = when {
            plan.size == 0 -> if (error != null) Player.STATE_IDLE else Player.STATE_BUFFERING
            ended -> Player.STATE_ENDED
            buffering -> Player.STATE_BUFFERING
            else -> when (inner.playbackState) {
                Player.STATE_IDLE -> Player.STATE_IDLE
                Player.STATE_READY -> Player.STATE_READY
                else -> Player.STATE_BUFFERING
            }
        }
        val (pos, dur) = sectionPosition()
        return ExternalState(
            mediaId = "book:$id:${chapterId ?: 0}",
            title = b?.title,
            subtitle = chapterTitle,
            artworkUri = b?.coverPath?.let { Uri.fromFile(File(it)) },
            durationMs = dur,
            positionMs = pos,
            playWhenReady = intendedPlay && !ended,
            playbackState = playbackState,
            speed = speed,
            error = error,
        )
    }

    override fun onPlayWhenReady(play: Boolean) = if (play) resume() else pause()
    override fun onSeekInSection(positionMs: Long) {
        if (plan.size > 0) seekGlobal(span.first + positionMs)
    }
    override fun onSkipSentences(delta: Int) = skipSentences(delta)
    override fun onSeekByMs(deltaMs: Long) = seekByMs(deltaMs)
    override fun onSetSpeed(speed: Float) = setSpeed(speed)
    override fun onStop() = pause()
    override fun onRelease() = release()
    override fun onPlayMediaId(mediaId: String) {
        val id = mediaId.removePrefix("book:").substringBefore(':').toLongOrNull() ?: return
        play(id, null)
    }

    /** Headset long-press: hands the sentence being spoken to the app's highlight action. */
    fun highlightCurrent() {
        val id = bookId ?: return
        deps.onHighlightGesture?.invoke(id, ordinal)
    }

    fun release() {
        if (released) return
        released = true
        flushProgress()
        ticker?.cancel()
        shake.stop()
        scope.cancel()
        runCatching { inner.release() }
        hub.stateFlow.value = hub.stateFlow.value.copy(playing = false, buffering = false)
    }

    // ================= session loading =================

    private suspend fun open(id: BookId, from: Int?) {
        if (bookId != null) flushProgress()
        generation++
        resetPlayer()
        bookId = id
        ended = false
        error = null
        buffering = true
        intendedPlay = true
        ensuring = false
        publish(true)

        val b = deps.library.book(id).first()
        if (b == null) { fail("This book can't be found."); return }
        book = b
        val count = deps.spine.sentenceCount(id).takeIf { it > 0 } ?: b.sentenceCount
        sentenceCount = count
        sections = SectionIndex(deps.spine.sections(id), count, b.title)
        val s = deps.settings.settings.first()
        applySpeed(s.speed)
        val all = deps.audio.segments(id).first()
        voice = s.voice ?: all.maxByOrNull { it.id }?.voice ?: voice
        updateAverage(SegmentPicker.active(all, voice))

        val saved = from
            ?: deps.positions.position(id, PositionKind.LISTENING).first()?.ordinal
            ?: deps.positions.position(id, PositionKind.READING).first()?.ordinal
            ?: 0
        val start = sections.firstNarratedFrom(saved) ?: sections.firstNarrated
        observe(id)
        loadAt(id, start, autoplay = true)
    }

    private fun resetPlayer() {
        ticker?.cancel()
        observeJob?.cancel()
        inner.stop()
        inner.clearMediaItems()
        plan = PlaybackPlan.EMPTY
        lookups.clear()
        paragraphs.clear()
        spanIndex = -1
        chapterId = null
        chapterTitle = null
        aheadMs = 0
    }

    private fun observe(id: BookId) {
        observeJob?.cancel()
        observeJob = scope.launch {
            launch {
                deps.audio.segments(id).collect { all ->
                    activeSegments = SegmentPicker.active(all, voice)
                    updateAverage(activeSegments)
                    extend()
                }
            }
            launch {
                deps.narration.state(id).collect { st ->
                    narrationRunning = st is NarrationState.Running
                    publish(false)
                }
            }
        }
    }

    /** Opens the plan at [target]. Generates audio first when none exists there. */
    private suspend fun loadAt(id: BookId, target: Int, autoplay: Boolean) {
        val gen = ++generation
        var segs = SegmentPicker.active(deps.audio.segments(id).first(), voice)
        var seg = segs.firstOrNull { target in it.startOrdinal..it.endOrdinal }
        if (seg == null) {
            buffering = true
            ensuring = true
            intendedPlay = autoplay
            ordinal = target
            publish(true)
            try {
                deps.narration.ensureAhead(id, NarrationConfig(voice), target, AHEAD_MINUTES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NarrationBlockedException) {
                ensuring = false
                fail("$NOT_READY ${e.message}")
                return
            } catch (e: Exception) {
                ensuring = false
                fail(e.message?.takeIf { it.isNotBlank() } ?: NOT_READY)
                return
            }
            ensuring = false
            if (gen != generation) return
            segs = SegmentPicker.active(deps.audio.segments(id).first(), voice)
            seg = segs.firstOrNull { target in it.startOrdinal..it.endOrdinal }
            if (seg == null) { fail(NOT_READY); return }
        }
        activeSegments = segs
        val run = listOf(seg) + contiguousRun(segs, seg.endOrdinal + 1, INITIAL_RUN - 1)
        val items = buildItems(null, run)
        if (gen != generation) return

        plan = PlaybackPlan.EMPTY + items
        spanIndex = -1
        val index = plan.indexOfOrdinal(target)
        val offset = lookupFor(seg).startMs(target) ?: 0L
        inner.setMediaSources(items.map(::source), index, offset)
        ordinal = target
        consume.onSeek(target)
        buffering = false
        ended = false
        error = null
        intendedPlay = autoplay
        inner.playWhenReady = autoplay
        inner.prepare()
        if (autoplay) startTicker()
        tick()
        publish(true)
    }

    private fun fail(message: String) {
        error = message
        buffering = false
        intendedPlay = false
        publish(true)
    }

    // ================= plan building =================

    private fun contiguousRun(segs: List<AudioSegment>, afterOrdinal: Int, limit: Int): List<AudioSegment> {
        val out = ArrayList<AudioSegment>()
        var expected = sections.firstNarratedFrom(afterOrdinal) ?: return out
        var i = segs.binarySearchBy(expected) { it.startOrdinal }
        if (i < 0) return out
        while (i < segs.size && out.size < limit && segs[i].startOrdinal == expected) {
            out += segs[i]
            expected = sections.firstNarratedFrom(segs[i].endOrdinal + 1) ?: break
            i++
        }
        return out
    }

    private suspend fun buildItems(previous: AudioSegment?, run: List<AudioSegment>): List<PlanItem> {
        val id = bookId ?: return emptyList()
        if (run.isEmpty()) return emptyList()
        val rows = deps.audio.syncRows(id, run.first().startOrdinal, run.last().endOrdinal).groupBy { it.segmentId }
        for (s in run) lookups[s.id] = rows[s.id]?.takeIf { it.isNotEmpty() }?.let(SyncLookup::of) ?: SyncLookup.uniform(s)
        var prev = previous
        for (s in run) {
            if (prev != null) {
                for (o in intArrayOf(prev.endOrdinal, s.startOrdinal)) {
                    if (o !in paragraphs) deps.spine.sentence(id, o)?.let { paragraphs[o] = it.paragraphIndex to it.sectionId }
                }
            }
            prev = s
        }
        return PlaybackPlan.itemsFor(previous, run, gaps) { a, b ->
            val pa = paragraphs[a]
            val pb = paragraphs[b]
            when {
                pa == null || pb == null -> Boundary.PARAGRAPH
                pa.second != pb.second -> Boundary.SECTION
                pa.first == pb.first -> Boundary.SAME_PARAGRAPH
                else -> Boundary.PARAGRAPH
            }
        }
    }

    private fun source(item: PlanItem): MediaSource = when (item) {
        is PlanItem.Audio -> sourceFactory.createMediaSource(MediaItem.fromUri(Uri.fromFile(File(item.segment.filePath))))
        is PlanItem.Silence -> SilenceMediaSource(item.durationMs * 1000)
    }

    /** Appends freshly generated audio that continues the plan. */
    private suspend fun extend() = extendMutex.withLock {
        val id = bookId ?: return
        val last = plan.lastSegment ?: return
        if (plan.size - inner.currentMediaItemIndex > MAX_ITEMS_AHEAD) return
        val run = contiguousRun(activeSegments, last.endOrdinal + 1, EXTEND_RUN)
        if (run.isEmpty()) return
        val gen = generation
        val items = buildItems(last, run)
        if (gen != generation || id != bookId) return
        val firstNew = plan.size
        plan = plan + items
        inner.addMediaSources(items.map(::source))
        if (inner.playbackState == Player.STATE_ENDED || ended) {
            ended = false
            inner.seekTo(firstNew, 0)
            inner.prepare()
        }
        buffering = false
        publish(true)
    }

    private fun lookupFor(segment: AudioSegment): SyncLookup =
        lookups.getOrPut(segment.id) { SyncLookup.uniform(segment) }

    private fun updateAverage(segments: List<AudioSegment>) {
        val covered = segments.sumOf { it.endOrdinal - it.startOrdinal + 1 }
        msPerSentence = BookTimeline.msPerSentence(segments.sumOf { it.durationMs }, covered)
    }

    // ================= seeking =================

    private fun seekInPlan(target: Int): Boolean {
        val index = plan.indexOfOrdinal(target)
        if (index < 0) return false
        val seg = (plan.items[index] as PlanItem.Audio).segment
        inner.seekTo(index, lookupFor(seg).startMs(target) ?: 0L)
        jumpedTo(target)
        return true
    }

    private fun seekGlobal(globalMs: Long) {
        val (index, offset) = plan.locate(globalMs.coerceAtLeast(0))
        inner.seekTo(index, offset)
        jumpedTo(plan.ordinalAt(index, offset, ::lookupFor))
    }

    private fun jumpedTo(target: Int) {
        pausedAt = 0
        ordinal = target
        consume.onSeek(target)
        flushProgress()
        tick()
        publish(true)
    }

    private fun smartRewind() {
        if (plan.size == 0 || pausedAt == 0L) return
        val index = inner.currentMediaItemIndex
        val item = plan.items.getOrNull(index) as? PlanItem.Audio ?: return
        val sentenceStart = lookupFor(item.segment).startMs(ordinal) ?: return
        val current = plan.globalMs(index, inner.currentPosition)
        val target = SmartRewind.targetMs(current, plan.startMs(index) + sentenceStart, clock() - pausedAt)
        if (target < current) seekGlobal(target)
    }

    private fun positionInSentenceMs(): Long {
        val index = inner.currentMediaItemIndex
        val item = plan.items.getOrNull(index) as? PlanItem.Audio ?: return 0
        val start = lookupFor(item.segment).startMs(ordinal) ?: return 0
        return inner.currentPosition - start
    }

    // ================= ticking =================

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                tick()
                if (!inner.playWhenReady && !buffering) break
                delay(TICK_MS)
            }
        }
    }

    private fun tick() {
        val id = bookId ?: return
        if (plan.size == 0) { publish(false); return }
        val now = clock()
        val index = inner.currentMediaItemIndex.coerceIn(0, plan.size - 1)
        val position = inner.currentPosition
        var discrete = false

        val o = plan.ordinalAt(index, position, ::lookupFor)
        if (o != ordinal) {
            ordinal = o
            if (inner.isPlaying) consume.onOrdinal(o) { p, n -> sections.step(p, 1) == n }
        }
        val chapter = sections.chapterAt(o)
        if (chapter?.sectionId != chapterId) {
            chapterId = chapter?.sectionId
            chapterTitle = chapter?.title
            discrete = true
        }
        if (index != spanIndex || discrete) {
            span = plan.chapterSpan(index) { ord -> sections.chapterAt(ord)?.sectionId?.toInt() ?: 0 }
            spanIndex = index
            discrete = true
        }
        val globalNow = plan.globalMs(index, position)

        if (sleep.active) {
            val chapterEnd = sections.chapterAt(o)?.endOrdinal ?: sections.lastNarrated
            val spanKnown = !(span.last >= plan.totalMs - 1 && plan.lastOrdinal < chapterEnd)
            val wall = if (spanKnown && chapterId == sleepChapterId) ((span.last - globalNow).coerceAtLeast(0) / speed).toLong() else null
            val t = sleep.tick(now, wall, sectionEnded = sleep.atSectionEnd && chapterId != sleepChapterId)
            sleepRemainingMs = t.remainingMs
            inner.volume = t.volume
            if (t.expired) {
                inner.volume = 1f
                sleepRemainingMs = null
                expiredAt = now
                pause()
                scope.launch { delay(SHAKE_AFTER_EXPIRY_MS); if (!sleep.active) shake.stop() }
                discrete = true
            }
        }

        if (now - lastSaveAt >= SAVE_EVERY_MS) flushProgress()
        if (now - lastAheadCheck >= AHEAD_CHECK_MS) { lastAheadCheck = now; checkAhead(id, globalNow, now) }
        publish(discrete)
    }

    private fun checkAhead(id: BookId, globalNow: Long, now: Long) {
        aheadMs = (plan.totalMs - globalNow).coerceAtLeast(0)
        if (plan.lastOrdinal >= sections.lastNarrated || ensuring || now < nextEnsureAt) return
        if (aheadMs / speed >= LOW_WATER_MS) return
        val next = sections.firstNarratedFrom(plan.lastOrdinal + 1) ?: return
        ensuring = true
        scope.launch {
            try {
                deps.narration.ensureAhead(id, NarrationConfig(voice), next, AHEAD_MINUTES)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                ensuring = false
                nextEnsureAt = clock() + ENSURE_RETRY_MS
            }
            if (id == bookId) extend()
        }
    }

    private fun onInnerEnded() {
        if (plan.size == 0) return
        if (plan.lastOrdinal < sections.lastNarrated) {
            buffering = true
            nextEnsureAt = 0
            lastAheadCheck = 0
            startTicker()
            scope.launch { extend() }
        } else {
            ended = true
            intendedPlay = false
            consume.onOrdinal(ordinal + 1) { _, _ -> true }
            flushProgress()
        }
    }

    private fun onInnerError() {
        errorCount++
        error = "Part of this narration couldn't be played."
        if (errorCount <= MAX_SKIPPED_ERRORS && inner.hasNextMediaItem()) {
            inner.seekToNextMediaItem()
            inner.prepare()
        } else {
            intendedPlay = false
            inner.pause()
        }
        publish(true)
    }

    private fun onShake() {
        val now = clock()
        val remaining = sleepRemainingMs
        when {
            sleep.active && remaining != null && remaining <= SHAKE_WINDOW_MS -> sleep.extend(SHAKE_EXTEND_MIN, now)
            !sleep.active && expiredAt > 0 && now - expiredAt <= SHAKE_AFTER_EXPIRY_MS -> {
                sleep.set(SHAKE_EXTEND_MIN, now)
                expiredAt = 0
                resume()
            }
        }
    }

    // ================= persistence =================

    private fun flushProgress() {
        val id = bookId ?: return
        val ranges = consume.drain()
        val ord = ordinal
        lastSaveAt = clock()
        if (ranges.isEmpty() && ord == lastSavedOrdinal) return
        lastSavedOrdinal = ord
        persistScope.launch {
            runCatching {
                for (r in ranges) deps.positions.markConsumed(id, r.first, r.last, PositionKind.LISTENING)
                deps.positions.savePosition(id, PositionKind.LISTENING, ord)
            }
        }
    }

    // ================= state =================

    private fun applySpeed(value: Float) {
        speed = value.coerceIn(MIN_SPEED, MAX_SPEED)
        inner.playbackParameters = PlaybackParameters(speed, 1f)
    }

    private fun sectionPosition(): Pair<Long, Long> {
        if (plan.size == 0) return 0L to 0L
        val global = plan.globalMs(inner.currentMediaItemIndex, inner.currentPosition)
        return (global - span.first).coerceAtLeast(0) to (span.last - span.first).coerceAtLeast(0)
    }

    private fun publish(refreshPlayer: Boolean) {
        val b = book
        val (pos, dur) = sectionPosition()
        hub.stateFlow.value = PlaybackState(
            bookId = bookId,
            playing = intendedPlay && !ended && error == null,
            buffering = buffering || (intendedPlay && plan.size > 0 && inner.playbackState == Player.STATE_BUFFERING),
            ordinal = ordinal,
            positionMs = pos,
            durationMs = dur,
            speed = speed,
            sleepRemainingMs = sleepRemainingMs,
            title = b?.title,
            error = error,
        )
        val count = sentenceCount
        hub.infoFlow.value = PlaybackInfo(
            bookId = bookId,
            bookTitle = b?.title,
            author = b?.authors,
            coverPath = b?.coverPath,
            sectionTitle = chapterTitle,
            voiceName = voice.name.takeIf { it.isNotBlank() },
            sentenceCount = count,
            marks = sections.marks,
            aheadMs = aheadMs,
            generating = narrationRunning || ensuring,
            bookElapsedMs = (ordinal * msPerSentence).toLong(),
            bookTotalMs = (count * msPerSentence).toLong(),
            sleepAtSectionEnd = sleep.atSectionEnd,
        )
        if (refreshPlayer) onExternalChange()
    }

    private companion object {
        const val TICK_MS = 100L
        const val SAVE_EVERY_MS = 5_000L
        const val AHEAD_CHECK_MS = 1_000L
        const val AHEAD_MINUTES = 10
        const val LOW_WATER_MS = 4 * 60_000L
        const val ENSURE_RETRY_MS = 15_000L
        const val INITIAL_RUN = 60
        const val EXTEND_RUN = 60
        const val MAX_ITEMS_AHEAD = 120
        const val EXTEND_BELOW = 40
        const val RESTART_SENTENCE_AFTER_MS = 1_500L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.5f
        const val MAX_SKIPPED_ERRORS = 3
        const val SHAKE_WINDOW_MS = 60_000L
        const val SHAKE_AFTER_EXPIRY_MS = 30_000L
        const val SHAKE_EXTEND_MIN = 10
        const val NOT_READY = "Narration for this part isn't ready yet."
    }
}
