package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.api.CreateQuoteRequest
import com.rfm.edubot.crm.api.dto
import com.rfm.edubot.crm.api.dtos
import com.rfm.edubot.crm.api.respondGeneratedPdf
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.requireModules
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.enumValue
import com.rfm.edubot.http.idParam
import com.rfm.edubot.http.idValue
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** `/crm/quotes`: quotes, their status and PDF, and invoicing one (which needs invoices too). */
internal object QuoteRoutes : DashboardFeature(DashboardModules.QUOTES) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            get("/quotes") {
                val crm = deps.crm(call)
                val clientId = call.request.queryParameters["clientId"]?.let { idValue(it, "clientId") }
                val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { enumValue<QuoteStatus>(it.uppercase(), "status") }
                call.respond(crm.quotes.list(clientId, status).dtos(crm.clients))
            }
            post("/quotes") {
                val crm = deps.crm(call)
                val request = call.receive<CreateQuoteRequest>()
                if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
                val quote = crm.quoteService.create(
                    idValue(request.clientId, "clientId"),
                    request.items.map { it.toLineItem() },
                    request.notes,
                    request.validUntil?.takeIf { it.isNotBlank() }?.let { dateValue(it, "validUntil") },
                )
                val client = crm.clients.findById(quote.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                call.respond(HttpStatusCode.Created, quote.dto(client))
            }
            get("/quotes/{id}") {
                val crm = deps.crm(call)
                val quote = crm.quotes.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(quote.dto(crm.clients.findById(quote.clientId)))
            }
            patch("/quotes/{id}") {
                val crm = deps.crm(call)
                val request = call.receive<QuoteStatusRequest>()
                val status = runCatching { QuoteStatus.valueOf(request.status.uppercase()) }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid status"))
                val quote = crm.quoteService.setStatus(call.idParam("id"), status) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(quote.dto(crm.clients.findById(quote.clientId)))
            }
            requireModules(DashboardModules.INVOICES) {
                post("/quotes/{id}/invoice") {
                    val crm = deps.crm(call)
                    val quote = crm.quotes.findById(call.idParam("id")) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val request = call.receive<ConvertQuoteRequest>()
                    val dueDate = runCatching { LocalDate.parse(request.dueDate) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "due date required"))
                    when (val outcome = crm.quoteService.invoice(quote.id, dueDate)) {
                        is QuoteInvoicing.Outcome.Invoiced -> call.respond(HttpStatusCode.Created, outcome.invoice.dto(outcome.client))
                        is QuoteInvoicing.Outcome.Refused -> when (outcome.reason) {
                            QuoteInvoicing.ALREADY_INVOICED -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_invoiced"))
                            QuoteInvoicing.CLIENT_NOT_FOUND -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                            else -> call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
            }
            get("/quotes/{id}/pdf") {
                val crm = deps.crm(call)
                val quote = crm.quotes.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                val client = crm.clients.findById(quote.clientId) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondGeneratedPdf { crm.pdfGenerator.generateQuote(quote, client, crm.documentTemplate) }
            }
        }
    }
}

@Serializable private data class QuoteStatusRequest(val status: String)
@Serializable private data class ConvertQuoteRequest(val dueDate: String)
