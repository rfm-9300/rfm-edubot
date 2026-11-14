package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.timesheets.Timesheets.workedUntil
import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.ReviewStatus
import com.rfm.edubot.timesheets.model.SegmentSource
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.bson.types.ObjectId

/**
 * The period's shifts as CSV for payroll or a labour inspection: one row per shift with start, end, breaks and
 * hours per worker and day (Código do Trabalho, art. 202.º). Headers are in the company's language; `;`
 * separates and a BOM leads, so Excel in Portuguese or Spanish opens it as columns.
 */
internal object TimesheetExport {
    fun csv(shifts: List<Shift>, employees: Map<ObjectId, Employee>, zone: TimeZone, locale: String, now: Instant): String {
        val labels = Labels.of(locale)
        val out = StringBuilder("\uFEFF")
        out.row(labels.header)
        shifts.sortedWith(compareBy({ employees[it.employeeId]?.name?.lowercase().orEmpty() }, { it.startAt })).forEach { shift ->
            val employee = employees[shift.employeeId]
            val end = shift.endAt
            val worked = shift.workedUntil(now)
            out.row(
                listOf(
                    employee?.number.orEmpty(),
                    employee?.name.orEmpty(),
                    employee?.taxId.orEmpty(),
                    shift.day.toString(),
                    ShiftMath.localTime(shift.startAt, zone),
                    end?.let { ShiftMath.localTime(it, zone) + if (ShiftMath.localDay(it, zone) > shift.day) " (+1)" else "" }.orEmpty(),
                    (if (shift.isOpen) ShiftMath.breakMinutes(shift.times, now) else shift.breakMinutes).toString(),
                    "${worked / 60}:${(worked % 60).toString().padStart(2, '0')}",
                    worked.toString(),
                    shift.siteName.orEmpty(),
                    shift.flags.joinToString(","),
                    when {
                        shift.isOpen -> labels.open
                        shift.review.status == ReviewStatus.APPROVED -> labels.approved
                        else -> labels.pending
                    },
                    if (shift.manual || shift.edits.any { it.before != null }) labels.yes else labels.no,
                    shift.note.orEmpty(),
                    shift.planned?.let { "${ShiftMath.localTime(it.startAt, zone)}–${ShiftMath.localTime(it.endAt, zone)}" }.orEmpty(),
                    shift.lateMinutes.takeIf { shift.planned != null }?.toString().orEmpty(),
                    shift.earlyLeaveMinutes.takeIf { shift.planned != null && !shift.isOpen }?.toString().orEmpty(),
                    if (shift.segments.isEmpty()) "" else WorkLog.spans(shift, now).joinToString(" | ") { "${it.segment.job.label()} ${hours(it.minutes)}" },
                ),
            )
        }
        return out.toString()
    }

    /**
     * The work log: one row per segment (a stretch of a shift on one job) with its times, minutes, client, project,
     * activity, note, whether it is billable, who set it and the shift's status.
     */
    fun workLog(rows: List<WorkRow>, employees: Map<ObjectId, Employee>, rules: TimesheetSettings, zone: TimeZone, locale: String): String {
        val labels = Labels.of(locale)
        val out = StringBuilder("﻿")
        out.row(labels.workLogHeader)
        rows.sortedWith(compareBy({ employees[it.shift.employeeId]?.name?.lowercase().orEmpty() }, { it.span.startAt })).forEach { row ->
            val employee = employees[row.shift.employeeId]
            val job = row.job
            val end = row.span.endAt
            out.row(
                listOf(
                    employee?.number.orEmpty(),
                    employee?.name.orEmpty(),
                    ShiftMath.localDay(row.span.startAt, zone).toString(),
                    ShiftMath.localTime(row.span.startAt, zone),
                    end?.let { ShiftMath.localTime(it, zone) }.orEmpty(),
                    hours(row.span.minutes),
                    row.span.minutes.toString(),
                    job?.clientName.orEmpty(),
                    job?.projectName.orEmpty(),
                    job?.activityName.orEmpty(),
                    job?.note.orEmpty(),
                    if (job?.billable == true) labels.yes else labels.no,
                    if (WorkLog.missing(job, rules.jobNeeds) == null) labels.yes else labels.no,
                    labels.source(row.span.segment.source),
                    when (WorkLogService.status(row.shift)) {
                        "OPEN" -> labels.open
                        "APPROVED" -> labels.approved
                        else -> labels.pending
                    },
                ),
            )
        }
        return out.toString()
    }

