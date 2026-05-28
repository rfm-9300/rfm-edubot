package com.rfm.edubot.admin

import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.crm.StandardItem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.Path

fun Route.adminRoutes(
    clients: ClientRepository,
    quotes: QuoteRepository,
    invoices: InvoiceRepository,
    standardItems: StandardItemRepository,
    pdfGenerator: PdfGenerator,
    pdfStoragePath: String,
) {
    get("/admin") { call.respondRedirect("/admin/") }
    get("/admin/") {
        val html = this::class.java.classLoader.getResource("admin/index.html")?.readText()
        call.respondText(html ?: "Admin UI missing", ContentType.Text.Html)
    }
    get("/admin/{asset}") {
        val asset = call.parameters["asset"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val contentType = when (asset.substringAfterLast('.', "")) {
            "js" -> ContentType.Application.JavaScript
            "css" -> ContentType.Text.CSS
            else -> ContentType.Application.OctetStream
        }
        val bytes = this::class.java.classLoader.getResource("admin/$asset")?.readBytes()
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, contentType)
    }

    route("/admin/api") {
        get("/clients") { call.respond(clients.search(call.request.queryParameters["q"].orEmpty()).map { it.dto() }) }
        get("/standard-items") {
            call.respond(
                standardItems.search(
                    query = call.request.queryParameters["q"],
                    type = call.request.queryParameters["type"],
                )
            )
        }
        post("/standard-items") {
            val request = call.receive<StandardItemRequest>()
            val item = standardItems.create(request.toStandardItem(request.id))
            call.respond(HttpStatusCode.Created, item)
        }
        post("/standard-items/{id}") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<StandardItemRequest>()
            val item = standardItems.update(id, request.toStandardItem(id)) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(item)
        }
        delete("/standard-items/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (!standardItems.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("deleted" to true))
        }
        get("/clients/{id}") {
            val client = clients.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(client.dto())
        }
        post("/clients") {
            val request = call.receive<CreateClientRequest>()
            if (request.name.isBlank() || request.phone.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
            }
            val client = clients.create(request.name, request.phone, request.address)
            call.respond(HttpStatusCode.Created, client.dto())
        }
        get("/quotes") {
            val result = quotes.list(
                clientId = call.request.queryParameters["clientId"]?.let { ObjectId(it) },
                status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { QuoteStatus.valueOf(it.uppercase()) },
            )
            call.respond(result.map { it.dto(clients.findById(it.clientId)) })
        }
        post("/quotes") {
            val request = call.receive<CreateQuoteRequest>()
            if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
            val quote = quotes.create(
                clientId = ObjectId(request.clientId),
                items = request.items.map { it.toLineItem() },
                notes = request.notes,
                validUntil = request.validUntil?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
            )
            val client = clients.findById(quote.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
            val path = savePdf(pdfStoragePath, "quotes", "Orcamento ${quote.number}.pdf", pdfGenerator.generateQuote(quote, client))
            quotes.setPdfPath(quote.id, path.toString())
            call.respond(HttpStatusCode.Created, quote.copy(pdfPath = path.toString()).dto(client))
        }
        get("/quotes/{id}") {
            val quote = quotes.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(quote.dto(clients.findById(quote.clientId)))
        }
        get("/quotes/{id}/pdf") {
            val quote = quotes.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondPdf(quote.pdfPath)
        }
        get("/invoices") {
            val result = invoices.list(
                clientId = call.request.queryParameters["clientId"]?.let { ObjectId(it) },
                status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { InvoiceStatus.valueOf(it.uppercase()) },
            )
            call.respond(result.map { it.dto(clients.findById(it.clientId)) })
        }
        post("/invoices") {
            val request = call.receive<CreateInvoiceRequest>()
            if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
            val invoice = invoices.create(
                clientId = ObjectId(request.clientId),
                quoteId = request.quoteId?.takeIf { it.isNotBlank() }?.let { ObjectId(it) },
                items = request.items.map { it.toLineItem() },
                dueDate = LocalDate.parse(request.dueDate),
            )
            val client = clients.findById(invoice.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
            val path = savePdf(pdfStoragePath, "invoices", "Fatura ${invoice.number}.pdf", pdfGenerator.generateInvoice(invoice, client))
            invoices.setPdfPath(invoice.id, path.toString())
            call.respond(HttpStatusCode.Created, invoice.copy(pdfPath = path.toString()).dto(client))
        }
        get("/invoices/{id}") {
            val invoice = invoices.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(invoice.dto(clients.findById(invoice.clientId)))
        }
        get("/invoices/{id}/pdf") {
            val invoice = invoices.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondPdf(invoice.pdfPath)
        }
        patch("/invoices/{id}/paid") {
            val invoice = invoices.markPaid(ObjectId(call.parameters["id"])) ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(invoice.dto(clients.findById(invoice.clientId)))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondPdf(path: String?) {
    if (path.isNullOrBlank()) return respond(HttpStatusCode.NotFound, mapOf("error" to "PDF not generated"))
    val pdf = Path.of(path)
    if (!Files.exists(pdf)) return respond(HttpStatusCode.NotFound, mapOf("error" to "PDF file missing"))
    respondBytes(Files.readAllBytes(pdf), ContentType.Application.Pdf)
}

private fun savePdf(basePath: String, folder: String, filename: String, bytes: ByteArray): Path {
    val directory = Path.of(basePath, folder)
    Files.createDirectories(directory)
    val path = directory.resolve(filename)
    Files.write(path, bytes)
    return path
}

@Serializable
private data class CreateClientRequest(
    val name: String,
    val phone: String,
    val address: String? = null,
)

@Serializable
private data class CreateQuoteRequest(
    val clientId: String,
    val items: List<CreateLineItemRequest>,
    val notes: String? = null,
    val validUntil: String? = null,
)

@Serializable
private data class CreateInvoiceRequest(
    val clientId: String,
    val quoteId: String? = null,
    val items: List<CreateLineItemRequest>,
    val dueDate: String,
)

@Serializable
private data class CreateLineItemRequest(
    val description: String,
    val quantity: Double = 1.0,
    val unitPriceEur: Double,
) {
    fun toLineItem() = lineItem(description, quantity, unitPriceEur)
}

@Serializable
private data class StandardItemRequest(
    val id: String,
    val type: String,
    val category: String,
    val description: String,
    val unit: String,
    val defaultUnitPriceEur: Double,
) {
    fun toStandardItem(itemId: String) = StandardItem(
        id = itemId.trim(),
        type = type.trim().lowercase(),
        category = category.trim(),
        description = description.trim(),
        unit = unit.trim(),
        defaultUnitPriceEur = defaultUnitPriceEur,
    )
}

@Serializable
private data class ClientDto(
    val id: String,
    val number: String,
    val name: String,
    val phone: String,
    val address: String? = null,
    val createdAt: String,
)

@Serializable
private data class QuoteDto(
    val id: String,
    val number: String,
    val clientId: String,
    val clientName: String,
    val status: String,
    val totalEur: Double,
    val validUntil: String?,
    val hasPdf: Boolean,
    val createdAt: String,
)

@Serializable
private data class InvoiceDto(
    val id: String,
    val number: String,
    val clientId: String,
    val clientName: String,
    val status: String,
    val dueDate: String,
    val totalEur: Double,
    val hasPdf: Boolean,
    val createdAt: String,
)

private fun Client.dto() = ClientDto(
    id = id.toHexString(),
    number = number,
    name = name,
    phone = phone,
    address = address,
    createdAt = createdAt.toString(),
)

private fun Quote.dto(client: Client?) = QuoteDto(
    id = id.toHexString(),
    number = number,
    clientId = clientId.toHexString(),
    clientName = client?.name ?: "",
    status = status.name,
    totalEur = totalCents / 100.0,
    validUntil = validUntil?.toString(),
    hasPdf = pdfPath != null,
    createdAt = createdAt.toString(),
)

private fun Invoice.dto(client: Client?) = InvoiceDto(
    id = id.toHexString(),
    number = number,
    clientId = clientId.toHexString(),
    clientName = client?.name ?: "",
    status = status.name,
    dueDate = dueDate.toString(),
    totalEur = totalCents / 100.0,
    hasPdf = pdfPath != null,
    createdAt = createdAt.toString(),
)
