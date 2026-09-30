package com.rfm.edubot.agents.registry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A small JSON Schema subset for action inputs and trigger configs. `x-widget` tells the dashboard
 * which control to draw (template text, duration, weekday set, client picker…); the LLM never sees it.
 */
object Schema {
    fun obj(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.toMap()))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }

    fun string(
        widget: String? = null,
        enum: List<String>? = null,
        default: String? = null,
        maxLength: Int? = null,
        description: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "string")
        widget?.let { put("x-widget", it) }
        enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
        default?.let { put("default", it) }
        maxLength?.let { put("maxLength", it) }
        description?.let { put("description", it) }
    }

    fun integer(min: Int? = null, max: Int? = null, default: Int? = null, widget: String? = null, description: String? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
        default?.let { put("default", it) }
        widget?.let { put("x-widget", it) }
        description?.let { put("description", it) }
    }

    fun number(min: Double? = null, max: Double? = null, default: Double? = null, widget: String? = null, description: String? = null): JsonObject = buildJsonObject {
        put("type", "number")
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
        default?.let { put("default", it) }
        widget?.let { put("x-widget", it) }
        description?.let { put("description", it) }
    }

    fun boolean(default: Boolean? = null, description: String? = null): JsonObject = buildJsonObject {
        put("type", "boolean")
        default?.let { put("default", it) }
        description?.let { put("description", it) }
    }

    fun array(items: JsonObject, widget: String? = null, maxItems: Int? = null, description: String? = null, default: JsonArray? = null): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
        widget?.let { put("x-widget", it) }
        maxItems?.let { put("maxItems", it) }
        description?.let { put("description", it) }
        default?.let { put("default", it) }
    }

    fun isTemplate(value: JsonElement?): Boolean = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.contains("{{") == true

    /** The schema without dashboard hints, for LLM tool definitions. */
    fun forLlm(schema: JsonObject): JsonObject = JsonObject(
        schema.filterKeys { !it.startsWith("x-") && it != "default" }.mapValues { (_, value) -> stripHints(value) },
    )

    private fun stripHints(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> forLlm(value)
        is JsonArray -> JsonArray(value.map { stripHints(it) })
        else -> value
    }

    /** Fills declared defaults for missing keys, so templates and the runtime see complete inputs. */
    fun withDefaults(schema: JsonObject, input: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: return input
        val merged = input.toMutableMap()
        properties.forEach { (key, property) ->
            val default = (property as? JsonObject)?.get("default")
            if (key !in merged && default != null) merged[key] = default
        }
        return JsonObject(merged)
    }
}

data class SchemaProblem(val path: String, val code: String, val detail: String? = null)

object SchemaValidator {
    /**
     * Checks [value] against [schema]. With [lenientTemplates], a `{{…}}` string is accepted for any type:
     * it is only known once the run renders it.
     */
    fun validate(schema: JsonObject, value: JsonElement?, path: String, lenientTemplates: Boolean): List<SchemaProblem> {
        val problems = mutableListOf<SchemaProblem>()
        check(schema, value, path, lenientTemplates, problems)
        return problems
    }

    private fun check(schema: JsonObject, value: JsonElement?, path: String, lenient: Boolean, problems: MutableList<SchemaProblem>) {
        if (value == null || value is JsonNull) return
        if (lenient && Schema.isTemplate(value)) return
        when (schema["type"]?.jsonPrimitive?.content) {
            "object" -> {
                val obj = value as? JsonObject ?: return run { problems += SchemaProblem(path, "not_object") }
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                (schema["required"] as? JsonArray)?.forEach { required ->
                    val key = required.jsonPrimitive.content
                    val present = obj[key]
                    if (present == null || present is JsonNull || (present is JsonPrimitive && present.isString && present.content.isBlank())) {
                        problems += SchemaProblem(join(path, key), "required")
                    }
                }
                obj.forEach { (key, item) ->
                    val property = properties[key] as? JsonObject
                    if (property == null) problems += SchemaProblem(join(path, key), "unknown_field") else check(property, item, join(path, key), lenient, problems)
                }
            }
            "array" -> {
                val array = value as? JsonArray ?: return run { problems += SchemaProblem(path, "not_array") }
                schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { if (array.size > it) problems += SchemaProblem(path, "too_many") }
                val items = schema["items"] as? JsonObject
                if (items != null) array.forEachIndexed { index, item -> check(items, item, "$path[$index]", lenient, problems) }
            }
            "string" -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return run { problems += SchemaProblem(path, "not_string") }
                schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { if (text.length > it) problems += SchemaProblem(path, "too_long") }
                enumValues(schema)?.let { if (text !in it) problems += SchemaProblem(path, "not_allowed", text) }
            }
            "integer", "number" -> {
                val primitive = value as? JsonPrimitive
                val number = primitive?.takeIf { !it.isString }?.doubleOrNull ?: return run { problems += SchemaProblem(path, "not_number") }
                if (schema["type"]?.jsonPrimitive?.content == "integer" && number % 1.0 != 0.0) problems += SchemaProblem(path, "not_integer")
                schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (number < it) problems += SchemaProblem(path, "too_small") }
                schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (number > it) problems += SchemaProblem(path, "too_large") }
            }
            "boolean" -> if ((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null) problems += SchemaProblem(path, "not_boolean")
        }
    }

    /**
     * After rendering, turns strings into the declared types ("3" → 3, "true" → true) so templated
     * numbers and flags reach actions as real values.
     */
    fun coerce(schema: JsonObject, value: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: return value
        return JsonObject(
            value.mapValues { (key, item) ->
                val property = properties[key] as? JsonObject ?: return@mapValues item
                coerceValue(property, item)
            },
        )
    }

    private fun coerceValue(schema: JsonObject, value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive
        return when (schema["type"]?.jsonPrimitive?.content) {
            "integer" -> primitive?.contentOrNull?.trim()?.let { text -> text.toLongOrNull()?.let { JsonPrimitive(it) } ?: text.toDoubleOrNull()?.let { JsonPrimitive(it.toLong()) } } ?: value
            "number" -> primitive?.contentOrNull?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: value
            "boolean" -> when (primitive?.contentOrNull?.trim()?.lowercase()) {
                "true", "yes", "1" -> JsonPrimitive(true)
                "false", "no", "0", "" -> JsonPrimitive(false)
                else -> value
            }
            "object" -> (value as? JsonObject)?.let { coerce(schema, it) } ?: value
            "array" -> {
                val items = schema["items"] as? JsonObject
                if (value is JsonArray && items != null) JsonArray(value.map { coerceValue(items, it) }) else value
            }
            else -> value
        }
    }

    private fun enumValues(schema: JsonObject): Set<String>? = (schema["enum"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.lowercase()?.toBooleanStrictOrNull() }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }.orEmpty()

internal fun JsonObject.ints(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.let { value -> value.intOrNull ?: value.contentOrNull?.toIntOrNull() } }.orEmpty()

internal fun JsonElement?.asObjectOrNull(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()

internal fun JsonElement?.asArrayOrNull(): JsonArray? = runCatching { this?.jsonArray }.getOrNull()
