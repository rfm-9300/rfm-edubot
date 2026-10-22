package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.crm.ServiceSubmissionRepository
import com.rfm.edubot.crm.model.ServiceSubmissionStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.overview.AgentsOverview.AgentsBlock
import com.rfm.edubot.dashboard.overview.InboxOverview.WaitingConversation
import com.rfm.edubot.dashboard.OverviewAssistantDto
import com.rfm.edubot.dashboard.OverviewAttentionItemDto
import com.rfm.edubot.dashboard.OverviewCalendarDto
import com.rfm.edubot.dashboard.OverviewCashDto
import com.rfm.edubot.dashboard.OverviewEmployeesDto
import com.rfm.edubot.dashboard.OverviewInboxDto
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewPaymentsDto
import com.rfm.edubot.dashboard.OverviewPipelineDto
import com.rfm.edubot.dashboard.OverviewSocialDto
import com.rfm.edubot.instagram.InstagramCommentRepository
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId

/** "Needs you": what each card says needs someone, most urgent first. */
internal class AttentionOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    suspend fun attentionItems(
        tenantId: ObjectId,
        window: OverviewMath.Window,
        modules: Set<String>,
        cash: OverviewCashDto?,
        pipeline: OverviewPipelineDto?,
        inbox: OverviewInboxDto?,
        calendar: OverviewCalendarDto?,
        social: OverviewSocialDto?,
        assistant: OverviewAssistantDto?,
        payments: OverviewPaymentsDto?,
        agents: AgentsBlock?,
        waiting: List<WaitingConversation>,
        reconnects: List<IntegrationConnection>,
        employees: OverviewEmployeesDto?,
        projects: List<OverviewAttentionItemDto>,
    ): List<OverviewAttentionItemDto> {
        val items = projects.toMutableList()
        if ((employees?.pendingSubmissions ?: 0) > 0) {
            // The ones waiting longest first.
            val pending = coll(ServiceSubmissionRepository.COLLECTION).find(
                Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("status", ServiceSubmissionStatus.PENDING.name)),
            ).sort(Document("createdAt", 1)).limit(5).toList()
            val staff = employeeNames(tenantId, pending.mapNotNull { it.getObjectIdOrNull("employeeId") })
            pending.forEach { doc ->
                val who = doc.getObjectIdOrNull("employeeId")?.let { staff[it] }.orEmpty()
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_SERVICE_SUBMISSION,
                    tab = DashboardModules.EMPLOYEES,
                    id = doc.getObjectId("_id").toHexString(),
                    detail = listOf(who, doc.getString("name").orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                    amountCents = doc.get("totalCents").asLong(),
                    at = doc.getDate("createdAt")?.let { Instant.fromEpochMilliseconds(it.time).toString() },
                )
            }
        }
        if (DashboardModules.INVOICES in modules && cash != null) {
            val open = coll("crm.invoices").find(
                Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("status", listOf("PENDING", "OVERDUE"))),
            ).limit(80).toList()
            val names = clientNames(tenantId, open.mapNotNull { it.getObjectIdOrNull("clientId") })
            open.sortedBy { it.localDate("dueDate") ?: window.today }.forEach { doc ->
                val due = doc.localDate("dueDate")
                val status = doc.getString("status") ?: "PENDING"
                val parts = doc.openParts()
                val overdue = parts.filter { (partDue, _) -> OverviewMath.isEffectivelyOverdue(status, partDue, window.today) }
                val number = doc.getString("number") ?: ""
                val name = doc.getObjectIdOrNull("clientId")?.let { names[it] }.orEmpty()
                val detail = listOf(number, name).filter { it.isNotBlank() }.joinToString(" · ")
                if (overdue.isNotEmpty()) {
                    items += OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_OVERDUE_INVOICE,
                        tab = DashboardModules.INVOICES,
                        id = doc.getObjectId("_id").toHexString(),
                        detail = detail,
                        amountCents = overdue.sumOf { it.second },
                        at = due?.toString(),
                    )
                } else if (status == "PENDING" && due != null && due >= window.today && due <= window.dueSoonEnd && items.count { it.kind == OverviewMath.KIND_DUE_SOON_INVOICE } < 3) {
                    items += OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_DUE_SOON_INVOICE,
                        tab = DashboardModules.INVOICES,
                        id = doc.getObjectId("_id").toHexString(),
                        detail = detail,
                        amountCents = parts.filter { it.first == due }.sumOf { it.second },
                        at = due.toString(),
                    )
                }
            }
        }
        if (DashboardModules.PAYMENTS in modules && payments != null) {
            val open = coll("crm.payments").find(
                Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("status", listOf("PENDING", "OVERDUE"))),
            ).limit(80).toList()
            val names = supplierNames(tenantId, open.mapNotNull { it.getObjectIdOrNull("supplierId") })
            val staff = employeeNames(tenantId, open.mapNotNull { it.getObjectIdOrNull("employeeId") })
            open.sortedBy { it.localDate("dueDate") ?: window.today }.forEach { doc ->
                val due = doc.localDate("dueDate")
                val status = doc.getString("status") ?: "PENDING"
                val cents = doc.get("totalCents").asLong()
                val number = doc.getString("number") ?: ""
                val name = doc.getObjectIdOrNull("employeeId")?.let { staff[it] }
                    ?: doc.getObjectIdOrNull("supplierId")?.let { names[it] }.orEmpty()
                val detail = listOf(number, name).filter { it.isNotBlank() }.joinToString(" · ")
                if (OverviewMath.isEffectivelyOverdue(status, due, window.today)) {
                    items += OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_OVERDUE_PAYMENT,
                        tab = DashboardModules.PAYMENTS,
                        id = doc.getObjectId("_id").toHexString(),
                        detail = detail,
                        amountCents = cents,
                        at = due?.toString(),
                    )
                } else if (status == "PENDING" && due != null && due >= window.today && due <= window.dueSoonEnd && items.count { it.kind == OverviewMath.KIND_DUE_SOON_PAYMENT } < 3) {
                    items += OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_DUE_SOON_PAYMENT,
                        tab = DashboardModules.PAYMENTS,
                        id = doc.getObjectId("_id").toHexString(),
                        detail = detail,
                        amountCents = cents,
                        at = due.toString(),
                    )
                }
            }
        }
        if (DashboardModules.CONVERSATIONS in modules) {
            waiting.take(5).forEach { row ->
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_WAITING_CHAT,
                    tab = DashboardModules.CONVERSATIONS,
                    id = row.id,
                    detail = row.detail,
                    at = row.at,
                )
            }
        }
        if (DashboardModules.BOOKINGS in modules) {
            BookingRepository(mongo, tenantId).list(status = BookingStatus.PENDING)
                .filter { it.startAt >= window.now }
                .sortedBy { it.startAt }
                .take(5)
                .forEach { booking ->
                    items += OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_PENDING_BOOKING,
                        tab = DashboardModules.BOOKINGS,
                        id = booking.id.toHexString(),
                        detail = booking.contactName,
                        at = booking.startAt.toString(),
                    )
                }
        }
        if (DashboardModules.INSTAGRAM in modules && (social?.unreplied ?: 0) > 0) {
            InstagramCommentRepository(mongo, tenantId).listUnreplied(5).forEach { comment ->
                val who = comment.fromUsername?.let { "@$it" } ?: "—"
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_INSTAGRAM_COMMENT,
                    tab = DashboardModules.INSTAGRAM,
                    id = comment.commentId,
                    detail = listOf(who, comment.text.trim().take(80)).filter { it.isNotBlank() }.joinToString(" · "),
                    at = comment.createdAt.toString(),
                )
            }
        }
        if (DashboardModules.QUOTES in modules && (pipeline?.expiringSoonCount ?: 0) > 0) {
            val expiring = coll("crm.quotes").find(
                Filters.and(
                    Filters.eq("tenantId", tenantId),
                    Filters.`in`("status", listOf("PENDENTE", "SENT")),
                    Filters.gte("validUntil", window.today.toString()),
                    Filters.lte("validUntil", window.dueSoonEnd.toString()),
                ),
            ).limit(4).toList()
            val names = clientNames(tenantId, expiring.mapNotNull { it.getObjectIdOrNull("clientId") })
            expiring.forEach { doc ->
                val number = doc.getString("number") ?: ""
                val name = doc.getObjectIdOrNull("clientId")?.let { names[it] }.orEmpty()
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_QUOTE_EXPIRING,
                    tab = DashboardModules.QUOTES,
                    id = doc.getObjectId("_id").toHexString(),
                    detail = listOf(number, name).filter { it.isNotBlank() }.joinToString(" · "),
                    amountCents = doc.get("totalCents").asLong(),
                    at = doc.getString("validUntil"),
                )
            }
        }
        if (DashboardModules.AI_ASSISTANT in modules && (assistant?.pendingActions ?: 0) > 0) {
            items += OverviewAttentionItemDto(
                kind = OverviewMath.KIND_ASSISTANT_ACTION,
                tab = DashboardModules.AI_ASSISTANT,
                detail = assistant!!.pendingActions.toString(),
            )
        }
        if (agents != null) {
            fun detail(vararg parts: String?) = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
            agents.approvals.forEach {
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_AGENT_APPROVAL,
                    tab = DashboardModules.AGENTS,
                    id = it.id.toHexString(),
                    detail = detail(it.agentName, it.subjectLabel),
                    at = it.createdAt.toString(),
                )
            }
            agents.tasksDue.forEach {
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_AGENT_TASK_DUE,
                    tab = DashboardModules.AGENTS,
                    id = it.id.toHexString(),
                    detail = detail(it.title, it.subjectLabel),
                    at = it.dueAt?.toString(),
                )
            }
            agents.failed.forEach {
                items += OverviewAttentionItemDto(
                    kind = OverviewMath.KIND_AGENT_FAILED,
                    tab = DashboardModules.AGENTS,
                    id = it.id.toHexString(),
                    detail = detail(it.agentName, it.subjectLabel),
                    at = (it.finishedAt ?: it.updatedAt).toString(),
                )
            }
        }
        reconnects.forEach {
            items += OverviewAttentionItemDto(
                kind = OverviewMath.KIND_INTEGRATION_RECONNECT,
                tab = DashboardModules.SETTINGS,
                id = it.id.toHexString(),
                detail = it.accountEmail,
                at = it.updatedAt.toString(),
            )
        }
        return rankAttention(items)
    }

    private fun rankAttention(items: List<OverviewAttentionItemDto>): List<OverviewAttentionItemDto> {
        val order = listOf(
            OverviewMath.KIND_OVERDUE_INVOICE,
            OverviewMath.KIND_OVERDUE_PAYMENT,
            OverviewMath.KIND_WAITING_CHAT,
            OverviewMath.KIND_INTEGRATION_RECONNECT,
            OverviewMath.KIND_AGENT_APPROVAL,
            OverviewMath.KIND_SERVICE_SUBMISSION,
            OverviewMath.KIND_PENDING_BOOKING,
            OverviewMath.KIND_SITE_ISSUE_OVERDUE,
            OverviewMath.KIND_PROJECT_DELAYED,
            OverviewMath.KIND_INSTAGRAM_COMMENT,
            OverviewMath.KIND_AGENT_TASK_DUE,
            OverviewMath.KIND_AGENT_FAILED,
            OverviewMath.KIND_DUE_SOON_INVOICE,
            OverviewMath.KIND_DUE_SOON_PAYMENT,
            OverviewMath.KIND_MEASUREMENT_TO_INVOICE,
            OverviewMath.KIND_CHANGE_ORDER_PENDING,
            OverviewMath.KIND_QUOTE_EXPIRING,
            OverviewMath.KIND_ASSISTANT_ACTION,
        )
        return items.sortedBy { order.indexOf(it.kind).let { idx -> if (idx < 0) 99 else idx } }.take(10)
    }
}
