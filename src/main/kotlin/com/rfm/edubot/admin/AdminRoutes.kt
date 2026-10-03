package com.rfm.edubot.admin

import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.Supplier
import com.rfm.edubot.crm.MIN_BOOKING_MINUTES
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.lines
import com.rfm.edubot.shared.SystemClock
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

fun Route.adminRoutes() {
    // The old tenant CRM at /admin is retired. Clients use /app; you configure
    // tenants in /backoffice. Shared CSS/JS/catalogs stay under /admin/{asset}.
    get("/admin") { call.respondRedirect("/backoffice/") }
    get("/admin/") { call.respondRedirect("/backoffice/") }
    get("/admin/{asset}") {
        val asset = call.parameters["asset"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val contentType = when (asset.substringAfterLast('.', "")) {
            "js" -> ContentType.Application.JavaScript
            "css" -> ContentType.Text.CSS
            else -> ContentType.Application.OctetStream
        }
        val bytes = this::class.java.classLoader.getResource("admin/$asset")?.readBytes()
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, contentType)
    }
}

/** Always builds a fresh PDF from the current document + template. Never reads or writes a saved file. */
internal suspend fun io.ktor.server.application.ApplicationCall.respondGeneratedPdf(
    generate: () -> ByteArray,
) {
    respondBytes(generate(), ContentType.Application.Pdf)
}

