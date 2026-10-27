package com.rfm.edubot.crm.api

import com.mongodb.ErrorCategory
import com.mongodb.MongoServerException
import com.rfm.edubot.crm.ClientDirectory
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.StandardItemRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes

/** Always builds a fresh PDF from the current document + template. Never reads or writes a saved file. */
internal suspend fun ApplicationCall.respondGeneratedPdf(
    generate: () -> ByteArray,
) {
    respondBytes(generate(), ContentType.Application.Pdf)
}

/** `POST …/standard-items` for the dashboard and the backoffice: 201 with the saved item, or 400/409 with a stable error code. */
internal suspend fun ApplicationCall.createStandardItem(items: StandardItemRepository) {
    val request = receive<StandardItemRequest>()
    request.error()?.let { return respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
    val requestedId = request.id?.trim()?.takeIf { it.isNotBlank() }
    if (requestedId != null && items.findById(requestedId) != null) return respond(HttpStatusCode.Conflict, mapOf("error" to "id_taken"))
    val draft = request.toStandardItem(requestedId.orEmpty())
    val item = if (requestedId != null) draft else draft.copy(id = items.freeId(draft.title, draft.type))
    respondCatalogWrite(HttpStatusCode.Created) { items.create(item) }
}

/** `POST …/standard-items/{id}`: the internal id never changes, the code may. */
internal suspend fun ApplicationCall.updateStandardItem(items: StandardItemRepository, id: String) {
    val existing = items.findById(id) ?: return respond(HttpStatusCode.NotFound)
    val request = receive<StandardItemRequest>()
    request.error()?.let { return respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
    respondCatalogWrite(HttpStatusCode.OK) { items.update(id, request.toStandardItem(id, existing)) }
}

/** Ids are checked or generated free before writing, so a duplicate key here is another item's code. */
private suspend fun ApplicationCall.respondCatalogWrite(status: HttpStatusCode, write: suspend () -> StandardItem?) {
    val saved = try {
        write()
    } catch (e: MongoServerException) {
        if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
        return respond(HttpStatusCode.Conflict, mapOf("error" to "code_taken"))
    }
    if (saved == null) respond(HttpStatusCode.NotFound) else respond(status, saved)
}

/**
 * A client write as the client form reads it: the saved client, 400 with the error code (and, for one of the
 * company's own fields, its key as `detail`) or 404.
 */
internal suspend fun ApplicationCall.respondClientWrite(outcome: ClientDirectory.Outcome, saved: HttpStatusCode) = when (outcome) {
    is ClientDirectory.Outcome.Saved -> respond(saved, outcome.client.dto())
    is ClientDirectory.Outcome.Invalid -> respond(
        HttpStatusCode.BadRequest,
        if (outcome.field == null) mapOf("error" to outcome.error) else mapOf("error" to outcome.error, "detail" to outcome.field),
    )
    ClientDirectory.Outcome.NotFound -> respond(HttpStatusCode.NotFound)
}