    private fun hours(minutes: Int): String = "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

    private fun Job?.label(): String = this?.let { listOfNotNull(it.clientName, it.projectName, it.activityName).joinToString(" · ").ifEmpty { it.note.orEmpty() } }.orEmpty().ifEmpty { "—" }

    private fun StringBuilder.row(cells: List<String>) {
        cells.joinTo(this, ";") { escape(it) }
        append("\r\n")
    }

    /** Quotes what would break a column, and defuses text a spreadsheet would run as a formula. */
    private fun escape(value: String): String {
        val safe = if (value.firstOrNull() in FORMULA_STARTS) "'$value" else value
        return if (safe.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t')

    private class Labels(
        val header: List<String>,
        val workLogHeader: List<String>,
        val open: String,
        val pending: String,
        val approved: String,
        val yes: String,
        val no: String,
        private val sources: Map<SegmentSource, String>,
    ) {
        fun source(source: SegmentSource): String = sources[source].orEmpty()

        companion object {
            fun of(locale: String): Labels = when (locale.lowercase().substringBefore('-').substringBefore('_')) {
                "pt" -> Labels(
                    listOf(
                        "Número", "Colaborador", "NIF", "Dia", "Entrada", "Saída", "Pausas (min)", "Horas", "Minutos", "Local", "Alertas", "Estado",
                        "Corrigido", "Nota", "Planeado", "Atraso (min)", "Saída antecipada (min)", "Trabalhos",
                    ),
                    listOf(
                        "Número", "Colaborador", "Dia", "Início", "Fim", "Horas", "Minutos", "Cliente", "Obra", "Atividade", "Nota", "Faturável",
                        "Completo", "Registado por", "Estado",
                    ),
                    open = "Em curso", pending = "Por rever", approved = "Aprovado", yes = "Sim", no = "Não",
                    sources = mapOf(SegmentSource.CLOCK to "Relógio", SegmentSource.EMPLOYEE to "Colaborador", SegmentSource.TEAM to "Equipa"),
                )
                "es" -> Labels(
                    listOf(
                        "Número", "Empleado", "NIF", "Día", "Entrada", "Salida", "Pausas (min)", "Horas", "Minutos", "Centro", "Alertas", "Estado",
                        "Corregido", "Nota", "Planificado", "Retraso (min)", "Salida anticipada (min)", "Trabajos",
                    ),
                    listOf(
                        "Número", "Empleado", "Día", "Inicio", "Fin", "Horas", "Minutos", "Cliente", "Obra", "Actividad", "Nota", "Facturable",
                        "Completo", "Registrado por", "Estado",
                    ),
                    open = "En curso", pending = "Por revisar", approved = "Aprobado", yes = "Sí", no = "No",
                    sources = mapOf(SegmentSource.CLOCK to "Fichaje", SegmentSource.EMPLOYEE to "Empleado", SegmentSource.TEAM to "Equipo"),
                )
                else -> Labels(
                    listOf(
                        "Number", "Employee", "Tax ID", "Day", "Start", "End", "Breaks (min)", "Hours", "Minutes", "Site", "Flags", "Status",
                        "Corrected", "Note", "Planned", "Late (min)", "Left early (min)", "Jobs",
                    ),
                    listOf(
                        "Number", "Employee", "Day", "Start", "End", "Hours", "Minutes", "Client", "Project", "Activity", "Note", "Billable",
                        "Complete", "Set by", "Status",
                    ),
                    open = "Open", pending = "To review", approved = "Approved", yes = "Yes", no = "No",
                    sources = mapOf(SegmentSource.CLOCK to "Clock", SegmentSource.EMPLOYEE to "Employee", SegmentSource.TEAM to "Team"),
                )
            }
        }
    }
}
