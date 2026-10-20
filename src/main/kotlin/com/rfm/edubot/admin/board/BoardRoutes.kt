package com.rfm.edubot.admin.board

import com.rfm.edubot.admin.adminEmail
import com.rfm.edubot.http.ApiErrors
import com.rfm.edubot.http.ApiException
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.enumValue
import com.rfm.edubot.http.idParam
import com.rfm.edubot.http.idValue
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** `/admin/api/board`: the backoffice's boards, their columns and cards, cards' checklists and comments. */
fun Route.boardRoutes(service: BoardService) {
    authenticate("admin-jwt") {
        route("/admin/api/board") {
            get("/boards") {
                call.respond(service.boards().map { it.board.dto(it.cardCount) })
            }
            post("/boards") {
                val request = call.receive<CreateBoardRequest>()
                val board = refusing {
                    service.createBoard(request.name, request.key, request.description, request.columns.map { it.input() }, call.adminEmail())
                }
                call.respond(HttpStatusCode.Created, board.dto(0))
            }
            patch("/boards/{id}") {
                val body = call.receive<JsonObject>()
                val id = call.idParam("id")
                val summary = refusing { service.updateBoard(id, body.text("name"), body.nullableText("description")) }
                call.respond(summary.board.dto(summary.cardCount))
            }
            put("/boards/{id}/columns") {
                val id = call.idParam("id")
                val request = call.receive<ColumnsRequest>()
                val summary = refusing { service.setColumns(id, request.columns.map { it.input() }) }
                call.respond(summary.board.dto(summary.cardCount))
            }
            delete("/boards/{id}") {
                val id = call.idParam("id")
                refusing { service.deleteBoard(id) }
                call.respond(mapOf("deleted" to true))
            }
            get("/boards/{id}/cards") {
                val id = call.idParam("id")
                val archived = call.request.queryParameters["archived"] == "true"
                call.respond(refusing { service.cards(id, archived) }.map { it.dto() })
            }
            post("/boards/{id}/cards") {
                val id = call.idParam("id")
                val request = call.receive<CreateCardRequest>()
                val input = NewCard(
                    columnId = request.columnId,
                    title = request.title,
                    description = request.description,
                    assignee = request.assignee,
                    dueDate = request.dueDate?.takeIf { it.isNotBlank() }?.let { dateValue(it, "dueDate") },
                    priority = request.priority?.let { enumValue<CardPriority>(it, "priority", INVALID_PRIORITY) },
                    labels = request.labels,
                    tenantId = request.tenantId?.takeIf { it.isNotBlank() }?.let { idValue(it, "tenantId") },
                )
                call.respond(HttpStatusCode.Created, refusing { service.createCard(id, input, call.adminEmail()) }.dto())
            }
            // The cards for one tenant, for its record.
            get("/cards") {
                val tenantId = idValue(call.request.queryParameters["tenantId"], "tenantId")
                call.respond(service.tenantCards(tenantId).map { it.dto() })
            }
            get("/cards/{id}") {
                val id = call.idParam("id")
                val detail = refusing { service.card(id) }
                call.respond(CardDetailDto(detail.card.dto(), detail.board.dto(null), detail.comments.map { it.dto() }))
            }
            patch("/cards/{id}") {
                val id = call.idParam("id")
                val changes = call.receive<JsonObject>().cardChanges()
                call.respond(refusing { service.updateCard(id, changes, call.adminEmail()) }.dto())
            }
            post("/cards/{id}/move") {
                val id = call.idParam("id")
                val request = call.receive<MoveCardRequest>()
                call.respond(refusing { service.moveCard(id, request.columnId, request.index, call.adminEmail()) }.dto())
            }
            delete("/cards/{id}") {
                val id = call.idParam("id")
                refusing { service.deleteCard(id) }
                call.respond(mapOf("deleted" to true))
            }
            post("/cards/{id}/comments") {
                val id = call.idParam("id")
                val request = call.receive<CommentRequest>()
                call.respond(HttpStatusCode.Created, refusing { service.addComment(id, request.body, call.adminEmail()) }.dto())
            }
            delete("/comments/{id}") {
                val id = call.idParam("id")
                refusing { service.deleteComment(id, call.adminEmail()) }
                call.respond(mapOf("deleted" to true))
            }
        }
    }
}

private const val INVALID_PRIORITY = "invalid_priority"

/** The board's refusals as the error contract's answers. */
private inline fun <T> refusing(block: () -> T): T = try {
    block()
} catch (e: BoardRefusal) {
    val status = when (e.kind) {
        BoardRefusal.Kind.INVALID -> HttpStatusCode.BadRequest
        BoardRefusal.Kind.NOT_FOUND -> HttpStatusCode.NotFound
        BoardRefusal.Kind.CONFLICT -> HttpStatusCode.Conflict
        BoardRefusal.Kind.FORBIDDEN -> HttpStatusCode.Forbidden
    }
    throw ApiException(status, e.error, e.detail)
}

