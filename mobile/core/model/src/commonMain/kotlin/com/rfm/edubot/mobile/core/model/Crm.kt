package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * The backend leaves out every property equal to its default (`encodeDefaults` is off there), so every
 * field the server can default carries the same default here: a missing list is empty, a missing
 * number is zero.
 */

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
    val taxId: String? = null,
    val notes: String? = null,
    val createdAt: String = "",
    val updatedAt: String? = null,
    val archivedAt: String? = null,
    val automationPaused: Boolean = false,
    /** Values of the company's own client fields, by field key ([CustomField.key]). */
    val customFields: Map<String, JsonPrimitive> = emptyMap(),
    /** The catalog items the client usually buys. */
    val catalogItemIds: List<String> = emptyList(),
) {
    val archived: Boolean get() = archivedAt != null
}

/**
 * `GET /app/api/crm/clients/fields`: which client fields this company asks staff for. Each company
 * chooses, so the form is built from this instead of hard-coding which fields are required.
 */
@Serializable
data class DirectoryFields(
    /** In form order. Name and phone come `locked` (always required). */
    val standard: List<StandardField> = emptyList(),
    val custom: List<CustomField> = emptyList(),
    /** Whether this user may change the fields (admins). The app only reads them. */
    val canEdit: Boolean = false,
) {
    fun isRequired(key: String): Boolean = standard.any { it.key == key && it.required }

    companion object {
        const val NAME = "name"
        const val PHONE = "phone"
        const val TAX_ID = "taxId"
        const val EMAIL = "email"
        const val CONTACT_PERSON = "contactPerson"
        const val ADDRESS = "address"
        const val POSTAL_CODE = "postalCode"
        const val CITY = "city"

        /**
         * What a company that never chose asks for (the backend's `ClientFields.DEFAULT`): NIF and
         * address. Used only until the real definitions arrive, never to refuse a save on its own.
         */
        val DEFAULT = DirectoryFields(
            standard = listOf(
                StandardField(NAME, required = true, locked = true),
                StandardField(PHONE, required = true, locked = true),
                StandardField(TAX_ID, required = true),
                StandardField(EMAIL),
                StandardField(CONTACT_PERSON),
                StandardField(ADDRESS, required = true),
                StandardField(POSTAL_CODE),
                StandardField(CITY),
            ),
        )
    }
}

@Serializable
data class StandardField(val key: String, val required: Boolean = false, val locked: Boolean = false)

/** One of the company's own client fields. [type] is `TEXT`, `NUMBER`, `DATE`, `SELECT` or `CHECKBOX`. */
@Serializable
data class CustomField(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean = false,
    val showInList: Boolean = false,
    val options: List<String> = emptyList(),
) {
    companion object {
        const val TEXT = "TEXT"
        const val NUMBER = "NUMBER"
        const val DATE = "DATE"
        const val SELECT = "SELECT"
        const val CHECKBOX = "CHECKBOX"
    }
}

/**
 * `POST`/`PATCH /app/api/crm/clients`. On a PATCH an omitted detail keeps what is stored and an empty
 * string clears it, so the form sends every field it shows. Which fields are required is the
 * company's choice ([DirectoryFields]); the backend answers `tax_id_required`, `email_required`,
 * `custom_field_required`… for a missing one.
 */
@Serializable
data class SaveClient(
    val name: String,
    val phone: String,
    val taxId: String? = null,
    val address: String? = null,
    val email: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val contactPerson: String? = null,
    val notes: String? = null,
    /** Only the custom values the form shows; a JSON null or empty string clears one. */
    val customFields: Map<String, JsonElement>? = null,
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
    /** Set once a project's measurements bill this quote: it can't be invoiced or serviced any more. */
    val projectId: String? = null,
) {
    val accepted: Boolean get() = status.equals("ACEITO", ignoreCase = true)
}

@Serializable
data class InvoiceInstallment(val amountEur: Double = 0.0, val dueDate: String = "", val paidAt: String? = null) {
    val paid: Boolean get() = paidAt != null
}

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
    val paidEur: Double = 0.0,
    val outstandingEur: Double = 0.0,
    /** The tax office's ATCUD, typed by staff. */
    val taxOfficeCode: String? = null,
    val installments: List<InvoiceInstallment> = emptyList(),
    val projectId: String? = null,
    val projectLabel: String? = null,
    val measurementId: String? = null,
    val measurementNumber: Int? = null,
    val retentionRelease: Boolean = false,
) {
    val paid: Boolean get() = paidAt != null || status.equals("PAGA", ignoreCase = true) || status.equals("PAID", ignoreCase = true)

    val cancelled: Boolean get() = status.equals("CANCELLED", ignoreCase = true)

    /** Cancelled or paid: nothing more can be received or changed. */
    val closed: Boolean get() = paid || cancelled
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

    val isService: Boolean get() = type == "service" || type == "servico"
}

