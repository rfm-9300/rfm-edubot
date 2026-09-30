package com.rfm.edubot.agents.store

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.model.OutboundLogEntry
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId

/**
 * Every message an agent sends to someone outside the company (`outbound_log`). The row is written
 * before the provider call, keyed by run and step, so a restart never sends the same message twice;
 * it also counts messages per recipient for the frequency caps.
 */
class OutboundLogRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** False when [entry]'s key was already used: that send was attempted before and must not repeat. */
    suspend fun begin(entry: OutboundLogEntry): Boolean = try {
        collection.insertOne(entry.toDocument())
        true
    } catch (e: MongoWriteException) {
        if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
        false
    }

    suspend fun find(idempotencyKey: String): OutboundLogEntry? =
        collection.find(Filters.eq("idempotencyKey", idempotencyKey)).firstOrNull()?.toEntry()

    suspend fun markSent(idempotencyKey: String, providerMessageId: String?) {
        collection.updateOne(
            Filters.eq("idempotencyKey", idempotencyKey),
            Updates.combine(Updates.set("status", OutboundStatus.SENT.name), Updates.set("providerMessageId", providerMessageId), Updates.set("sentAt", clock().toDate())),
        )
    }

    suspend fun markFailed(idempotencyKey: String, error: String) {
        collection.updateOne(
            Filters.eq("idempotencyKey", idempotencyKey),
            Updates.combine(Updates.set("status", OutboundStatus.FAILED.name), Updates.set("error", error.take(500))),
        )
    }

    /** Messages that reached, or may have reached, [recipient] since [since]. */
    suspend fun countForRecipient(tenantId: ObjectId, recipient: String, since: Instant): Long =
        collection.countDocuments(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("recipient", recipient),
                Filters.gte("at", since.toDate()),
                Filters.`in`("status", listOf(OutboundStatus.SENDING.name, OutboundStatus.SENT.name)),
            ),
        )

    suspend fun countForChannel(tenantId: ObjectId, channel: String, since: Instant): Long =
        collection.countDocuments(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("channel", channel),
                Filters.gte("at", since.toDate()),
                Filters.`in`("status", listOf(OutboundStatus.SENDING.name, OutboundStatus.SENT.name)),
            ),
        )

    private fun OutboundLogEntry.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("idempotencyKey", idempotencyKey)
        .append("channel", channel)
        .append("recipient", recipient)
        .append("status", status.name)
        .append("at", at.toDate())
        .append("runId", runId)
        .append("agentId", agentId)
        .append("providerMessageId", providerMessageId)
        .append("error", error)

    private fun Document.toEntry() = OutboundLogEntry(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        idempotencyKey = getString("idempotencyKey").orEmpty(),
        channel = getString("channel").orEmpty(),
        recipient = getString("recipient").orEmpty(),
        status = runCatching { OutboundStatus.valueOf(getString("status")) }.getOrDefault(OutboundStatus.SENDING),
        at = instant("at") ?: Instant.fromEpochMilliseconds(0),
        runId = get("runId", ObjectId::class.java),
        agentId = get("agentId", ObjectId::class.java),
        providerMessageId = getString("providerMessageId"),
        error = getString("error"),
    )

    companion object {
        const val COLLECTION = "outbound_log"

        fun normalizeRecipient(channel: String, raw: String): String = when (channel) {
            "email" -> raw.trim().lowercase()
            else -> raw.filter { it.isDigit() }.ifEmpty { raw.trim() }
        }
    }
}
