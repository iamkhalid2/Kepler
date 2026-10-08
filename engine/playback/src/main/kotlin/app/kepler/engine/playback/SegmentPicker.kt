package app.kepler.engine.playback

import app.kepler.core.model.AudioSegment
import app.kepler.core.model.VoiceRef
import java.util.TreeMap

/** Chooses the segments that actually play: one voice, newest take wins where ranges overlap. */
object SegmentPicker {
    fun active(all: List<AudioSegment>, voice: VoiceRef?): List<AudioSegment> {
        if (all.isEmpty()) return emptyList()
        val wanted = voice?.takeIf { v -> all.any { it.voice.provider == v.provider && it.voice.id == v.id } }
            ?: all.maxByOrNull { it.id }!!.voice
        val chosen = TreeMap<Int, AudioSegment>()
        val candidates = all.filter { it.voice.provider == wanted.provider && it.voice.id == wanted.id }
            .sortedWith(compareByDescending<AudioSegment> { it.take }.thenByDescending { it.id })
        for (s in candidates) {
            val before = chosen.floorEntry(s.endOrdinal)?.value
            if (before == null || before.endOrdinal < s.startOrdinal) chosen[s.startOrdinal] = s
        }
        return chosen.values.toList()
    }
}
