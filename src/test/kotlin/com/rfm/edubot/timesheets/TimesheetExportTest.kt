package com.rfm.edubot.timesheets

import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.timesheets.model.Punch
import com.rfm.edubot.timesheets.model.PunchChannel
import com.rfm.edubot.timesheets.model.PunchType
import com.rfm.edubot.timesheets.model.TimesheetSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimesheetExportTest {
    private val lisbon = TimeZone.of("Europe/Lisbon")
    private val tenantId = ObjectId()
    private val now = Instant.parse("2026-10-10T12:00:00Z")

    private fun employee(name: String) = Employee(tenantId = tenantId, number = "COL-001", name = name, phone = "910000000", taxId = "123456789", createdAt = now, updatedAt = now)

    @Test
    fun `one row per shift, in the company's language, ready for a spreadsheet`() {
        val worker = employee("=Ana; \"Costa\"")
        val times = ShiftMath.wallTimes(LocalDate(2026, 10, 5), LocalTime(22, 0), LocalTime(6, 30), listOf(LocalTime(2, 0) to LocalTime(2, 30)), lisbon)
        val start = ShiftRules.start(tenantId, worker.id, Punch(PunchType.IN, times.startAt, PunchChannel.APP), null, "Turno da noite", TimesheetSettings.DEFAULT, lisbon, times.startAt)
        val shift = ShiftRules.edit(
            ShiftRules.punch(start, Punch(PunchType.OUT, times.endAt!!, PunchChannel.APP), TimesheetSettings.DEFAULT, lisbon, times.endAt!!).let { (it as Transition.Ok).shift },
            times, "rui@obras.test", "Pausa às 2h", TimesheetSettings.DEFAULT, lisbon, now,
        ).let { (it as Transition.Ok).shift }

        val csv = TimesheetExport.csv(listOf(shift), mapOf(worker.id to worker), lisbon, "pt-PT", now)
        val lines = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals("Número;Colaborador;NIF;Dia;Entrada;Saída;Pausas (min);Horas;Minutos;Local;Alertas;Estado;Corrigido;Nota", lines[0])
        assertEquals("COL-001;\"'=Ana; \"\"Costa\"\"\";123456789;2026-10-05;22:00;06:30 (+1);30;8:00;480;;EDITED;Por rever;Sim;Turno da noite", lines[1])
        assertEquals("Number", TimesheetExport.csv(emptyList(), emptyMap(), lisbon, "en", now).removePrefix("\uFEFF").substringBefore(";"))
    }
}
