package com.rfm.edubot.admin.board

import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.TenantRepository
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/** A request the board refuses: [error] is the stable code the backoffice translates, [detail] the field it is about. */
class BoardRefusal(val kind: Kind, val error: String, val detail: String? = null) : Exception(error) {
    enum class Kind { INVALID, NOT_FOUND, CONFLICT, FORBIDDEN }
}

data class BoardSummary(val board: Board, val cardCount: Int)

data class CardDetail(val card: BoardCard, val board: Board, val comments: List<CardComment>)

data class TenantCard(val card: BoardCard, val board: Board)

/** A column as the backoffice sends it: an existing one keeps its [id], a new one has none. */
data class ColumnInput(val id: String?, val name: String, val done: Boolean)

data class NewCard(
    val columnId: String,
    val title: String,
    val description: String? = null,
    val assignee: String? = null,
    val dueDate: LocalDate? = null,
    val priority: CardPriority? = null,
    val labels: List<String> = emptyList(),
    val tenantId: ObjectId? = null,
)

/**
 * The platform team's boards: their columns, cards, checklists and comments. Every backoffice admin reads and
 * changes every board; who created or last changed something is the signed-in admin's email.
 */
class BoardService(
    private val boards: BoardRepository,
    private val cards: BoardCardRepository,
    private val comments: CardCommentRepository,
    private val tenantExists: suspend (ObjectId) -> Boolean,
    private val clock: () -> Instant = SystemClock::now,
) {
    suspend fun boards(): List<BoardSummary> {
        val counts = cards.countsByBoard()
        return boards.list().map { BoardSummary(it, counts[it.id] ?: 0) }
    }

    suspend fun createBoard(name: String, key: String, description: String?, columns: List<ColumnInput>, by: String?): Board {
        val now = now()
        val board = Board(
            key = boardKey(key),
            name = text(name, MAX_BOARD_NAME, "name"),
            description = boardDescription(description),
            columns = columns(columns, existing = emptyList()),
            createdAt = now,
            createdBy = by,
            updatedAt = now,
        )
        if (!boards.insert(board)) throw BoardRefusal(BoardRefusal.Kind.CONFLICT, "key_taken", "key")
        return board
    }

    suspend fun updateBoard(id: ObjectId, name: String?, description: Change<String?>?): BoardSummary {
        board(id)
        val updated = boards.update(
            id,
            name?.let { text(it, MAX_BOARD_NAME, "name") },
            description?.let { Change(boardDescription(it.value)) },
            now(),
        ) ?: throw notFound("board_not_found")
        return summary(updated)
    }

    /**
     * Replaces the board's columns: renamed, reordered, added or removed in one go. A column that still holds cards
     * can't go; its archived cards move to the first column left.
     */
    suspend fun setColumns(id: ObjectId, input: List<ColumnInput>): BoardSummary {
        val board = board(id)
        val next = columns(input, board.columns)
        val removed = board.columns.map { it.id } - next.map { it.id }.toSet()
        cards.countInColumns(id, removed).entries.firstOrNull { it.value > 0 }?.let {
            throw BoardRefusal(BoardRefusal.Kind.CONFLICT, "column_not_empty", it.key)
        }
        cards.moveArchived(id, removed, next.first().id)
        return summary(boards.setColumns(id, next, now()) ?: throw notFound("board_not_found"))
    }

    /** Deletes the board with its cards and their comments. */
    suspend fun deleteBoard(id: ObjectId) {
        if (!boards.delete(id)) throw notFound("board_not_found")
        cards.deleteByBoard(id)
        comments.deleteByBoard(id)
    }

    suspend fun cards(boardId: ObjectId, archived: Boolean): List<BoardCard> {
        board(boardId)
        return cards.list(boardId, archived)
    }

    /** A new card at the end of its column. */
    suspend fun createCard(boardId: ObjectId, input: NewCard, by: String?): BoardCard {
        val board = board(boardId)
        val column = column(board, input.columnId)
        val title = text(input.title, MAX_TITLE, "title")
        val description = cardDescription(input.description.orEmpty())
        val assignee = input.assignee?.let(::assignee)
        val labels = labels(input.labels)
        input.tenantId?.let { tenant(it) }
        val number = boards.takeNumber(boardId) ?: throw notFound("board_not_found")
        val now = now()
        val card = BoardCard(
            boardId = boardId,
            number = number,
            columnId = column.id,
            position = (cards.lastPosition(boardId, column.id) ?: 0.0) + BoardCardRepository.GAP,
            title = title,
            description = description,
            assignee = assignee,
            dueDate = input.dueDate,
            priority = input.priority ?: CardPriority.MEDIUM,
            labels = labels,
            tenantId = input.tenantId,
            createdAt = now,
            createdBy = by,
            updatedAt = now,
            updatedBy = by,
        )
        cards.insert(card)
        return card
    }

    suspend fun card(id: ObjectId): CardDetail {
        val card = cards.findById(id) ?: throw notFound("card_not_found")
        return CardDetail(card, board(card.boardId), comments.list(id))
    }

    /** Changes only the fields [changes] names, so two people editing different fields of a card don't undo each other. */
    suspend fun updateCard(id: ObjectId, changes: CardChanges, by: String?): BoardCard {
        cards.findById(id) ?: throw notFound("card_not_found")
        val checked = CardChanges(
            title = changes.title?.let { text(it, MAX_TITLE, "title") },
            description = changes.description?.let(::cardDescription),
            assignee = changes.assignee?.let { Change(it.value?.let(::assignee)) },
            dueDate = changes.dueDate,
            priority = changes.priority,
            labels = changes.labels?.let(::labels),
            checklist = changes.checklist?.let(::checklist),
            tenantId = changes.tenantId?.also { change -> change.value?.let { tenant(it) } },
            archived = changes.archived,
        )
        return cards.update(id, checked, now(), by) ?: throw notFound("card_not_found")
    }

    /** Puts the card at [index] among the other cards of column [columnId] (0 is the top). */
    suspend fun moveCard(id: ObjectId, columnId: String, index: Int, by: String?): BoardCard {
        val card = cards.findById(id) ?: throw notFound("card_not_found")
        val column = column(board(card.boardId), columnId)
        return cards.move(id, column.id, place(card, column.id, index), now(), by) ?: throw notFound("card_not_found")
    }

    suspend fun deleteCard(id: ObjectId) {
        if (!cards.delete(id)) throw notFound("card_not_found")
        comments.deleteByCard(id)
    }

    suspend fun addComment(cardId: ObjectId, body: String, by: String?): CardComment {
        val card = cards.findById(cardId) ?: throw notFound("card_not_found")
        val comment = CardComment(cardId = cardId, boardId = card.boardId, author = by, body = text(body, MAX_COMMENT, "body"), createdAt = now())
        comments.insert(comment)
        cards.addComments(cardId, 1)
        return comment
    }

    /** Only the comment's author deletes it. */
    suspend fun deleteComment(id: ObjectId, by: String?) {
        val comment = comments.findById(id) ?: throw notFound("comment_not_found")
        if (!comment.author.equals(by, ignoreCase = true)) throw BoardRefusal(BoardRefusal.Kind.FORBIDDEN, "not_author")
        if (comments.delete(id)) cards.addComments(comment.cardId, -1)
    }

    /** The cards for a tenant that aren't archived, with their boards. */
    suspend fun tenantCards(tenantId: ObjectId): List<TenantCard> {
        val found = cards.forTenant(tenantId)
        if (found.isEmpty()) return emptyList()
        val byId = boards.list().associateBy { it.id }
        return found.mapNotNull { card -> byId[card.boardId]?.let { TenantCard(card, it) } }
    }

    private suspend fun place(card: BoardCard, columnId: String, index: Int): Double {
        val others = cards.inColumn(card.boardId, columnId).filter { it.id != card.id }
        val at = index.coerceIn(0, others.size)
        between(others.getOrNull(at - 1)?.position, others.getOrNull(at)?.position)?.let { return it }
        cards.respace(others)
        return between(if (at > 0) at * BoardCardRepository.GAP else null, if (at < others.size) (at + 1) * BoardCardRepository.GAP else null)
            ?: error("respaced positions always leave room")
    }

    /** Halfway between two neighbours (null: none on that side), or null when they are too close to tell apart. */
    private fun between(before: Double?, after: Double?): Double? = when {
        before == null && after == null -> BoardCardRepository.GAP
        before == null -> after!!.takeIf { it > MIN_GAP }?.div(2)
        after == null -> before + BoardCardRepository.GAP
        after - before > MIN_GAP -> (before + after) / 2
        else -> null
    }

    private suspend fun board(id: ObjectId): Board = boards.findById(id) ?: throw notFound("board_not_found")

    private suspend fun summary(board: Board) = BoardSummary(board, cards.countsByBoard()[board.id] ?: 0)

    private fun column(board: Board, id: String): BoardColumn =
        board.columns.firstOrNull { it.id == id } ?: throw invalid("column_not_found", "columnId")

    private suspend fun tenant(id: ObjectId) {
        if (!tenantExists(id)) throw invalid("tenant_not_found", "tenantId")
    }

    private fun boardKey(raw: String): String {
        val key = raw.trim().uppercase()
        if (!KEY.matches(key)) throw invalid("invalid_key", "key")
        return key
    }

    private fun boardDescription(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > MAX_BOARD_DESCRIPTION) throw invalid("too_long", "description")
        return value
    }

    private fun columns(input: List<ColumnInput>, existing: List<BoardColumn>): List<BoardColumn> {
        if (input.isEmpty()) throw invalid("required", "columns")
        if (input.size > MAX_COLUMNS) throw invalid("too_many", "columns")
        val known = existing.map { it.id }.toSet()
        val taken = mutableSetOf<String>()
        return input.map { column ->
            val id = column.id?.takeIf { it in known && taken.add(it) } ?: newId()
            BoardColumn(id, text(column.name, MAX_COLUMN_NAME, "columns"), column.done)
        }
    }

    private fun cardDescription(raw: String): String {
        val value = raw.trim()
        if (value.length > MAX_DESCRIPTION) throw invalid("too_long", "description")
        return value
    }

    /** A blank assignee leaves the card unassigned. */
    private fun assignee(raw: String): String? {
        val email = raw.trim().lowercase()
        if (email.isEmpty()) return null
        if (email.length > MAX_EMAIL || !EMAIL.matches(email)) throw invalid("invalid_assignee", "assignee")
        return email
    }

    private fun labels(raw: List<String>): List<String> {
        val labels = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (labels.size > MAX_LABELS) throw invalid("too_many", "labels")
        if (labels.any { it.length > MAX_LABEL }) throw invalid("too_long", "labels")
        return labels
    }

    private fun checklist(raw: List<ChecklistItem>): List<ChecklistItem> {
        if (raw.size > MAX_CHECKLIST) throw invalid("too_many", "checklist")
        val taken = mutableSetOf<String>()
        return raw.map { item ->
            val id = item.id.takeIf { it.isNotBlank() && it.length <= MAX_ITEM_ID && taken.add(it) } ?: newId()
            ChecklistItem(id, text(item.text, MAX_CHECKLIST_ITEM, "checklist"), item.done)
        }
    }

    private fun text(raw: String, max: Int, field: String): String {
        val value = raw.trim()
        if (value.isEmpty()) throw invalid("required", field)
        if (value.length > max) throw invalid("too_long", field)
        return value
    }

    // Mongo keeps milliseconds, so a timestamp read back compares equal to the one written.
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock().toEpochMilliseconds())

    private fun newId(): String = ObjectId().toHexString()

    private fun invalid(error: String, field: String) = BoardRefusal(BoardRefusal.Kind.INVALID, error, field)

    private fun notFound(error: String) = BoardRefusal(BoardRefusal.Kind.NOT_FOUND, error)

    companion object {
        private val KEY = Regex("^[A-Z][A-Z0-9]{1,5}$")
        private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
        private const val MAX_EMAIL = 254
        private const val MAX_BOARD_NAME = 80
        private const val MAX_BOARD_DESCRIPTION = 2_000
        private const val MAX_COLUMNS = 12
        private const val MAX_COLUMN_NAME = 40
        private const val MAX_TITLE = 500
        private const val MAX_DESCRIPTION = 20_000
        private const val MAX_LABELS = 10
        private const val MAX_LABEL = 30
        private const val MAX_CHECKLIST = 100
        private const val MAX_CHECKLIST_ITEM = 500
        private const val MAX_ITEM_ID = 40
        private const val MAX_COMMENT = 5_000

        /** Below this, two neighbours' positions are too close for a card between them, and the column is respaced. */
        private const val MIN_GAP = 1e-6

        fun of(mongo: MongoModule, tenants: TenantRepository) = BoardService(
            BoardRepository(mongo),
            BoardCardRepository(mongo),
            CardCommentRepository(mongo),
            tenantExists = { tenants.findById(it) != null },
        )
    }
}
