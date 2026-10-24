package com.rfm.edubot.crm.api

import com.rfm.edubot.crm.ClientDraft
import com.rfm.edubot.crm.InvoiceInstallments
import com.rfm.edubot.crm.MIN_BOOKING_MINUTES
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.eurToCents
import com.rfm.edubot.crm.isCatalogCode
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.SupplierService
import com.rfm.edubot.shared.SystemClock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    /** Values of the tenant's own fields by key; checked by [com.rfm.edubot.crm.CustomFields.values]. */
    val customFields: Map<String, JsonElement>? = null,
) {
    fun toDraft() = ClientDraft(name, phone, address, email, taxId, notes, postalCode, city, contactPerson, customFields)
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
    val taxOfficeCode: String? = null,
) {
    fun detailsError(): String? = taxOfficeCodeError(taxOfficeCode)
}

/** A blank or missing code clears it. */
@Serializable
internal data class InvoiceTaxOfficeCodeRequest(val taxOfficeCode: String? = null) {
    fun error(): String? = taxOfficeCodeError(taxOfficeCode)
}

/** The parts still to receive; installments already received are kept as they are. */
@Serializable
internal data class InvoiceInstallmentsRequest(val installments: List<InvoiceInstallmentRequest>) {
    /** The parts, or null when an amount or a date isn't one. */
    fun parts(): List<InvoiceInstallments.Part>? = installments.map { part ->
        val date = runCatching { LocalDate.parse(part.dueDate.trim()) }.getOrNull() ?: return null
        if (!part.amountEur.isFinite()) return null
        InvoiceInstallments.Part(eurToCents(part.amountEur), date)
    }
}

@Serializable
internal data class InvoiceInstallmentRequest(val amountEur: Double, val dueDate: String)

/** The ATCUD is about 70 characters at most; this leaves room for a prefix someone types with it. */
internal const val MAX_TAX_OFFICE_CODE = 80

internal fun taxOfficeCodeError(code: String?): String? =
    if ((code?.trim()?.length ?: 0) > MAX_TAX_OFFICE_CODE) "tax_office_code_too_long" else null

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
    private val codeText: String? get() = code?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

    /** Stable error code for the first invalid field, or null. */
    fun error(): String? = when {
        titleText.isBlank() -> "title_required"
        (codeText?.length ?: 0) > MAX_CODE -> "code_too_long"
        codeText?.let(::isCatalogCode) == false -> "code_invalid"
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

/** Also the PATCH body: an omitted [type] or [services] keeps the stored one; an empty string or list clears it. */
@Serializable
internal data class CreateSupplierRequest(
    val name: String,
    val phone: String,
    val address: String? = null,
    val type: String? = null,
    val services: List<SupplierServiceRequest>? = null,
) {
    /** Stable error code for the first invalid optional field, or null. */
    fun detailsError(): String? = when {
        (type?.trim()?.length ?: 0) > MAX_TYPE -> "type_too_long"
        services == null -> null
        services.size > MAX_SERVICES -> "too_many_services"
        services.any { it.description.isBlank() } -> "service_description_required"
        services.any { it.description.trim().length > MAX_SERVICE_DESCRIPTION } -> "service_description_too_long"
        services.any { it.unit.trim().length > MAX_SERVICE_UNIT } -> "service_unit_too_long"
        services.any { price -> price.unitPriceEur?.let { !it.isFinite() || it < 0 } == true } -> "service_price_invalid"
        else -> null
    }

    fun supplierServices(): List<SupplierService>? = services?.map {
        SupplierService(it.description.trim(), it.unit.trim(), it.unitPriceEur?.let(::eurToCents))
    }

    companion object {
        const val MAX_TYPE = 60
        const val MAX_SERVICES = 50
        const val MAX_SERVICE_DESCRIPTION = 200
        const val MAX_SERVICE_UNIT = 20
    }
}

/** A service the supplier usually does; without a price when it changes from job to job. */
@Serializable
internal data class SupplierServiceRequest(val description: String, val unit: String = "", val unitPriceEur: Double? = null)

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
            (address?.trim()?.length ?: 0) > ClientDraft.MAX_ADDRESS -> "address_too_long"
            (taxId?.trim()?.length ?: 0) > ClientDraft.MAX_TAX_ID -> "tax_id_too_long"
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
    val projectId: String? = null,
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
    /** Who does the work; blank or omitted leaves it unassigned. */
    val employeeId: String? = null,
)

/** Omitted [items] keep the stored ones. An omitted [employeeId] keeps who does the work, and "" clears it. */
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
    val employeeId: String? = null,
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
