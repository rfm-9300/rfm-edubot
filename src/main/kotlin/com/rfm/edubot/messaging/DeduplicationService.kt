package com.rfm.edubot.messaging

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.Date

class DeduplicationService(mongoModule: MongoModule) {
    private val collection = mongoModule.database.getCollection<Document>("webhook_events")
    private val mutex = Mutex()
    private val log = LoggerFactory.getLogger("DeduplicationService")

    /**
     * Records the event and reports whether it was seen before. Pass the [inbound] message about to be
     * enqueued for it so [unprocessedInbound] can re-queue it if the process stops before the pipeline
     * marks the event processed or failed.
     */
    suspend fun isDuplicate(eventId: String, rawPayload: String, tenantId: ObjectId? = null, inbound: InboundMessage? = null): Boolean {
        return mutex.withLock {
            val now = Date()
            val filter = Filters.eq("eventId", eventId)
            val fields = Document("eventId", eventId)
                .append("type", "message")
                .append("rawPayload", rawPayload)
                .append("tenantId", tenantId)
                .append("receivedAt", now)
                .append("status", "received")
            if (inbound != null) fields.append("inbound", inbound.toDocument())
            val update = Updates.setOnInsert(fields)
            val options = com.mongodb.client.model.UpdateOptions().upsert(true)

            try {
                val result = collection.updateOne(filter, update, options)
                result.matchedCount > 0
            } catch (e: com.mongodb.MongoWriteException) {
                if (e.code == 11000) {
                    log.debug("Duplicate event detected: eventId={}", eventId)
                    true
                } else {
                    throw e
                }
            }
        }
    }

    suspend fun markProcessed(eventId: String) {
        val filter = Filters.eq("eventId", eventId)
        val update = Updates.combine(
            Updates.set("status", "processed"),
            Updates.set("processedAt", Date())
        )
        collection.updateOne(filter, update)
    }

    suspend fun markFailed(eventId: String) {
        val filter = Filters.eq("eventId", eventId)
        val update = Updates.combine(
            Updates.set("status", "failed"),
            Updates.set("processedAt", Date())
        )
        collection.updateOne(filter, update)
    }

    /** Messages enqueued in `[receivedFrom, receivedBefore)` that were never marked processed or failed, oldest first. */
    suspend fun unprocessedInbound(receivedFrom: Instant, receivedBefore: Instant): List<InboundMessage> =
        collection.find(
            Filters.and(
                Filters.eq("status", "received"),
                Filters.exists("inbound"),
                Filters.gte("receivedAt", Date(receivedFrom.toEpochMilliseconds())),
                Filters.lt("receivedAt", Date(receivedBefore.toEpochMilliseconds())),
            )
        ).sort(Document("receivedAt", 1)).toList().mapNotNull { doc ->
            runCatching { doc.get("inbound", Document::class.java).toInboundMessage() }
                .onFailure { log.warn("Skipping unreadable queued message: eventId={}", doc.getString("eventId"), it) }
                .getOrNull()
        }
}

private fun InboundMessage.toDocument(): Document = Document("tenantId", tenantId)
    .append("phoneNumberId", phoneNumberId)
    .append("platform", platform.name)
    .append("channelExternalId", channelExternalId)
    .append("waId", waId)
    .append("waMessageId", waMessageId)
    .append("profileName", profileName)
    .append("messageText", messageText)
    .append("timestamp", timestamp)
    .append("eventId", eventId)
    .append("registerOnly", registerOnly)

private fun Document.toInboundMessage(): InboundMessage = InboundMessage(
    tenantId = getObjectId("tenantId"),
    phoneNumberId = getString("phoneNumberId"),
    platform = Platform.valueOf(getString("platform")),
    channelExternalId = getString("channelExternalId"),
    waId = getString("waId"),
    waMessageId = getString("waMessageId"),
    profileName = getString("profileName"),
    messageText = getString("messageText"),
    timestamp = getString("timestamp"),
    eventId = getString("eventId"),
    registerOnly = getBoolean("registerOnly", false),
)