/** Also the PATCH body: omitted details other than [address] keep what is stored; an empty string clears them. */
@Serializable
internal data class CreateClientRequest(
    val name: String,
    val phone: String,
    val address: String? = null,
    val email: String? = null,
    val taxId: String? = null,
    val notes: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val contactPerson: String? = null,
) {
    /**
     * Stable error code when staff save a client without its NIF or address, or null. On an update an
     * omitted NIF keeps [existing]'s. Bookings and the bot still create clients from a name and phone.
     */
    fun requiredError(existing: Client? = null): String? = when {
        (taxId ?: existing?.taxId).isNullOrBlank() -> "tax_id_required"
        address.isNullOrBlank() -> "address_required"
        else -> null
    }

    /** Stable error code for the first invalid optional field, or null. */
    fun detailsError(): String? {
        val mail = email?.trim().orEmpty()
        return when {
            mail.length > MAX_EMAIL || (mail.isNotEmpty() && !EMAIL_SHAPE.matches(mail)) -> "invalid_email"
            (taxId?.trim()?.length ?: 0) > MAX_TAX_ID -> "tax_id_too_long"
            (notes?.trim()?.length ?: 0) > MAX_NOTES -> "notes_too_long"
            (address?.trim()?.length ?: 0) > MAX_ADDRESS -> "address_too_long"
            (postalCode?.trim()?.length ?: 0) > MAX_POSTAL_CODE -> "postal_code_too_long"
            (city?.trim()?.length ?: 0) > MAX_CITY -> "city_too_long"
            (contactPerson?.trim()?.length ?: 0) > MAX_CONTACT_PERSON -> "contact_person_too_long"
            else -> null
        }
    }

    companion object {
        const val MAX_EMAIL = 254
        const val MAX_TAX_ID = 32
        const val MAX_NOTES = 4000
        const val MAX_ADDRESS = 300
        const val MAX_POSTAL_CODE = 20
        const val MAX_CITY = 100
        const val MAX_CONTACT_PERSON = 120
        private val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

@Serializable
internal data class CreateQuoteRequest(
    val clientId: String,
    val items: List<CreateLineItemRequest>,
    val notes: String? = null,
    val validUntil: String? = null,
)

@Serializable
internal data class CreateInvoiceRequest(
    val clientId: String,
    val quoteId: String? = null,
    val items: List<CreateLineItemRequest>,
    val dueDate: String,
)

@Serializable
internal data class CreateLineItemRequest(
    val description: String,
    val quantity: Double = 1.0,
    val unit: String = "",
    val unitPriceEur: Double,
) {
    fun toLineItem() = lineItem(description, quantity, unitPriceEur, unit)
}

/**
 * Create and update body of a catalog item. Clients that predate titles (the mobile app) send only a
 * description, which then is the title. A new item without an [id] gets one from its title.
 */
@Serializable
internal data class StandardItemRequest(
    val id: String? = null,
    val type: String,
    val category: String,
    val description: String = "",
    val unit: String,
    val defaultUnitPriceEur: Double,
    val durationMinutes: Int? = null,
    val bookable: Boolean? = null,
    val title: String? = null,
    val code: String? = null,
) {
    private val titleText: String get() = title?.trim()?.takeIf { it.isNotBlank() } ?: description.trim()
    private val codeText: String? get() = code?.trim()?.takeIf { it.isNotBlank() }

    /** Stable error code for the first invalid field, or null. */
    fun error(): String? = when {
        titleText.isBlank() -> "title_required"
        (codeText?.length ?: 0) > MAX_CODE -> "code_too_long"
        else -> null
    }

    /** Omitted booking fields and code keep [existing]'s values, so clients that don't know them never reset them. */
    fun toStandardItem(itemId: String, existing: StandardItem? = null) = StandardItem(
        id = itemId.trim(),
        type = type.trim().lowercase(),
        category = category.trim(),
        description = description.trim().ifBlank { titleText },
        unit = unit.trim(),
        defaultUnitPriceEur = defaultUnitPriceEur,
        durationMinutes = durationMinutes?.takeIf { it >= MIN_BOOKING_MINUTES } ?: existing?.durationMinutes,
        bookable = bookable ?: existing?.bookable ?: false,
        title = titleText,
        code = codeText ?: existing?.code,
    )

    companion object {
        const val MAX_CODE = 40
    }
}

/** `POST …/standard-items` for the dashboard and the backoffice: 201 with the saved item, or 400/409 with a stable error code. */
internal suspend fun ApplicationCall.createStandardItem(items: StandardItemRepository) {
    val request = receive<StandardItemRequest>()
    request.error()?.let { return respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
    val requestedId = request.id?.trim()?.takeIf { it.isNotBlank() }
    if (requestedId != null && items.findById(requestedId) != null) return respond(HttpStatusCode.Conflict, mapOf("error" to "id_taken"))
    val draft = request.toStandardItem(requestedId.orEmpty())
    val item = if (requestedId != null) draft else draft.copy(id = items.freeId(draft.title, draft.type))
    respondCatalogWrite(HttpStatusCode.Created) { items.create(item) }
}

/** `POST …/standard-items/{id}`: the internal id never changes, the code may. */
internal suspend fun ApplicationCall.updateStandardItem(items: StandardItemRepository, id: String) {
    val existing = items.findById(id) ?: return respond(HttpStatusCode.NotFound)
    val request = receive<StandardItemRequest>()
    request.error()?.let { return respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
    respondCatalogWrite(HttpStatusCode.OK) { items.update(id, request.toStandardItem(id, existing)) }
}

/** Ids are checked or generated free before writing, so a duplicate key here is another item's code. */
private suspend fun ApplicationCall.respondCatalogWrite(status: HttpStatusCode, write: suspend () -> StandardItem?) {
    val saved = try {
        write()
    } catch (e: MongoServerException) {
        if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
        return respond(HttpStatusCode.Conflict, mapOf("error" to "code_taken"))
    }
    if (saved == null) respond(HttpStatusCode.NotFound) else respond(status, saved)
}

@Serializable
internal data class ClientDto(
    val id: String,
    val number: String,
    val name: String,
    val phone: String,
    val address: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val contactPerson: String? = null,
    val email: String? = null,
    val taxId: String? = null,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String? = null,
    val archivedAt: String? = null,
    val automationPaused: Boolean = false,
)

@Serializable
internal data class LineItemDto(
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceEur: Double,
)

@Serializable
internal data class QuoteDto(
    val id: String,
    val number: String,
    val clientId: String,
    val clientName: String,
    val status: String,
    val totalEur: Double,
    val validUntil: String?,
    val hasPdf: Boolean,
    val createdAt: String,
    val notes: String? = null,
    val items: List<LineItemDto> = emptyList(),
)

@Serializable
internal data class InvoiceDto(
    val id: String,
    val number: String,
    val clientId: String,
    val clientName: String,
    val status: String,
    val dueDate: String,
    val totalEur: Double,
    val hasPdf: Boolean,
    val createdAt: String,
    val quoteId: String? = null,
    val quoteNumber: String? = null,
    val paidAt: String? = null,
    val items: List<LineItemDto> = emptyList(),
)

/** Also the PATCH body: an omitted [type] keeps the stored one; an empty string clears it. */
@Serializable
internal data class CreateSupplierRequest(
    val name: String,
    val phone: String,
    val address: String? = null,
    val type: String? = null,
) {
    /** Stable error code for the first invalid optional field, or null. */
    fun detailsError(): String? = if ((type?.trim()?.length ?: 0) > MAX_TYPE) "type_too_long" else null

    companion object {
        const val MAX_TYPE = 60
    }
}

/** Also the PATCH body: omitted [birthDate], [address] and [taxId] keep what is stored; an empty string clears them. */
@Serializable
internal data class CreateEmployeeRequest(
    val name: String,
    val phone: String,
    val role: String? = null,
    /** `yyyy-MM-dd`. */
    val birthDate: String? = null,
    val address: String? = null,
    val taxId: String? = null,
) {
    /** Stable error code for the first invalid optional field, or null. */
    fun detailsError(today: LocalDate = SystemClock.now().toLocalDateTime(TimeZone.UTC).date): String? {
        val born = birthDate?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            born != null && !plausibleBirthDate(born, today) -> "invalid_birth_date"
            (address?.trim()?.length ?: 0) > CreateClientRequest.MAX_ADDRESS -> "address_too_long"
            (taxId?.trim()?.length ?: 0) > CreateClientRequest.MAX_TAX_ID -> "tax_id_too_long"
            else -> null
        }
    }

    private fun plausibleBirthDate(raw: String, today: LocalDate): Boolean {
        val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return false
        return date.year >= MIN_BIRTH_YEAR && date <= today
    }

    companion object {
        const val MIN_BIRTH_YEAR = 1900
    }
}

@Serializable
internal data class CreatePaymentRequest(
    val supplierId: String? = null,
    val employeeId: String? = null,
    val items: List<CreateLineItemRequest>,
    val dueDate: String,
    val notes: String? = null,
    val clientId: String? = null,
)

@Serializable
internal data class SupplierDto(
    val id: String,
    val number: String,
    val name: String,
    val phone: String,
    val address: String? = null,
    val type: String? = null,
    val createdAt: String,
    val archivedAt: String? = null,
)

@Serializable
internal data class EmployeeDto(
    val id: String,
    val number: String,
    val name: String,
    val phone: String,
    val role: String? = null,
    val birthDate: String? = null,
    val address: String? = null,
    val taxId: String? = null,
    val createdAt: String,
    val archivedAt: String? = null,
)

@Serializable
internal data class PaymentDto(
    val id: String,
    val number: String,
    val supplierId: String? = null,
    val supplierName: String = "",
    val employeeId: String? = null,
    val employeeName: String = "",
    val clientId: String? = null,
    val clientName: String = "",
    val status: String,
    val dueDate: String,
    val totalEur: Double,
    val notes: String? = null,
    val paidAt: String? = null,
    val items: List<LineItemDto> = emptyList(),
    val createdAt: String,
)

internal fun Client.dto() = ClientDto(
    id = id.toHexString(),
    number = number,
    name = name,
    phone = phone,
    address = address,
    postalCode = postalCode,
    city = city,
    contactPerson = contactPerson,
    email = email,
    taxId = taxId,
    notes = notes,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    archivedAt = archivedAt?.toString(),
    automationPaused = automationPaused,
)

internal fun Quote.dto(client: Client?) = QuoteDto(
    id = id.toHexString(),
    number = number,
    clientId = clientId.toHexString(),
    clientName = client?.name ?: "",
    status = status.name,
    totalEur = totalCents / 100.0,
    validUntil = validUntil?.toString(),
    hasPdf = true,
    createdAt = createdAt.toString(),
    notes = notes,
    items = items.map { LineItemDto(it.description, it.quantity, it.unit, it.unitPriceCents / 100.0) },
)

@Serializable
internal data class ClientServiceDto(
    val id: String,
    val clientId: String,
    val clientName: String,
    val name: String,
    val notes: String? = null,
    val quantity: Double,
    val unit: String,
    val unitPriceEur: Double,
    val totalEur: Double,
    /** Always sent: the row's items, or the one line an older row is. */
    val items: List<LineItemDto>,
    val status: String,
    val invoiceId: String? = null,
    val bookingServiceId: String? = null,
    val catalogItemId: String? = null,
    val bookingId: String? = null,
    val performedAt: String? = null,
    val createdAt: String,
    /** Who did the work, when the row came from an employee's registered service. */
    val employeeId: String? = null,
)

/** Without [items] the row is the one line [name], [quantity], [unit] and [unitPriceEur] describe. */
@Serializable
internal data class CreateClientServiceRequest(
    val clientId: String,
    val name: String,
    val notes: String? = null,
    val quantity: Double = 1.0,
    val unit: String = "",
    val unitPriceEur: Double = 0.0,
    val items: List<CreateLineItemRequest> = emptyList(),
    val bookingServiceId: String? = null,
    val catalogItemId: String? = null,
    val performedAt: String? = null,
)

/** Omitted [items] keep the stored ones. */
@Serializable
internal data class UpdateClientServiceRequest(
    val name: String? = null,
    val notes: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val unitPriceEur: Double? = null,
    val performedAt: String? = null,
    val status: String? = null,
    val items: List<CreateLineItemRequest>? = null,
)

/** Stable error code for the first invalid service line, or null. */
internal fun serviceItemsError(items: List<CreateLineItemRequest>?): String? = when {
    items == null -> null
    items.size > MAX_SERVICE_ITEMS -> "too_many_items"
    items.any { it.description.isBlank() } -> "item_description_required"
    items.any { it.quantity <= 0.0 } -> "invalid_quantity"
    else -> null
}

private const val MAX_SERVICE_ITEMS = 100

@Serializable
internal data class InvoiceClientServicesRequest(
    val clientId: String,
    val serviceIds: List<String>,
    val dueDate: String,
)

internal fun com.rfm.edubot.crm.model.ClientService.dto(client: Client?) = ClientServiceDto(
    id = id.toHexString(),
    clientId = clientId.toHexString(),
    clientName = client?.name ?: "",
    name = name,
    notes = notes,
    quantity = quantity,
    unit = unit,
    unitPriceEur = unitPriceCents / 100.0,
    totalEur = totalCents / 100.0,
    items = lines().map { LineItemDto(it.description, it.quantity, it.unit, it.unitPriceCents / 100.0) },
    status = status.name,
    invoiceId = invoiceId?.toHexString(),
    bookingServiceId = bookingServiceId?.toHexString(),
    catalogItemId = catalogItemId,
    bookingId = bookingId?.toHexString(),
    performedAt = performedAt?.toString(),
    createdAt = createdAt.toString(),
    employeeId = employeeId?.toHexString(),
)

internal fun Invoice.dto(client: Client?, quoteNumber: String? = null) = InvoiceDto(
    id = id.toHexString(),
    number = number,
    clientId = clientId.toHexString(),
    clientName = client?.name ?: "",
    status = status.name,
    dueDate = dueDate.toString(),
    totalEur = totalCents / 100.0,
    hasPdf = true,
    createdAt = createdAt.toString(),
    quoteId = quoteId?.toHexString(),
    quoteNumber = quoteNumber,
    paidAt = paidAt?.toString(),
    items = items.map { LineItemDto(it.description, it.quantity, it.unit, it.unitPriceCents / 100.0) },
)

internal fun Employee.dto() = EmployeeDto(
    id = id.toHexString(),
    number = number,
    name = name,
    phone = phone,
    role = role,
    birthDate = birthDate?.toString(),
    address = address,
    taxId = taxId,
    createdAt = createdAt.toString(),
    archivedAt = archivedAt?.toString(),
)

internal fun Supplier.dto() = SupplierDto(
    id = id.toHexString(),
    number = number,
    name = name,
    phone = phone,
    address = address,
    type = type,
    createdAt = createdAt.toString(),
    archivedAt = archivedAt?.toString(),
)

internal fun Payment.dto(supplier: Supplier?, employee: Employee? = null, client: Client? = null) = PaymentDto(
    id = id.toHexString(),
    number = number,
    supplierId = supplierId?.toHexString(),
    supplierName = supplier?.name ?: "",
    employeeId = employeeId?.toHexString(),
    employeeName = employee?.name ?: "",
    clientId = clientId?.toHexString(),
    clientName = client?.name ?: "",
    status = status.name,
    dueDate = dueDate.toString(),
    totalEur = totalCents / 100.0,
    notes = notes,
    paidAt = paidAt?.toString(),
    items = items.map { LineItemDto(it.description, it.quantity, it.unit, it.unitPriceCents / 100.0) },
    createdAt = createdAt.toString(),
)
