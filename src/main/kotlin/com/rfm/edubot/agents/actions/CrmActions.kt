package com.rfm.edubot.agents.actions

import com.mongodb.MongoWriteException
import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.registry.ActionCategory
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.int
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.bookings.BookingRuleException
import com.rfm.edubot.bookings.NewBooking
import com.rfm.edubot.bookings.bookingDeps
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceBilling
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectTypes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import kotlin.math.roundToLong

private fun RunContext.subjectId(): ObjectId? = run.subject?.id?.let { runCatching { ObjectId(it) }.getOrNull() }

private fun RunContext.clientId(): ObjectId? = AgentMessaging.variable(this, "client.id")?.let { runCatching { ObjectId(it) }.getOrNull() }

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }

/** A client, unless one with that phone exists (then that one, restored if archived). */
object CreateClientAction : AgentAction {
    override val key = "crm.client.create"
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.CLIENTS)
    override val toolDescription = "Create a client in the CRM from a name and phone; returns the existing client when the phone is already known."
    override val outputSchema = Schema.obj("clientId" to Schema.string(), "number" to Schema.string(), "existing" to Schema.boolean())
    override val inputSchema = Schema.obj(
        "name" to Schema.string(widget = "template", maxLength = 200),
        "phone" to Schema.string(widget = "template", maxLength = 40),
        "email" to Schema.string(widget = "template", maxLength = 200),
        "address" to Schema.string(widget = "template", maxLength = 300),
        "notes" to Schema.string(widget = "template", maxLength = 2000),
        required = listOf("name", "phone"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        fields = listOfNotNull("name", "phone", "email", "address").mapNotNull { key -> input.string(key)?.let { key to it } }.toMap(),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val name = input.string("name") ?: return ActionResult.Failed("name_required")
        val phone = input.string("phone") ?: return ActionResult.Failed("phone_required")
        val clients = ClientRepository(ctx.services.mongo, ctx.tenant.id)
        clients.findByPhone(phone)?.let { found ->
            if (found.archivedAt != null) clients.setArchived(found.id, archived = false)
            return ActionResult.Done(buildJsonObject { put("clientId", found.id.toHexString()); put("number", found.number); put("existing", true) }, note = "existing_client")
        }
        val created = try {
            clients.create(name, phone, input.string("address"), input.string("email"), notes = input.string("notes"))
        } catch (e: MongoWriteException) {
            clients.findByPhone(phone) ?: return ActionResult.Failed("phone_taken")
        }
        return ActionResult.Done(buildJsonObject { put("clientId", created.id.toHexString()); put("number", created.number); put("existing", false) })
    }
}

/** Fills or corrects the record's client: email, address, tax number, notes. Blank inputs keep what's stored. */
object UpdateClientAction : AgentAction {
    override val key = "crm.client.update"
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.CLIENTS)
    override val toolDescription = "Update the email, address, tax number or notes of the client of the record the agent works on."
    override val inputSchema = Schema.obj(
        "email" to Schema.string(widget = "template", maxLength = 200),
        "address" to Schema.string(widget = "template", maxLength = 300),
        "taxId" to Schema.string(widget = "template", maxLength = 40),
        "notes" to Schema.string(widget = "template", maxLength = 2000),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        subject = ctx.run.subjectLabel,
        fields = listOf("email", "address", "taxId", "notes").mapNotNull { key -> input.string(key)?.let { key to it } }.toMap(),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val clientId = ctx.clientId() ?: return ActionResult.Skipped("no_client")
        val clients = ClientRepository(ctx.services.mongo, ctx.tenant.id)
        val client = clients.findById(clientId) ?: return ActionResult.Skipped("no_client")
        clients.update(
            id = client.id,
            name = client.name,
            phone = client.phone,
            address = input.string("address") ?: client.address,
            email = input.string("email"),
            taxId = input.string("taxId"),
            notes = input.string("notes")?.let { listOfNotNull(client.notes, it).joinToString("\n") },
        ) ?: return ActionResult.Failed("client_not_found")
        return ActionResult.Done(buildJsonObject { put("clientId", client.id.toHexString()) })
    }
}

