package com.rfm.edubot.crm

import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date

/**
 * Removing a client, supplier or employee. One that documents refer to (quotes, invoices, Serviços
 * rows, bookings or payments) can't be deleted, only archived, so those documents keep their name and PDFs.
 */
enum class DirectoryDelete { NOT_FOUND, IN_USE, DELETED }

/** Directory lists show either the active records or the archived ones. */
internal fun archivedFilter(archived: Boolean): Bson =
    if (archived) Filters.ne("archivedAt", null) else Filters.eq("archivedAt", null)

/** Deletes [id] unless a document of this tenant in one of [references] points at it through [field]. */
internal suspend fun MongoCollection<Document>.deleteUnreferenced(
    mongo: MongoModule,
    tenantId: ObjectId,
    id: ObjectId,
    field: String,
    references: List<String>,
): DirectoryDelete {
    val record = Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id))
    if (countDocuments(record, CountOptions().limit(1)) == 0L) return DirectoryDelete.NOT_FOUND
    val referenced = references.any { name ->
        mongo.database.getCollection<Document>(name)
            .countDocuments(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq(field, id)), CountOptions().limit(1)) > 0
    }
    if (referenced) return DirectoryDelete.IN_USE
    deleteOne(record)
    return DirectoryDelete.DELETED
}

/** Archives or restores [id]. Archiving twice keeps the first date. Null when there is no such record. */
internal suspend fun MongoCollection<Document>.setArchived(tenantId: ObjectId, id: ObjectId, archived: Boolean): Document? {
    val now = Date(SystemClock.now().toEpochMilliseconds())
    val record = Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id))
    val (target, update) = if (archived) {
        Filters.and(record, Filters.eq("archivedAt", null)) to Updates.combine(Updates.set("archivedAt", now), Updates.set("updatedAt", now))
    } else {
        record to Updates.combine(Updates.unset("archivedAt"), Updates.set("updatedAt", now))
    }
    return findOneAndUpdate(target, update, FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER))
        ?: find(record).firstOrNull()
}
