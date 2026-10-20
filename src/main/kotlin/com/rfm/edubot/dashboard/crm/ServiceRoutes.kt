package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.ClientServiceDelete
import com.rfm.edubot.crm.InvoiceService
import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.api.CreateClientServiceRequest
import com.rfm.edubot.crm.api.InvoiceClientServicesRequest
import com.rfm.edubot.crm.api.UpdateClientServiceRequest
import com.rfm.edubot.crm.api.dto
import com.rfm.edubot.crm.api.dtos
import com.rfm.edubot.crm.api.serviceItemsError
import com.rfm.edubot.crm.eurToCents
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.requireModules
import com.rfm.edubot.http.idValue
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
import org.bson.types.ObjectId

/** `/crm/services`: Serviços, the work done for clients, and invoicing it (which needs invoices too). */
internal object ServiceRoutes : DashboardFeature(DashboardModules.SERVICES) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            get("/services") {
                val crm = deps.crm(call)
                val clientId = call.request.queryParameters["clientId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
                val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { runCatching { ClientServiceStatus.valueOf(it.uppercase()) }.getOrNull() }
                val quoteId = call.request.queryParameters["quoteId"]?.takeIf { it.isNotBlank() }?.let { idValue(it, "quoteId") }
                val rows = if (quoteId != null) crm.clientServices.findByQuoteId(quoteId) else crm.clientServices.list(clientId, status)
                call.respond(rows.dtos(crm.clients))
            }
            get("/services/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                val service = crm.clientServices.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(service.dto(crm.clients.findById(service.clientId)))
            }
            post("/services") {
                val crm = deps.crm(call)
                val request = call.receive<CreateClientServiceRequest>()
                val clientId = runCatching { ObjectId(request.clientId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client required"))
                val client = crm.clients.findById(clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                if (request.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name required"))
                serviceItemsError(request.items)?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val service = crm.clientServices.create(
                    clientId = clientId,
                    name = request.name,
                    notes = request.notes,
                    quantity = request.quantity,
                    unit = request.unit,
                    unitPriceCents = eurToCents(request.unitPriceEur),
                    bookingServiceId = request.bookingServiceId?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() },
                    catalogItemId = request.catalogItemId,
                    performedAt = request.performedAt?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    items = request.items.map { it.toLineItem() },
                )
                call.respond(HttpStatusCode.Created, service.dto(client))
            }
            requireModules(DashboardModules.INVOICES) {
                post("/services/invoice") {
                    val crm = deps.crm(call)
                    val request = call.receive<InvoiceClientServicesRequest>()
                    val clientId = runCatching { ObjectId(request.clientId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client required"))
                    val dueDate = runCatching { LocalDate.parse(request.dueDate) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "due date required"))
                    val ids = request.serviceIds.mapNotNull { runCatching { ObjectId(it) }.getOrNull() }
                    when (val outcome = crm.invoiceService.invoiceClientServices(clientId, ids, dueDate)) {
                        is InvoiceService.ServicesInvoiced.Refused -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to outcome.reason))
                        InvoiceService.ServicesInvoiced.ClientNotFound -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                        is InvoiceService.ServicesInvoiced.Invoiced -> call.respond(HttpStatusCode.Created, outcome.invoice.dto(outcome.client))
                    }
                }
            }
            patch("/services/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<UpdateClientServiceRequest>()
                serviceItemsError(request.items)?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val status = request.status?.takeIf { it.isNotBlank() }?.let { runCatching { ClientServiceStatus.valueOf(it.uppercase()) }.getOrNull() }
                if (status == ClientServiceStatus.OPEN) {
                    val existing = crm.clientServices.findById(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
                    val quote = existing.quoteId?.takeIf { existing.status == ClientServiceStatus.CANCELLED }?.let { crm.quotes.findById(it) }
                    if (quote != null && QuoteInvoicing.billedBy(quote, crm.invoices, crm.clientServices) != null) {
                        return@patch call.respond(HttpStatusCode.Conflict, mapOf("error" to "quote_billed"))
                    }
                }
                val service = crm.clientServices.update(
                    id = id,
                    name = request.name,
                    notes = request.notes,
                    quantity = request.quantity,
                    unit = request.unit,
                    unitPriceCents = request.unitPriceEur?.let { eurToCents(it) },
                    performedAt = request.performedAt?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    status = status,
                    items = request.items?.takeIf { it.isNotEmpty() }?.map { it.toLineItem() },
                ) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(service.dto(crm.clients.findById(service.clientId)))
            }
            delete("/services/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
                when (crm.clientServices.delete(id)) {
                    ClientServiceDelete.NotFound -> call.respond(HttpStatusCode.NotFound)
                    ClientServiceDelete.Invoiced -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "invoiced services cannot be deleted"))
                    ClientServiceDelete.Removed -> call.respond(mapOf("deleted" to true))
                }
            }
        }
    }
}