// A card's PATCH names only the fields it changes; a nullable field sent as null clears it.
private fun JsonObject.cardChanges() = CardChanges(
    title = text("title"),
    description = text("description"),
    assignee = nullableText("assignee"),
    dueDate = nullableText("dueDate")?.let { change -> Change(change.value?.takeIf { it.isNotBlank() }?.let { dateValue(it, "dueDate") }) },
    priority = text("priority")?.let { enumValue<CardPriority>(it, "priority", INVALID_PRIORITY) },
    labels = array("labels")?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw invalidBody("labels") },
    checklist = array("checklist")?.map { it.checklistItem() },
    tenantId = nullableText("tenantId")?.let { change -> Change(change.value?.takeIf { it.isNotBlank() }?.let { idValue(it, "tenantId") }) },
    archived = this["archived"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: throw invalidBody("archived") },
)

private fun JsonElement.checklistItem(): ChecklistItem {
    val item = this as? JsonObject ?: throw invalidBody("checklist")
    return ChecklistItem(
        id = item.text("id").orEmpty(),
        text = item.text("text") ?: throw invalidBody("checklist"),
        done = item["done"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: throw invalidBody("checklist") } ?: false,
    )
}

private fun JsonObject.text(key: String): String? = when (val value = this[key]) {
    null -> null
    is JsonPrimitive -> value.takeIf { it.isString }?.content ?: throw invalidBody(key)
    else -> throw invalidBody(key)
}

private fun JsonObject.nullableText(key: String): Change<String?>? = when (val value = this[key]) {
    null -> null
    JsonNull -> Change(null)
    is JsonPrimitive -> Change(value.takeIf { it.isString }?.content ?: throw invalidBody(key))
    else -> throw invalidBody(key)
}

private fun JsonObject.array(key: String): JsonArray? = when (val value = this[key]) {
    null -> null
    is JsonArray -> value
    else -> throw invalidBody(key)
}

private fun invalidBody(field: String) = ApiException(HttpStatusCode.BadRequest, ApiErrors.INVALID_BODY, field)

@Serializable
private data class ColumnRequest(val id: String? = null, val name: String, val done: Boolean = false) {
    fun input() = ColumnInput(id, name, done)
}

@Serializable
private data class CreateBoardRequest(val name: String, val key: String, val description: String? = null, val columns: List<ColumnRequest>)

@Serializable
private data class ColumnsRequest(val columns: List<ColumnRequest>)

@Serializable
private data class CreateCardRequest(
    val columnId: String,
    val title: String,
    val description: String? = null,
    val assignee: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    val labels: List<String> = emptyList(),
    val tenantId: String? = null,
)

@Serializable
private data class MoveCardRequest(val columnId: String, val index: Int)

@Serializable
private data class CommentRequest(val body: String)

@Serializable
private data class BoardColumnDto(val id: String, val name: String, val done: Boolean)

@Serializable
private data class BoardDto(
    val id: String,
    val key: String,
    val name: String,
    val description: String?,
    val columns: List<BoardColumnDto>,
    /** Cards that aren't archived; null where it isn't counted. */
    val cardCount: Int?,
    val createdAt: String,
    val createdBy: String?,
)

@Serializable
private data class ChecklistItemDto(val id: String, val text: String, val done: Boolean)

@Serializable
private data class CardDto(
    val id: String,
    val boardId: String,
    /** With the board's key it makes the card's key: `OPS-12`. */
    val number: Int,
    val columnId: String,
    val title: String,
    val description: String,
    val assignee: String?,
    val dueDate: String?,
    val priority: String,
    val labels: List<String>,
    val checklist: List<ChecklistItemDto>,
    val tenantId: String?,
    val archived: Boolean,
    val commentCount: Int,
    val createdAt: String,
    val createdBy: String?,
    val updatedAt: String,
    val updatedBy: String?,
)

@Serializable
private data class CommentDto(val id: String, val author: String?, val body: String, val createdAt: String)

@Serializable
private data class CardDetailDto(val card: CardDto, val board: BoardDto, val comments: List<CommentDto>)

@Serializable
private data class TenantCardDto(val card: CardDto, val boardKey: String, val boardName: String, val columnName: String, val done: Boolean)

private fun Board.dto(cardCount: Int?) = BoardDto(
    id = id.toHexString(),
    key = key,
    name = name,
    description = description,
    columns = columns.map { BoardColumnDto(it.id, it.name, it.done) },
    cardCount = cardCount,
    createdAt = createdAt.toString(),
    createdBy = createdBy,
)

private fun BoardCard.dto() = CardDto(
    id = id.toHexString(),
    boardId = boardId.toHexString(),
    number = number,
    columnId = columnId,
    title = title,
    description = description,
    assignee = assignee,
    dueDate = dueDate?.toString(),
    priority = priority.name,
    labels = labels,
    checklist = checklist.map { ChecklistItemDto(it.id, it.text, it.done) },
    tenantId = tenantId?.toHexString(),
    archived = archivedAt != null,
    commentCount = commentCount,
    createdAt = createdAt.toString(),
    createdBy = createdBy,
    updatedAt = updatedAt.toString(),
    updatedBy = updatedBy,
)

private fun CardComment.dto() = CommentDto(id.toHexString(), author, body, createdAt.toString())

private fun TenantCard.dto(): TenantCardDto {
    val column = board.columns.firstOrNull { it.id == card.columnId }
    return TenantCardDto(card.dto(), board.key, board.name, column?.name.orEmpty(), column?.done ?: false)
}