/** `GET /app/api/crm/catalog-categories`. */
@Serializable
data class CatalogCategory(val name: String, val itemCount: Int = 0)

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

/** `POST /app/api/crm/quotes/{id}/service`: without [performedAt] the work is dated today. */
@Serializable
data class QuoteToService(val performedAt: String? = null, val name: String? = null)

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
    val bookingServiceId: String? = null,
    val catalogItemId: String? = null,
    val bookingId: String? = null,
    val performedAt: String? = null,
    val createdAt: String = "",
    /** Who does or did the work. */
    val employeeId: String? = null,
    val employeeName: String = "",
    /** The quote the row was made from. */
    val quoteId: String? = null,
) {
    val invoiced: Boolean get() = invoiceId != null

    val cancelled: Boolean get() = status.equals("CANCELLED", ignoreCase = true)

    /** The day the work counts on: when it was done, else when it was registered. */
    val day: String get() = performedAt?.takeIf { it.isNotBlank() } ?: createdAt.take(10)
}

/**
 * `POST /app/api/crm/services`. The lines are the work; the backend keeps [items] and names the row
 * from them when [name] is a summary of the lines.
 */
@Serializable
data class CreateService(
    val clientId: String,
    val name: String,
    val items: List<LineItem>,
    val performedAt: String? = null,
    val notes: String? = null,
    val employeeId: String? = null,
    val catalogItemId: String? = null,
)

/** `PATCH /app/api/crm/services/{id}`: an omitted field keeps what is stored; `employeeId = ""` clears it. */
@Serializable
data class UpdateService(
    val status: String? = null,
    val employeeId: String? = null,
)

/** `POST /app/api/crm/services/invoice`: bills several finished jobs as one invoice. */
@Serializable
data class InvoiceClientServices(
    val clientId: String,
    val serviceIds: List<String>,
    val dueDate: String,
)

@Serializable
data class SupplierService(val description: String, val unit: String = "", val unitPriceEur: Double? = null)

@Serializable
data class Supplier(
    val id: String,
    val number: String = "",
    val name: String,
    val phone: String = "",
    val address: String? = null,
    val type: String? = null,
    /** Who to talk to there. */
    val contactPerson: String? = null,
    val createdAt: String = "",
    val archivedAt: String? = null,
    val services: List<SupplierService> = emptyList(),
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
    val email: String? = null,
    val createdAt: String = "",
    val archivedAt: String? = null,
) {
    /** Archived is how the web deactivates an employee: kept with their history, no longer picked. */
    val active: Boolean get() = archivedAt == null
}

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
    val projectId: String? = null,
    val projectLabel: String = "",
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

    val cancelled: Boolean get() = status.equals("CANCELLED", ignoreCase = true)
}

/**
 * A service an employee registered from their own sign-in, waiting for the team (`PENDING`), or
 * decided (`APPROVED`, which made [serviceId], or `REJECTED` with a [rejectionReason]).
 */
@Serializable
data class ServiceSubmission(
    val id: String,
    val employeeId: String = "",
    val employeeName: String = "",
    val clientId: String = "",
    val clientName: String = "",
    val name: String = "",
    val notes: String? = null,
    val items: List<LineItem> = emptyList(),
    val totalEur: Double = 0.0,
    val catalogItemId: String? = null,
    val performedAt: String = "",
    val status: String = PENDING,
    val serviceId: String? = null,
    val reviewedBy: String? = null,
    val reviewedAt: String? = null,
    val rejectionReason: String? = null,
    /** The team changed it before approving. */
    val adjusted: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val pending: Boolean get() = status.equals(PENDING, ignoreCase = true)

    companion object {
        const val PENDING = "PENDING"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
    }
}

/** What an employee sends (`/app/api/portal/services`). A blank [name] is made from the lines. */
@Serializable
data class SubmitService(
    val clientId: String,
    val performedAt: String,
    val items: List<LineItem>,
    val name: String? = null,
    val notes: String? = null,
    val catalogItemId: String? = null,
)

@Serializable
data class RejectSubmission(val reason: String? = null)

/** A client as an employee's sign-in sees one: enough to pick it. */
@Serializable
data class PortalClient(val id: String, val number: String = "", val name: String)

@Serializable
data class DeletedResource(val deleted: Boolean = false)

@Serializable
data class ArchiveState(val archivedAt: String? = null)
