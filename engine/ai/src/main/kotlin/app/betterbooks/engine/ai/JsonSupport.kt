package app.betterbooks.engine.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

internal val AiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** What the model must return: a serializer for decoding, a JSON schema for the request, and a semantic check. */
internal class JsonSpec<T>(
    val name: String,
    val schema: JsonObject,
    val serializer: KSerializer<T>,
    /** Returns an error message when the decoded value is unusable (triggers the repair retry). */
    val validate: (T) -> String? = { null },
)

/** Parses a model reply into [T], or returns the reason it could not. */
internal fun <T> parseReply(raw: String?, spec: JsonSpec<T>): Pair<T?, String?> {
    val json = extractJson(raw) ?: return null to "no JSON object found in the reply"
    val value = try {
        AiJson.decodeFromString(spec.serializer, json)
    } catch (e: Exception) {
        return null to "JSON did not match the schema: ${e.message?.take(200)}"
    }
    val problem = spec.validate(value)
    return if (problem == null) value to null else null to problem
}

/** Pulls the first balanced JSON object out of a reply (strips code fences and reasoning tags). */
internal fun extractJson(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    var s = raw.replace(Regex("(?is)<think>.*?</think>"), "").trim()
    val start = s.indexOf('{')
    if (start < 0) return null
    s = s.substring(start)
    var depth = 0
    var inString = false
    var escaped = false
    for (i in s.indices) {
        val c = s[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
        } else when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return s.substring(0, i + 1) }
        }
    }
    return null
}

internal fun sha256(vararg parts: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) { md.update(p.toByteArray(Charsets.UTF_8)); md.update(0) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Tiny builder for strict JSON schemas (all properties required, no extras), as structured outputs need. */
internal object Sch {
    fun str(description: String? = null): JsonObject = buildJsonObject {
        put("type", "string"); description?.let { put("description", it) }
    }
    fun int(): JsonObject = buildJsonObject { put("type", "integer") }
    fun enum(vararg values: String): JsonObject = buildJsonObject {
        put("type", "string"); put("enum", JsonArray(values.map { JsonPrimitive(it) }))
    }
    fun arr(item: JsonElement): JsonObject = buildJsonObject { put("type", "array"); put("items", item) }
    fun obj(vararg props: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", JsonArray(props.map { JsonPrimitive(it.first) }))
        put("additionalProperties", false)
    }
}
