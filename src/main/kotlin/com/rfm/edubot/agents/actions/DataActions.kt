package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.registry.ActionCategory
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.int
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.agents.runtime.ValueFormatter
import com.rfm.edubot.agents.templates.AgentCopy
import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.events.SubjectTypes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import java.io.File

/** Regenerates the record's quote or invoice PDF and stores it, so the copy on file is current. */
object GeneratePdfAction : AgentAction {
    override val key = "doc.pdf"
    override val category = ActionCategory.DOCUMENT
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val subjectTypes = setOf(SubjectTypes.QUOTE, SubjectTypes.INVOICE)
    override val aiCallable = false
    override val toolDescription = "Generate the PDF of the quote or invoice the agent works on."
    override val inputSchema = Schema.obj("document" to Schema.string(enum = listOf("auto", "quote", "invoice"), default = "auto"))

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "document",
        attachments = listOfNotNull(AgentDocuments.forRun(ctx, input.string("document"))?.filename),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val pdf = AgentDocuments.forRun(ctx, input.string("document")) ?: return ActionResult.Skipped("no_document")
        val dir = File(ctx.services.pdfStoragePath(), "${pdf.kind}s").apply { mkdirs() }
        val file = File(dir, "${ctx.tenant.id.toHexString()}-${pdf.filename}")
        file.writeBytes(pdf.bytes)
        val id = ctx.run.subject?.id?.let { runCatching { ObjectId(it) }.getOrNull() }
        if (id != null && ctx.run.subject?.type == pdf.kind) {
            if (pdf.kind == SubjectTypes.QUOTE) QuoteRepository(ctx.services.mongo, ctx.tenant.id).setPdfPath(id, file.path)
            else InvoiceRepository(ctx.services.mongo, ctx.tenant.id).setPdfPath(id, file.path)
        }
        return ActionResult.Done(buildJsonObject { put("filename", pdf.filename); put("bytes", pdf.bytes.size) })
    }
}

/**
 * A short digest of the company's data, in its language: today's agenda, payables, overdue invoices,
 * the week's cash, waiting chats, incomplete clients or unbilled work. Later steps use its `text`.
 */
object SummaryAction : AgentAction {
    val kinds = listOf(
        "agenda_today", "pending_bookings", "payables_week", "receivables_overdue", "cash_week", "waiting_chats",
        "missing_client_data", "open_services",
    )

