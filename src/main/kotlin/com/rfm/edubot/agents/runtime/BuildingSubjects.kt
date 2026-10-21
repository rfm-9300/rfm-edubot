package com.rfm.edubot.agents.runtime

import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.ProjectProgress
import com.rfm.edubot.building.approvedEffect
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import kotlin.math.roundToInt

/**
 * Building's records as a run sees them (`AgentVariables.project`, `diary`, `issue`, `change`, `measurement`):
 * the record, its project's figures as of today, and the project's client, so a step can message that client.
 */
internal class BuildingSubjects(private val mongo: MongoModule) {
    class Found(val variables: Map<String, JsonObject>, val label: String, val client: Client?)

    suspend fun load(tenant: Tenant, type: String, id: ObjectId, today: LocalDate, formatter: ValueFormatter): Found? {
        val records = BuildingRecords(mongo, tenant.id)
        return when (type) {
            SubjectTypes.PROJECT -> records.projects.findById(id)?.let { project -> found(records, project, today, formatter, label(project)) }
            SubjectTypes.SITE_LOG -> records.logs.findById(id)?.let { entry ->
                val project = records.projects.findById(entry.projectId) ?: return null
                found(records, project, today, formatter, "${label(project)} · ${entry.day}", "diary" to buildJsonObject {
                    put("id", entry.id.toHexString())
                    put("day", entry.day.toString())
                    put("workDone", entry.workDone.take(MAX_TEXT))
                    entry.workers?.let { put("workers", it) }
                    entry.weather?.let { put("weather", it.name) }
                    put("shared", entry.shared)
                })
            }
            SubjectTypes.SITE_ISSUE -> records.issues.findById(id)?.let { issue ->
                val project = records.projects.findById(issue.projectId) ?: return null
                val assignee = issue.employeeId?.let { records.employees.findById(it)?.name } ?: issue.supplierId?.let { records.suppliers.findById(it)?.name }
                found(records, project, today, formatter, "${issue.number} · ${issue.title}", "issue" to buildJsonObject {
                    put("id", issue.id.toHexString())
                    put("number", issue.number)
                    put("title", issue.title)
                    put("kind", issue.kind.name)
                    put("priority", issue.priority.name)
                    put("status", issue.status.name)
                    issue.dueDate?.let { put("dueDate", it.toString()) }
                    put("isOverdue", issue.isOverdue(today))
                    issue.location?.let { put("location", it) }
                    assignee?.let { put("assigneeName", it) }
                })
            }
            SubjectTypes.CHANGE_ORDER -> records.changes.findById(id)?.let { change ->
                val project = records.projects.findById(change.projectId) ?: return null
                found(records, project, today, formatter, "${change.number} · ${change.title}", "change" to buildJsonObject {
                    put("id", change.id.toHexString())
                    put("number", change.number)
                    put("title", change.title)
                    put("kind", change.kind.name)
                    put("status", change.status.name)
                    put("total", formatter.formatCents(change.signedCents))
                    put("totalCents", change.signedCents)
                    put("daysImpact", change.daysImpact)
                    change.decidedBy?.let { put("decidedBy", it) }
                })
            }
            SubjectTypes.MEASUREMENT -> records.measurements.findById(id)?.let { m ->
                val project = records.projects.findById(m.projectId) ?: return null
                val invoice = m.invoiceId?.let { records.invoices.findById(it) }
                found(records, project, today, formatter, "${label(project)} · ${m.number}", "measurement" to buildJsonObject {
                    put("id", m.id.toHexString())
                    put("number", m.number)
                    put("status", m.status.name)
                    put("periodEnd", m.periodEnd.toString())
                    put("gross", formatter.formatCents(m.grossCents))
                    put("retention", formatter.formatCents(m.retentionCents))
                    put("net", formatter.formatCents(m.netCents))
                    put("netCents", m.netCents)
                    invoice?.let { put("invoiceNumber", it.number) }
                })
            }
            else -> null
        }
    }

    private suspend fun found(records: BuildingRecords, project: Project, today: LocalDate, formatter: ValueFormatter, label: String, own: Pair<String, JsonObject>? = null): Found {
        val client = project.clientId?.let { records.clients.findById(it) }
        return Found(listOfNotNull("project" to projectVariables(records, project, today, formatter), own).toMap(), label, client)
    }

    private suspend fun projectVariables(records: BuildingRecords, project: Project, today: LocalDate, formatter: ValueFormatter): JsonObject {
        val effect = approvedEffect(records.changes.list(project.id))
        val manager = project.managerId?.let { records.employees.findById(it) }
        val openIssues = records.issues.list(project.id, listOf(IssueStatus.OPEN, IssueStatus.IN_PROGRESS))
        return buildJsonObject {
            put("id", project.id.toHexString())
            put("number", project.number)
            put("name", project.name)
            put("status", project.status.name)
            put("health", ProjectProgress.health(project, today, effect.days).name)
            project.address?.let { put("address", it) }
            put("progressPct", ProjectProgress.actualPct(project).roundToInt())
            ProjectProgress.plannedPct(project, ProjectProgress.asOf(today))?.let { put("plannedPct", it.roundToInt()) }
            ProjectProgress.scheduleDays(project, today)?.let { put("scheduleDays", it) }
            ProjectProgress.revisedEnd(project, effect.days)?.let { put("revisedEnd", it.toString()); put("daysToEnd", today.daysUntil(it)) }
            manager?.let { put("managerName", it.name); put("managerPhone", it.phone) }
            put("openIssues", openIssues.size)
            put("openIssuesOverdue", openIssues.count { it.isOverdue(today) })
            put("contract", formatter.formatCents(project.contractCents + effect.cents))
            put("contractCents", project.contractCents + effect.cents)
        }
    }

    private fun label(project: Project) = "${project.number} · ${project.name}"

    private companion object {
        const val MAX_TEXT = 2_000
    }
}
