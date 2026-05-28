package com.rfm.edubot.messaging

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.Date

class DeduplicationService(mongoModule: MongoModule) {
    private val collection = mongoModule.database.getCollection<Document>("webhook_events")
    private val mutex = Mutex()
    private val log = LoggerFactory.getLogger("DeduplicationService")

    suspend fun isDuplicate(eventId: String, rawPayload: String): Boolean {
        return mutex.withLock {
            val now = Date()
            val filter = Filters.eq("eventId", eventId)
            val update = Updates.setOnInsert(
                Document("eventId", eventId)
                    .append("type", "message")
                    .append("rawPayload", rawPayload)
                    .append("receivedAt", now)
                    .append("status", "received")
            )
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
}
