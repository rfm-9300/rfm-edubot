package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.api.CreateSupplierRequest
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

/** `/crm/suppliers`: the vendors the company pays, with the services they usually do. */
internal object SupplierRoutes : DashboardFeature(DashboardModules.SUPPLIERS) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            get("/suppliers") {
                val crm = deps.crm(call)
                val archived = call.request.queryParameters["archived"] == "1"
                call.respond(crm.suppliers.search(call.request.queryParameters["q"].orEmpty(), archived).map { it.dto() })
            }
            get("/suppliers/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.respond(crm.suppliers.findById(id)?.dto() ?: return@get call.respond(HttpStatusCode.NotFound))
            }
            post("/suppliers") {
                val crm = deps.crm(call)
                val request = call.receive<CreateSupplierRequest>()
                if (request.name.isBlank() || request.phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
                request.detailsError()?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val supplier = call.uniquePhone {
                    crm.suppliers.create(request.name, request.phone, request.address, request.type, request.supplierServices().orEmpty(), request.contactPerson)
                } ?: return@post
                call.respond(HttpStatusCode.Created, supplier.value.dto())
            }
            patch("/suppliers/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<CreateSupplierRequest>()
                if (request.name.isBlank() || request.phone.isBlank()) return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
                request.detailsError()?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val supplier = (call.uniquePhone {
                    crm.suppliers.update(id, request.name, request.phone, request.address, request.type, request.supplierServices(), request.contactPerson)
                } ?: return@patch).value ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(supplier.dto())
            }
            removableDirectory("suppliers", deps, { suppliers.delete(it) }) { id, archived ->
                suppliers.setArchived(id, archived)?.let { ArchiveStateDto(it.archivedAt?.toString()) }
            }
        }
    }
}
