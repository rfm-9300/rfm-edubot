package com.rfm.edubot.dashboard.overview

import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.ProjectHealth
import com.rfm.edubot.building.ProjectProgress
import com.rfm.edubot.building.approvedEffect
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
import org.bson.types.ObjectId
import kotlin.math.roundToInt

/** The Building card and its Needs-you rows: delayed projects, overdue issues, changes waiting, measurements to invoice. */
internal class ProjectsOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    data class Block(val dto: OverviewProjectsDto, val attention: List<OverviewAttentionItemDto>)

    suspend fun projects(tenantId: ObjectId, window: OverviewMath.Window, modules: Set<String>): Block {
        val records = BuildingRecords(mongo, tenantId)
        val today = window.today
        val projects = records.projects.list(listOf(ProjectStatus.PLANNING, ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD))
        val ids = projects.map { it.id }
        val daysByProject = records.changes.forProjects(ids).groupBy { it.projectId }.mapValues { (_, list) -> approvedEffect(list).days }
        val active = projects.filter { it.status == ProjectStatus.ACTIVE }
        val health = active.associate { it.id to ProjectProgress.health(it, today, daysByProject[it.id] ?: 0) }
        val behind = active
            .map { it to ProjectProgress.scheduleDays(it, today) }
            .filter { (project, _) -> health[project.id] == ProjectHealth.AT_RISK || health[project.id] == ProjectHealth.DELAYED }
            .sortedBy { it.second ?: 0 }
        // Issues and changes count on a finished project too: snags after the handover, an extra still to sign.
        val issues = if (DashboardModules.SITE_ISSUES in modules) records.issues.list(statuses = listOf(IssueStatus.OPEN, IssueStatus.IN_PROGRESS)) else emptyList()
        val overdue = issues.filter { it.isOverdue(today) }.sortedBy { it.dueDate }
        val diaryOn = DashboardModules.SITE_DIARY in modules
        val logged = if (diaryOn) records.logs.list(null, today, today).map { it.projectId }.toSet() else emptySet()
        val waiting = if (DashboardModules.CHANGE_ORDERS in modules) {
            records.changes.list(statuses = listOf(ChangeOrderStatus.SENT)).sortedBy { it.sentAt ?: it.updatedAt }
        } else {
            emptyList()
        }
        val toInvoice = if (DashboardModules.MEASUREMENTS in modules) {
            records.measurements.list(statuses = listOf(MeasurementStatus.APPROVED)).sortedBy { it.approvedAt ?: it.updatedAt }
        } else {
            emptyList()
        }
        val finished = (overdue.map { it.projectId } + waiting.map { it.projectId } + toInvoice.map { it.projectId }).toSet() - ids.toSet()
        val label = (projects + records.projects.byIds(finished)).associate { it.id to "${it.number} · ${it.name}" }

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

    private companion object {
        const val BEHIND_ROWS = 4
        const val ATTENTION_ROWS = 3
    }
}
