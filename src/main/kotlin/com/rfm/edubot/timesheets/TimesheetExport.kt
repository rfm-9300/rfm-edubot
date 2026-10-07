package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.timesheets.Timesheets.workedUntil
import com.rfm.edubot.timesheets.model.ReviewStatus
import com.rfm.edubot.timesheets.model.Shift
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
                ),
            )
        }
        return out.toString()
    }

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

    private class Labels(val header: List<String>, val open: String, val pending: String, val approved: String, val yes: String, val no: String) {
        companion object {
            fun of(locale: String): Labels = when (locale.lowercase().substringBefore('-').substringBefore('_')) {
                "pt" -> Labels(
                    listOf("Número", "Colaborador", "NIF", "Dia", "Entrada", "Saída", "Pausas (min)", "Horas", "Minutos", "Local", "Alertas", "Estado", "Corrigido", "Nota"),
                    open = "Em curso", pending = "Por rever", approved = "Aprovado", yes = "Sim", no = "Não",
                )
                "es" -> Labels(
                    listOf("Número", "Empleado", "NIF", "Día", "Entrada", "Salida", "Pausas (min)", "Horas", "Minutos", "Centro", "Alertas", "Estado", "Corregido", "Nota"),
                    open = "En curso", pending = "Por revisar", approved = "Aprobado", yes = "Sí", no = "No",
                )
                else -> Labels(
                    listOf("Number", "Employee", "Tax ID", "Day", "Start", "End", "Breaks (min)", "Hours", "Minutes", "Site", "Flags", "Status", "Corrected", "Note"),
                    open = "Open", pending = "To review", approved = "Approved", yes = "Yes", no = "No",
                )
            }
        }
    }
}
