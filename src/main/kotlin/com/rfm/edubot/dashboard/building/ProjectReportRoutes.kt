package com.rfm.edubot.dashboard.building

import com.rfm.edubot.building.Outcome
import com.rfm.edubot.building.ProjectReportService
import com.rfm.edubot.building.api.ReportDraftDto
import com.rfm.edubot.building.api.ReportDraftRequest
import com.rfm.edubot.building.api.ReportRequest
import com.rfm.edubot.building.api.dto
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.http.ErrorBody
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.idParam
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate

/**
 * `/building/projects/{id}/reports`: a project's updates for its client, which the AI drafts from a period's shared
 * diary days (502 `ai_unavailable` when the model doesn't answer) and the team posts to the client's page;
 * `/building/reports/{id}` edits or removes one.
 */
internal object ProjectReportRoutes : DashboardFeature(DashboardModules.PROJECTS) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/building") {
            get("/projects/{id}/reports") {
                val b = deps.building(call)
                val project = b.records.projects.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(b.records.reports.list(project.id).map { it.dto() })
            }
            post("/projects/{id}/reports/draft") {
                val b = deps.building(call)
                val request = call.receive<ReportDraftRequest>()
                val outcome = b.reports(deps.aiClient).draft(call.idParam("id"), day(request.from, "from"), day(request.to, "to"), b.tenant, b.stamp)
                if (outcome is Outcome.Refused && outcome.error == ProjectReportService.AI_UNAVAILABLE) {
                    return@post call.respond(HttpStatusCode.BadGateway, ErrorBody(outcome.error))
                }
                call.respondOutcome(outcome) { ReportDraftDto(it.from.toString(), it.to.toString(), it.text, it.sharedDays) }
            }
            post("/projects/{id}/reports") {
                val b = deps.building(call)
                val request = call.receive<ReportRequest>()
                val outcome = b.reports(deps.aiClient).post(call.idParam("id"), day(request.from, "from"), day(request.to, "to"), request.text, b.stamp)
                call.respondOutcome(outcome, HttpStatusCode.Created) { it.dto() }
            }
            patch("/reports/{id}") {
                val b = deps.building(call)
                val id = call.idParam("id")
                call.respondOutcome(b.reports(deps.aiClient).edit(id, call.receive<ReportRequest>().text, b.stamp)) { it.dto() }
            }
            delete("/reports/{id}") {
                val b = deps.building(call)
                call.respondOutcome(b.reports(deps.aiClient).delete(call.idParam("id"))) { DeletedDto(true) }
            }
        }
    }

    private fun day(raw: String?, field: String): LocalDate? = raw?.takeIf { it.isNotBlank() }?.let { dateValue(it.trim(), field) }
}
