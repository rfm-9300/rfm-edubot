package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.templates.AgentCopy
import com.rfm.edubot.building.ProjectRepository
import com.rfm.edubot.building.SiteIssueRepository
import com.rfm.edubot.building.model.IssuePriority
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.SiteIssue
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.ServiceDays
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.ClientService
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tags.RecordTagRepository
import com.rfm.edubot.tags.TagKind
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.timesheets.PlannedShiftRepository
import com.rfm.edubot.timesheets.ShiftMath
import com.rfm.edubot.timesheets.model.PlannedShift
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

/**
 * What a run about an employee knows (`AgentVariables.employee` and the groups after it): the person, and their
 * day from each module the company has. Each group is a count and one line of text that fits a WhatsApp template
 * parameter, which can't hold a line break or a tab and takes at most 1024 characters. A module the company doesn't
 * have gives an empty group, so a guard like `shifts.count > 0` simply sends nothing.
 *
 * - `shifts`: their planned shifts today (time clock), excused ones left out.
 * - `services`: the Serviços rows they do today, with the client and where.
 * - `issues`: the site issues assigned to them that are still open, overdue ones first (Building).
 */
class EmployeeBriefing(private val mongo: MongoModule) {
    suspend fun variables(tenant: Tenant, employee: Employee, now: Instant, zone: TimeZone, locale: String, formatter: ValueFormatter): Map<String, JsonElement> {
        val modules = DashboardModules.effectiveFor(tenant).toSet()
        val today = ShiftMath.localDay(now, zone)
        val tags = RecordTagRepository(mongo, tenant.id).forRecord(TagKind.EMPLOYEE, employee.id.toHexString())
        val plans = if (DashboardModules.TIMESHEETS in modules) PlannedShiftRepository(mongo, tenant.id).list(today, today, employee.id) else emptyList()
        val rows = if (DashboardModules.SERVICES in modules) {
            ClientServiceRepository(mongo, tenant.id).list(employeeId = employee.id, days = ServiceDays(today, today, zone))
                .filter { it.status != ClientServiceStatus.CANCELLED }
        } else {
            emptyList()
        }
        val clients = if (rows.isEmpty()) emptyMap() else ClientRepository(mongo, tenant.id).findByIds(rows.map { it.clientId }.distinct())
        val assigned = if (DashboardModules.SITE_ISSUES in modules) {
            SiteIssueRepository(mongo, tenant.id).forEmployee(employee.id, listOf(IssueStatus.OPEN, IssueStatus.IN_PROGRESS))
        } else {
            emptyList()
        }
        val projects = if (assigned.isEmpty()) emptyMap() else ProjectRepository(mongo, tenant.id).byIds(assigned.map { it.projectId }.distinct()).associate { it.id to it.name }
        return mapOf(
            "employee" to person(employee, tags),
            "shifts" to shifts(plans, zone, locale),
            "services" to services(rows, clients, locale),
            "issues" to issues(assigned, projects, today, locale, formatter),
        )
    }

