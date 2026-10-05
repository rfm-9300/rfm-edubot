package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Persona(
    val compiledInstructions: String = "",
    val version: Int = 0,
    val tokenEstimate: Int = 0,
    val status: String = "",
    val updatedAt: String? = null,
    val sources: List<PersonaSource> = emptyList(),
) {
    /** The backend is still rebuilding the prompt; the screen polls while this holds. */
    val compiling: Boolean get() = status.equals("COMPILING", ignoreCase = true)
}

@Serializable
data class PersonaSource(
    val id: String,
    val kind: String,
    val label: String,
    val compiled: Boolean = false,
    val createdAt: String = "",
)

@Serializable
data class PersonaTestMessage(val role: String, val content: String)

@Serializable
data class PersonaTest(val messages: List<PersonaTestMessage>)

@Serializable
data class PersonaReply(val reply: String = "")

@Serializable
data class PersonaSourceText(val content: String)

@Serializable
data class PersonaUpdate(val compiledInstructions: String)
