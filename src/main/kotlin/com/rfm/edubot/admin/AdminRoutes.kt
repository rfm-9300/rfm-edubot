package com.rfm.edubot.admin

import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.StandardItem
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

fun Route.adminRoutes() {
    // The old tenant CRM at /admin is retired. Clients use /app; you configure
    // tenants in /backoffice. Shared CSS/JS/catalogs stay under /admin/{asset}.
    get("/admin") { call.respondRedirect("/backoffice/") }
    get("/admin/") { call.respondRedirect("/backoffice/") }
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
}

internal suspend fun io.ktor.server.application.ApplicationCall.respondPdf(path: String?) {
    if (path.isNullOrBlank()) return respond(HttpStatusCode.NotFound, mapOf("error" to "PDF not generated"))
    val pdf = Path.of(path)
    if (!Files.exists(pdf)) return respond(HttpStatusCode.NotFound, mapOf("error" to "PDF file missing"))
    respondBytes(Files.readAllBytes(pdf), ContentType.Application.Pdf)
}

internal fun savePdf(basePath: String, folder: String, filename: String, bytes: ByteArray): Path {
    val directory = Path.of(basePath, folder)
    Files.createDirectories(directory)
    val path = directory.resolve(filename)
    Files.write(path, bytes)
    return path
}

@Serializable
internal data class CreateClientRequest(
    val name: String,
    val phone: String,
    val address: String? = null,
)

@Serializable
internal data class CreateQuoteRequest(
    val clientId: String,
    val items: List<CreateLineItemRequest>,
    val notes: String? = null,
    val validUntil: String? = null,
)

@Serializable
internal data class CreateInvoiceRequest(
    val clientId: String,
    val quoteId: String? = null,
    val items: List<CreateLineItemRequest>,
    val dueDate: String,
)

@Serializable
internal data class CreateLineItemRequest(
    val description: String,
    val quantity: Double = 1.0,
    val unitPriceEur: Double,
) {
    fun toLineItem() = lineItem(description, quantity, unitPriceEur)
}

@Serializable
internal data class StandardItemRequest(
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
internal data class ClientDto(
    val id: String,
    val number: String,
    val name: String,
    val phone: String,
    val address: String? = null,
    val createdAt: String,
)

@Serializable
internal data class LineItemDto(
    val description: String,
    val quantity: Double,
    val unit: String,
    val unitPriceEur: Double,
)

@Serializable
internal data class QuoteDto(
    val id: String,
    val number: String,
    val clientId: String,
    val clientName: String,
    val status: String,
    val totalEur: Double,
    val validUntil: String?,
    val hasPdf: Boolean,
    val createdAt: String,
    val notes: String? = null,
    val items: List<LineItemDto> = emptyList(),
)

@Serializable
internal data class InvoiceDto(
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

internal fun Client.dto() = ClientDto(
    id = id.toHexString(),
    number = number,
    name = name,
    phone = phone,
    address = address,
    createdAt = createdAt.toString(),
)

internal fun Quote.dto(client: Client?) = QuoteDto(
    id = id.toHexString(),
    number = number,
    clientId = clientId.toHexString(),
    clientName = client?.name ?: "",
    status = status.name,
    totalEur = totalCents / 100.0,
    validUntil = validUntil?.toString(),
    hasPdf = pdfPath != null,
    createdAt = createdAt.toString(),
    notes = notes,
    items = items.map { LineItemDto(it.description, it.quantity, it.unit, it.unitPriceCents / 100.0) },
)

internal fun Invoice.dto(client: Client?) = InvoiceDto(
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