    companion object {
        /** Room left under Meta's 1024 characters per template parameter for the "…and N more" ending. */
        const val MAX_LINE = 1000

        fun person(employee: Employee, tags: List<String>): JsonObject = buildJsonObject {
            put("id", employee.id.toHexString())
            put("name", employee.name)
            put("firstName", employee.name.trim().substringBefore(' '))
            put("phone", employee.phone)
            put("role", employee.role.orEmpty())
            // An array, so a condition like `employee.tags contains equipa-a` matches the whole tag.
            put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        }

        /** The day's planned shifts, in order, without the excused ones (the team already cancelled those). */
        fun shifts(plans: List<PlannedShift>, zone: TimeZone, locale: String): JsonObject {
            val active = plans.filter { it.excuse == null }.sortedBy { it.startAt }
            return buildJsonObject {
                put("count", active.size)
                put("today", oneLine(active.map { shiftLine(it, zone) }, locale))
                put("first", active.firstOrNull()?.let { ShiftMath.localTime(it.startAt, zone) }.orEmpty())
            }
        }

        /** `08:00-16:00 Site A`: the hours, then the site, else the project, client or activity of the job. */
        fun shiftLine(plan: PlannedShift, zone: TimeZone): String {
            val times = "${ShiftMath.localTime(plan.startAt, zone)}-${ShiftMath.localTime(plan.endAt, zone)}"
            val where = listOf(plan.siteName, plan.job?.projectName, plan.job?.clientName, plan.job?.activityName)
                .firstNotNullOfOrNull { it?.takeIf(String::isNotBlank) }
            return flatten(if (where == null) times else "$times $where")
        }

        /** The day's Serviços rows, in the order they were planned. */
        fun services(rows: List<ClientService>, clients: Map<ObjectId, Client>, locale: String): JsonObject {
            val ordered = rows.sortedBy { it.createdAt }
            return buildJsonObject {
                put("count", ordered.size)
                put("today", oneLine(ordered.map { serviceLine(it, clients[it.clientId]) }, locale))
            }
        }

        /** `Deep clean · Ana Ribeiro, Rua das Flores 12, Lisboa`: the work, who it's for and where. */
        fun serviceLine(row: ClientService, client: Client?): String {
            val place = client?.let { listOf(it.name, it.address, it.city).filterNot { part -> part.isNullOrBlank() }.joinToString(", ") }
            return flatten(if (place.isNullOrBlank()) row.name else "${row.name} · $place")
        }

        /** Open issues: overdue first, then by due date (none last) and priority. */
        fun issues(list: List<SiteIssue>, projects: Map<ObjectId, String>, today: LocalDate, locale: String, formatter: ValueFormatter): JsonObject {
            val open = list.filter { it.isOpen }.sortedWith(
                compareBy<SiteIssue>({ !it.isOverdue(today) }, { it.dueDate == null }, { it.dueDate }, { priorityRank(it.priority) }, { it.number }),
            )
            return buildJsonObject {
                put("count", open.size)
                put("overdue", open.count { it.isOverdue(today) })
                put("list", oneLine(open.map { issueLine(it, projects[it.projectId], locale, formatter) }, locale))
            }
        }

        /** `OC-012 Leak in the bathroom (Casa Rocha, due 26/11/2026)`. */
        fun issueLine(issue: SiteIssue, project: String?, locale: String, formatter: ValueFormatter): String {
            val due = issue.dueDate?.let { AgentCopy.t(locale, "summary.line.due", "date" to (formatter.formatDate(it.toString()) ?: it.toString())) }
            val about = listOfNotNull(project?.takeIf { it.isNotBlank() }, due).joinToString(", ")
            return flatten(if (about.isEmpty()) "${issue.number} ${issue.title}" else "${issue.number} ${issue.title} ($about)")
        }

        /**
         * [parts] joined with `; ` into at most [MAX_LINE] characters: whole parts only, then "…and N more" for the
         * rest, so a long day never breaks the template's limit mid-word.
         */
        fun oneLine(parts: List<String>, locale: String): String {
            val kept = mutableListOf<String>()
            var length = 0
            for (part in parts) {
                val added = if (kept.isEmpty()) part.length else part.length + 2
                if (length + added > MAX_LINE) break
                kept += part
                length += added
            }
            val rest = parts.size - kept.size
            if (rest == 0) return kept.joinToString("; ")
            val more = AgentCopy.t(locale, "summary.more", "count" to rest.toString())
            return (kept.joinToString("; ") + " " + more).trim()
        }

        private fun priorityRank(priority: IssuePriority): Int = when (priority) {
            IssuePriority.HIGH -> 0
            IssuePriority.MEDIUM -> 1
            IssuePriority.LOW -> 2
        }

        /** Meta rejects line breaks, tabs and runs of four spaces in a template parameter. */
        private fun flatten(text: String) = text.replace(Regex("\\s+"), " ").trim()
    }
}
