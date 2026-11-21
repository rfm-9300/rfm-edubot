package com.rfm.edubot.building

import com.rfm.edubot.building.model.ApprovalLink
import com.rfm.edubot.building.model.DecidedVia
import com.rfm.edubot.building.model.LinkProof
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.MeasurementDispute
import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.building.model.RetentionRelease
import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.LineItem
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import kotlin.math.abs

/**
 * A project's measurements (autos de medição), numbered in order: a draft with every billable line at the
 * quantities of the one before, quantities to date typed in, approved (by the team, or by the client from the link
 * it was sent with), then invoiced for what it adds, less the retention, which is invoiced back once the project is
 * finished. From its first measurement the project's quote is billed only through them.
 */
class MeasurementService(private val records: BuildingRecords) {
    data class Invoiced(val measurement: Measurement, val invoice: Invoice)

    data class RetentionInvoiced(val project: Project, val invoice: Invoice)

    /** Refused while the latest one is still a draft ([Outcome.Refused.detail] is its id). */
    suspend fun create(projectId: ObjectId?, periodEnd: LocalDate?, notes: String?, stamp: Stamp): Outcome<Measurement> {
        val project = projectId?.let { records.projects.findById(it) } ?: return invalid("project_not_found")
        val previous = records.measurements.latest(project.id)
        if (previous?.status == MeasurementStatus.DRAFT) return conflict("measurement_open", previous.id.toHexString())
        val end = periodEnd ?: stamp.today
        if (previous != null && end < previous.periodEnd) return invalid("period_before_previous")
        val quote = project.quoteId?.let { records.quotes.findById(it) }
        if (quote != null && quote.projectId != project.id && QuoteInvoicing.billedBy(quote, records.invoices, records.clientServices) != null) {
            return conflict("quote_billed")
        }
        val billable = MeasurementMath.billableLines(project, records.changes.list(project.id))
        if (billable.isEmpty()) return invalid("nothing_to_measure")
        val lines = MeasurementMath.draftLines(billable, previous)
        val totals = MeasurementMath.totals(lines, project.retentionPct)
        val measurement = Measurement(
            tenantId = records.tenantId,
            projectId = project.id,
            number = (previous?.number ?: 0) + 1,
            periodEnd = end,
            lines = lines,
            retentionPct = project.retentionPct,
            grossCents = totals.grossCents,
            retentionCents = totals.retentionCents,
            netCents = totals.netCents,
            toDateCents = totals.toDateCents,
            notes = notes?.trim()?.take(BuildingLimits.TEXT)?.ifEmpty { null },
            createdBy = stamp.who,
            createdAt = stamp.now,
            updatedAt = stamp.now,
        )
        records.measurements.insert(measurement) ?: return conflict("conflict")
        if (quote != null && quote.projectId == null) records.quoteService.setBillingProject(quote.id, project.id)
        records.events.measurementCreated(measurement, project)
        return Outcome.Done(measurement)
    }

