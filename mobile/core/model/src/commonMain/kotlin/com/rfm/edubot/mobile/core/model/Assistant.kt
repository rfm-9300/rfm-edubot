package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AssistantThread(
    val id: String,
    val title: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class AssistantThreadDetail(
    val thread: AssistantThread,
    val messages: List<AssistantMessage> = emptyList(),
)

@Serializable
data class AssistantMessage(
    val id: String,
    val role: String,
    val content: String = "",
    val createdAt: String = "",
    val action: AssistantAction? = null,
)

/**
 * A tool call the assistant wants to make. While [status] is `PENDING` the user has to confirm or
 * cancel it; [preview] is the rendered effect when the backend could produce one.
 */
@Serializable
data class AssistantAction(
    val id: String,
    val toolName: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val status: String,
    val result: JsonObject? = null,
    val preview: String? = null,
) {
    val pending: Boolean get() = status.equals("PENDING", ignoreCase = true)
}

@Serializable
data class NewAssistantThread(val title: String = "")

@Serializable
data class AssistantPrompt(val content: String)