/** Marks the record's quote as pending, sent or accepted. */
object SetQuoteStatusAction : AgentAction {
    override val key = "crm.quote.set_status"
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.QUOTES)
    override val subjectTypes = setOf(SubjectTypes.QUOTE)
    override val toolDescription = "Change the status of the quote the agent works on: PENDENTE, SENT or ACEITO."
    override val inputSchema = Schema.obj(
        "status" to Schema.string(enum = QuoteStatus.entries.map { it.name }),
        required = listOf("status"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        subject = ctx.run.subjectLabel,
        fields = mapOf("from" to AgentMessaging.variable(ctx, "quote.status").orEmpty(), "to" to input.string("status").orEmpty()),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val status = input.string("status")?.let { runCatching { QuoteStatus.valueOf(it.uppercase()) }.getOrNull() } ?: return ActionResult.Failed("invalid_status")
        val id = ctx.subjectId() ?: return ActionResult.Skipped("no_record")
        QuoteRepository(ctx.services.mongo, ctx.tenant.id).update(id, null, null, null, status) ?: return ActionResult.Failed("quote_not_found")
        return ActionResult.Done(buildJsonObject { put("status", status.name) })
    }
}

/** Invoices the record's quote (whole, or a deposit share) and marks the quote accepted. */
object InvoiceFromQuoteAction : AgentAction {
    override val key = "crm.invoice.from_quote"
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.QUOTES, DashboardModules.INVOICES)
    override val subjectTypes = setOf(SubjectTypes.QUOTE)
    override val toolDescription = "Create an invoice from the quote the agent works on, optionally for a deposit percentage, and mark the quote accepted."
    override val outputSchema = Schema.obj("invoiceId" to Schema.string(), "number" to Schema.string(), "totalCents" to Schema.integer())
    override val inputSchema = Schema.obj(
        "dueInDays" to Schema.integer(min = 0, max = 180, default = 30),
        "depositPercent" to Schema.integer(min = 1, max = 100, default = 100),
        "depositLabel" to Schema.string(widget = "template", maxLength = 200),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        subject = ctx.run.subjectLabel,
        fields = mapOf(
            "total" to AgentMessaging.variable(ctx, "quote.total").orEmpty(),
            "deposit_percent" to (input.int("depositPercent") ?: 100).toString(),
            "due_in_days" to (input.int("dueInDays") ?: 30).toString(),
        ),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val id = ctx.subjectId() ?: return ActionResult.Skipped("no_record")
        val quotes = QuoteRepository(ctx.services.mongo, ctx.tenant.id)
        val invoices = InvoiceRepository(ctx.services.mongo, ctx.tenant.id)
        val quote = quotes.findById(id) ?: return ActionResult.Failed("quote_not_found")
        invoices.list(quote.clientId).firstOrNull { it.quoteId == quote.id && it.status != InvoiceStatus.CANCELLED }?.let {
            return ActionResult.Done(buildJsonObject { put("invoiceId", it.id.toHexString()); put("number", it.number) }, note = "already_invoiced")
        }
        val percent = (input.int("depositPercent") ?: 100).coerceIn(1, 100)
        val items: List<LineItem> = if (percent == 100) {
            quote.items
        } else {
            val label = input.string("depositLabel") ?: "${quote.number} · $percent%"
            listOf(lineItem(label, unitPriceEur = (quote.totalCents * percent / 100.0).roundToLong() / 100.0))
        }
        val due = ctx.now.toLocalDateTime(ctx.zone).date.plus(input.int("dueInDays") ?: 30, DateTimeUnit.DAY)
        val invoice = invoices.create(quote.clientId, quote.id, items, due)
        quotes.update(quote.id, null, null, null, QuoteStatus.ACEITO)
        return ActionResult.Done(buildJsonObject { put("invoiceId", invoice.id.toHexString()); put("number", invoice.number); put("totalCents", invoice.totalCents) })
    }
}

