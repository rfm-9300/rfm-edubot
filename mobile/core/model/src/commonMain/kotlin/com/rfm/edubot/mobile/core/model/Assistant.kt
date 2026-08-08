package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AssistantThread(val id: String, val title: String, val createdAt: String, val updatedAt: String)

@Serializable
data class AssistantThreadDetail(val thread: AssistantThread, val messages: List<AssistantMessage>)

@Serializable
data class AssistantMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val action: AssistantAction? = null,
)

@Serializable
data class AssistantAction(
    val id: String,
    val toolName: String,
    val arguments: JsonObject,
    val status: String,
)
