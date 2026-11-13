package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.core.model.DashboardModules
import com.rfm.edubot.mobile.core.model.OverviewAttentionItem
import com.rfm.edubot.mobile.feature.building.BuildingRoute
import com.rfm.edubot.mobile.feature.crm.CrmRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttentionRoutesTest {
    private val modules = setOf(DashboardModules.INVOICES, DashboardModules.SITE_ISSUES, DashboardModules.CONVERSATIONS, DashboardModules.AGENTS)

    @Test
    fun `a row about a record opens that record`() {
        assertEquals(
            Destination.Crm(CrmRoute.Invoice("i1")),
            attentionDestination(OverviewAttentionItem("overdue_invoice", DashboardModules.INVOICES, "i1"), modules),
        )
        assertEquals(
            Destination.Building(BuildingRoute.Issue("x1")),
            attentionDestination(OverviewAttentionItem("site_issue_overdue", DashboardModules.SITE_ISSUES, "x1"), modules),
        )
        assertEquals(
            Destination.Conversation("c1"),
            attentionDestination(OverviewAttentionItem("waiting_chat", DashboardModules.CONVERSATIONS, "c1"), modules),
        )
    }

    @Test
    fun `a row the app has no record screen for opens its module instead`() {
        assertNull(attentionDestination(OverviewAttentionItem("agent_approval", DashboardModules.AGENTS, "a1"), modules))
        assertNull(attentionDestination(OverviewAttentionItem("overdue_invoice", DashboardModules.INVOICES, null), modules))
    }

    @Test
    fun `a record in a module the company lacks is never opened`() {
        assertNull(attentionDestination(OverviewAttentionItem("quote_expiring", DashboardModules.QUOTES, "q1"), modules))
    }
}
