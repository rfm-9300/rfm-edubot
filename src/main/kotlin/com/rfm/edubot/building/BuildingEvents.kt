package com.rfm.edubot.building

import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.DecidedVia
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectReport
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.building.model.SiteLogEntry
import com.rfm.edubot.building.model.SitePhoto
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
 * The domain events Building appends after its writes, best effort like the CRM's: agents react to the ones with a
 * spec, all of them are the activity log, and naming the project and its client as related records puts them on
 * those records' history.
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

    suspend fun projectUpdated(project: Project) =
        append(DomainEventTypes.PROJECT_UPDATED, SubjectRef.of(SubjectTypes.PROJECT, project.id), project, project(project))

    suspend fun projectDeleted(project: Project) =
        append(DomainEventTypes.PROJECT_DELETED, SubjectRef.of(SubjectTypes.PROJECT, project.id), project, project(project))

    /** The phases rewritten, or [phase]'s progress set to [toPct]. */
    suspend fun projectPhasesChanged(project: Project, phase: String? = null, toPct: Int? = null) =
        append(
            DomainEventTypes.PROJECT_PHASES_CHANGED, SubjectRef.of(SubjectTypes.PROJECT, project.id), project,
            project(project) {
                put("phases", project.phases.size)
                phase?.let { put("phase", it) }
                toPct?.let { put("toPct", it) }
            },
        )

    /** [action] is one of [ShareActions]. */
    suspend fun projectShareChanged(project: Project, action: String) =
        append(DomainEventTypes.PROJECT_SHARE_CHANGED, SubjectRef.of(SubjectTypes.PROJECT, project.id), project, project(project) { put("action", action) })

    /** The client's project page was sent by email to [to]. */
    suspend fun projectLinkEmailed(project: Project, to: String) =
        append(DomainEventTypes.PROJECT_LINK_EMAILED, SubjectRef.of(SubjectTypes.PROJECT, project.id), project, project(project) { put("to", to) })

    suspend fun retentionInvoiced(project: Project, invoice: Invoice, amountCents: Long) =
        append(
            DomainEventTypes.PROJECT_RETENTION_INVOICED, SubjectRef.of(SubjectTypes.PROJECT, project.id), project,
            project(project) { put("invoiceId", invoice.id.toHexString()); put("invoiceNumber", invoice.number); put("amountCents", amountCents) },
            extra = listOf(SubjectRef.of(SubjectTypes.INVOICE, invoice.id)),
        )

    suspend fun siteLogCreated(entry: SiteLogEntry, project: Project) =
        append(DomainEventTypes.SITE_LOG_CREATED, SubjectRef.of(SubjectTypes.SITE_LOG, entry.id), project, siteLog(entry, project))

    suspend fun siteLogUpdated(entry: SiteLogEntry, project: Project) =
        append(DomainEventTypes.SITE_LOG_UPDATED, SubjectRef.of(SubjectTypes.SITE_LOG, entry.id), project, siteLog(entry, project))

    suspend fun siteLogDeleted(entry: SiteLogEntry, project: Project) =
        append(DomainEventTypes.SITE_LOG_DELETED, SubjectRef.of(SubjectTypes.SITE_LOG, entry.id), project, siteLog(entry, project))

    suspend fun photoAdded(photo: SitePhoto, project: Project) =
        append(DomainEventTypes.SITE_PHOTO_ADDED, SubjectRef.of(SubjectTypes.SITE_PHOTO, photo.id), project, photo(photo, project), photoRefs(photo))

    suspend fun photoUpdated(photo: SitePhoto, project: Project) =
        append(DomainEventTypes.SITE_PHOTO_UPDATED, SubjectRef.of(SubjectTypes.SITE_PHOTO, photo.id), project, photo(photo, project), photoRefs(photo))

    suspend fun photoDeleted(photo: SitePhoto, project: Project) =
        append(DomainEventTypes.SITE_PHOTO_DELETED, SubjectRef.of(SubjectTypes.SITE_PHOTO, photo.id), project, photo(photo, project), photoRefs(photo))

    suspend fun issueCreated(issue: SiteIssue, project: Project) =
        append(DomainEventTypes.SITE_ISSUE_CREATED, SubjectRef.of(SubjectTypes.SITE_ISSUE, issue.id), project, issue(issue, project))

    /** [via] is [DecidedVia.SUPPLIER_LINK] when the assigned supplier moved it from their link. */
    suspend fun issueStatusChanged(issue: SiteIssue, from: IssueStatus, project: Project, via: DecidedVia = DecidedVia.TEAM) =
        append(
            DomainEventTypes.SITE_ISSUE_STATUS_CHANGED, SubjectRef.of(SubjectTypes.SITE_ISSUE, issue.id), project,
            issue(issue, project) { put("from", from.name); put("to", issue.status.name); put("via", via.name) },
            extra = listOfNotNull(issue.supplierId?.let { SubjectRef.of(SubjectTypes.SUPPLIER, it) }),
        )

    suspend fun issueUpdated(issue: SiteIssue, project: Project) =
        append(DomainEventTypes.SITE_ISSUE_UPDATED, SubjectRef.of(SubjectTypes.SITE_ISSUE, issue.id), project, issue(issue, project))

    suspend fun issueDeleted(issue: SiteIssue, project: Project) =
        append(DomainEventTypes.SITE_ISSUE_DELETED, SubjectRef.of(SubjectTypes.SITE_ISSUE, issue.id), project, issue(issue, project))

    /** A supplier's link to their issues: [action] is one of [ShareActions] (`on`, `rotated`, `off`). */
    suspend fun supplierLinkChanged(supplierId: ObjectId, supplierName: String, action: String) =
        log.append(
            tenantId, DomainEventTypes.SUPPLIER_LINK_CHANGED, SubjectRef.of(SubjectTypes.SUPPLIER, supplierId),
            buildJsonObject { put("name", supplierName); put("action", action) },
        )

    /** A supplier's link to their issues was sent by email to [to]. */
    suspend fun supplierLinkEmailed(supplierId: ObjectId, supplierName: String, to: String) =
        log.append(
            tenantId, DomainEventTypes.SUPPLIER_LINK_EMAILED, SubjectRef.of(SubjectTypes.SUPPLIER, supplierId),
            buildJsonObject { put("name", supplierName); put("to", to) },
        )

    /** A sent change's approval link was emailed to [to]. */
    suspend fun changeLinkEmailed(change: ChangeOrder, project: Project, to: String) =
        append(DomainEventTypes.CHANGE_ORDER_LINK_EMAILED, SubjectRef.of(SubjectTypes.CHANGE_ORDER, change.id), project, change(change, project) { put("to", to) })

    suspend fun changeCreated(change: ChangeOrder, project: Project) =
        append(DomainEventTypes.CHANGE_ORDER_CREATED, SubjectRef.of(SubjectTypes.CHANGE_ORDER, change.id), project, change(change, project))

    suspend fun changeUpdated(change: ChangeOrder, project: Project) =
        append(DomainEventTypes.CHANGE_ORDER_UPDATED, SubjectRef.of(SubjectTypes.CHANGE_ORDER, change.id), project, change(change, project))

    suspend fun changeDeleted(change: ChangeOrder, project: Project) =
        append(DomainEventTypes.CHANGE_ORDER_DELETED, SubjectRef.of(SubjectTypes.CHANGE_ORDER, change.id), project, change(change, project))

    suspend fun changeReopened(change: ChangeOrder, project: Project, from: ChangeOrderStatus) =
        append(
            DomainEventTypes.CHANGE_ORDER_REOPENED, SubjectRef.of(SubjectTypes.CHANGE_ORDER, change.id), project,
            change(change, project) { put("from", from.name) },
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
        append(
            DomainEventTypes.MEASUREMENT_APPROVED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project,
            measurement(measurement, project) {
                put("via", (measurement.approvedVia ?: DecidedVia.TEAM).name)
                measurement.approvedBy?.let { put("decidedBy", it) }
            },
        )

    suspend fun measurementCreated(measurement: Measurement, project: Project) =
        append(DomainEventTypes.MEASUREMENT_CREATED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project, measurement(measurement, project))

    suspend fun measurementUpdated(measurement: Measurement, project: Project) =
        append(DomainEventTypes.MEASUREMENT_UPDATED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project, measurement(measurement, project))

    suspend fun measurementReopened(measurement: Measurement, project: Project) =
        append(DomainEventTypes.MEASUREMENT_REOPENED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project, measurement(measurement, project))

    /** A measurement's approval link was emailed to [to]. */
    suspend fun measurementLinkEmailed(measurement: Measurement, project: Project, to: String) =
        append(DomainEventTypes.MEASUREMENT_LINK_EMAILED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project, measurement(measurement, project) { put("to", to) })

    suspend fun measurementDeleted(measurement: Measurement, project: Project) =
        append(DomainEventTypes.MEASUREMENT_DELETED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project, measurement(measurement, project))

    suspend fun measurementSent(measurement: Measurement, project: Project) =
        append(DomainEventTypes.MEASUREMENT_SENT, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project, measurement(measurement, project))

    /** The client said no from the link: who, and what they wrote. */
    suspend fun measurementDisputed(measurement: Measurement, project: Project) =
        append(
            DomainEventTypes.MEASUREMENT_DISPUTED, SubjectRef.of(SubjectTypes.MEASUREMENT, measurement.id), project,
            measurement(measurement, project) {
                put("via", DecidedVia.CLIENT_LINK.name)
                measurement.dispute?.let { put("decidedBy", it.name); put("note", it.note) }
            },
        )

    suspend fun reportPosted(report: ProjectReport, project: Project) =
        append(DomainEventTypes.REPORT_POSTED, SubjectRef.of(SubjectTypes.REPORT, report.id), project, report(report, project))

    suspend fun reportUpdated(report: ProjectReport, project: Project) =
        append(DomainEventTypes.REPORT_UPDATED, SubjectRef.of(SubjectTypes.REPORT, report.id), project, report(report, project))

    suspend fun reportDeleted(report: ProjectReport, project: Project) =
        append(DomainEventTypes.REPORT_DELETED, SubjectRef.of(SubjectTypes.REPORT, report.id), project, report(report, project))

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

    private fun siteLog(entry: SiteLogEntry, project: Project) = buildJsonObject {
        projectFacts(project)
        put("day", entry.day.toString())
        entry.workers?.let { put("workers", it) }
        put("shared", entry.shared)
        put("phasesMoved", entry.progress.size)
    }

    private fun photo(photo: SitePhoto, project: Project) = buildJsonObject {
        projectFacts(project)
        put("day", photo.day.toString())
        photo.caption?.let { put("caption", it) }
        put("shared", photo.shared)
    }

    /** A photo of a diary day or an issue is in that record's history too. */
    private fun photoRefs(photo: SitePhoto) = listOfNotNull(
        photo.logId?.let { SubjectRef.of(SubjectTypes.SITE_LOG, it) },
        photo.issueId?.let { SubjectRef.of(SubjectTypes.SITE_ISSUE, it) },
    )

    private fun report(report: ProjectReport, project: Project) = buildJsonObject {
        projectFacts(project)
        put("from", report.from.toString())
        put("to", report.to.toString())
    }

    private fun project(project: Project, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        projectFacts(project)
        put("number", project.number)
        put("name", project.name)
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

/** What happened to a link a record gives someone outside the company. */
object ShareActions {
    const val ON = "on"
    const val OFF = "off"
    const val ROTATED = "rotated"
    const val BUDGET_SHOWN = "budget_shown"
    const val BUDGET_HIDDEN = "budget_hidden"
}
