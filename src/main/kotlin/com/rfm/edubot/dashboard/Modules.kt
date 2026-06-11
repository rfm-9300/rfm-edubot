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

    val alwaysOn = setOf(OVERVIEW, CONVERSATIONS, CONTACTS, SETTINGS)
    private val optional = setOf(PERSONA)
    private val crm = setOf(CLIENTS, QUOTES, INVOICES, CATALOG)
    private val legacyBase = listOf(OVERVIEW, CONVERSATIONS, CONTACTS, SETTINGS)
    private val legacyCrm = listOf(CLIENTS, QUOTES, INVOICES, CATALOG)

    fun availableFor(tenant: Tenant): Set<String> = buildSet {
        addAll(alwaysOn)
        addAll(optional)
        if (tenant.agentType == "CRM_V1") addAll(crm)
    }

    fun effectiveFor(tenant: Tenant): List<String> {
        val available = availableFor(tenant)
        val selected = tenant.enabledModules ?: legacyFor(tenant)
        return selected.filter { it in available }.let { modules ->
            (alwaysOn.filter { it in available } + modules).distinct()
        }
    }

    fun sanitizeForTenant(tenant: Tenant, requested: List<String>?): List<String>? {
        if (requested == null) return null
        val available = availableFor(tenant)
        return (alwaysOn + requested).filter { it in available }.distinct()
    }

    private fun legacyFor(tenant: Tenant): List<String> = buildList {
        addAll(legacyBase)
        if (tenant.agentType == "CRM_V1") addAll(legacyCrm)
    }
}