/** One invoice for all of the record's client's open Serviços rows. */
object InvoiceOpenServicesAction : AgentAction {
    override val key = "crm.invoice.from_open_services"
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.SERVICES, DashboardModules.INVOICES)
    override val toolDescription = "Invoice every open service row of the client the agent works on in one invoice."
    override val outputSchema = Schema.obj("invoiceId" to Schema.string(), "number" to Schema.string(), "rows" to Schema.integer())
    override val inputSchema = Schema.obj("dueInDays" to Schema.integer(min = 0, max = 180, default = 30))

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        subject = ctx.run.subjectLabel,
        fields = mapOf(
            "open_services" to AgentMessaging.variable(ctx, "client.openServicesCount").orEmpty(),
            "total" to AgentMessaging.variable(ctx, "client.openServicesTotal").orEmpty(),
        ),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val clientId = ctx.clientId() ?: return ActionResult.Skipped("no_client")
        val rows = ClientServiceRepository(ctx.services.mongo, ctx.tenant.id)
        val open = rows.list(clientId, ClientServiceStatus.OPEN)
        if (open.isEmpty()) return ActionResult.Skipped("no_open_services")
        return when (val prepared = ClientServiceBilling.prepareInvoice(clientId, open)) {
            is ClientServiceBilling.Outcome.Rejected -> ActionResult.Failed(prepared.reason)
            is ClientServiceBilling.Outcome.Ready -> {
                val due = ctx.now.toLocalDateTime(ctx.zone).date.plus(input.int("dueInDays") ?: 30, DateTimeUnit.DAY)
                val invoice = InvoiceRepository(ctx.services.mongo, ctx.tenant.id).create(clientId, null, prepared.items, due)
                rows.markInvoiced(open.map { it.id }, invoice.id)
                ActionResult.Done(buildJsonObject { put("invoiceId", invoice.id.toHexString()); put("number", invoice.number); put("rows", open.size) })
            }
        }
    }
}

/** An outgoing bill for a supplier or an employee (e.g. from a supplier's invoice email). */
object CreatePaymentAction : AgentAction {
    override val key = "crm.payment.create"
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.PAYMENTS)
    override val toolDescription = "Record a bill to pay to a supplier or employee, with a description, amount in euros and due date."
    override val outputSchema = Schema.obj("paymentId" to Schema.string(), "number" to Schema.string())
    override val inputSchema = Schema.obj(
        "payeeType" to Schema.string(enum = listOf("supplier", "employee"), default = "supplier"),
        "payeeId" to Schema.string(widget = "payee"),
        "description" to Schema.string(widget = "template", maxLength = 300),
        "amountEur" to Schema.number(min = 0.01, max = 10_000_000.0),
        "dueInDays" to Schema.integer(min = 0, max = 365, default = 30),
        required = listOf("payeeId", "description", "amountEur"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        fields = listOf("payeeType", "description", "amountEur", "dueInDays").mapNotNull { key -> input[key]?.let { key to (it as? JsonPrimitive)?.content.orEmpty() } }.toMap(),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val payee = input.string("payeeId")?.let { runCatching { ObjectId(it) }.getOrNull() } ?: return ActionResult.Failed("payee_required")
        val amount = input.number("amountEur")?.takeIf { it > 0 } ?: return ActionResult.Failed("amount_required")
        val employee = input.string("payeeType") == "employee"
        if (employee && DashboardModules.EMPLOYEES !in DashboardModules.effectiveFor(ctx.tenant)) return ActionResult.Failed("employees_module_off")
        val due = ctx.now.toLocalDateTime(ctx.zone).date.plus(input.int("dueInDays") ?: 30, DateTimeUnit.DAY)
        val payment = PaymentRepository(ctx.services.mongo, ctx.tenant.id).create(
            supplierId = if (employee) null else payee,
            employeeId = if (employee) payee else null,
            items = listOf(lineItem(input.string("description") ?: "", unitPriceEur = amount)),
            dueDate = due,
            notes = null,
            clientId = ctx.clientId(),
        )
        return ActionResult.Done(buildJsonObject { put("paymentId", payment.id.toHexString()); put("number", payment.number) })
    }
}

