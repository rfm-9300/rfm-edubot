package com.rfm.edubot.building

import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.model.Invoice
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import kotlin.math.abs

/**
 * A project's measurements (autos de medição), numbered in order: a draft with every billable line at the
 * quantities of the one before, quantities to date typed in, approved, then invoiced for what it adds, less the
 * retention. From its first measurement the project's quote is billed only through them.
 */
class MeasurementService(private val records: BuildingRecords) {
    data class Invoiced(val measurement: Measurement, val invoice: Invoice)

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
        return Outcome.Done(measurement)
    }

    /**
     * A draft's period end, notes and quantities to date by line key; a refused quantity names its line in
     * [Outcome.Refused.detail]. A blank [notes] clears them; null keeps either.
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
            updatedAt = stamp.now,
        )
        return records.measurements.replace(measurement, next)?.let { Outcome.Done(it) } ?: conflict("conflict")
    }

    suspend fun approve(id: ObjectId, stamp: Stamp): Outcome<Measurement> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status != MeasurementStatus.DRAFT) return conflict("measurement_locked")
        if (measurement.lines.all { abs(MeasurementMath.periodQty(it)) < 1e-9 }) return invalid("nothing_measured")
        val next = measurement.copy(status = MeasurementStatus.APPROVED, approvedAt = stamp.now, approvedBy = stamp.who, updatedAt = stamp.now)
        return records.measurements.replace(measurement, next)?.let { Outcome.Done(it) } ?: conflict("conflict")
    }

    /** Back to draft: only the project's latest, and only before it is invoiced. */
    suspend fun reopen(id: ObjectId, stamp: Stamp): Outcome<Measurement> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status != MeasurementStatus.APPROVED) return conflict("measurement_locked")
        if (records.measurements.latest(measurement.projectId)?.id != measurement.id) return conflict("not_latest")
        val next = measurement.copy(status = MeasurementStatus.DRAFT, approvedAt = null, approvedBy = null, updatedAt = stamp.now)
        return records.measurements.replace(measurement, next)?.let { Outcome.Done(it) } ?: conflict("conflict")
    }

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
        return Outcome.Done(Invoiced(stored, invoice))
    }

    /** Only the project's latest, before it is invoiced. Removing the last one frees the quote to be billed again. */
    suspend fun delete(id: ObjectId): Outcome<Unit> {
        val measurement = records.measurements.findById(id) ?: return Outcome.NotFound
        if (measurement.status == MeasurementStatus.INVOICED) return conflict("measurement_invoiced")
        if (records.measurements.latest(measurement.projectId)?.id != measurement.id) return conflict("not_latest")
        records.measurements.delete(measurement.id)
        if (records.measurements.count(measurement.projectId) == 0L) {
            val project = records.projects.findById(measurement.projectId)
            val quote = project?.quoteId?.let { records.quotes.findById(it) }
            if (quote != null && quote.projectId == project.id) records.quoteService.setBillingProject(quote.id, null)
        }
        return Outcome.Done(Unit)
    }
}
