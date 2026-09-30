package com.rfm.edubot.agents.registry

import com.rfm.edubot.events.SubjectTypes
import kotlinx.serialization.Serializable

enum class VarType { TEXT, MONEY, DATE, DATETIME, NUMBER, BOOLEAN, ID }

@Serializable
data class VariableSpec(val path: String, val type: VarType)

/**
 * The `{{…}}` variables and condition fields a run can use, by the record it is about. The runtime's
 * context builder fills exactly these paths; the validator rejects any other reference.
 */
object AgentVariables {
    private fun vars(prefix: String, vararg fields: Pair<String, VarType>) = fields.map { (name, type) -> VariableSpec("$prefix.$name", type) }

    val common = listOf(VariableSpec("now", VarType.DATETIME), VariableSpec("today", VarType.DATE))

    val company = vars(
        "company",
        "name" to VarType.TEXT, "phone" to VarType.TEXT, "email" to VarType.TEXT, "address" to VarType.TEXT, "taxId" to VarType.TEXT,
    )

    val event = vars(
        "event",
        "type" to VarType.TEXT, "from" to VarType.TEXT, "to" to VarType.TEXT, "actorType" to VarType.TEXT,
        "text" to VarType.TEXT, "channel" to VarType.TEXT,
    )

    val client = vars(
        "client",
        "id" to VarType.ID, "number" to VarType.TEXT, "name" to VarType.TEXT, "firstName" to VarType.TEXT,
        "phone" to VarType.TEXT, "email" to VarType.TEXT, "address" to VarType.TEXT, "taxId" to VarType.TEXT,
        "hasEmail" to VarType.BOOLEAN, "hasTaxId" to VarType.BOOLEAN,
        "openServicesCount" to VarType.NUMBER, "openServicesTotal" to VarType.MONEY, "openServicesTotalCents" to VarType.NUMBER,
        "lastActivityAt" to VarType.DATE, "daysSinceActivity" to VarType.NUMBER,
    )

    val quote = vars(
        "quote",
        "id" to VarType.ID, "number" to VarType.TEXT, "status" to VarType.TEXT, "total" to VarType.MONEY, "totalCents" to VarType.NUMBER,
        "validUntil" to VarType.DATE, "createdAt" to VarType.DATETIME, "sentAt" to VarType.DATETIME,
        "daysSinceSent" to VarType.NUMBER, "daysUntilExpiry" to VarType.NUMBER, "itemCount" to VarType.NUMBER,
    )

    val invoice = vars(
        "invoice",
        "id" to VarType.ID, "number" to VarType.TEXT, "status" to VarType.TEXT, "total" to VarType.MONEY, "totalCents" to VarType.NUMBER,
        "dueDate" to VarType.DATE, "daysOverdue" to VarType.NUMBER, "daysUntilDue" to VarType.NUMBER, "isOverdue" to VarType.BOOLEAN,
        "paidAt" to VarType.DATETIME, "quoteNumber" to VarType.TEXT,
    )

    val payment = vars(
        "payment",
        "id" to VarType.ID, "number" to VarType.TEXT, "status" to VarType.TEXT, "total" to VarType.MONEY, "totalCents" to VarType.NUMBER,
        "dueDate" to VarType.DATE, "daysOverdue" to VarType.NUMBER, "daysUntilDue" to VarType.NUMBER, "isOverdue" to VarType.BOOLEAN,
        "payeeName" to VarType.TEXT, "payeeType" to VarType.TEXT, "payeePhone" to VarType.TEXT,
    )

    val booking = vars(
        "booking",
        "id" to VarType.ID, "serviceName" to VarType.TEXT, "start" to VarType.DATETIME, "startDate" to VarType.DATE,
        "startTime" to VarType.TEXT, "end" to VarType.DATETIME, "status" to VarType.TEXT, "contactName" to VarType.TEXT,
        "contactPhone" to VarType.TEXT, "price" to VarType.MONEY, "source" to VarType.TEXT, "hoursUntilStart" to VarType.NUMBER,
    )

    val service = vars(
        "service",
        "id" to VarType.ID, "name" to VarType.TEXT, "status" to VarType.TEXT, "total" to VarType.MONEY, "totalCents" to VarType.NUMBER,
    )

    val conversation = vars(
        "conversation",
        "id" to VarType.ID, "channel" to VarType.TEXT, "waId" to VarType.TEXT, "contactName" to VarType.TEXT,
        "lastMessage" to VarType.TEXT, "waitingMinutes" to VarType.NUMBER, "autoReplyEnabled" to VarType.BOOLEAN,
        "windowOpen" to VarType.BOOLEAN, "unreadCount" to VarType.NUMBER,
    )

    val contact = vars("contact", "id" to VarType.ID, "channel" to VarType.TEXT, "waId" to VarType.TEXT, "displayName" to VarType.TEXT)

    val instagramComment = vars(
        "comment",
        "id" to VarType.ID, "text" to VarType.TEXT, "fromUsername" to VarType.TEXT, "mediaId" to VarType.TEXT,
    )

    val email = vars(
        "email",
        "id" to VarType.ID, "from" to VarType.TEXT, "fromName" to VarType.TEXT, "subject" to VarType.TEXT,
        "snippet" to VarType.TEXT, "text" to VarType.TEXT, "hasAttachments" to VarType.BOOLEAN, "hasPdf" to VarType.BOOLEAN,
        "knownClient" to VarType.BOOLEAN, "threadId" to VarType.TEXT, "automated" to VarType.BOOLEAN,
    )

    fun forSubject(subjectType: String?): List<VariableSpec> {
        val specific = when (subjectType) {
            SubjectTypes.CLIENT -> client
            SubjectTypes.QUOTE -> quote + client
            SubjectTypes.INVOICE -> invoice + client
            SubjectTypes.PAYMENT -> payment + client
            SubjectTypes.BOOKING -> booking + client
            SubjectTypes.SERVICE -> service + client
            SubjectTypes.CONVERSATION -> conversation + client
            SubjectTypes.CONTACT -> contact + client
            SubjectTypes.INSTAGRAM_COMMENT -> instagramComment
            SubjectTypes.EMAIL -> email + client
            else -> emptyList()
        }
        return common + company + event + specific
    }

    fun typeOf(subjectType: String?, path: String): VarType? = forSubject(subjectType).firstOrNull { it.path == path }?.type
}
