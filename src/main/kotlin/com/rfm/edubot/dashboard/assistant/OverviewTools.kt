package com.rfm.edubot.dashboard.assistant

import com.rfm.edubot.agents.ai.UntrustedContent
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.OverviewService
import com.rfm.edubot.dashboard.assistantOwnerKey
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `get_business_overview`: Home's figures for the modules the assistant may use, and what needs attention. */
internal class OverviewTools(private val s: AssistantToolSession) : AssistantToolGroup {
    override val spec: ToolGroupSpec get() = Companion

    override suspend fun execute(name: String, args: JsonObject): JsonObject = when (name) {
        OVERVIEW -> overview()
        else -> throw ToolProblem("unknown_tool", "Unknown tool.")
    }

    private suspend fun overview(): JsonObject {
        val o = OverviewService(s.mongo).build(s.tenant, extended = false, assistantOwnerKey = s.ctx.assistantOwnerKey())
        fun on(module: String) = module in s.usable
        return buildJsonObject {
            put("today", o.today)
            put("timezone", o.timezone)
            putJsonObject("overview") {
                if (on(DashboardModules.INVOICES)) o.cash?.let { c ->
                    putJsonObject("money_in") {
                        put("received_this_month_eur", eur(c.collectedThisMonthCents))
                        put("received_last_month_eur", eur(c.collectedLastMonthCents))
                        put("invoiced_this_month_eur", eur(c.issuedThisMonthCents))
                        put("outstanding_eur", eur(c.outstandingCents))
                        put("overdue_eur", eur(c.overdueCents))
                        put("overdue_invoices", c.overdueCount)
                        put("due_next_7_days_eur", eur(c.dueSoonCents))
                        put("due_next_7_days_invoices", c.dueSoonCount)
                    }
                    if (c.topOverdue.isNotEmpty()) {
                        putJsonArray("largest_overdue") {
                            c.topOverdue.forEach { addJsonObject { put("client", it.name); it.number?.let { n -> put("invoice", n) }; put("amount_eur", eur(it.amountCents)) } }
                        }
                    }
                }
                if (on(DashboardModules.PAYMENTS)) o.payments?.let { p ->
                    putJsonObject("money_out") {
                        put("paid_this_month_eur", eur(p.paidThisMonthCents))
                        put("to_pay_eur", eur(p.outstandingCents))
                        put("overdue_eur", eur(p.overdueCents))
                        put("overdue_payments", p.overdueCount)
                        put("due_next_7_days_eur", eur(p.dueSoonCents))
                        put("due_next_7_days_payments", p.dueSoonCount)
                    }
                }
                if (on(DashboardModules.QUOTES)) o.pipeline?.let { q ->
                    putJsonObject("quotes") {
                        put("open_eur", eur(q.openCents))
                        put("pending", q.pendingCount)
                        put("sent", q.sentCount)
                        put("accepted", q.acceptedCount)
                        put("accepted_this_month_eur", eur(q.acceptedThisMonthCents))
                        put("win_rate_pct", q.winRatePct)
                        put("expiring_soon", q.expiringSoonCount)
                    }
                }
                if (on(DashboardModules.CLIENTS)) o.customers?.let { c ->
                    putJsonObject("clients") { put("total", c.total); put("new_this_month", c.newThisMonth); put("new_last_month", c.newLastMonth) }
                }
                if (on(DashboardModules.SERVICES)) o.services?.let { sv ->
                    putJsonObject("services") {
                        put("not_invoiced", sv.openCount)
                        put("not_invoiced_eur", eur(sv.openCents))
                        put("invoiced_this_month_eur", eur(sv.invoicedThisMonthCents))
                    }
                }
                if (on(DashboardModules.CONVERSATIONS)) o.inbox?.let { i ->
                    putJsonObject("inbox") {
                        put("waiting_for_reply", i.waiting)
                        put("messages_today", i.messagesToday)
                        put("bot_paused_chats", i.autoReplyPaused)
                    }
                }
                if (on(DashboardModules.BOOKINGS)) o.calendar?.let { b ->
                    putJsonObject("bookings") {
                        put("today", b.today)
                        put("this_week", b.thisWeek)
                        put("waiting_confirmation", b.pending)
                        b.next?.let { next -> put("next", "${s.localTime(Instant.parse(next.startAt))} · ${next.contactName}") }
                    }
                }
                if (on(DashboardModules.EMPLOYEES)) o.employees?.let { e ->
                    putJsonObject("employees") { put("total", e.total); put("services_to_approve", e.pendingSubmissions) }
                }
                if (on(DashboardModules.AGENTS)) o.agents?.let { a ->
                    putJsonObject("agents") {
                        put("active", a.activeAgents)
                        put("approvals_waiting", a.pendingApprovals)
                        put("tasks_due", a.tasksDue)
                        put("failed_this_week", a.failedThisWeek)
                    }
                }
            }
            putJsonArray("needs_attention") {
                o.attention.filter { it.tab == DashboardModules.OVERVIEW || it.tab in s.usable }.take(10).forEach { item ->
                    addJsonObject {
                        put("kind", item.kind)
                        put("area", item.tab)
                        put("detail", if (item.tab in OUTSIDER_AREAS) UntrustedContent.wrap("attention.${item.kind}", item.detail) else item.detail)
                        item.amountCents?.let { put("amount_eur", eur(it)) }
                        item.at?.let { put("at", it) }
                    }
                }
            }
        }
    }

    companion object : ToolGroupSpec {
        const val OVERVIEW = "get_business_overview"

        /** Attention items whose text people outside the company wrote. */
        private val OUTSIDER_AREAS = setOf(DashboardModules.CONVERSATIONS, DashboardModules.CONTACTS, DashboardModules.INSTAGRAM)

        override val definitions = listOf(
            tool(
                OVERVIEW,
                "The business at a glance, as on Home: money received, outstanding and overdue, bills to pay, open quotes, clients, " +
                    "work not invoiced, chats waiting for a reply, bookings, and what needs attention. Use it for \"how is business\" questions.",
                buildJsonObject {},
            ),
        )
        override val moduleOf = mapOf(OVERVIEW to DashboardModules.OVERVIEW)
        override val readOnly = setOf(OVERVIEW)
    }
}
