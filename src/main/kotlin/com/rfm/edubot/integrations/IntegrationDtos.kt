package com.rfm.edubot.integrations

import com.rfm.edubot.integrations.google.GoogleScopes
import kotlinx.serialization.Serializable

@Serializable
data class IntegrationsDto(
    val google: GoogleAvailabilityDto,
    val connections: List<IntegrationConnectionDto>,
    /** Admins and operators change settings and disconnect. */
    val canManage: Boolean,
)

@Serializable
data class GoogleAvailabilityDto(
    /** The platform has a Google OAuth client and a token key. False hides the Gmail row. */
    val configured: Boolean,
    /** Only a company admin signed in as themselves may consent; operators opening the dashboard can't. */
    val canConnect: Boolean,
)

/** A connected account as the dashboard sees it; the tokens never leave the server. */
@Serializable
data class IntegrationConnectionDto(
    val id: String,
    val provider: String,
    val accountEmail: String,
    val status: String,
    val isDefault: Boolean,
    val canSend: Boolean,
    val senderName: String? = null,
    val replyTo: String? = null,
    val signature: String? = null,
    val connectedBy: String? = null,
    val lastError: String? = null,
    val connectedAt: String,
    val updatedAt: String,
)

/** Null leaves a field as it is; a blank string clears it. */
@Serializable
data class UpdateConnectionRequest(
    val senderName: String? = null,
    val replyTo: String? = null,
    val signature: String? = null,
    val isDefault: Boolean? = null,
)

internal fun IntegrationConnection.dto() = IntegrationConnectionDto(
    id = id.toHexString(),
    provider = provider,
    accountEmail = accountEmail,
    status = status.name,
    isDefault = isDefault,
    canSend = status == ConnectionStatus.ACTIVE && provider == IntegrationProviders.GOOGLE && GoogleScopes.canSend(scopes),
    senderName = settings.senderName,
    replyTo = settings.replyTo,
    signature = settings.signature,
    connectedBy = connectedByEmail,
    lastError = lastError,
    connectedAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
