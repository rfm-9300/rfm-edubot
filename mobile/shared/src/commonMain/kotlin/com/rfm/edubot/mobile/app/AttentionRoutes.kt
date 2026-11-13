package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.core.model.OverviewAttentionItem
import com.rfm.edubot.mobile.feature.building.BuildingRoute
import com.rfm.edubot.mobile.feature.crm.CrmRoute

/**
 * The record a Home "needs you" row is about, as the web opens it, or null when the app has no
 * screen for it (an agent's approval, an Instagram comment) and the row opens its module instead.
 * A record is only opened in a module the company has: anything else answers 403.
 */
internal fun attentionDestination(item: OverviewAttentionItem, modules: Set<String>): Destination? {
    val id = item.id?.takeIf { it.isNotBlank() } ?: return null
    if (item.tab !in modules) return null
    return when (item.kind) {
        "overdue_invoice", "due_soon_invoice" -> Destination.Crm(CrmRoute.Invoice(id))
        "quote_expiring" -> Destination.Crm(CrmRoute.Quote(id))
        "waiting_chat" -> Destination.Conversation(id)
        "project_delayed" -> Destination.Building(BuildingRoute.Project(id))
        "site_issue_overdue" -> Destination.Building(BuildingRoute.Issue(id))
        "change_order_pending" -> Destination.Building(BuildingRoute.Change(id))
        "measurement_to_invoice" -> Destination.Building(BuildingRoute.Measurement(id))
        else -> null
    }
}
