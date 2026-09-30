package com.rfm.edubot.integrations.email

import com.rfm.edubot.events.SubjectRef
import kotlinx.datetime.Instant
import org.bson.types.ObjectId

enum class EmailDirection { OUTBOUND, INBOUND }

data class EmailAttachmentInfo(val filename: String, val mimeType: String, val size: Int)

/**
 * One email sent from or received in a company's connected account (`email_messages`), for the
 * client's Emails tab, replies in thread and `email.*` triggers. The body is kept capped and purged
 * after the retention period; the rest stays as the record of what was said.
 */
data class EmailMessage(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val connectionId: ObjectId,
    /** Gmail's message id. */
    val providerMessageId: String,
    val threadId: String? = null,
    /** The RFC 5322 Message-ID, which a reply's In-Reply-To names. */
    val messageIdHeader: String? = null,
    val direction: EmailDirection,
    val from: String,
    val fromName: String? = null,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val snippet: String,
    val bodyText: String? = null,
    val attachments: List<EmailAttachmentInfo> = emptyList(),
    val clientId: ObjectId? = null,
    /** The CRM record it is about, e.g. the quote it carried. */
    val record: SubjectRef? = null,
    /** Who sent it (`USER`, `AGENT`, `SYSTEM`…) and, for agents, the run. */
    val sentByType: String? = null,
    val sentById: String? = null,
    val sentByName: String? = null,
    val runId: String? = null,
    val date: Instant,
    val createdAt: Instant,
    val bodyPurgedAt: Instant? = null,
) {
    companion object {
        const val MAX_BODY = 20_000
        const val MAX_SNIPPET = 200

        fun snippetOf(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_SNIPPET)
    }
}
