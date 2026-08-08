package com.rfm.edubot.dashboard

import com.rfm.edubot.tenant.model.Tenant

object DashboardModules {
    const val OVERVIEW = "overview"
    const val CONVERSATIONS = "conversations"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    const val PERSONA = "persona"
    const val CLIENTS = "clients"
    const val QUOTES = "quotes"
    const val INVOICES = "invoices"
    const val CATALOG = "catalog"
    const val AI_ASSISTANT = "ai-assistant"
    const val BOOKINGS = "bookings"

    val alwaysOn = listOf(OVERVIEW, CONVERSATIONS, CONTACTS, SETTINGS)
    val optional = listOf(PERSONA, CLIENTS, QUOTES, INVOICES, CATALOG, AI_ASSISTANT, BOOKINGS)
    val catalog = alwaysOn + optional

    fun availableFor(): List<String> = catalog

    fun effectiveFor(tenant: Tenant): List<String> {
        val selected = tenant.enabledModules ?: catalog
        return (alwaysOn + selected).filter { it in catalog }.distinct()
    }

    fun sanitize(requested: List<String>?): List<String>? {
        if (requested == null) return null
        return (alwaysOn + requested).filter { it in catalog }.distinct()
    }
}
