package com.rfm.edubot.agents.runtime

import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.dashboard.InboxService
import com.rfm.edubot.events.DomainEvent
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.instagram.InstagramCommentRepository
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.Document
import org.bson.types.ObjectId

/** The record a run is about, loaded fresh: its variables and a short label for lists. */
data class SubjectContext(val variables: JsonObject, val label: String?, val clientId: ObjectId?, val automationPaused: Boolean, val exists: Boolean)

/**
 * Builds a run's variables (`AgentVariables`): the company, the record and its client, the event that
 * woke the agent, earlier step outputs and template parameters. Runs rebuild it after each wait so
 * guards see live data (the invoice may have been paid meanwhile).
 */
class AgentContextBuilder(private val mongo: MongoModule, private val clock: () -> Instant) {

    suspend fun build(
        tenant: Tenant,
        subject: SubjectRef?,
        event: DomainEvent? = null,
        previous: JsonObject? = null,
        params: JsonObject? = null,
        locale: String = tenant.locale,
    ): SubjectContext {
        val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
        val now = clock()
        val today = now.toLocalDateTime(zone).date
        val formatter = ValueFormatter(locale, zone)
        val record = subject?.let { load(tenant, it, today, now, formatter) }
        val variables = buildJsonObject {
            put("now", now.toString())
            put("today", today.toString())
            put("company", company(tenant))
            put("event", eventVariables(event, previous))
            record?.variables?.forEach { (key, value) -> put(key, value) }
            (previous?.get("steps") as? JsonObject)?.let { put("steps", it) }
            (params ?: previous?.get("params") as? JsonObject)?.let { put("params", it) }
        }
        return SubjectContext(
            variables = variables,
            label = record?.label,
            clientId = record?.clientId,
            automationPaused = record?.automationPaused ?: false,
            exists = subject == null || record?.exists == true,
        )
    }

    private fun company(tenant: Tenant): JsonObject {
        val template = tenant.documentTemplate.withCompanyFallback(tenant.name)
        return buildJsonObject {
            put("name", template.companyName.ifBlank { tenant.name })
            put("phone", template.phone)
            put("email", template.email)
            put("address", template.address)
            put("taxId", template.taxId)
        }
    }