    override val key = "data.summary"
    override val category = ActionCategory.DATA
    override val sideEffect = SideEffect.NONE
    override val toolDescription = "Read a digest of company data: ${kinds.joinToString()}."
    override val inputSchema = Schema.obj(
        "kind" to Schema.string(enum = kinds, default = "agenda_today"),
        "limit" to Schema.integer(min = 1, max = 50, default = 15),
        required = listOf("kind"),
    )
    override val outputSchema = Schema.obj("text" to Schema.string(), "count" to Schema.integer(), "lines" to Schema.array(Schema.string()))

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "generic", body = summarize(input, ctx).text)

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val summary = summarize(input, ctx)
        return ActionResult.Done(
            buildJsonObject {
                put("text", summary.text)
                put("count", summary.count)
                put("lines", JsonArray(summary.lines.map { JsonPrimitive(it) }))
            },
        )
    }

    private data class Summary(val text: String, val count: Int, val lines: List<String>)

    private suspend fun summarize(input: JsonObject, ctx: RunContext): Summary {
        val kind = input.string("kind")?.takeIf { it in kinds } ?: "agenda_today"
        val limit = (input.int("limit") ?: 15).coerceIn(1, 50)
        val format = ValueFormatter(ctx.locale, ctx.zone)
        val today = ctx.now.toLocalDateTime(ctx.zone).date
        val mongo = ctx.services.mongo
        val tenantId = ctx.tenant.id
        fun date(value: LocalDate) = format.formatDate(value.toString()).orEmpty()
        fun due(value: LocalDate) = AgentCopy.t(ctx.locale, "summary.line.due", "date" to date(value))

        val lines: List<String> = when (kind) {
            "agenda_today", "pending_bookings" -> {
                val bookings = BookingRepository(mongo, tenantId)
                val rows = if (kind == "agenda_today") {
                    val start = LocalDateTime(today, LocalTime(0, 0)).toInstant(ctx.zone)
                    bookings.list(from = start, to = LocalDateTime(today.plus(1, DateTimeUnit.DAY), LocalTime(0, 0)).toInstant(ctx.zone))
                        .filter { it.status != BookingStatus.CANCELLED && it.status != BookingStatus.NO_SHOW }
                } else {
                    bookings.list(from = ctx.now, status = BookingStatus.PENDING)
                }
                rows.map { booking ->
                    val at = booking.startAt.toLocalDateTime(ctx.zone)
                    val time = "%02d:%02d".format(at.hour, at.minute)
                    val day = if (kind == "agenda_today") time else "${date(at.date)} $time"
                    "$day · ${booking.serviceName} · ${booking.contactName}"
                }
            }
            "payables_week" -> {
                val suppliers = SupplierRepository(mongo, tenantId)
                val employees = EmployeeRepository(mongo, tenantId)
                PaymentRepository(mongo, tenantId).list(status = PaymentStatus.PENDING)
                    .filter { it.dueDate <= today.plus(7, DateTimeUnit.DAY) }
                    .sortedBy { it.dueDate }
                    .map { payment ->
                        val payee = payment.supplierId?.let { suppliers.findById(it)?.name } ?: payment.employeeId?.let { employees.findById(it)?.name }.orEmpty()
                        "${payment.number} · $payee · ${format.formatCents(payment.totalCents)} · ${due(payment.dueDate)}"
                    }
            }
            "receivables_overdue" -> {
                val clients = ClientRepository(mongo, tenantId)
                InvoiceRepository(mongo, tenantId).list(status = InvoiceStatus.PENDING)
                    .filter { it.dueDate < today }
                    .sortedBy { it.dueDate }
                    .map { invoice -> "${invoice.number} · ${clients.findById(invoice.clientId)?.name.orEmpty()} · ${format.formatCents(invoice.totalCents)} · ${due(invoice.dueDate)}" }
            }
            "cash_week" -> {
                val weekStart = today.minus(today.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
                val invoices = InvoiceRepository(mongo, tenantId).list()
                val collected = invoices.filter { it.status == InvoiceStatus.PAID && it.paidAt?.toLocalDateTime(ctx.zone)?.date?.let { day -> day >= weekStart } == true }.sumOf { it.totalCents }
                val pending = invoices.filter { it.status == InvoiceStatus.PENDING || it.status == InvoiceStatus.OVERDUE }
                val overdue = pending.filter { it.dueDate < today }
                val payables = PaymentRepository(mongo, tenantId).list(status = PaymentStatus.PENDING).filter { it.dueDate <= today.plus(7, DateTimeUnit.DAY) }
                listOf(
                    AgentCopy.t(ctx.locale, "summary.cash.collected", "amount" to format.formatCents(collected)),
                    AgentCopy.t(ctx.locale, "summary.cash.outstanding", "amount" to format.formatCents(pending.sumOf { it.totalCents })),
                    AgentCopy.t(ctx.locale, "summary.cash.overdue", "amount" to format.formatCents(overdue.sumOf { it.totalCents }), "count" to overdue.size.toString()),
                    AgentCopy.t(ctx.locale, "summary.cash.payables", "amount" to format.formatCents(payables.sumOf { it.totalCents })),
                )
            }
            "waiting_chats" -> {
                val conversations = ConversationRepository(mongo, tenantId).list(limit = 100)
                val last = MessageRepository(mongo, tenantId).lastByConversationIds(conversations.map { it.id })
                val names = UserRepository(mongo, tenantId).displayNamesByIds(conversations.map { it.userId })
                conversations.filter { last[it.id]?.role == UserRole.USER }.map { conversation ->
                    val waiting = conversation.lastInboundAt?.let { (ctx.now - it).inWholeMinutes } ?: 0
                    "${names[conversation.userId] ?: conversation.waId} · ${conversation.channel.name.lowercase()} · ${waiting} min"
                }
            }
            "missing_client_data" -> ClientRepository(mongo, tenantId).search("", limit = 500)
                .filter { it.email.isNullOrBlank() || it.taxId.isNullOrBlank() }
                .map { client ->
                    val missing = listOfNotNull(
                        AgentCopy.t(ctx.locale, "summary.line.missing_email").takeIf { client.email.isNullOrBlank() },
                        AgentCopy.t(ctx.locale, "summary.line.missing_tax").takeIf { client.taxId.isNullOrBlank() },
                    )
                    "${client.number} · ${client.name} · ${missing.joinToString(", ")}"
                }
            "open_services" -> {
                val clients = ClientRepository(mongo, tenantId)
                ClientServiceRepository(mongo, tenantId).list(status = ClientServiceStatus.OPEN)
                    .groupBy { it.clientId }
                    .map { (clientId, rows) -> "${clients.findById(clientId)?.name.orEmpty()} · ${rows.size} · ${format.formatCents(rows.sumOf { it.totalCents })}" }
            }
            else -> emptyList()
        }
        val heading = AgentCopy.t(ctx.locale, "summary.$kind")
        val shown = lines.take(limit)
        val body = when {
            lines.isEmpty() -> AgentCopy.t(ctx.locale, "summary.empty")
            lines.size > limit -> (shown + AgentCopy.t(ctx.locale, "summary.more", "count" to (lines.size - limit).toString())).joinToString("\n") { "• $it" }
            else -> shown.joinToString("\n") { "• $it" }
        }
        return Summary("$heading\n$body", if (kind == "cash_week") 0 else lines.size, shown)
    }
}
