package com.rfm.edubot.building

import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.DecidedVia
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

/**
 * The domain events Building appends after its writes, best effort like the CRM's: agents react to them,
 * and naming the project and its client as related records puts them on those timelines.
 */
class BuildingEvents(private val log: DomainEventLog, private val tenantId: ObjectId) {
    suspend fun projectCreated(project: Project) =
        append(DomainEventTypes.PROJECT_CREATED, SubjectRef.of(SubjectTypes.PROJECT, project.id), project, project(project))

    suspend fun projectStatusChanged(project: Project, from: ProjectStatus) =
        append(DomainEventTypes.PROJECT_STATUS_CHANGED, SubjectRef.of(SubjectTypes.PROJECT, project.id), project, project(project) { put("from", from.name); put("to", project.status.name) })

    /** Appends `project.at_risk` or `project.delayed` when [to] is one of those; any other move is no event. Whether it appended. */
    suspend fun projectFellBehind(project: Project, from: ProjectHealth, to: ProjectHealth): Boolean {
        val type = when (to) {
            ProjectHealth.AT_RISK -> DomainEventTypes.PROJECT_AT_RISK
            ProjectHealth.DELAYED -> DomainEventTypes.PROJECT_DELAYED
            else -> return false
        }
        append(type, SubjectRef.of(SubjectTypes.PROJECT, project.id), project, project(project) { put("from", from.name); put("to", to.name) })
        return true
    }

    suspend fun siteLogCreated(entry: SiteLogEntry, project: Project) =
        append(
            DomainEventTypes.SITE_LOG_CREATED, SubjectRef.of(SubjectTypes.SITE_LOG, entry.id), project,
            buildJsonObject {
                projectFacts(project)
                put("day", entry.day.toString())
                entry.workers?.let { put("workers", it) }
                put("shared", entry.shared)
                put("phasesMoved", entry.progress.size)
            },
        )

    suspend fun issueCreated(issue: SiteIssue, project: Project) =
        append(DomainEventTypes.SITE_ISSUE_CREATED, SubjectRef.of(SubjectTypes.SITE_ISSUE, issue.id), project, issue(issue, project))

    suspend fun issueStatusChanged(issue: SiteIssue, from: IssueStatus, project: Project) =
        append(
            DomainEventTypes.SITE_ISSUE_STATUS_CHANGED, SubjectRef.of(SubjectTypes.SITE_ISSUE, issue.id), project,
            issue(issue, project) { put("from", from.name); put("to", issue.status.name) },
        )

    suspend fun changeSent(change: ChangeOrder, project: Project) =
        append(DomainEventTypes.CHANGE_ORDER_SENT, SubjectRef.of(SubjectTypes.CHANGE_ORDER, change.id), project, change(change, project))

    suspend fun changeDecided(change: ChangeOrder, project: Project, via: DecidedVia) =
        append(
            DomainEventTypes.CHANGE_ORDER_DECIDED, SubjectRef.of(SubjectTypes.CHANGE_ORDER, change.id), project,
            change(change, project) {
                put("to", change.status.name)
                put("via", via.name)
                change.decidedBy?.let { put("decidedBy", it) }
            },
        )

    suspend fun measurementApproved(measurement: Measurement, project: Project) =
        append(DomainEventTypes.MEASUREMENT_APPROVED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project, measurement(measurement, project))

    suspend fun measurementInvoiced(measurement: Measurement, project: Project, invoice: Invoice) =
        append(
            DomainEventTypes.MEASUREMENT_INVOICED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project,
            measurement(measurement, project) { put("invoiceId", invoice.id.toHexString()); put("invoiceNumber", invoice.number) },
            extra = listOf(SubjectRef.of(SubjectTypes.INVOICE, invoice.id)),
        )

    private suspend fun append(type: String, subject: SubjectRef, project: Project, payload: JsonObject, extra: List<SubjectRef> = emptyList()) {
        val related = listOfNotNull(
            SubjectRef.of(SubjectTypes.PROJECT, project.id),
            project.clientId?.let { SubjectRef.of(SubjectTypes.CLIENT, it) },
        ) + extra
        log.append(tenantId, type, subject, payload, related)
    }

    private fun project(project: Project, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        projectFacts(project)
        put("status", project.status.name)
        put("contractCents", project.contractCents)
        extra()
    }

    private fun issue(issue: SiteIssue, project: Project, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        projectFacts(project)
        put("number", issue.number)
        put("title", issue.title)
        put("kind", issue.kind.name)
        put("priority", issue.priority.name)
        put("status", issue.status.name)
        issue.dueDate?.let { put("dueDate", it.toString()) }
        extra()
    }

    private fun change(change: ChangeOrder, project: Project, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        projectFacts(project)
        put("number", change.number)
        put("title", change.title)
        put("kind", change.kind.name)
        put("totalCents", change.signedCents)
        put("daysImpact", change.daysImpact)
        put("status", change.status.name)
        extra()
    }

    private fun measurement(measurement: Measurement, project: Project, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        projectFacts(project)
        put("number", measurement.number)
        put("periodEnd", measurement.periodEnd.toString())
        put("netCents", measurement.netCents)
        put("retentionCents", measurement.retentionCents)
        put("status", measurement.status.name)
        extra()
    }

    private fun JsonObjectBuilder.projectFacts(project: Project) {
        put("projectId", project.id.toHexString())
        put("projectNumber", project.number)
        put("projectName", project.name)
        project.clientId?.let { put("clientId", it.toHexString()) }
    }
}
