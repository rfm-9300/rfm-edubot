package com.rfm.edubot.admin.board

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date

private fun Instant.date() = Date(toEpochMilliseconds())
private fun Date.instant() = Instant.fromEpochMilliseconds(time)

/** The team's boards, in the platform database. */
class BoardRepository(mongo: MongoModule) {
    private val collection = mongo.platform.getCollection<Document>(COLLECTION)

    suspend fun list(): List<Board> = collection.find().sort(Sorts.ascending("createdAt")).toList().map { it.toBoard() }

    suspend fun findById(id: ObjectId): Board? = collection.find(Filters.eq("_id", id)).firstOrNull()?.toBoard()

    /** False when another board already uses the key. */
    suspend fun insert(board: Board): Boolean = try {
        collection.insertOne(board.toDocument())
        true
    } catch (e: MongoWriteException) {
        if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
        false
    }

    suspend fun update(id: ObjectId, name: String?, description: Change<String?>?, now: Instant): Board? {
        val changes = listOfNotNull(
            name?.let { Updates.set("name", it) },
            description?.let { Updates.set("description", it.value) },
            Updates.set("updatedAt", now.date()),
        )
        return collection.findOneAndUpdate(Filters.eq("_id", id), Updates.combine(changes), AFTER)?.toBoard()
    }

    suspend fun setColumns(id: ObjectId, columns: List<BoardColumn>, now: Instant): Board? =
        collection.findOneAndUpdate(
            Filters.eq("_id", id),
            Updates.combine(Updates.set("columns", columns.map { it.toDocument() }), Updates.set("updatedAt", now.date())),
            AFTER,
        )?.toBoard()

    /** The number the board's next card takes, or null when the board is gone. */
    suspend fun takeNumber(id: ObjectId): Int? =
        collection.findOneAndUpdate(Filters.eq("_id", id), Updates.inc("lastNumber", 1), AFTER)?.getInteger("lastNumber")

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(Filters.eq("_id", id)).deletedCount > 0

    private fun Board.toDocument() = Document("_id", id)
        .append("key", key)
        .append("name", name)
        .append("description", description)
        .append("columns", columns.map { it.toDocument() })
        .append("lastNumber", lastNumber)
        .append("createdAt", createdAt.date())
        .append("createdBy", createdBy)
        .append("updatedAt", updatedAt.date())

    private fun BoardColumn.toDocument() = Document("id", id).append("name", name).append("done", done)

    private fun Document.toBoard() = Board(
        id = getObjectId("_id"),
        key = getString("key"),
        name = getString("name"),
        description = getString("description"),
        columns = getList("columns", Document::class.java).orEmpty().map {
            BoardColumn(it.getString("id"), it.getString("name"), it.getBoolean("done") ?: false)
        },
        lastNumber = getInteger("lastNumber") ?: 0,
        createdAt = getDate("createdAt").instant(),
        createdBy = getString("createdBy"),
        updatedAt = getDate("updatedAt").instant(),
    )

    companion object {
        const val COLLECTION = "board.boards"
        private val AFTER = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    }
}

/** The boards' cards, in the platform database. */
class BoardCardRepository(mongo: MongoModule) {
    private val collection = mongo.platform.getCollection<Document>(COLLECTION)

    /** A board's cards, archived ones or the others, each column's in order. */
    suspend fun list(boardId: ObjectId, archived: Boolean): List<BoardCard> =
        collection.find(Filters.and(Filters.eq("boardId", boardId), archivedFilter(archived)))
            .sort(Sorts.ascending("position", "number")).toList().map { it.toCard() }

    /** The cards that aren't archived in one column, in order. */
    suspend fun inColumn(boardId: ObjectId, columnId: String): List<BoardCard> =
        collection.find(Filters.and(Filters.eq("boardId", boardId), Filters.eq("columnId", columnId), archivedFilter(false)))
            .sort(Sorts.ascending("position", "number")).toList().map { it.toCard() }

