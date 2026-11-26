package com.rfm.edubot.agents.runtime

import com.rfm.edubot.building.model.IssuePriority
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientService
import com.rfm.edubot.timesheets.model.Excuse
import com.rfm.edubot.timesheets.model.Job
import com.rfm.edubot.timesheets.model.PlannedShift
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How an employee's day reads in their message: one line per group, as a WhatsApp template parameter takes it. */
class EmployeeBriefingTest {
    private val lisbon = TimeZone.of("Europe/Lisbon")
    private val tenantId = ObjectId()
    private val employeeId = ObjectId()
    private val at = Instant.parse("2026-10-01T10:00:00Z")
    private val today = LocalDate(2026, 10, 2)
    private val formatter = ValueFormatter("en", lisbon)

    private fun plan(start: String, end: String, site: String? = null, job: Job? = null, excuse: Excuse? = null) = PlannedShift(
        tenantId = tenantId, employeeId = employeeId, day = today,
        startAt = Instant.parse(start), endAt = Instant.parse(end), siteName = site, job = job, excuse = excuse,
        createdBy = "test", createdAt = at, updatedAt = at,
    )

    private fun service(name: String, client: Client?, createdAt: String = "2026-10-01T10:00:00Z") = ClientService(
        tenantId = tenantId, clientId = client?.id ?: ObjectId(), name = name, unitPriceCents = 5000, totalCents = 5000,
        performedAt = today, employeeId = employeeId, createdAt = Instant.parse(createdAt), updatedAt = Instant.parse(createdAt),
    )

    private fun client(name: String, address: String? = null, city: String? = null) = Client(
        tenantId = tenantId, number = "CL-001", name = name, phone = "+351 912 000 000", address = address, city = city, createdAt = at, updatedAt = at,
    )

    private fun issue(number: String, title: String, due: LocalDate? = null, status: IssueStatus = IssueStatus.OPEN, priority: IssuePriority = IssuePriority.MEDIUM, project: ObjectId = ObjectId()) = SiteIssue(
        tenantId = tenantId, projectId = project, number = number, title = title, status = status, priority = priority, employeeId = employeeId,
        dueDate = due, createdBy = "test", createdAt = at, updatedAt = at,
    )

    @Test
    fun `shifts read in the company's time zone, in order, on one line`() {
        val afternoon = plan("2026-10-02T17:00:00Z", "2026-10-02T21:00:00Z", site = "Obra B")
        val morning = plan("2026-10-02T07:00:00Z", "2026-10-02T15:00:00Z", site = "Obra A")

        val shifts = EmployeeBriefing.shifts(listOf(afternoon, morning), lisbon, "en")

        assertEquals("08:00-16:00 Obra A; 18:00-22:00 Obra B", shifts["today"]!!.jsonPrimitive.content)
        assertEquals(2, shifts["count"]!!.jsonPrimitive.int)
        assertEquals("08:00", shifts["first"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an excused shift is left out, and a day with none counts zero`() {
        val excused = plan("2026-10-02T07:00:00Z", "2026-10-02T15:00:00Z", excuse = Excuse("sick", "ana@example.test", at))

        val shifts = EmployeeBriefing.shifts(listOf(excused), lisbon, "en")

        assertEquals(0, shifts["count"]!!.jsonPrimitive.int)
        assertEquals("", shifts["today"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a shift without a place shows only its hours, and a name never breaks the line`() {
        val bare = EmployeeBriefing.shiftLine(plan("2026-10-02T07:00:00Z", "2026-10-02T15:00:00Z"), lisbon)
        val messy = EmployeeBriefing.shiftLine(plan("2026-10-02T07:00:00Z", "2026-10-02T15:00:00Z", site = "Obra\n  Norte\tFase 2"), lisbon)
        val byJob = EmployeeBriefing.shiftLine(plan("2026-10-02T07:00:00Z", "2026-10-02T15:00:00Z", job = Job(clientName = "Ana Lda", projectName = "Casa Rocha")), lisbon)

        assertEquals("08:00-16:00", bare)
        assertEquals("08:00-16:00 Obra Norte Fase 2", messy)
        assertFalse(messy.contains('\n') || messy.contains('\t'))
        assertEquals("08:00-16:00 Casa Rocha", byJob, "without a site the project, then the client, names the place")
    }

    @Test
    fun `services say the work, who it's for and where, in the order they were planned`() {
        val ana = client("Ana Ribeiro", address = "Rua das Flores 12", city = "Lisboa")
        val rui = client("Rui Costa")
        val later = service("Window cleaning", rui, createdAt = "2026-10-01T12:00:00Z")
        val first = service("Deep clean", ana, createdAt = "2026-10-01T09:00:00Z")

        val services = EmployeeBriefing.services(listOf(later, first), mapOf(ana.id to ana, rui.id to rui), "en")

        assertEquals(2, services["count"]!!.jsonPrimitive.int)
        assertEquals("Deep clean · Ana Ribeiro, Rua das Flores 12, Lisboa; Window cleaning · Rui Costa", services["today"]!!.jsonPrimitive.content)
    }

    @Test
    fun `open issues come overdue first, then by due date, with their site`() {
        val site = ObjectId()
        val issues = listOf(
            issue("OC-003", "Paint the hall", due = LocalDate(2026, 10, 9), project = site),
            issue("OC-001", "Leak in the bathroom", due = LocalDate(2026, 9, 28), project = site),
            issue("OC-004", "Fix the gate", status = IssueStatus.RESOLVED, project = site),
            issue("OC-002", "Order tiles", priority = IssuePriority.HIGH, project = site),
        )

        val group = EmployeeBriefing.issues(issues, mapOf(site to "Casa Rocha"), today, "en", formatter)

        assertEquals(3, group["count"]!!.jsonPrimitive.int, "a resolved issue isn't open")
        assertEquals(1, group["overdue"]!!.jsonPrimitive.int)
        val list = group["list"]!!.jsonPrimitive.content
        assertTrue(list.startsWith("OC-001 Leak in the bathroom (Casa Rocha, due "), list)
        assertTrue(list.indexOf("OC-003") < list.indexOf("OC-002"), "one with a due date comes before one without: $list")
    }

    @Test
    fun `a long day stops at whole items and says how many more, under the template's limit`() {
        val parts = (1..60).map { "Part $it of a long day with a long description" }

        val line = EmployeeBriefing.oneLine(parts, "en")

        assertTrue(line.length <= 1024, "${line.length} characters")
        assertTrue(line.endsWith("more"), line)
        assertFalse(line.contains("Part 60"), "whole items only, the rest counted")
        assertEquals(parts.take(3).joinToString("; "), EmployeeBriefing.oneLine(parts.take(3), "en"), "a short day is all there")
    }
}
