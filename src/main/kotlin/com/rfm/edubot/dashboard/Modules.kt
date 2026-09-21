package com.rfm.edubot.dashboard

import com.rfm.edubot.tenant.model.Tenant

object DashboardModules {
    const val OVERVIEW = "overview"
    const val CONVERSATIONS = "conversations"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    const val PERSONA = "persona"
    const val CLIENTS = "clients"
    const val SERVICES = "services"
    const val QUOTES = "quotes"
    const val INVOICES = "invoices"
    const val CATALOG = "catalog"
    const val AI_ASSISTANT = "ai-assistant"
    const val BOOKINGS = "bookings"
    const val INSTAGRAM = "instagram"

    /**
     * Only the dashboard landing page is mandatory — it is the fallback view every tenant needs.
     * Everything else (messaging, contacts, settings, CRM) is opt-in: the product is no longer
     * WhatsApp-first, so a tenant may run CRM-only with no inbox at all.
     */
    val alwaysOn = listOf(OVERVIEW)
    val optional = listOf(
        CONVERSATIONS, CONTACTS, SETTINGS, PERSONA, CLIENTS, SERVICES, QUOTES, INVOICES, CATALOG,
        AI_ASSISTANT, BOOKINGS, INSTAGRAM,
    )
    val catalog = alwaysOn + optional

    fun availableFor(): List<String> = catalog

    fun effectiveFor(tenant: Tenant): List<String> {
        val selected = tenant.enabledModules ?: catalog
        val resolved = (alwaysOn + selected).filter { it in catalog }.toMutableList()
        if (CLIENTS in resolved && SERVICES in catalog) resolved += SERVICES
        return resolved.distinct()
    }

    fun sanitize(requested: List<String>?): List<String>? {
        if (requested == null) return null
        return (alwaysOn + requested).filter { it in catalog }.distinct()
    }
}
