package com.rfm.edubot.dashboard

import com.rfm.edubot.events.ActivityFilter
import com.rfm.edubot.events.ActorType
import com.rfm.edubot.events.DomainEvent
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.idValue
import com.rfm.edubot.timesheets.zone
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `/activity`: who did what in the company, read from the domain event log. The whole log, filtered by person, area
 * and days, is the admins'; a record's history (its own events and the ones naming it, so a project's shows its
 * changes and measurements) is for anyone whose company has that record's module.
 */
internal object ActivityRoutes : DashboardFeature(DashboardModules.OVERVIEW) {
    override fun Route.routes(deps: DashboardDeps) {
        val events = DomainEventLog(deps.mongo)
        route("/activity") {
            get {
                val ctx = call.moduleContext()
                if (!ctx.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden, ErrorBody("not_allowed"))
                val q = call.request.queryParameters
                val zone = ctx.tenant.zone()
                val area = q["area"]?.takeIf { it.isNotBlank() }
                val types = area?.let { ActivityAreas.subjects[it] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("invalid_area", "area")) }
                val filter = ActivityFilter(
                    actorType = ActivityAreas.actorType(q["actor"]),
                    actorId = q["actor"]?.substringAfter("user:", "")?.takeIf { it.isNotBlank() },
                    subjectTypes = types.orEmpty(),
                    from = q["from"]?.takeIf { it.isNotBlank() }?.let { dateValue(it, "from").atStartOfDayIn(zone) },
                    until = q["to"]?.takeIf { it.isNotBlank() }?.let { dateValue(it, "to").plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone) },
                )
                val before = q["before"]?.takeIf { it.isNotBlank() }?.let { events.findById(ctx.tenant.id, idValue(it, "before")) }
                val limit = (q["limit"]?.toIntOrNull() ?: PAGE).coerceIn(1, PAGE_MAX)
                val page = events.activity(ctx.tenant.id, filter, before, limit + 1)
                val shown = page.take(limit)
                call.respond(ActivityPageDto(shown.map { it.activityDto() }, next = shown.lastOrNull()?.id?.toHexString()?.takeIf { page.size > limit }))
            }
            // The people the log can be filtered by: the company's team, admins and members, and its employees' sign-ins.
            get("/people") {
                val ctx = call.moduleContext()
                if (!ctx.isAdmin()) return@get call.respond(HttpStatusCode.Forbidden, ErrorBody("not_allowed"))
                val people = deps.dashboardUsers.listByTenant(ctx.tenant.primaryTenantId)
                    .filter { !it.isEmployee || it.employeeTenantId == ctx.tenant.id }
                    .map { ActivityPersonDto(it.id.toHexString(), it.email, it.role.name) }
                call.respond(people)
            }
            get("/records/{type}/{id}") {
                val ctx = call.moduleContext()
                val type = call.parameters["type"].orEmpty()
                val id = call.parameters["id"].orEmpty().takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return@get call.respond(HttpStatusCode.NotFound)
                val module = ActivityAreas.moduleOf[type] ?: return@get call.respond(HttpStatusCode.NotFound)
                if (!ctx.requireModule(module)) return@get call.respond(HttpStatusCode.Forbidden)
                val items = events.timeline(ctx.tenant.id, SubjectRef(type, id), limit = HISTORY)
                    .filter { it.type !in DomainEventTypes.inbound }
                call.respond(items.map { it.activityDto() })
            }
        }
    }

    private const val PAGE = 50
    private const val PAGE_MAX = 200
    private const val HISTORY = 100
    private const val MAX_ID = 64
}

