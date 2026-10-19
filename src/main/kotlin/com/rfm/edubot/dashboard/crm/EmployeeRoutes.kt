package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.DirectoryDelete
import com.rfm.edubot.crm.api.CreateEmployeeRequest
import com.rfm.edubot.crm.api.dto
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.bson.types.ObjectId

/** `/crm/employees`: the team's records. Their sign-ins and registered services are in `EmployeeWorkRoutes`. */
internal object EmployeeRoutes : DashboardFeature(DashboardModules.EMPLOYEES) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            get("/employees") {
                val crm = deps.crm(call)
                val archived = call.request.queryParameters["archived"] == "1"
                call.respond(crm.employees.search(call.request.queryParameters["q"].orEmpty(), archived).map { it.dto() })
            }
            get("/employees/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.respond(crm.employees.findById(id)?.dto() ?: return@get call.respond(HttpStatusCode.NotFound))
            }
            post("/employees") {
                val crm = deps.crm(call)
                val request = call.receive<CreateEmployeeRequest>()
                if (request.name.isBlank() || request.phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
                request.detailsError()?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val employee = call.uniquePhone {
                    crm.employees.create(request.name, request.phone, request.role, request.birthDate, request.address, request.taxId)
                } ?: return@post
                call.respond(HttpStatusCode.Created, employee.value.dto())
            }
            patch("/employees/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<CreateEmployeeRequest>()
                if (request.name.isBlank() || request.phone.isBlank()) return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
                request.detailsError()?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val employee = (call.uniquePhone {
                    crm.employees.update(id, request.name, request.phone, request.role, request.birthDate, request.address, request.taxId)
                } ?: return@patch).value ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(employee.dto())
            }
            // A deleted employee's sign-in goes too; an archived one's stays, refused until the record is restored.
            removableDirectory("employees", deps, { id ->
                employees.delete(id).also { if (it == DirectoryDelete.DELETED) deps.dashboardUsers.deleteByEmployee(id) }
            }) { id, archived ->
                employees.setArchived(id, archived)?.let { ArchiveStateDto(it.archivedAt?.toString()) }
            }
        }
    }
}
