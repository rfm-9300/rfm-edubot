package com.rfm.edubot.tags

import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

/**
 * One company's tags, kept in `record_tags` as one document per tagged record: `{tenantId, kind, recordId, tags}`.
 * They sit beside the records instead of on them, so no record type, its draft or its write paths know about
 * tags, and a record saved by the bot, a booking or an agent can't wipe them. Staff-only, like a client's notes.
 */
class RecordTagRepository(private val mongo: MongoModule, private val tenantId: ObjectId) {
    private val collection get() = mongo.database.getCollection<Document>(COLLECTION)

    private fun scoped(kind: TagKind) = Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("kind", kind.key))

    /**
     * Every tagged record of [kind] as `recordId → tags`. Tags of a record that no longer exists are deleted here,
     * so removing a record needs no cleanup of its own.
     */
    suspend fun forKind(kind: TagKind): Map<String, List<String>> {
        val documents = collection.find(scoped(kind)).toList()
        if (documents.isEmpty()) return emptyMap()
        val ids = documents.mapNotNull { document -> document.getString("recordId")?.let { kind.storedId(it) } }
        val alive = mongo.database.getCollection<Document>(kind.collection)
            .find(Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`(kind.idField, ids)))
            .projection(Document(kind.idField, 1))
            .toList()
            .map { it[kind.idField].toString() }
            .toSet()
        val (live, orphans) = documents.partition { it.getString("recordId") in alive }
        if (orphans.isNotEmpty()) collection.deleteMany(Filters.`in`("_id", orphans.map { it["_id"] }))
        return live.associate { it.getString("recordId") to it.getList("tags", String::class.java).orEmpty() }
    }

    /**
     * Replaces the tags of [recordId] with the already cleaned [tags]; none removes them. False when the company
     * has no such record, so tags can't pile up for ids that were never records.
     */
    suspend fun set(kind: TagKind, recordId: String, tags: List<String>): Boolean {
        val stored = kind.storedId(recordId) ?: return false
        val exists = mongo.database.getCollection<Document>(kind.collection)
            .countDocuments(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq(kind.idField, stored)), COUNT_ONE) > 0
        if (!exists) return false
        val record = Filters.and(scoped(kind), Filters.eq("recordId", recordId))
        if (tags.isEmpty()) {
            collection.deleteOne(record)
        } else {
            collection.findOneAndUpdate(
                record,
                Updates.combine(Updates.set("tags", tags), Updates.set("updatedAt", Date(SystemClock.now().toEpochMilliseconds()))),
                FindOneAndUpdateOptions().upsert(true),
            )
        }
        return true
    }

    companion object {
        const val COLLECTION = "record_tags"

        private val COUNT_ONE = CountOptions().limit(1)
    }
}
