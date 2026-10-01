package com.rfm.edubot.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

/**
 * Converts free-form JSON (agent definitions, event payloads, step outputs) to BSON and back without
 * Extended JSON wrappers, so stored documents stay readable and round-trip to the same JSON.
 */
object BsonJson {
    fun toDocument(obj: JsonObject): Document {
        val doc = Document()
        obj.forEach { (key, value) -> doc[key] = toBsonValue(value) }
        return doc
    }

    fun toBsonValue(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonObject -> toDocument(element)
        is JsonArray -> element.map { toBsonValue(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull ?: element.content
        }
    }

    fun toJsonObject(doc: Document?): JsonObject {
        if (doc == null) return JsonObject(emptyMap())
        return JsonObject(doc.entries.associate { (key, value) -> key to toJsonElement(value) })
    }

    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value.toLong())
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Date -> JsonPrimitive(java.time.Instant.ofEpochMilli(value.time).toString())
        is ObjectId -> JsonPrimitive(value.toHexString())
        is Document -> toJsonObject(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) })
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