    /** The cards that aren't archived for a tenant, on every board, the most recently changed first. */
    suspend fun forTenant(tenantId: ObjectId): List<BoardCard> =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), archivedFilter(false)))
            .sort(Sorts.descending("updatedAt")).toList().map { it.toCard() }

    /** How many cards that aren't archived each board has. */
    suspend fun countsByBoard(): Map<ObjectId, Int> =
        collection.aggregate(
            listOf(Aggregates.match(archivedFilter(false)), Aggregates.group("\$boardId", Accumulators.sum("n", 1))),
        ).toList().associate { it.getObjectId("_id") to (it.getInteger("n") ?: 0) }

    suspend fun findById(id: ObjectId): BoardCard? = collection.find(Filters.eq("_id", id)).firstOrNull()?.toCard()

    suspend fun lastPosition(boardId: ObjectId, columnId: String): Double? =
        collection.find(Filters.and(Filters.eq("boardId", boardId), Filters.eq("columnId", columnId)))
            .sort(Sorts.descending("position")).limit(1).firstOrNull()?.get("position", Number::class.java)?.toDouble()

    suspend fun insert(card: BoardCard) {
        collection.insertOne(card.toDocument())
    }

    suspend fun update(id: ObjectId, changes: CardChanges, now: Instant, by: String?): BoardCard? {
        val sets = listOfNotNull(
            changes.title?.let { Updates.set("title", it) },
            changes.description?.let { Updates.set("description", it) },
            changes.assignee?.let { Updates.set("assignee", it.value) },
            changes.dueDate?.let { Updates.set("dueDate", it.value?.toString()) },
            changes.priority?.let { Updates.set("priority", it.name) },
            changes.labels?.let { Updates.set("labels", it) },
            changes.checklist?.let { items -> Updates.set("checklist", items.map { it.toDocument() }) },
            changes.tenantId?.let { Updates.set("tenantId", it.value) },
            changes.archived?.let { Updates.set("archivedAt", if (it) now.date() else null) },
        )
        return touch(id, sets, now, by)
    }

    suspend fun move(id: ObjectId, columnId: String, position: Double, now: Instant, by: String?): BoardCard? =
        touch(id, listOf(Updates.set("columnId", columnId), Updates.set("position", position)), now, by)

    /** Spreads a column's positions out again once moves have left no room between two neighbours. */
    suspend fun respace(cards: List<BoardCard>) {
        if (cards.isEmpty()) return
        collection.bulkWrite(
            cards.mapIndexed { i, card -> UpdateOneModel<Document>(Filters.eq("_id", card.id), Updates.set("position", (i + 1) * GAP)) },
        )
    }

    /** Sends the archived cards of removed columns to [to], so restoring one finds a column. */
    suspend fun moveArchived(boardId: ObjectId, from: Collection<String>, to: String) {
        if (from.isEmpty()) return
        collection.updateMany(
            Filters.and(Filters.eq("boardId", boardId), Filters.`in`("columnId", from), archivedFilter(true)),
            Updates.set("columnId", to),
        )
    }

    suspend fun countInColumns(boardId: ObjectId, columnIds: Collection<String>): Map<String, Int> {
        if (columnIds.isEmpty()) return emptyMap()
        return collection.aggregate(
            listOf(
                Aggregates.match(Filters.and(Filters.eq("boardId", boardId), Filters.`in`("columnId", columnIds), archivedFilter(false))),
                Aggregates.group("\$columnId", Accumulators.sum("n", 1)),
            ),
        ).toList().associate { it.getString("_id") to (it.getInteger("n") ?: 0) }
    }

    suspend fun addComments(id: ObjectId, by: Int) {
        collection.updateOne(Filters.eq("_id", id), Updates.inc("commentCount", by))
    }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(Filters.eq("_id", id)).deletedCount > 0

    suspend fun deleteByBoard(boardId: ObjectId) {
        collection.deleteMany(Filters.eq("boardId", boardId))
    }

    private suspend fun touch(id: ObjectId, sets: List<Bson>, now: Instant, by: String?): BoardCard? =
        collection.findOneAndUpdate(
            Filters.eq("_id", id),
            Updates.combine(sets + Updates.set("updatedAt", now.date()) + Updates.set("updatedBy", by)),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toCard()

    private fun archivedFilter(archived: Boolean): Bson =
        if (archived) Filters.type("archivedAt", "date") else Filters.not(Filters.type("archivedAt", "date"))

    private fun ChecklistItem.toDocument() = Document("id", id).append("text", text).append("done", done)

    private fun BoardCard.toDocument() = Document("_id", id)
        .append("boardId", boardId)
        .append("number", number)
        .append("columnId", columnId)
        .append("position", position)
        .append("title", title)
        .append("description", description)
        .append("assignee", assignee)
        .append("dueDate", dueDate?.toString())
        .append("priority", priority.name)
        .append("labels", labels)
        .append("checklist", checklist.map { it.toDocument() })
        .append("tenantId", tenantId)
        .append("archivedAt", archivedAt?.date())
        .append("commentCount", commentCount)
        .append("createdAt", createdAt.date())
        .append("createdBy", createdBy)
        .append("updatedAt", updatedAt.date())
        .append("updatedBy", updatedBy)

    private fun Document.toCard() = BoardCard(
        id = getObjectId("_id"),
        boardId = getObjectId("boardId"),
        number = getInteger("number"),
        columnId = getString("columnId"),
        position = get("position", Number::class.java)?.toDouble() ?: 0.0,
        title = getString("title"),
        description = getString("description").orEmpty(),
        assignee = getString("assignee"),
        dueDate = getString("dueDate")?.let(LocalDate::parse),
        priority = getString("priority")?.let { name -> CardPriority.entries.firstOrNull { it.name == name } } ?: CardPriority.MEDIUM,
        labels = getList("labels", String::class.java).orEmpty(),
        checklist = getList("checklist", Document::class.java).orEmpty().map {
            ChecklistItem(it.getString("id"), it.getString("text"), it.getBoolean("done") ?: false)
        },
        tenantId = getObjectId("tenantId"),
        archivedAt = getDate("archivedAt")?.instant(),
        commentCount = getInteger("commentCount") ?: 0,
        createdAt = getDate("createdAt").instant(),
        createdBy = getString("createdBy"),
        updatedAt = getDate("updatedAt").instant(),
        updatedBy = getString("updatedBy"),
    )

    companion object {
        const val COLLECTION = "board.cards"

        /** The room left between two cards when a column's positions are spread out. */
        const val GAP = 1024.0
    }
}

