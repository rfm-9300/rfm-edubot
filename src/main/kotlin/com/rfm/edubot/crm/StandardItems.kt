package com.rfm.edubot.crm

import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
data class StandardItem(
    val id: String,
    val type: String,
    val category: String,
    val description: String,
    val unit: String,
    val defaultUnitPriceEur: Double,
    /** Appointment length when this service is offered in Bookings. Kept when [bookable] is switched off. */
    val durationMinutes: Int? = null,
    val bookable: Boolean = false,
)

const val MIN_BOOKING_MINUTES = 5

fun StandardItem.isService(): Boolean = type == "service" || type == "servico"

fun StandardItem.isBookable(): Boolean = bookable && isService() && (durationMinutes ?: 0) >= MIN_BOOKING_MINUTES

fun catalogSlug(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
