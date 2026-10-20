package com.rfm.edubot.building

import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.OverviewAttentionItemDto
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewProjectRowDto
import com.rfm.edubot.dashboard.OverviewProjectsDto
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.timesheets.zone
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/** Home's Building card and its Needs-you rows: delayed projects, overdue issues, changes waiting, measurements to invoice. */
internal object BuildingOverview {
    data class Block(val dto: OverviewProjectsDto, val attention: List<OverviewAttentionItemDto>)

    private const val BEHIND_ROWS = 4
    private const val ATTENTION_ROWS = 3

    suspend fun build(mongo: MongoModule, tenant: Tenant, modules: Set<String>, now: Instant): Block {
        val today = now.toLocalDateTime(tenant.zone()).date
        val projects = ProjectRepository(mongo, tenant.id).list(listOf(ProjectStatus.PLANNING, ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD))
        val label = projects.associate { it.id to "${it.number} · ${it.name}" }
        val changes = ChangeOrderRepository(mongo, tenant.id).list()
        val daysByProject = changes.groupBy { it.projectId }.mapValues { (_, list) -> approvedEffect(list).days }
        val active = projects.filter { it.status == ProjectStatus.ACTIVE }
        val health = active.associate { it.id to ProjectProgress.health(it, today, daysByProject[it.id] ?: 0) }
        val behind = active
            .map { it to ProjectProgress.scheduleDays(it, today) }
            .filter { (project, _) -> health[project.id] == ProjectHealth.AT_RISK || health[project.id] == ProjectHealth.DELAYED }
            .sortedBy { it.second ?: 0 }
        val issues = if (DashboardModules.SITE_ISSUES in modules) {
            SiteIssueRepository(mongo, tenant.id).list(statuses = listOf(IssueStatus.OPEN, IssueStatus.IN_PROGRESS))
        } else {
            emptyList()
        }
        val overdue = issues.filter { it.isOverdue(today) }.sortedBy { it.dueDate }
        val diaryOn = DashboardModules.SITE_DIARY in modules
        val logged = if (diaryOn) SiteLogRepository(mongo, tenant.id).list(null, today, today).map { it.projectId }.toSet() else emptySet()
        val changesOn = DashboardModules.CHANGE_ORDERS in modules
        val waiting = if (changesOn) changes.filter { it.status == ChangeOrderStatus.SENT }.sortedBy { it.sentAt ?: it.updatedAt } else emptyList()
        val toInvoice = if (DashboardModules.MEASUREMENTS in modules) {
            MeasurementRepository(mongo, tenant.id).list(statuses = listOf(MeasurementStatus.APPROVED)).sortedBy { it.approvedAt ?: it.updatedAt }
        } else {
            emptyList()
        }

        val attention = buildList {
            behind.filter { health[it.first.id] == ProjectHealth.DELAYED }.take(ATTENTION_ROWS).forEach { (project, _) ->
                add(
                    OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_PROJECT_DELAYED,
                        tab = DashboardModules.PROJECTS,
                        id = project.id.toHexString(),
                        detail = label[project.id].orEmpty(),
                        at = ProjectProgress.revisedEnd(project, daysByProject[project.id] ?: 0)?.toString(),
                    ),
                )
            }
            overdue.take(ATTENTION_ROWS).forEach { issue ->
                add(
                    OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_SITE_ISSUE_OVERDUE,
                        tab = DashboardModules.SITE_ISSUES,
                        id = issue.id.toHexString(),
                        detail = listOfNotNull(issue.number, issue.title, label[issue.projectId]).joinToString(" · "),
                        at = issue.dueDate?.toString(),
                    ),
                )
            }
            waiting.take(ATTENTION_ROWS).forEach { change ->
                add(
                    OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_CHANGE_ORDER_PENDING,
                        tab = DashboardModules.CHANGE_ORDERS,
                        id = change.id.toHexString(),
                        detail = listOfNotNull(change.number, change.title, label[change.projectId]).joinToString(" · "),
                        amountCents = change.signedCents,
                        at = (change.sentAt ?: change.updatedAt).toString(),
                    ),
                )
            }
            toInvoice.take(ATTENTION_ROWS).forEach { m ->
                add(
                    OverviewAttentionItemDto(
                        kind = OverviewMath.KIND_MEASUREMENT_TO_INVOICE,
                        tab = DashboardModules.MEASUREMENTS,
                        id = m.id.toHexString(),
                        detail = listOfNotNull(label[m.projectId], "#${m.number}").joinToString(" · "),
                        amountCents = m.netCents,
                        at = (m.approvedAt ?: m.updatedAt).toString(),
                    ),
                )
            }
        }

        return Block(
            OverviewProjectsDto(
                active = active.size,
                planning = projects.count { it.status == ProjectStatus.PLANNING },
                atRisk = health.values.count { it == ProjectHealth.AT_RISK },
                delayed = health.values.count { it == ProjectHealth.DELAYED },
                openIssues = issues.size,
                overdueIssues = overdue.size,
                diariesToday = logged.size,
                activeWithoutDiary = if (diaryOn) active.count { it.id !in logged } else 0,
                pendingChanges = waiting.size,
                pendingChangesCents = waiting.sumOf { it.signedCents },
                toInvoiceCount = toInvoice.size,
                toInvoiceCents = toInvoice.sumOf { it.netCents },
                behind = behind.take(BEHIND_ROWS).map { (project, days) ->
                    OverviewProjectRowDto(
                        id = project.id.toHexString(),
                        number = project.number,
                        name = project.name,
                        health = health[project.id]?.name ?: ProjectHealth.ON_TRACK.name,
                        progressPct = ProjectProgress.actualPct(project).roundToInt(),
                        plannedPct = ProjectProgress.plannedPct(project, ProjectProgress.asOf(today))?.roundToInt(),
                        scheduleDays = days,
                    )
                },
            ),
            attention,
        )
    }
}
