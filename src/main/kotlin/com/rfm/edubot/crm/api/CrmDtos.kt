package com.rfm.edubot.crm.api

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.SupplierRepository
import org.bson.types.ObjectId
import com.rfm.edubot.crm.lines
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientService
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.Supplier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

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
    val customFields: Map<String, JsonPrimitive> = emptyMap(),
    /** The catalog items the client usually buys, as catalog ids. */
    val catalogItemIds: List<String> = emptyList(),
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
    /** The project whose measurements bill this quote: it can't be invoiced or turned into a service any more. */
    val projectId: String? = null,
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
    val paidEur: Double,
    val outstandingEur: Double,
    val taxOfficeCode: String? = null,
    val installments: List<InvoiceInstallmentDto> = emptyList(),
    /**
     * The project and measurement the invoice bills, when a measurement made it, or the project whose retention it
     * gives back ([retentionRelease]); the label, number and flag only on its own record.
     */
    val projectId: String? = null,
    val projectLabel: String? = null,
    val measurementId: String? = null,
    val measurementNumber: Int? = null,
    val retentionRelease: Boolean = false,
)

@Serializable
internal data class InvoiceInstallmentDto(val amountEur: Double, val dueDate: String, val paidAt: String? = null)

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
    val services: List<SupplierServiceDto> = emptyList(),
)

@Serializable
internal data class SupplierServiceDto(val description: String, val unit: String = "", val unitPriceEur: Double? = null)

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
    val projectId: String? = null,
    val projectLabel: String = "",
    val status: String,
    val dueDate: String,
    val totalEur: Double,
    val notes: String? = null,
    val paidAt: String? = null,
    val items: List<LineItemDto> = emptyList(),
    val createdAt: String,
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
    /** Who does or did the work. */
    val employeeId: String? = null,
    val employeeName: String = "",
    /** The quote the row was made from. */
    val quoteId: String? = null,
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
    customFields = customFields,
    catalogItemIds = catalogItemIds,
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
    projectId = projectId?.toHexString(),
)

internal fun ClientService.dto(client: Client?, employee: Employee? = null) = ClientServiceDto(
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
    employeeName = employee?.name ?: "",
    quoteId = quoteId?.toHexString(),
)

internal fun Invoice.dto(
    client: Client?,
    quoteNumber: String? = null,
    projectLabel: String? = null,
    measurementNumber: Int? = null,
    retentionRelease: Boolean = false,
) = InvoiceDto(
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
    paidEur = paidCents / 100.0,
    outstandingEur = outstandingCents / 100.0,
    taxOfficeCode = taxOfficeCode,
    installments = installments.map { InvoiceInstallmentDto(it.amountCents / 100.0, it.dueDate.toString(), it.paidAt?.toString()) },
    projectId = projectId?.toHexString(),
    projectLabel = projectLabel,
    measurementId = measurementId?.toHexString(),
    measurementNumber = measurementNumber,
    retentionRelease = retentionRelease,
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
    services = services.map { SupplierServiceDto(it.description, it.unit, it.unitPriceCents?.let { cents -> cents / 100.0 }) },
)

/** These quotes as lists show them, their clients read in one query. */
@JvmName("quoteDtos")
internal suspend fun List<Quote>.dtos(clients: ClientRepository): List<QuoteDto> {
    val byId = clients.findByIds(map { it.clientId })
    return map { it.dto(byId[it.clientId]) }
}

/** These invoices as lists show them, their clients read in one query. */
@JvmName("invoiceDtos")
internal suspend fun List<Invoice>.dtos(clients: ClientRepository): List<InvoiceDto> {
    val byId = clients.findByIds(map { it.clientId })
    return map { it.dto(byId[it.clientId]) }
}

/** These Serviços rows as lists show them, their clients read in one query. */
@JvmName("clientServiceDtos")
/** With [employees], each row also names who does the work. */
internal suspend fun List<ClientService>.dtos(clients: ClientRepository, employees: EmployeeRepository? = null): List<ClientServiceDto> {
    val byId = clients.findByIds(map { it.clientId })
    val employeeById = employees?.findByIds(mapNotNull { it.employeeId }).orEmpty()
    return map { it.dto(byId[it.clientId], it.employeeId?.let(employeeById::get)) }
}

/**
 * These payments as lists show them, with one query per kind of payee and one for the clients; [projectLabels]
 * names the projects they were for.
 */
@JvmName("paymentDtos")
internal suspend fun List<Payment>.dtos(
    suppliers: SupplierRepository,
    employees: EmployeeRepository,
    clients: ClientRepository,
    projectLabels: Map<ObjectId, String> = emptyMap(),
): List<PaymentDto> {
    val supplierById = suppliers.findByIds(mapNotNull { it.supplierId })
    val employeeById = employees.findByIds(mapNotNull { it.employeeId })
    val clientById = clients.findByIds(mapNotNull { it.clientId })
    return map { payment ->
        payment.dto(
            supplier = payment.supplierId?.let { supplierById[it] },
            employee = payment.employeeId?.let { employeeById[it] },
            client = payment.clientId?.let { clientById[it] },
            projectLabel = payment.projectId?.let { projectLabels[it] },
        )
    }
}

internal fun Payment.dto(supplier: Supplier?, employee: Employee? = null, client: Client? = null, projectLabel: String? = null) = PaymentDto(
    id = id.toHexString(),
    number = number,
    supplierId = supplierId?.toHexString(),
    supplierName = supplier?.name ?: "",
    employeeId = employeeId?.toHexString(),
    employeeName = employee?.name ?: "",
    clientId = clientId?.toHexString(),
    clientName = client?.name ?: "",
    projectId = projectId?.toHexString(),
    projectLabel = projectLabel.orEmpty(),
    status = status.name,
    dueDate = dueDate.toString(),
    totalEur = totalCents / 100.0,
    notes = notes,
    paidAt = paidAt?.toString(),
    items = items.map { LineItemDto(it.description, it.quantity, it.unit, it.unitPriceCents / 100.0) },
    createdAt = createdAt.toString(),
)
