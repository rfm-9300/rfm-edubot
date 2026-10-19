package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.ClientFields
import com.rfm.edubot.crm.api.CreateClientRequest
import com.rfm.edubot.crm.api.respondClientWrite
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

/** `/crm/clients`: the Clients directory, its own fields, and removing a client. */
internal object ClientRoutes : DashboardFeature(DashboardModules.CLIENTS) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") { clientRoutes(deps) }
    }

    private fun Route.clientRoutes(deps: DashboardDeps) {
        get("/clients") {
            val crm = deps.crm(call)
            val q = call.request.queryParameters["q"].orEmpty()
            val archived = call.request.queryParameters["archived"] == "1"
            val customKeys = ClientFields.searchable(ClientFields.of(crm.tenant))
            // The Clients page, and the client pickers in the quote/invoice forms, list the whole directory.
            call.respond(crm.clients.search(q, limit = if (q.isBlank()) CLIENT_DIRECTORY_LIMIT else 50, archived = archived, customKeys = customKeys).map { it.dto() })
        }
        get("/clients/by-phone") {
            val crm = deps.crm(call)
            val phone = call.request.queryParameters["phone"].orEmpty()
            val client = crm.clients.findByPhone(phone) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "client_not_found"))
            call.respond(client.dto())
        }
        post("/clients") {
            val crm = deps.crm(call)
            val request = call.receive<CreateClientRequest>()
            call.respondClientWrite(crm.clientDirectory.create(request.toDraft()), HttpStatusCode.Created)
        }
        patch("/clients/{id}") {
            val crm = deps.crm(call)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<CreateClientRequest>()
            call.respondClientWrite(crm.clientDirectory.update(id, request.toDraft()), HttpStatusCode.OK)
        }
        clientFieldRoutes(deps.tenants)
        get("/clients/{id}") {
            val crm = deps.crm(call)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(crm.clients.findById(id)?.dto() ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        removableDirectory("clients", deps, { clients.delete(it) }) { id, archived ->
            clients.setArchived(id, archived)?.let { ArchiveStateDto(it.archivedAt?.toString()) }
        }
    }
}

private const val CLIENT_DIRECTORY_LIMIT = 2000
