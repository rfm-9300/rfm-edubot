package com.rfm.edubot.integrations.email

import com.rfm.edubot.events.SubjectRef
import kotlinx.datetime.Instant
import org.bson.types.ObjectId

enum class EmailDirection { OUTBOUND, INBOUND }

data class EmailAttachmentInfo(val filename: String, val mimeType: String, val size: Int) {
    val isPdf: Boolean get() = mimeType.equals("application/pdf", ignoreCase = true) || filename.endsWith(".pdf", ignoreCase = true)
}

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
    /** Where the sender asked replies to go, when that isn't [from]. */
    val replyTo: String? = null,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val snippet: String,
    val bodyText: String? = null,
    val attachments: List<EmailAttachmentInfo> = emptyList(),
    /** Received mail a machine sent (a newsletter, a notification, an automatic reply), which automations never answer. */
    val automated: Boolean = false,
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
    /** When someone on the team opened it on the Email page; received mail only. Gmail's own read state isn't touched. */
    val readAt: Instant? = null,
    /** What the model read in it, for the Email page's suggestions; derived from the text, so it goes with it. */
    val insights: EmailInsights? = null,
    /** What the team did from it on the Email page: the records it led to and the suggestions set aside. */
    val actions: List<EmailAction> = emptyList(),
) {
    companion object {
        const val MAX_BODY = 20_000
        const val MAX_SNIPPET = 200
        const val MAX_SUBJECT = 300

        fun snippetOf(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_SNIPPET)

        /** `Re: ` once, whatever the sender's mail program called it. */
        fun replySubject(original: String): String {
            val trimmed = original.trim()
            return if (REPLY_PREFIX.containsMatchIn(trimmed)) trimmed else "Re: $trimmed".trim().take(MAX_SUBJECT)
        }

        private val REPLY_PREFIX = Regex("^(re|res|aw|sv)\\s*:", RegexOption.IGNORE_CASE)
    }
}

enum class EmailActionStatus { DONE, DISMISSED }

/**
 * One suggestion of the Email page settled by someone: done, with the record it created or changed
 * ([recordType] is a subject type such as `client` or `quote`), or dismissed. Keyed by [type] and
 * [recordId], so marking the same invoice paid twice keeps one entry.
 */
data class EmailAction(
    val type: String,
    val status: EmailActionStatus,
    val recordType: String? = null,
    val recordId: String? = null,
    /** How the record reads in the list (a quote's number, a client's name), as it was when the action was taken. */
    val recordLabel: String? = null,
    val byName: String? = null,
    val at: Instant,
)