    /** The waking event's facts; after a wait the stored ones are kept, since the event is gone. */
    private fun eventVariables(event: DomainEvent?, previous: JsonObject?): JsonObject {
        if (event == null) return previous?.get("event") as? JsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("type", event.type)
            put("actorType", event.actor.type.name)
            event.payload["from"]?.let { put("from", it) }
            event.payload["to"]?.let { put("to", it) }
            event.payload["text"]?.let { put("text", it) }
            event.payload["channel"]?.let { put("channel", it) }
            event.payload.forEach { (key, value) -> if (key !in setOf("from", "to", "text", "channel")) put("data_$key", value) }
        }
    }

    private class Loaded(val variables: Map<String, JsonElement>, val label: String?, val clientId: ObjectId?, val automationPaused: Boolean, val exists: Boolean = true)

    private suspend fun load(tenant: Tenant, subject: SubjectRef, today: LocalDate, now: Instant, formatter: ValueFormatter): Loaded? {
        val id = runCatching { ObjectId(subject.id) }.getOrNull()
        return when (subject.type) {
            SubjectTypes.CLIENT -> id?.let { ClientRepository(mongo, tenant.id).findById(it) }?.let { client ->
                Loaded(mapOf("client" to clientVariables(tenant, client, today, formatter, withActivity = true)), "${client.number} · ${client.name}", client.id, client.automationPaused)
            }
            SubjectTypes.QUOTE -> id?.let { QuoteRepository(mongo, tenant.id).findById(it) }?.let { quote ->
                val client = ClientRepository(mongo, tenant.id).findById(quote.clientId)
                val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
                val variables = buildJsonObject {
                    put("id", quote.id.toHexString())
                    put("number", quote.number)
                    put("status", quote.status.name)
                    put("total", formatter.formatCents(quote.totalCents))
                    put("totalCents", quote.totalCents)
                    quote.validUntil?.let { put("validUntil", it.toString()); put("daysUntilExpiry", today.daysUntil(it)) }
                    put("createdAt", quote.createdAt.toString())
                    quote.sentAt?.let { put("sentAt", it.toString()); put("daysSinceSent", it.toLocalDateTime(zone).date.daysUntil(today)) }
                    put("itemCount", quote.items.size)
                }
                Loaded(withClient("quote", variables, tenant, client, today, formatter), label(quote.number, client), client?.id, client?.automationPaused ?: false)
            }
            SubjectTypes.INVOICE -> id?.let { InvoiceRepository(mongo, tenant.id).findById(it) }?.let { invoice ->
                val client = ClientRepository(mongo, tenant.id).findById(invoice.clientId)
                val quoteNumber = invoice.quoteId?.let { QuoteRepository(mongo, tenant.id).findById(it)?.number }
                val open = invoice.status == InvoiceStatus.PENDING || invoice.status == InvoiceStatus.OVERDUE
                val variables = buildJsonObject {
                    put("id", invoice.id.toHexString())
                    put("number", invoice.number)
                    put("status", invoice.status.name)
                    put("total", formatter.formatCents(invoice.totalCents))
                    put("totalCents", invoice.totalCents)
                    // An installment plan asks for the parts due by then, not the whole total.
                    put("amountDue", formatter.formatCents(invoice.amountDueCents(today)))
                    put("amountDueCents", invoice.amountDueCents(today))
                    put("dueDate", invoice.dueDate.toString())
                    val untilDue = today.daysUntil(invoice.dueDate)
                    put("daysUntilDue", untilDue)
                    put("daysOverdue", if (open && untilDue < 0) -untilDue else 0)
                    put("isOverdue", open && untilDue < 0)
                    invoice.paidAt?.let { put("paidAt", it.toString()) }
                    quoteNumber?.let { put("quoteNumber", it) }
                }
                Loaded(withClient("invoice", variables, tenant, client, today, formatter), label(invoice.number, client), client?.id, client?.automationPaused ?: false)
            }
            SubjectTypes.PAYMENT -> id?.let { PaymentRepository(mongo, tenant.id).findById(it) }?.let { payment ->
                val client = payment.clientId?.let { ClientRepository(mongo, tenant.id).findById(it) }
                val supplier = payment.supplierId?.let { SupplierRepository(mongo, tenant.id).findById(it) }
                val employee = payment.employeeId?.let { EmployeeRepository(mongo, tenant.id).findById(it) }
                val open = payment.status == PaymentStatus.PENDING || payment.status == PaymentStatus.OVERDUE
                val variables = buildJsonObject {
                    put("id", payment.id.toHexString())
                    put("number", payment.number)
                    put("status", payment.status.name)
                    put("total", formatter.formatCents(payment.totalCents))
                    put("totalCents", payment.totalCents)
                    put("dueDate", payment.dueDate.toString())
                    val untilDue = today.daysUntil(payment.dueDate)
                    put("daysUntilDue", untilDue)
                    put("daysOverdue", if (open && untilDue < 0) -untilDue else 0)
                    put("isOverdue", open && untilDue < 0)
                    put("payeeName", supplier?.name ?: employee?.name ?: "")
                    put("payeePhone", supplier?.phone ?: employee?.phone ?: "")
                    put("payeeType", if (supplier != null) "supplier" else "employee")
                }
                Loaded(withClient("payment", variables, tenant, client, today, formatter), label(payment.number, client) ?: "${payment.number} · ${supplier?.name ?: employee?.name.orEmpty()}", client?.id, client?.automationPaused ?: false)
            }
            SubjectTypes.BOOKING -> id?.let { BookingRepository(mongo, tenant.id).findById(it) }?.let { booking ->
                val client = booking.clientId?.let { ClientRepository(mongo, tenant.id).findById(it) }
                val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
                val start = booking.startAt.toLocalDateTime(zone)
                val variables = buildJsonObject {
                    put("id", booking.id.toHexString())
                    put("serviceName", booking.serviceName)
                    put("start", booking.startAt.toString())
                    put("startDate", start.date.toString())
                    put("startTime", "%02d:%02d".format(start.hour, start.minute))
                    put("end", booking.endAt.toString())
                    put("status", booking.status.name)
                    put("contactName", booking.contactName)
                    put("contactPhone", booking.contactPhone)
                    booking.priceCents?.let { put("price", formatter.formatCents(it)) }
                    put("source", booking.source.name)
                    put("hoursUntilStart", (booking.startAt - now).inWholeHours)
                }
                val fallbackClient = client ?: Client(tenantId = tenant.id, number = "", name = booking.contactName, phone = booking.contactPhone, createdAt = now, updatedAt = now)
                Loaded(withClient("booking", variables, tenant, fallbackClient, today, formatter), "${booking.serviceName} · ${booking.contactName}", client?.id, client?.automationPaused ?: false)
            }
            SubjectTypes.SERVICE -> id?.let { ClientServiceRepository(mongo, tenant.id).findById(it) }?.let { service ->
                val client = ClientRepository(mongo, tenant.id).findById(service.clientId)
                val variables = buildJsonObject {
                    put("id", service.id.toHexString())
                    put("name", service.name)
                    put("status", service.status.name)
                    put("total", formatter.formatCents(service.totalCents))
                    put("totalCents", service.totalCents)
                }
                Loaded(withClient("service", variables, tenant, client, today, formatter), label(service.name, client), client?.id, client?.automationPaused ?: false)
            }
            SubjectTypes.CONVERSATION -> id?.let { ConversationRepository(mongo, tenant.id).findById(it) }?.let { conversation ->
                val user = UserRepository(mongo, tenant.id).findById(conversation.userId)
                val messages = MessageRepository(mongo, tenant.id)
                val last = messages.lastByConversationIds(listOf(conversation.id))[conversation.id]
                val lastInbound = conversation.lastInboundAt ?: messages.lastInboundAt(conversation.id)
                val waiting = last?.role == UserRole.USER && lastInbound != null
                val client = if (conversation.channel == Platform.WHATSAPP) ClientRepository(mongo, tenant.id).findByPhone(conversation.waId) else null
                val variables = buildJsonObject {
                    put("id", conversation.id.toHexString())
                    put("channel", conversation.channel.name)
                    put("waId", conversation.waId)
                    put("contactName", user?.displayName ?: conversation.waId)
                    (last?.content as? MessageContent.Text)?.let { put("lastMessage", it.body.take(1000)) }
                    put("waitingMinutes", if (waiting) (now - lastInbound!!).inWholeMinutes else 0)
                    put("autoReplyEnabled", conversation.autoReplyEnabled)
                    put("windowOpen", conversation.channel != Platform.WHATSAPP || (lastInbound != null && now < lastInbound + InboxService.WINDOW))
                    put("unreadCount", conversation.unreadCount)
                }
                Loaded(withClient("conversation", variables, tenant, client, today, formatter), user?.displayName ?: conversation.waId, client?.id, client?.automationPaused ?: false)
            }
            SubjectTypes.CONTACT -> id?.let { UserRepository(mongo, tenant.id).findById(it) }?.let { user ->
                val client = if (user.channel == Platform.WHATSAPP) ClientRepository(mongo, tenant.id).findByPhone(user.waId) else null
                val variables = buildJsonObject {
                    put("id", user.id.toHexString())
                    put("channel", user.channel.name)
                    put("waId", user.waId)
                    put("displayName", user.displayName?.takeIf { it.isNotBlank() } ?: user.waId)
                }
                Loaded(withClient("contact", variables, tenant, client, today, formatter), user.displayName ?: user.waId, client?.id, client?.automationPaused ?: false)
            }
            SubjectTypes.INSTAGRAM_COMMENT -> InstagramCommentRepository(mongo, tenant.id).findByCommentId(subject.id)?.let { comment ->
                val variables = buildJsonObject {
                    put("id", comment.commentId)
                    put("text", comment.text.take(1000))
                    put("fromUsername", comment.fromUsername.orEmpty())
                    put("mediaId", comment.mediaId)
                }
                Loaded(mapOf("comment" to variables), comment.fromUsername?.let { "@$it" } ?: comment.commentId, null, false)
            }
            SubjectTypes.EMAIL -> id?.let { EmailMessageRepository(mongo).find(tenant.id, it) }?.let { email ->
                val client = email.clientId?.let { ClientRepository(mongo, tenant.id).findById(it) }
                val variables = buildJsonObject {
                    put("id", email.id.toHexString())
                    put("from", email.from)
                    put("fromName", email.fromName?.takeIf { it.isNotBlank() } ?: email.from)
                    put("subject", email.subject)
                    put("snippet", email.snippet)
                    put("text", (email.bodyText ?: email.snippet).take(MAX_EMAIL_TEXT))
                    put("hasAttachments", email.attachments.isNotEmpty())
                    put("hasPdf", email.attachments.any { it.isPdf })
                    put("knownClient", client != null)
                    email.threadId?.let { put("threadId", it) }
                    put("automated", email.automated)
                }
                Loaded(withClient("email", variables, tenant, client, today, formatter), email.subject.ifBlank { email.from }, client?.id, client?.automationPaused ?: false)
            }
            else -> null
        } ?: Loaded(emptyMap(), null, null, false, exists = false)
    }

    private suspend fun withClient(key: String, variables: JsonObject, tenant: Tenant, client: Client?, today: LocalDate, formatter: ValueFormatter): Map<String, JsonElement> =
        buildMap {
            put(key, variables)
            client?.let { put("client", clientVariables(tenant, it, today, formatter, withActivity = false)) }
        }

    private fun label(number: String, client: Client?): String = client?.let { "$number · ${it.name}" } ?: number

    private suspend fun clientVariables(tenant: Tenant, client: Client, today: LocalDate, formatter: ValueFormatter, withActivity: Boolean): JsonObject = buildJsonObject {
        if (client.number.isNotBlank()) put("id", client.id.toHexString())
        put("number", client.number)
        put("name", client.name)
        put("firstName", client.name.trim().substringBefore(' '))
        put("phone", client.phone)
        put("email", client.email.orEmpty())
        put("address", client.address.orEmpty())
        put("taxId", client.taxId.orEmpty())
        put("hasEmail", !client.email.isNullOrBlank())
        put("hasTaxId", !client.taxId.isNullOrBlank())
        if (withActivity && client.number.isNotBlank()) activity(tenant, client, today, formatter)
    }

    private suspend fun JsonObjectBuilder.activity(tenant: Tenant, client: Client, today: LocalDate, formatter: ValueFormatter) {
        val open = ClientServiceRepository(mongo, tenant.id).list(client.id, ClientServiceStatus.OPEN)
        put("openServicesCount", open.size)
        val openTotal = open.sumOf { it.totalCents }
        put("openServicesTotal", formatter.formatCents(openTotal))
        put("openServicesTotalCents", openTotal)
        val last = lastActivity(tenant.id, client.id) ?: client.createdAt
        val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
        val lastDate = last.toLocalDateTime(zone).date
        put("lastActivityAt", lastDate.toString())
        put("daysSinceActivity", lastDate.daysUntil(today))
    }

    /** The client's newest quote, invoice or booking. */
    suspend fun lastActivity(tenantId: ObjectId, clientId: ObjectId): Instant? =
        listOf("crm.quotes", "crm.invoices", "bookings.appointments").mapNotNull { name ->
            mongo.database.getCollection<Document>(name)
                .find(Document("tenantId", tenantId).append("clientId", clientId))
                .sort(Document("createdAt", -1)).limit(1).toList().firstOrNull()
                ?.getDate("createdAt")?.let { Instant.fromEpochMilliseconds(it.time) }
        }.maxOrNull()

    private companion object {
        /** How much of an email's text steps see. */
        const val MAX_EMAIL_TEXT = 8_000
    }
}
