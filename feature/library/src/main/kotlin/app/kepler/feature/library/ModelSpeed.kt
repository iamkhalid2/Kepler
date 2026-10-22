package app.kepler.feature.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** The free model Automatic mode would pick right now and how fast it started answering in the last probe. */
data class ModelSpeed(val modelId: String, val firstWordsMs: Long)

/**
 * Reads the stored model probe (the AI engine's `model-probe/v1` record: per model the latest time-to-first-token
 * samples and when it last failed) and returns the fastest model that has not failed since it last answered.
 * Returns null for a missing, empty or unreadable record.
 */
fun parseModelSpeed(probeJson: String?): ModelSpeed? {
    if (probeJson.isNullOrBlank()) return null
    val models = try {
        (Json.parseToJsonElement(probeJson).jsonObject["models"] as? JsonObject) ?: return null
    } catch (_: Exception) {
        return null
    }
    return models.mapNotNull { (id, stat) ->
        val o = stat as? JsonObject ?: return@mapNotNull null
        val failedAt = (o["failedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
        val samples = (o["samples"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty().sorted()
        if (failedAt > 0L || samples.isEmpty()) null else ModelSpeed(id, samples[samples.size / 2])
    }.minByOrNull { it.firstWordsMs }
}

/** "ling-3.1-flash" from "inclusionai/ling-3.1-flash:free": the part people recognise. */
fun shortModelName(id: String): String = id.substringAfterLast('/').removeSuffix(":free")
