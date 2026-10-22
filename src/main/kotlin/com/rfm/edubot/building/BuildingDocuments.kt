package com.rfm.edubot.building

import com.rfm.edubot.tenant.model.DocumentTemplate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import org.bson.types.ObjectId

/** A PDF Building wrote, and the name it downloads as. */
class BuildingDocument(val fileName: String, val bytes: ByteArray)

/**
 * Building's PDFs from its records, in the company's language and with its document template: a measurement, and
 * a project's diary for a period of at most [MAX_DIARY_DAYS] days with some of each day's photos.
 */
class BuildingDocuments(private val records: BuildingRecords, private val store: PhotoStore) {
    suspend fun measurement(id: ObjectId, template: DocumentTemplate, locale: String, stamp: Stamp): BuildingDocument? {
        val measurement = records.measurements.findById(id) ?: return null
        val project = records.projects.findById(measurement.projectId) ?: return null
        val client = project.clientId?.let { records.clients.findById(it) }
        val invoice = measurement.invoiceId?.let { records.invoices.findById(it) }
        val sheet = MeasurementSheet(measurement, project, client, measurement.approvedAt?.let(stamp::day), invoice?.number)
        return BuildingDocument("measurement-${measurement.number}-${project.number}.pdf", BuildingPdf(template, locale).measurement(sheet))
    }

    /** The project's diary from [from] (30 days before [to] when null) to [to] (today when null). */
    suspend fun diary(projectId: ObjectId?, from: LocalDate?, to: LocalDate?, template: DocumentTemplate, locale: String, stamp: Stamp): Outcome<BuildingDocument> {
        val project = projectId?.let { records.projects.findById(it) } ?: return invalid("project_not_found")
        val end = to ?: stamp.today
        val start = from ?: end.minus(DEFAULT_DIARY_DAYS - 1, DateTimeUnit.DAY)
        if (end < start) return invalid("invalid_range")
        if (start.daysUntil(end) >= MAX_DIARY_DAYS) return invalid("range_too_long")
        val entries = records.logs.list(project.id, start, end).sortedBy { it.day }
        val photos = records.photos.forLogs(entries.map { it.id }).groupBy { it.logId }
        val days = entries.map { entry ->
            val all = photos[entry.id].orEmpty().sortedBy { it.takenAt ?: it.createdAt }
            val printed = all.take(BuildingPdf.DIARY_PHOTOS).mapNotNull { photo -> store.read(photo, thumb = true)?.let { DiaryPhoto(photo.caption, it) } }
            DiaryDay(entry, printed, (all.size - BuildingPdf.DIARY_PHOTOS).coerceAtLeast(0))
        }
        val sheet = DiarySheet(
            project = project,
            client = project.clientId?.let { records.clients.findById(it) },
            managerName = project.managerId?.let { records.employees.findById(it)?.name },
            from = start,
            to = end,
            days = days,
        )
        return Outcome.Done(BuildingDocument("diary-${project.number}-$start-$end.pdf", BuildingPdf(template, locale).diary(sheet)))
    }

    companion object {
        const val DEFAULT_DIARY_DAYS = 30
        const val MAX_DIARY_DAYS = 92
    }
}
