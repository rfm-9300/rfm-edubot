package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class CrmClient(
    val id: String,
    val number: String = "",
    val name: String,
    val phone: String,
    val address: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val contactPerson: String? = null,
    val email: String? = null,
    /** NIF. The backend refuses to save a client without it, so forms must collect it. */
    val taxId: String? = null,
    val notes: String? = null,
    val createdAt: String = "",
    val updatedAt: String? = null,
    val archivedAt: String? = null,
    val automationPaused: Boolean = false,
) {
    val archived: Boolean get() = archivedAt != null
}

/**
 * `POST`/`PATCH /app/api/crm/clients`. [taxId] and [address] are required by the backend
 * (`tax_id_required` / `address_required`); everything else is optional.
 */
@Serializable
data class SaveClient(
    val name: String,
    val phone: String,
    val taxId: String,
    val address: String,
    val email: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val contactPerson: String? = null,
    val notes: String? = null,
)

@Serializable
data class LineItem(
    val description: String,
    val quantity: Double = 1.0,
    val unit: String = "",
    val unitPriceEur: Double = 0.0,
) {
    val totalEur: Double get() = quantity * unitPriceEur
}

@Serializable
data class Quote(
    val id: String,
    val number: String = "",
    val clientId: String = "",
    val clientName: String = "",
    val status: String,
    val totalEur: Double = 0.0,
    val validUntil: String? = null,
    val hasPdf: Boolean = false,
    val createdAt: String = "",
    val notes: String? = null,
    val items: List<LineItem> = emptyList(),
)

@Serializable
data class Invoice(
    val id: String,
    val number: String = "",
    val clientId: String = "",
    val clientName: String = "",
    val status: String,
    val dueDate: String = "",
    val totalEur: Double = 0.0,
    val hasPdf: Boolean = false,
    val createdAt: String = "",
    val quoteId: String? = null,
    val quoteNumber: String? = null,
    val paidAt: String? = null,
    val items: List<LineItem> = emptyList(),
) {
    val paid: Boolean get() = paidAt != null || status.equals("PAGA", ignoreCase = true) || status.equals("PAID", ignoreCase = true)
}

@Serializable
data class CatalogItem(
    val id: String = "",
    val type: String,
    val category: String = "",
    val description: String = "",
    val unit: String = "",
    val defaultUnitPriceEur: Double = 0.0,
    val durationMinutes: Int? = null,
    val bookable: Boolean = false,
    val title: String = "",
    val code: String? = null,
) {
    val label: String get() = title.takeIf { it.isNotBlank() } ?: description
}

/** `POST /app/api/crm/standard-items`. An item saved without an [id] gets one from its title. */
@Serializable
data class SaveCatalogItem(
    val type: String,
    val category: String,
    val unit: String,
    val defaultUnitPriceEur: Double,
    val title: String,
    val description: String = "",
    val code: String? = null,
    val durationMinutes: Int? = null,
    val bookable: Boolean? = null,
)

@Serializable
data class CreateQuote(
    val clientId: String,
    val items: List<LineItem>,
    val notes: String? = null,
    val validUntil: String? = null,
)

@Serializable
data class CreateInvoice(
    val clientId: String,
    val items: List<LineItem>,
    val dueDate: String,
    val quoteId: String? = null,
)

/** `POST /app/api/crm/quotes/{id}/invoice`. */
@Serializable
data class ConvertQuote(val dueDate: String)

@Serializable
data class QuoteStatusChange(val status: String)

@Serializable
data class ClientService(
    val id: String,
    val clientId: String = "",
    val clientName: String = "",
    val name: String,
    val notes: String? = null,
    val quantity: Double = 1.0,
    val unit: String = "",
    val unitPriceEur: Double = 0.0,
    val totalEur: Double = 0.0,
    val items: List<LineItem> = emptyList(),
    val status: String = "",
    val invoiceId: String? = null,
    val bookingId: String? = null,
    val performedAt: String? = null,
    val createdAt: String = "",
) {
    val invoiced: Boolean get() = invoiceId != null
}

/** `POST /app/api/crm/services/invoice`: bills several finished jobs as one invoice. */
@Serializable
data class InvoiceClientServices(
    val clientId: String,
    val serviceIds: List<String>,
    val dueDate: String,
)

@Serializable
data class Supplier(
    val id: String,
    val number: String = "",
    val name: String,
    val phone: String = "",
    val address: String? = null,
    val type: String? = null,
    val createdAt: String = "",
    val archivedAt: String? = null,
)

@Serializable
data class Employee(
    val id: String,
    val number: String = "",
    val name: String,
    val phone: String = "",
    val role: String? = null,
    val birthDate: String? = null,
    val address: String? = null,
    val taxId: String? = null,
    val createdAt: String = "",
    val archivedAt: String? = null,
)

@Serializable
data class Payment(
    val id: String,
    val number: String = "",
    val supplierId: String? = null,
    val supplierName: String = "",
    val employeeId: String? = null,
    val employeeName: String = "",
    val clientId: String? = null,
    val clientName: String = "",
    val status: String,
    val dueDate: String = "",
    val totalEur: Double = 0.0,
    val notes: String? = null,
    val paidAt: String? = null,
    val items: List<LineItem> = emptyList(),
    val createdAt: String = "",
) {
    val payeeName: String get() = supplierName.takeIf { it.isNotBlank() } ?: employeeName

    val paid: Boolean get() = paidAt != null
}

@Serializable
data class DeletedResource(val deleted: Boolean = false)

@Serializable
data class ArchiveState(val archivedAt: String? = null)