    /**
     * A draft's period end, notes and quantities to date by line key; a refused quantity names its line in
     * [Outcome.Refused.detail]. A blank [notes] clears them; null keeps either. A draft sent to the client loses its
     * link: the client approves what they read. A dispute stays in view until it is sent again.
     */
    suspend fun update(id: ObjectId, periodEnd: LocalDate?, notes: String?, toDate: Map<String, Double>, stamp: Stamp): Outcome<Measurement> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status != MeasurementStatus.DRAFT) return conflict("measurement_locked")
        val end = periodEnd ?: measurement.periodEnd
        val before = records.measurements.list(measurement.projectId).firstOrNull { it.number == measurement.number - 1 }
        if (before != null && end < before.periodEnd) return invalid("period_before_previous")
        val lines = when (val applied = MeasurementMath.apply(measurement.lines, toDate)) {
            is MeasurementMath.Result.Refused -> return invalid(applied.error, applied.key)
            is MeasurementMath.Result.Ok -> applied.lines
        }
        val totals = MeasurementMath.totals(lines, measurement.retentionPct)
        val next = measurement.copy(
            periodEnd = end,
            lines = lines,
            notes = if (notes == null) measurement.notes else notes.trim().take(BuildingLimits.TEXT).ifEmpty { null },
            grossCents = totals.grossCents,
            retentionCents = totals.retentionCents,
            netCents = totals.netCents,
            toDateCents = totals.toDateCents,
            approval = null,
            updatedAt = stamp.now,
        )
        val stored = records.measurements.replace(measurement, next) ?: return conflict("conflict")
        records.projects.findById(stored.projectId)?.let { records.events.measurementUpdated(stored, it) }
        return Outcome.Done(stored)
    }

    /**
     * Sends a draft to its client to approve: it gets a link (`/app/share/measurement/{token}`), a new one after a
     * dispute. Sending one the client hasn't answered yet keeps its link.
     */
    suspend fun send(id: ObjectId, stamp: Stamp): Outcome<Measurement> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status != MeasurementStatus.DRAFT) return conflict("measurement_locked")
        if (measurement.awaitingClient) return Outcome.Done(measurement)
        if (nothingMeasured(measurement)) return invalid("nothing_measured")
        val project = records.projects.findById(measurement.projectId) ?: return Outcome.NotFound
        val next = measurement.copy(approval = ApprovalLink(secretToken(), stamp.now), dispute = null, updatedAt = stamp.now)
        val stored = records.measurements.replace(measurement, next) ?: return conflict("conflict")
        records.events.measurementSent(stored, project)
        return Outcome.Done(stored)
    }

    /** The team approves a draft, for the client who signed it on paper or before it is sent. */
    suspend fun approve(id: ObjectId, stamp: Stamp): Outcome<Measurement> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status != MeasurementStatus.DRAFT) return conflict("measurement_locked")
        if (nothingMeasured(measurement)) return invalid("nothing_measured")
        val next = measurement.copy(
            status = MeasurementStatus.APPROVED, approvedAt = stamp.now, approvedBy = stamp.who, approvedVia = DecidedVia.TEAM, approvalProof = null,
            dispute = null, updatedAt = stamp.now,
        )
        val stored = records.measurements.replace(measurement, next) ?: return conflict("conflict")
        records.projects.findById(stored.projectId)?.let { records.events.measurementApproved(stored, it) }
        return Outcome.Done(stored)
    }

    /**
     * The client's answer from the measurement's link ([token]): `APPROVED`, or `DISPUTED` with a [note] saying why, in
     * the [name] they typed, [proof] where it came from. Once per sending: a second answer, or one racing the team's, is
     * `already_decided`. The team hears it on the bell, and agents from `measurement.approved` or `.disputed`.
     */
    suspend fun decideByLink(id: ObjectId, token: String, decision: String, name: String, note: String?, proof: LinkProof?, stamp: Stamp): Outcome<Measurement> {
        val measurement = records.measurements.findById(id)?.takeIf { it.approval?.token == token } ?: return Outcome.NotFound
        if (!measurement.awaitingClient) return conflict("already_decided")
        val approved = when (decision.trim().uppercase()) {
            MeasurementStatus.APPROVED.name -> true
            DISPUTED -> false
            else -> return invalid("invalid_decision", "decision")
        }
        val words = when (val typed = linkWords(name, note)) {
            is Outcome.Done -> typed.value
            is Outcome.Refused -> return typed
            Outcome.NotFound -> return Outcome.NotFound
        }
        val said = words.note
        if (!approved && said == null) return invalid("note_required", "note")
        val next = if (approved) {
            measurement.copy(
                status = MeasurementStatus.APPROVED, approvedAt = stamp.now, approvedBy = words.name, approvedVia = DecidedVia.CLIENT_LINK,
                approvalProof = proof, updatedAt = stamp.now,
            )
        } else {
            measurement.copy(dispute = MeasurementDispute(words.name, said.orEmpty(), stamp.now, proof), updatedAt = stamp.now)
        }
        val stored = records.measurements.replace(measurement, next) ?: return conflict("already_decided")
        val project = records.projects.findById(stored.projectId)
        if (project != null) {
            if (approved) records.events.measurementApproved(stored, project) else records.events.measurementDisputed(stored, project)
        }
        runCatching {
            records.notifications.notify(
                tenantId = records.tenantId,
                kind = NotificationKinds.MEASUREMENT_DECIDED,
                audience = NotificationAudience.ALL,
                params = mapOf(
                    "name" to words.name, "n" to stored.number.toString(), "project" to project?.name.orEmpty(),
                    "decision" to if (approved) MeasurementStatus.APPROVED.name else DISPUTED,
                ),
                link = DashboardModules.MEASUREMENTS,
                subject = SubjectRef.of(SubjectTypes.MEASUREMENT, stored.id),
            )
        }
        return Outcome.Done(stored)
    }

    /** Back to draft: only the project's latest, and only before it is invoiced. It must be sent to the client again. */
    suspend fun reopen(id: ObjectId, stamp: Stamp): Outcome<Measurement> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status != MeasurementStatus.APPROVED) return conflict("measurement_locked")
        if (records.measurements.latest(measurement.projectId)?.id != measurement.id) return conflict("not_latest")
        val next = measurement.copy(
            status = MeasurementStatus.DRAFT, approvedAt = null, approvedBy = null, approvedVia = null, approvalProof = null, approval = null,
            updatedAt = stamp.now,
        )
        val stored = records.measurements.replace(measurement, next) ?: return conflict("conflict")
        records.projects.findById(stored.projectId)?.let { records.events.measurementReopened(stored, it) }
        return Outcome.Done(stored)
    }

    private fun nothingMeasured(measurement: Measurement) = measurement.lines.all { abs(MeasurementMath.periodQty(it)) < 1e-9 }

    /**
     * The approved measurement's invoice, to the project's client: one line per line measured this period, named
     * with the measurement's number in the company's [locale], and the retention held as a negative line.
     */
    suspend fun invoice(id: ObjectId, dueDate: LocalDate, locale: String, stamp: Stamp): Outcome<Invoiced> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status == MeasurementStatus.INVOICED) return conflict("already_invoiced")
        if (measurement.status != MeasurementStatus.APPROVED) return conflict("not_approved")
        val project = records.projects.findById(measurement.projectId) ?: return Outcome.NotFound
        val clientId = project.clientId?.takeIf { records.clients.findById(it) != null } ?: return invalid("no_client")
        val prefix = BuildingCopy.t(locale, "measurement", "n" to measurement.number.toString())
        val items = MeasurementMath.invoiceItems(
            measurement,
            BuildingCopy.t(locale, "retention", "pct" to BuildingCopy.percent(locale, measurement.retentionPct)),
        ).map { if (it.totalCents >= 0) it.copy(description = "$prefix · ${it.description}") else it }
        if (items.sumOf { it.totalCents } <= 0) return invalid("nothing_to_invoice")
        // Claimed before the invoice exists, so a second click can't bill it twice.
        val claimed = measurement.copy(status = MeasurementStatus.INVOICED, invoicedAt = stamp.now, updatedAt = stamp.now)
        records.measurements.replace(measurement, claimed) ?: return conflict("conflict")
        val invoice = records.invoiceService.create(clientId, null, items, dueDate, projectId = project.id, measurementId = measurement.id)
        val stored = claimed.copy(invoiceId = invoice.id)
        records.measurements.replace(claimed, stored)
        records.events.measurementInvoiced(stored, project, invoice)
        return Outcome.Done(Invoiced(stored, invoice))
    }

    /**
     * The retention a finished project still holds, invoiced to its client in one line labelled in the company's
     * [locale], and kept on the project as a release. Refused while one of its measurements is still a draft or to
     * invoice ([Outcome.Refused.detail] is its id): its retention would be held after the release.
     */
    suspend fun invoiceRetention(projectId: ObjectId, dueDate: LocalDate, locale: String, stamp: Stamp): Outcome<RetentionInvoiced> {
        val project = records.projects.findById(projectId) ?: return Outcome.NotFound
        if (project.status != ProjectStatus.COMPLETED) return conflict("project_not_completed")
        val clientId = project.clientId?.takeIf { records.clients.findById(it) != null } ?: return invalid("no_client")
        val measurements = records.measurements.list(project.id)
        measurements.firstOrNull { it.status != MeasurementStatus.INVOICED }?.let { return conflict("measurement_pending", it.id.toHexString()) }
        val release = RetentionRelease(ObjectId().toHexString(), invoiceId = null, amountCents = 0, releasedBy = stamp.who, releasedAt = stamp.now)
        // Claimed before the invoice exists, so a second click finds nothing held.
        val claimed = records.projects.update(project.id) { current ->
            val held = MeasurementMath.retentionHeldCents(current, measurements)
            if (held <= 0) ProjectChange.Refused("nothing_held")
            else ProjectChange.Ok(current.copy(retentionReleases = current.retentionReleases + release.copy(amountCents = held), updatedAt = stamp.now))
        }
        val amount = when (claimed) {
            null -> return Outcome.NotFound
            is ProjectChange.Refused -> return conflict(claimed.error)
            is ProjectChange.Ok -> claimed.project.retentionReleases.single { it.id == release.id }.amountCents
        }
        val label = BuildingCopy.t(locale, "retentionRelease", "project" to "${project.number} · ${project.name}")
        val invoice = runCatching {
            records.invoiceService.create(clientId, null, listOf(LineItem(label, 1.0, "", amount, amount)), dueDate, projectId = project.id)
        }.getOrElse { error ->
            records.projects.update(project.id) { ProjectChange.Ok(it.copy(retentionReleases = it.retentionReleases.filterNot { r -> r.id == release.id })) }
            throw error
        }
        val stored = records.projects.update(project.id) { current ->
            ProjectChange.Ok(current.copy(retentionReleases = current.retentionReleases.map { if (it.id == release.id) it.copy(invoiceId = invoice.id) else it }))
        }
        val released = (stored as? ProjectChange.Ok)?.project ?: project
        records.events.retentionInvoiced(released, invoice, amount)
        return Outcome.Done(RetentionInvoiced(released, invoice))
    }

    /** Only the project's latest, before it is invoiced. Removing the last one frees the quote to be billed again. */
    suspend fun delete(id: ObjectId): Outcome<Unit> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status == MeasurementStatus.INVOICED) return conflict("measurement_invoiced")
        if (records.measurements.latest(measurement.projectId)?.id != measurement.id) return conflict("not_latest")
        records.measurements.delete(measurement.id)
        val project = records.projects.findById(measurement.projectId)
        if (records.measurements.count(measurement.projectId) == 0L) {
            val quote = project?.quoteId?.let { records.quotes.findById(it) }
            if (quote != null && quote.projectId == project.id) records.quoteService.setBillingProject(quote.id, null)
        }
        project?.let { records.events.measurementDeleted(measurement, it) }
        return Outcome.Done(Unit)
    }
}

/** The client's no to a measurement, as their link sends it. */
private const val DISPUTED = "DISPUTED"
