package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.BuildingRecords
import com.rfm.edubot.building.PhotoStore
import com.rfm.edubot.building.ProjectProgress
import com.rfm.edubot.building.SiteDiaryService
import com.rfm.edubot.building.SiteIssueService
import com.rfm.edubot.building.SitePhotoService
import com.rfm.edubot.building.Stamp
import com.rfm.edubot.building.api.IssueRequest
import com.rfm.edubot.building.api.MySiteDetailDto
import com.rfm.edubot.building.api.MySitePhaseDto
import com.rfm.edubot.building.api.SiteLogRequest
import com.rfm.edubot.building.api.dto
import com.rfm.edubot.building.api.issueDtos
import com.rfm.edubot.building.api.logDtos
import com.rfm.edubot.building.api.mySiteDto
import com.rfm.edubot.building.model.IssueStatus
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.building.model.ProjectStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.EmployeePortal
import com.rfm.edubot.dashboard.PortalSession
import com.rfm.edubot.dashboard.portalSession
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.idParam
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.timesheets.zone
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import org.bson.types.ObjectId

/**
 * My sites, an employee's own page (`/app/api/portal/…`): the projects they run as site manager. They write and edit
 * the diary (with `site-diary`), add photos, and log issues and move them (with `site-issues`), through the team's
 * services, stamped with their name. No money anywhere, and a project someone else runs isn't found.
 */
