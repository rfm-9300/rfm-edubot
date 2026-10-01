package com.rfm.edubot.crm

import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
data class StandardItem(
    /** Internal key that Serviços rows and bookings point at; it never changes. */
    val id: String,
    val type: String,
    val category: String,
    /** Printed with the title on documents. An item without one stores its title here, so readers that predate titles still get a name. */
    val description: String,
    val unit: String,
    val defaultUnitPriceEur: Double,
    /** Appointment length when this service is offered in Bookings. Kept when [bookable] is switched off. */
    val durationMinutes: Int? = null,
    val bookable: Boolean = false,
    val title: String,
    /** Reference the tenant sees and may change (`SRV-001`, `MAT-001` or their own), unique per tenant. Null until the item is saved. */
    val code: String? = null,
)

const val MIN_BOOKING_MINUTES = 5

fun isServiceType(type: String): Boolean = type == "service" || type == "servico"

fun StandardItem.isService(): Boolean = isServiceType(type)

fun StandardItem.isBookable(): Boolean = bookable && isService() && (durationMinutes ?: 0) >= MIN_BOOKING_MINUTES

/** The item's own description; empty when it only repeats the title. */
fun StandardItem.details(): String = description.takeUnless { it == title }.orEmpty()

/** Prefix of the codes the catalog hands out by itself. */
fun catalogCodePrefix(type: String): String = if (isServiceType(type)) "SRV" else "MAT"

fun catalogSlug(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
