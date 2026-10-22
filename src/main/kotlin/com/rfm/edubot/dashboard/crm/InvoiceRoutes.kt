package com.rfm.edubot.dashboard.crm

import com.rfm.edubot.crm.InvoiceChange
import com.rfm.edubot.crm.api.CreateInvoiceRequest
import com.rfm.edubot.crm.api.InvoiceInstallmentsRequest
import com.rfm.edubot.crm.api.InvoiceTaxOfficeCodeRequest
import com.rfm.edubot.crm.api.dto
import com.rfm.edubot.crm.api.dtos
import com.rfm.edubot.crm.api.respondGeneratedPdf
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.enumValue
import com.rfm.edubot.http.idParam
import com.rfm.edubot.http.idValue
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.bson.types.ObjectId

/** `/crm/invoices`: invoices, their tax office code, installments, payment, cancelling, deleting and PDF. */
internal object InvoiceRoutes : DashboardFeature(DashboardModules.INVOICES) {
    override fun Route.routes(deps: DashboardDeps) {
        route("/crm") {
            get("/invoices") {
                val crm = deps.crm(call)
                val clientId = call.request.queryParameters["clientId"]?.let { idValue(it, "clientId") }
                val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { enumValue<InvoiceStatus>(it.uppercase(), "status") }
                call.respond(crm.invoices.list(clientId, status).dtos(crm.clients))
            }
            post("/invoices") {
                val crm = deps.crm(call)
                val request = call.receive<CreateInvoiceRequest>()
                if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
                request.detailsError()?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val invoice = crm.invoiceService.create(
                    idValue(request.clientId, "clientId"),
                    request.quoteId?.takeIf { it.isNotBlank() }?.let { idValue(it, "quoteId") },
                    request.items.map { it.toLineItem() },
                    dateValue(request.dueDate, "dueDate"),
                    request.taxOfficeCode,
                )
                val client = crm.clients.findById(invoice.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                call.respond(HttpStatusCode.Created, invoice.dto(client))
            }
            get("/invoices/{id}") {
                val crm = deps.crm(call)
                val invoice = crm.invoices.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(crm.invoiceDto(invoice))
            }
            patch("/invoices/{id}/tax-office-code") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<InvoiceTaxOfficeCodeRequest>()
                request.error()?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
                val invoice = crm.invoiceService.setTaxOfficeCode(id, request.taxOfficeCode) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(crm.invoiceDto(invoice))
            }
            put("/invoices/{id}/installments") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
                val parts = call.receive<InvoiceInstallmentsRequest>().parts()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "installment_invalid"))
                call.respondInvoiceChange(crm, crm.invoiceService.setInstallments(id, parts))
            }
            patch("/invoices/{id}/installments/{index}/paid") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val index = call.parameters["index"]?.toIntOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                call.respondInvoiceChange(crm, crm.invoiceService.receiveInstallment(id, index))
            }
            // Cancelling and deleting both free the Serviços rows or the measurement the invoice billed, to be invoiced again.
            post("/invoices/{id}/cancel") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                call.respondInvoiceChange(crm, crm.invoiceService.cancel(id))
            }
            delete("/invoices/{id}") {
                val crm = deps.crm(call)
                val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
                crm.invoiceService.delete(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("deleted" to true))
            }
            get("/invoices/{id}/pdf") {
                val crm = deps.crm(call)
                val invoice = crm.invoices.findById(call.idParam("id")) ?: return@get call.respond(HttpStatusCode.NotFound)
                val client = crm.clients.findById(invoice.clientId) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondGeneratedPdf { crm.pdfGenerator.generateInvoice(invoice, client, crm.documentTemplate) }
            }
            patch("/invoices/{id}/paid") {
                val crm = deps.crm(call)
                val invoice = crm.invoiceService.markPaid(call.idParam("id")) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(invoice.dto(crm.clients.findById(invoice.clientId)))
            }
        }
    }
}

private suspend fun TenantCrm.invoiceDto(invoice: Invoice) = invoice.dto(
    clients.findById(invoice.clientId),
    invoice.quoteId?.let { quotes.findById(it)?.number },
    projectLabel = invoice.projectId?.let { projects.findById(it) }?.let { "${it.number} · ${it.name}" },
    measurementNumber = invoice.measurementId?.let { measurements.findById(it)?.number },
)

/** The invoice after a change, or why it was refused: 409 when its state doesn't allow it, 400 for a bad request. */
private suspend fun ApplicationCall.respondInvoiceChange(crm: TenantCrm, change: InvoiceChange) = when (change) {
    InvoiceChange.NotFound -> respond(HttpStatusCode.NotFound)
    is InvoiceChange.Refused -> respond(
        if (change.reason in INVOICE_STATE_CONFLICTS) HttpStatusCode.Conflict else HttpStatusCode.BadRequest,
        mapOf("error" to change.reason),
    )
    is InvoiceChange.Done -> respond(crm.invoiceDto(change.invoice))
}

private val INVOICE_STATE_CONFLICTS = setOf("invoice_closed", "invoice_paid", "invoice_cancelled", "installments_paid", "installment_paid", "conflict")
