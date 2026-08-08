package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Persona(
    val compiledInstructions: String,
    val version: Int,
    val tokenEstimate: Int,
    val status: String,
    val updatedAt: String? = null,
    val sources: List<PersonaSource> = emptyList(),
)

@Serializable
data class PersonaSource(
    val id: String,
    val kind: String,
    val label: String,
    val compiled: Boolean,
    val createdAt: String,
)
