package com.rfm.edubot.events

import com.rfm.edubot.dashboard.DashboardModules
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.bson.types.ObjectId

enum class ActorType { USER, OPERATOR, BOT, AGENT, SYSTEM }

/** Who caused a change: a dashboard user, a backoffice operator, the customer-facing bot, an agent run, or the system. */
data class Actor(
    val type: ActorType,
    val id: String? = null,
    val name: String? = null,
    val runId: String? = null,
) {
    companion object {
        val SYSTEM = Actor(ActorType.SYSTEM)
        fun bot(channel: String) = Actor(ActorType.BOT, id = channel.lowercase())
        fun agent(agentId: ObjectId, runId: ObjectId, name: String?) = Actor(ActorType.AGENT, agentId.toHexString(), name, runId.toHexString())
    }
}

/** The record an event is about. [id] is an ObjectId hex for CRM rows and stored emails, or a provider id (Instagram comment). */
data class SubjectRef(val type: String, val id: String) {
    companion object {
        fun of(type: String, id: ObjectId) = SubjectRef(type, id.toHexString())
    }
}

data class DomainEvent(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val type: String,
    val subject: SubjectRef,
    val related: List<SubjectRef> = emptyList(),
    val payload: JsonObject = JsonObject(emptyMap()),
    val actor: Actor = Actor.SYSTEM,
    /** How many agent runs led to this change; agents stop reacting past [DomainEventTypes.MAX_DEPTH]. */
    val depth: Int = 0,
    val occurredAt: Instant,
)

object SubjectTypes {
    const val CLIENT = "client"
    const val QUOTE = "quote"
    const val INVOICE = "invoice"
    const val PAYMENT = "payment"
    const val SERVICE = "service"
    const val BOOKING = "booking"
    const val CONTACT = "contact"
    const val CONVERSATION = "conversation"
    const val MESSAGE = "message"
    const val INSTAGRAM_COMMENT = "instagram_comment"
    const val EMAIL = "email"
    const val SUPPLIER = "supplier"
    const val EMPLOYEE = "employee"
    const val NONE = "none"

    /** Records an agent can run on by hand from their drawer. */
    val runnable = listOf(CLIENT, QUOTE, INVOICE, PAYMENT, BOOKING, CONVERSATION)
}

/** An event type and what a tenant needs for it to happen at all. */
data class EventTypeSpec(val type: String, val subjectType: String, val module: String?, val integration: String? = null)

object DomainEventTypes {
    const val CLIENT_CREATED = "client.created"
    const val CLIENT_UPDATED = "client.updated"
    const val CLIENT_ARCHIVED = "client.archived"
    const val CLIENT_RESTORED = "client.restored"
    const val QUOTE_CREATED = "quote.created"
    const val QUOTE_UPDATED = "quote.updated"
    const val QUOTE_STATUS_CHANGED = "quote.status_changed"
    const val INVOICE_CREATED = "invoice.created"
    const val INVOICE_PAID = "invoice.paid"
    const val PAYMENT_CREATED = "payment.created"
    const val PAYMENT_PAID = "payment.paid"
    const val SERVICE_CREATED = "service.created"
    const val SERVICE_INVOICED = "service.invoiced"
    const val BOOKING_CREATED = "booking.created"
    const val BOOKING_RESCHEDULED = "booking.rescheduled"
    const val BOOKING_STATUS_CHANGED = "booking.status_changed"
    const val CONTACT_CREATED = "contact.created"
    const val MESSAGE_RECEIVED = "message.received"
    const val CONVERSATION_HANDOFF = "conversation.handoff"
    const val INSTAGRAM_COMMENT_RECEIVED = "instagram.comment.received"
    const val EMAIL_RECEIVED = "email.received"
    const val EMAIL_SENT = "email.sent"

    /** Agents react to events at most this many agent steps deep, so two agents can't feed each other forever. */
    const val MAX_DEPTH = 3

    /** Frequent events stored only while some active agent of that company listens for them. */
    val onDemand = setOf(MESSAGE_RECEIVED)

    val specs: List<EventTypeSpec> = listOf(
        EventTypeSpec(CLIENT_CREATED, SubjectTypes.CLIENT, DashboardModules.CLIENTS),
        EventTypeSpec(CLIENT_UPDATED, SubjectTypes.CLIENT, DashboardModules.CLIENTS),
        EventTypeSpec(CLIENT_ARCHIVED, SubjectTypes.CLIENT, DashboardModules.CLIENTS),
        EventTypeSpec(CLIENT_RESTORED, SubjectTypes.CLIENT, DashboardModules.CLIENTS),
        EventTypeSpec(QUOTE_CREATED, SubjectTypes.QUOTE, DashboardModules.QUOTES),
        EventTypeSpec(QUOTE_UPDATED, SubjectTypes.QUOTE, DashboardModules.QUOTES),
        EventTypeSpec(QUOTE_STATUS_CHANGED, SubjectTypes.QUOTE, DashboardModules.QUOTES),
        EventTypeSpec(INVOICE_CREATED, SubjectTypes.INVOICE, DashboardModules.INVOICES),
        EventTypeSpec(INVOICE_PAID, SubjectTypes.INVOICE, DashboardModules.INVOICES),
        EventTypeSpec(PAYMENT_CREATED, SubjectTypes.PAYMENT, DashboardModules.PAYMENTS),
        EventTypeSpec(PAYMENT_PAID, SubjectTypes.PAYMENT, DashboardModules.PAYMENTS),
        EventTypeSpec(SERVICE_CREATED, SubjectTypes.SERVICE, DashboardModules.SERVICES),
        EventTypeSpec(SERVICE_INVOICED, SubjectTypes.SERVICE, DashboardModules.SERVICES),
        EventTypeSpec(BOOKING_CREATED, SubjectTypes.BOOKING, DashboardModules.BOOKINGS),
        EventTypeSpec(BOOKING_RESCHEDULED, SubjectTypes.BOOKING, DashboardModules.BOOKINGS),
        EventTypeSpec(BOOKING_STATUS_CHANGED, SubjectTypes.BOOKING, DashboardModules.BOOKINGS),
        EventTypeSpec(CONTACT_CREATED, SubjectTypes.CONTACT, DashboardModules.CONVERSATIONS),
        EventTypeSpec(MESSAGE_RECEIVED, SubjectTypes.CONVERSATION, DashboardModules.CONVERSATIONS),
        EventTypeSpec(CONVERSATION_HANDOFF, SubjectTypes.CONVERSATION, DashboardModules.CONVERSATIONS),
        EventTypeSpec(INSTAGRAM_COMMENT_RECEIVED, SubjectTypes.INSTAGRAM_COMMENT, DashboardModules.INSTAGRAM),
        EventTypeSpec(EMAIL_RECEIVED, SubjectTypes.EMAIL, null, integration = "gmail_inbox"),
        EventTypeSpec(EMAIL_SENT, SubjectTypes.EMAIL, null, integration = "gmail"),
    )

    private val byType = specs.associateBy { it.type }

    fun spec(type: String): EventTypeSpec? = byType[type]
}
