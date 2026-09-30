package com.rfm.edubot.integrations.email

import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.tenant.model.Tenant
import org.bson.types.ObjectId

data class EmailAttachment(val filename: String, val mimeType: String, val bytes: ByteArray)

data class OutgoingEmail(
    val to: List<String>,
    val subject: String,
    /** Plain text; the HTML part is built from it with the company's branding. */
    val text: String,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val replyTo: String? = null,
    val attachments: List<EmailAttachment> = emptyList(),
    /** Reply in this Gmail thread, answering [inReplyTo] (its Message-ID). */
    val threadId: String? = null,
    val inReplyTo: String? = null,
    val clientId: ObjectId? = null,
    /** The CRM record the email is about, for the client's Emails tab and the record's timeline. */
    val record: SubjectRef? = null,
)

sealed class EmailSendResult {
    /** [messageId] is Gmail's id; [alreadySent] when the idempotency key had gone out before and nothing was sent now. */
    data class Sent(val messageId: String, val threadId: String?, val from: String, val alreadySent: Boolean = false) : EmailSendResult()
    /**
     * [key] is a dashboard error key: no_email_account, needs_reconnect, invalid_recipient, too_many_recipients,
     * invalid_message, message_too_large, daily_send_limit, rate_limited, send_in_doubt or send_failed.
     */
    data class Failed(val key: String, val detail: String? = null, val retryable: Boolean = false) : EmailSendResult()
}

/** Sends email from a company's connected account. Agents, the dashboard's "Send by email" and tests share it. */
interface EmailSender {
    suspend fun isAvailable(tenant: Tenant): Boolean
    suspend fun send(tenant: Tenant, email: OutgoingEmail, idempotencyKey: String? = null): EmailSendResult
}