internal fun Route.mySitesRoutes(mongo: MongoModule, storagePath: () -> String) {
    authenticate("dashboard") {
        route("/app/api/portal") {
            get("/sites") {
                val my = call.mySites(mongo, storagePath) ?: return@get
                val projects = my.running()
                val ids = projects.map { it.id }.toSet()
                val today = my.stamp.today
                val written = my.records.logs.list(null, today, today).filter { it.projectId in ids }.associateBy { it.projectId }
                val open = my.records.issues.forProjects(ids, OPEN_ISSUES).groupBy { it.projectId }
                call.respond(projects.map { it.mySiteDto(written[it.id]?.id, open[it.id]?.size ?: 0) })
            }
            get("/sites/{id}") {
                val my = call.mySites(mongo, storagePath) ?: return@get
                val project = my.project(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                val today = my.stamp.today
                val diary = if (my.canDiary) my.records.logs.list(project.id, today.minus(DIARY_DAYS - 1, DateTimeUnit.DAY), today) else emptyList()
                val issues = if (my.canIssues) my.records.issues.list(project.id, OPEN_ISSUES + IssueStatus.RESOLVED) else emptyList()
                call.respond(
                    MySiteDetailDto(
                        site = project.mySiteDto(diary.firstOrNull { it.day == today }?.id, issues.count { it.status in OPEN_ISSUES }),
                        today = today.toString(),
                        phases = project.phases.map { MySitePhaseDto(it.id, it.name, it.progressPct, ProjectProgress.phaseState(it, today).name) },
                        diary = my.records.logDtos(diary),
                        issues = my.records.issueDtos(issues, today),
                        photos = my.records.photos.list(project.id, limit = PHOTOS).map { it.dto() },
                        canDiary = my.canDiary,
                        canIssues = my.canIssues,
                    ),
                )
            }
            post("/sites/{id}/diary") {
                val my = call.mySites(mongo, storagePath)?.takeIf { call.allowed(it.canDiary) } ?: return@post
                val project = my.project(call.idParam("id")) ?: return@post call.respond(HttpStatusCode.NotFound)
                val request = call.receive<SiteLogRequest>()
                val day = request.day?.takeIf { it.isNotBlank() }?.let { dateValue(it, "day") }
                call.respondOutcome(SiteDiaryService(my.records).create(project.id, request.toDraft(day), my.stamp), HttpStatusCode.Created) {
                    my.records.logDtos(listOf(it)).single()
                }
            }
            patch("/sites/{id}/diary/{entryId}") {
                val my = call.mySites(mongo, storagePath)?.takeIf { call.allowed(it.canDiary) } ?: return@patch
                val project = my.project(call.idParam("id")) ?: return@patch call.respond(HttpStatusCode.NotFound)
                val entry = my.records.logs.findById(call.idParam("entryId"))?.takeIf { it.projectId == project.id } ?: return@patch call.respond(HttpStatusCode.NotFound)
                val request = call.receive<SiteLogRequest>()
                val day = request.day?.takeIf { it.isNotBlank() }?.let { dateValue(it, "day") }
                call.respondOutcome(SiteDiaryService(my.records).update(entry.id, request.toDraft(day), my.stamp)) { my.records.logDtos(listOf(it)).single() }
            }
            post("/sites/{id}/photos") {
                val my = call.mySites(mongo, storagePath) ?: return@post
                val project = my.project(call.idParam("id")) ?: return@post call.respond(HttpStatusCode.NotFound)
                val upload = call.receivePhoto() ?: return@post
                call.respondOutcome(SitePhotoService(my.records, my.store).add(project.id, upload, my.session.tenant.slug, my.stamp), HttpStatusCode.Created) { it.dto() }
            }
            get("/photos/{photoId}") {
                val my = call.mySites(mongo, storagePath) ?: return@get
                val photo = my.records.photos.findById(call.idParam("photoId"))?.takeIf { my.project(it.projectId) != null }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val bytes = SitePhotoService(my.records, my.store).bytes(photo, thumb = call.request.queryParameters["size"] == "thumb")
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                call.respondBytes(bytes, ContentType.Image.JPEG)
            }
            post("/sites/{id}/issues") {
                val my = call.mySites(mongo, storagePath)?.takeIf { call.allowed(it.canIssues) } ?: return@post
                val project = my.project(call.idParam("id")) ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respondOutcome(SiteIssueService(my.records).create(project.id, call.receive<IssueRequest>().toDraft(), my.stamp), HttpStatusCode.Created) {
                    my.records.issueDtos(listOf(it), my.stamp.today).single()
                }
            }
            patch("/sites/{id}/issues/{issueId}") {
                val my = call.mySites(mongo, storagePath)?.takeIf { call.allowed(it.canIssues) } ?: return@patch
                val project = my.project(call.idParam("id")) ?: return@patch call.respond(HttpStatusCode.NotFound)
                val issue = my.records.issues.findById(call.idParam("issueId"))?.takeIf { it.projectId == project.id } ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respondOutcome(SiteIssueService(my.records).update(issue.id, call.receive<IssueRequest>().toDraft(), my.stamp)) {
                    my.records.issueDtos(listOf(it), my.stamp.today).single()
                }
            }
        }
    }
}

/** One employee's My sites for one request, stamped with their name. */
private class MySites(mongo: MongoModule, val session: PortalSession, storagePath: () -> String) {
    val records = BuildingRecords(mongo, session.tenant.id)
    val stamp = Stamp(session.employee.name, SystemClock.now(), session.tenant.zone())
    val store = PhotoStore(storagePath)
    private val modules = DashboardModules.effectiveFor(session.tenant)
    val canDiary = DashboardModules.SITE_DIARY in modules
    val canIssues = DashboardModules.SITE_ISSUES in modules

    /** The projects they run that aren't finished or cancelled. */
    suspend fun running(): List<Project> = records.projects.list(RUNNING).filter { it.managerId == session.employee.id }

    /** Project [id] when they run it, whatever its status. */
    suspend fun project(id: ObjectId): Project? = records.projects.findById(id)?.takeIf { it.managerId == session.employee.id }
}

private suspend fun ApplicationCall.mySites(mongo: MongoModule, storagePath: () -> String): MySites? =
    portalSession(EmployeePortal.MY_SITES)?.let { MySites(mongo, it, storagePath) }

/** False after answering 403 `page_unavailable` when the company doesn't have the diary or the issues. */
private suspend fun ApplicationCall.allowed(available: Boolean): Boolean {
    if (!available) respond(HttpStatusCode.Forbidden, ErrorBody("page_unavailable"))
    return available
}

private val RUNNING = listOf(ProjectStatus.PLANNING, ProjectStatus.ACTIVE, ProjectStatus.ON_HOLD)
private val OPEN_ISSUES = listOf(IssueStatus.OPEN, IssueStatus.IN_PROGRESS)
private const val DIARY_DAYS = 14
private const val PHOTOS = 24
