package com.rfm.edubot.integrations

import com.rfm.edubot.integrations.google.GoogleScopes
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days

@Serializable
data class IntegrationsDto(
    val google: GoogleAvailabilityDto,
    val connections: List<IntegrationConnectionDto>,
    /** Admins and operators change settings and disconnect. */
    val canManage: Boolean,
    val mailbox: MailboxAvailabilityDto = MailboxAvailabilityDto(),
)

@Serializable
data class MailboxAvailabilityDto(
    /** The platform gives companies their own address (Amazon SES); "Connect Gmail" isn't offered then. */
    val configured: Boolean = false,
    /** The domain the addresses are on. */
    val domain: String? = null,
)

@Serializable
data class ForwardingConfirmationInfoDto(
    val code: String? = null,
    /** Opens Gmail's own confirmation page; always on a Google mail host. */
    val link: String? = null,
    /** The Gmail account that asked to forward here. */
    val account: String? = null,
    val at: String,
)

@Serializable
data class GoogleAvailabilityDto(
    /** The platform has a Google OAuth client and a token key. False hides the Gmail row. */
    val configured: Boolean,
    /** Only a company admin signed in as themselves may consent; operators opening the dashboard can't. */
    val canConnect: Boolean,
    /** The platform lets companies use their inbox in automations (Gmail's restricted scopes). */
    val inbox: Boolean = false,
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
    /** Emails sent from the account on the company's current day, against [dailyLimit]. */
    val sentToday: Int = 0,
    val dailyLimit: Int? = null,
    /** Google let the app read this inbox; turning inbox sync on without it asks for consent first. */
    val canRead: Boolean = false,
    val inboxSync: Boolean = false,
    val inboxSyncedAt: String? = null,
    val inboxError: String? = null,
    /** A company address: when an email last reached it and how many did. */
    val receivedAt: String? = null,
    val received: Int = 0,
    /** The mailboxes seen forwarding to it, most recent first. */
    val forwarders: List<String> = emptyList(),
    /** Gmail's recent request to confirm forwarding to it. */
    val confirmation: ForwardingConfirmationInfoDto? = null,
    val connectedAt: String,
    val updatedAt: String,
)

@Serializable
data class TestEmailDto(val to: String, val messageId: String)

/** Null leaves a field as it is; a blank string clears it. */
@Serializable
data class UpdateConnectionRequest(
    val senderName: String? = null,
    val replyTo: String? = null,
    val signature: String? = null,
    val isDefault: Boolean? = null,
    /** Turning it on needs the read scopes: without them the answer is 409 `needs_consent`. */
    val inboxSync: Boolean? = null,
)

/** [canSend] comes from the email service, which knows whether the account's provider is set up on the platform. */
internal fun IntegrationConnection.dto(today: String? = null, dailyLimit: Int? = null, canSend: Boolean? = null, now: Instant? = null) = IntegrationConnectionDto(
    id = id.toHexString(),
    provider = provider,
    accountEmail = accountEmail,
    status = status.name,
    isDefault = isDefault,
    canSend = canSend ?: (status == ConnectionStatus.ACTIVE && provider == IntegrationProviders.GOOGLE && GoogleScopes.canSend(scopes)),
    senderName = settings.senderName,
    replyTo = settings.replyTo,
    signature = settings.signature,
    connectedBy = connectedByEmail,
    lastError = lastError,
    sentToday = today?.let(::sentOn) ?: 0,
    dailyLimit = dailyLimit,
    canRead = provider == IntegrationProviders.GOOGLE && GoogleScopes.canRead(scopes),
    inboxSync = settings.inboxSync,
    inboxSyncedAt = inbox.lastSyncedAt?.toString(),
    inboxError = inbox.lastError,
    receivedAt = mailbox.lastReceivedAt?.toString(),
    received = mailbox.received,
    forwarders = mailbox.forwarders,
    confirmation = mailbox.confirmation?.takeIf { now == null || now - it.at < CONFIRMATION_SHOWN }
        ?.let { ForwardingConfirmationInfoDto(it.code, it.link, it.account, it.at.toString()) },
    connectedAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

/** Gmail's forwarding codes stop working after a while; an old one is no use on the setup screen. */
private val CONFIRMATION_SHOWN = 7.days
