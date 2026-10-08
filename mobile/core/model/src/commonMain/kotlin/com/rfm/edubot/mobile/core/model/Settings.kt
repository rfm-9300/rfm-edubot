package com.rfm.edubot.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WebWidget(
    val publicKey: String? = null,
    val allowedOrigins: List<String> = emptyList(),
)

@Serializable
data class LocaleChange(val locale: String)

/** Every locale the dashboard accepts on `POST /app/api/settings/locale`. */
object SupportedLocales {
    const val ENGLISH = "en"
    const val PORTUGUESE = "pt-PT"
    const val SPANISH = "es"

    val all: List<String> = listOf(ENGLISH, PORTUGUESE, SPANISH)
}

/**
 * The module ids `GET /app/api/me` can return. The nav is built from this, so a module the backend
 * adds shows up as soon as the app knows how to render it.
 */
object DashboardModules {
    const val OVERVIEW = "overview"
    const val CONVERSATIONS = "conversations"
    const val CONTACTS = "contacts"
    const val INSTAGRAM = "instagram"
    const val CLIENTS = "clients"
    const val SERVICES = "services"
    const val QUOTES = "quotes"
    const val INVOICES = "invoices"
    const val SUPPLIERS = "suppliers"
    const val EMPLOYEES = "employees"
    const val PAYMENTS = "payments"
    const val CATALOG = "catalog"
    const val BOOKINGS = "bookings"
    const val PERSONA = "persona"
    const val AI_ASSISTANT = "ai-assistant"
    const val AGENTS = "agents"
    const val SETTINGS = "settings"
    const val TIMESHEETS = "timesheets"

    /** An employee's own pages, the only modules their sign-in has. */
    const val MY_HOURS = "my-hours"
    const val MY_SERVICES = "my-services"
}
