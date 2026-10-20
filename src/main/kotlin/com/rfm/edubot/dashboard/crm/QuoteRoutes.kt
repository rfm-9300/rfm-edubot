package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.QuoteInvoicing
import com.rfm.edubot.crm.QuoteServicing
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
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.timesheets.zone
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * `/crm/quotes`: quotes, their status and PDF, and billing one, by invoicing it (which needs invoices too) or by turning
 * it into a Serviços row (which needs services too).
 */
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
                        is QuoteInvoicing.Outcome.Refused -> call.respondQuoteRefused(outcome.reason)
                    }
                }
            }
            requireModules(DashboardModules.SERVICES) {
                post("/quotes/{id}/service") {
                    val crm = deps.crm(call)
                    val id = call.idParam("id")
                    val request = call.receive<QuoteServiceRequest>()
                    val performedAt = request.performedAt?.takeIf { it.isNotBlank() }?.let { dateValue(it, "performedAt") }
                        ?: SystemClock.now().toLocalDateTime(crm.tenant.zone()).date
                    when (val outcome = crm.quoteService.service(id, performedAt, request.name)) {
                        is QuoteServicing.Outcome.Created -> call.respond(HttpStatusCode.Created, outcome.service.dto(outcome.client))
                        is QuoteServicing.Outcome.Refused -> call.respondQuoteRefused(outcome.reason)
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
/** Without [performedAt] the work is dated today, in the company's timezone. */
@Serializable private data class QuoteServiceRequest(val performedAt: String? = null, val name: String? = null)

/** A quote that is gone, whose client is gone, or that is already billed ([QuoteInvoicing.billedBy]). */
private suspend fun ApplicationCall.respondQuoteRefused(reason: String) = when (reason) {
    QuoteInvoicing.ALREADY_INVOICED, QuoteInvoicing.ALREADY_SERVICED -> respond(HttpStatusCode.Conflict, mapOf("error" to reason))
    QuoteInvoicing.CLIENT_NOT_FOUND -> respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
    else -> respond(HttpStatusCode.NotFound)
}