/** Comments on cards, in the platform database. */
class CardCommentRepository(mongo: MongoModule) {
    private val collection = mongo.platform.getCollection<Document>(COLLECTION)

    suspend fun list(cardId: ObjectId): List<CardComment> =
        collection.find(Filters.eq("cardId", cardId)).sort(Sorts.ascending("createdAt")).toList().map { it.toComment() }

    suspend fun findById(id: ObjectId): CardComment? = collection.find(Filters.eq("_id", id)).firstOrNull()?.toComment()

    suspend fun insert(comment: CardComment) {
        collection.insertOne(
            Document("_id", comment.id)
                .append("cardId", comment.cardId)
                .append("boardId", comment.boardId)
                .append("author", comment.author)
                .append("body", comment.body)
                .append("createdAt", comment.createdAt.date()),
        )
    }

    suspend fun delete(id: ObjectId): Boolean = collection.deleteOne(Filters.eq("_id", id)).deletedCount > 0

    suspend fun deleteByCard(cardId: ObjectId) {
        collection.deleteMany(Filters.eq("cardId", cardId))
    }

    suspend fun deleteByBoard(boardId: ObjectId) {
        collection.deleteMany(Filters.eq("boardId", boardId))
    }

    private fun Document.toComment() = CardComment(
        id = getObjectId("_id"),
        cardId = getObjectId("cardId"),
        boardId = getObjectId("boardId"),
        author = getString("author"),
        body = getString("body"),
        createdAt = getDate("createdAt").instant(),
    )

    companion object {
        const val COLLECTION = "board.comments"
    }
}
