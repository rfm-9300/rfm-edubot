package com.rfm.edubot.dashboard.assistant

import com.rfm.edubot.ai.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A group's tools as the model is offered them, and what the assistant's policy needs to gate them. */
internal interface ToolGroupSpec {
    /** In the order the model reads them. */
    val definitions: List<ToolDefinition>
    val moduleOf: Map<String, String>
    val readOnly: Set<String>
}

/**
 * Some of the dashboard assistant's tools, for one session. [AssistantTools][com.rfm.edubot.dashboard.AssistantTools]
 * routes each call to the group whose [spec] has it; a new area of tools is a new group listed there.
 */
internal interface AssistantToolGroup {
    val spec: ToolGroupSpec

    /** Runs [name], one of [spec]'s tools; throws [ToolProblem] for a call it refuses. */
    suspend fun execute(name: String, args: JsonObject): JsonObject

    /** What a person confirming the write [name] sees beyond its arguments; null when they say it all. */
    suspend fun describe(name: String, args: JsonObject): JsonObject? = null

    /** Throws [ToolProblem] when the write [name] couldn't run as it is, before a person is asked to confirm it. */
    suspend fun check(name: String, args: JsonObject) {}
}

internal const val DATE = "YYYY-MM-DD"

internal fun tool(name: String, description: String, properties: JsonObject, required: List<String> = emptyList()) = ToolDefinition(
    name = name,
    description = description,
    parameters = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        put("required", JsonArray(required.map(::JsonPrimitive)))
    },
)

internal fun prop(type: String, description: String? = null, values: List<String>? = null): JsonObject = buildJsonObject {
    put("type", type)
    description?.let { put("description", it) }
    values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
}