/** A priced Serviços row on the record's client. */
object CreateServiceAction : AgentAction {
    override val key = "crm.service.create"
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.SERVICES)
    override val toolDescription = "Record work done for the client the agent works on, with a price in euros, ready to be invoiced."
    override val outputSchema = Schema.obj("serviceId" to Schema.string())
    override val inputSchema = Schema.obj(
        "name" to Schema.string(widget = "template", maxLength = 200),
        "amountEur" to Schema.number(min = 0.0, max = 10_000_000.0),
        "quantity" to Schema.number(min = 0.01, max = 100_000.0, default = 1.0),
        "unit" to Schema.string(maxLength = 30),
        required = listOf("name", "amountEur"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        subject = ctx.run.subjectLabel,
        fields = listOf("name", "amountEur", "quantity").mapNotNull { key -> input[key]?.let { key to (it as? JsonPrimitive)?.content.orEmpty() } }.toMap(),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val clientId = ctx.clientId() ?: return ActionResult.Skipped("no_client")
        val amount = input.number("amountEur") ?: return ActionResult.Failed("amount_required")
        val row = ClientServiceRepository(ctx.services.mongo, ctx.tenant.id).create(
            clientId = clientId,
            name = input.string("name") ?: return ActionResult.Failed("name_required"),
            notes = null,
            quantity = input.number("quantity") ?: 1.0,
            unit = input.string("unit").orEmpty(),
            unitPriceCents = (amount * 100).roundToLong(),
            bookingServiceId = null,
            catalogItemId = null,
            performedAt = ctx.now.toLocalDateTime(ctx.zone).date,
        )
        return ActionResult.Done(buildJsonObject { put("serviceId", row.id.toHexString()) })
    }
}

/** Confirms or cancels the record's booking, with the same rules as staff in the dashboard. */
class SetBookingStatusAction(override val key: String, private val status: BookingStatus) : AgentAction {
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.BOOKINGS)
    override val subjectTypes = setOf(SubjectTypes.BOOKING)
    override val toolDescription = if (status == BookingStatus.CONFIRMED) "Confirm the booking the agent works on." else "Cancel the booking the agent works on."
    override val inputSchema = Schema.obj()

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        subject = ctx.run.subjectLabel,
        fields = mapOf("from" to AgentMessaging.variable(ctx, "booking.status").orEmpty(), "to" to status.name),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val id = ctx.subjectId() ?: return ActionResult.Skipped("no_record")
        return try {
            val booking = bookingDeps(ctx.services.mongo, ctx.tenant, BookingSource.ASSISTANT).scheduler.setStatus(id, status, BookingSource.ASSISTANT)
            ActionResult.Done(buildJsonObject { put("bookingId", booking.id.toHexString()); put("status", booking.status.name) })
        } catch (e: BookingRuleException) {
            ActionResult.Failed(e.message ?: "booking_rule")
        }
    }
}

/** Books a catalog service for the record's client (or the given contact) at a moment. */
object CreateBookingAction : AgentAction {
    override val key = "booking.create"
    override val category = ActionCategory.CRM
    override val sideEffect = SideEffect.INTERNAL_WRITE
    override val requiredModules = setOf(DashboardModules.BOOKINGS)
    override val toolDescription = "Book a service (catalog id) at an ISO date-time for the client the agent works on, or for a given name and phone."
    override val outputSchema = Schema.obj("bookingId" to Schema.string(), "status" to Schema.string())
    override val inputSchema = Schema.obj(
        "serviceId" to Schema.string(widget = "bookable-service"),
        "startAt" to Schema.string(widget = "template", description = "ISO-8601 instant"),
        "contactName" to Schema.string(widget = "template", maxLength = 200),
        "contactPhone" to Schema.string(widget = "template", maxLength = 40),
        "notes" to Schema.string(widget = "template", maxLength = 1000),
        required = listOf("serviceId", "startAt"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "crm",
        fields = listOf("serviceId", "startAt", "contactName", "contactPhone").mapNotNull { key -> input.string(key)?.let { key to it } }.toMap(),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val startAt = input.string("startAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ActionResult.Failed("invalid_time")
        val deps = bookingDeps(ctx.services.mongo, ctx.tenant, BookingSource.ASSISTANT)
        return try {
            val booking = deps.scheduler.create(
                NewBooking(
                    serviceId = input.string("serviceId") ?: return ActionResult.Failed("service_required"),
                    startAt = startAt,
                    contactName = input.string("contactName") ?: AgentMessaging.variable(ctx, "client.name"),
                    contactPhone = input.string("contactPhone") ?: AgentMessaging.variable(ctx, "client.phone"),
                    clientId = ctx.clientId(),
                    notes = input.string("notes"),
                    source = BookingSource.ASSISTANT,
                ),
            )
            ActionResult.Done(buildJsonObject { put("bookingId", booking.id.toHexString()); put("status", booking.status.name) })
        } catch (e: BookingRuleException) {
            ActionResult.Failed(e.message ?: "booking_rule")
        } catch (e: com.rfm.edubot.bookings.BookingConflictException) {
            ActionResult.Failed("conflict")
        }
    }
}