/** Which records each area of the dashboard keeps, so the log filters by area and a record's history needs its module. */
internal object ActivityAreas {
    val subjects: Map<String, List<String>> = mapOf(
        DashboardModules.CLIENTS to listOf(SubjectTypes.CLIENT),
        DashboardModules.QUOTES to listOf(SubjectTypes.QUOTE),
        DashboardModules.INVOICES to listOf(SubjectTypes.INVOICE),
        DashboardModules.SERVICES to listOf(SubjectTypes.SERVICE),
        DashboardModules.PAYMENTS to listOf(SubjectTypes.PAYMENT),
        DashboardModules.SUPPLIERS to listOf(SubjectTypes.SUPPLIER),
        DashboardModules.EMPLOYEES to listOf(SubjectTypes.EMPLOYEE),
        DashboardModules.CATALOG to listOf(SubjectTypes.CATALOG_ITEM),
        DashboardModules.BOOKINGS to listOf(SubjectTypes.BOOKING),
        DashboardModules.CONVERSATIONS to listOf(SubjectTypes.CONTACT, SubjectTypes.CONVERSATION),
        DashboardModules.EMAIL to listOf(SubjectTypes.EMAIL),
        DashboardModules.INSTAGRAM to listOf(SubjectTypes.INSTAGRAM_COMMENT),
        DashboardModules.PROJECTS to listOf(SubjectTypes.PROJECT, SubjectTypes.SITE_PHOTO, SubjectTypes.REPORT),
        DashboardModules.SITE_DIARY to listOf(SubjectTypes.SITE_LOG),
        DashboardModules.SITE_ISSUES to listOf(SubjectTypes.SITE_ISSUE),
        DashboardModules.CHANGE_ORDERS to listOf(SubjectTypes.CHANGE_ORDER),
        DashboardModules.MEASUREMENTS to listOf(SubjectTypes.MEASUREMENT),
    )

    val moduleOf: Map<String, String> = subjects.flatMap { (module, types) -> types.map { it to module } }.toMap()

    /** `?actor=`: a kind of actor (`CLIENT`, `AGENT`…), or `user:<id>` for one of the team, who acts as a `USER`. */
    fun actorType(raw: String?): ActorType? = when {
        raw.isNullOrBlank() -> null
        raw.startsWith("user:") -> ActorType.USER
        else -> ActorType.entries.firstOrNull { it.name == raw.trim().uppercase() }
    }
}

@Serializable
internal data class ActivityPageDto(val items: List<ActivityItemDto>, val next: String?)

/**
 * One thing someone did: when, who ([actor]), what ([type] on the record [subjectType]/[subjectId], as [label] read when
 * it happened), the project it belongs to, and the few [facts] the sentence needs (a status it moved to, an amount…).
 */
@Serializable
internal data class ActivityItemDto(
    val id: String,
    val at: String,
    val type: String,
    val subjectType: String,
    val subjectId: String,
    val label: String?,
    val projectId: String?,
    val project: String?,
    val actor: ActivityActorDto,
    val facts: Map<String, String>,
)

@Serializable
internal data class ActivityActorDto(val type: String, val id: String?, val name: String?, val via: String?)

@Serializable
internal data class ActivityPersonDto(val id: String, val email: String, val role: String)

/** The payload keys an activity row may show; anything else on an event (phones, emails, ids) stays out. */
private val FACTS = setOf(
    "to", "from", "via", "decidedBy", "status", "action", "phase", "toPct", "totalCents", "netCents", "amountCents", "invoiceNumber", "field",
    "index", "installments", "code", "kind", "day", "periodEnd", "phases",
)

internal fun DomainEvent.activityDto(): ActivityItemDto {
    fun text(key: String) = (payload[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val label = when (subject.type) {
        SubjectTypes.MEASUREMENT -> text("number")
        SubjectTypes.SITE_LOG -> text("day")
        SubjectTypes.SITE_PHOTO -> text("caption") ?: text("day")
        SubjectTypes.REPORT -> listOfNotNull(text("from"), text("to")).joinToString(" – ").ifEmpty { null }
        SubjectTypes.BOOKING -> listOfNotNull(text("serviceName"), text("contactName")).joinToString(" · ").ifEmpty { null }
        else -> listOfNotNull(text("number"), text("title") ?: text("name") ?: text("serviceName") ?: text("displayName"))
            .distinct().joinToString(" · ").ifEmpty { null }
    }
    val project = listOfNotNull(text("projectNumber"), text("projectName")).joinToString(" · ").ifEmpty { null }
    return ActivityItemDto(
        id = id.toHexString(),
        at = occurredAt.toString(),
        type = type,
        subjectType = subject.type,
        subjectId = subject.id,
        label = label,
        projectId = text("projectId"),
        project = project,
        actor = ActivityActorDto(actor.type.name, actor.id, actor.name, actor.via),
        facts = payload.filterKeys { it in FACTS }.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap(),
    )
}
