package com.rfm.edubot.admin.board

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/** How urgent a card is. It only shows on the card; nothing is sorted by it. */
enum class CardPriority { LOW, MEDIUM, HIGH, URGENT }

/** One of a board's columns. Cards in a [done] column count as finished, so their due date no longer shows as late. */
data class BoardColumn(val id: String, val name: String, val done: Boolean)

/** A board the platform team follows its work on. Its cards are keyed `key-number` (OPS-12), numbered as they are created. */
data class Board(
    val id: ObjectId = ObjectId(),
    val key: String,
    val name: String,
    val description: String?,
    val columns: List<BoardColumn>,
    /** The number the last card created took. */
    val lastNumber: Int = 0,
    val createdAt: Instant,
    val createdBy: String?,
    val updatedAt: Instant,
)

data class ChecklistItem(val id: String, val text: String, val done: Boolean)

data class BoardCard(
    val id: ObjectId = ObjectId(),
    val boardId: ObjectId,
    val number: Int,
    val columnId: String,
    /** Order inside the column, smallest first. A move puts the card between its new neighbours. */
    val position: Double,
    val title: String,
    val description: String = "",
    /** The backoffice admin's email. */
    val assignee: String? = null,
    val dueDate: LocalDate? = null,
    val priority: CardPriority = CardPriority.MEDIUM,
    val labels: List<String> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    /** The tenant the work is for. */
    val tenantId: ObjectId? = null,
    val archivedAt: Instant? = null,
    val commentCount: Int = 0,
    val createdAt: Instant,
    val createdBy: String?,
    val updatedAt: Instant,
    val updatedBy: String?,
)

data class CardComment(
    val id: ObjectId = ObjectId(),
    val cardId: ObjectId,
    val boardId: ObjectId,
    /** Null for a password sign-in, which carries no email. */
    val author: String?,
    val body: String,
    val createdAt: Instant,
)

/** A value a partial update sets, null included; a field left out of [CardChanges] stays as it is. */
data class Change<out T>(val value: T)

data class CardChanges(
    val title: String? = null,
    val description: String? = null,
    val assignee: Change<String?>? = null,
    val dueDate: Change<LocalDate?>? = null,
    val priority: CardPriority? = null,
    val labels: List<String>? = null,
    val checklist: List<ChecklistItem>? = null,
    val tenantId: Change<ObjectId?>? = null,
    val archived: Boolean? = null,
)
