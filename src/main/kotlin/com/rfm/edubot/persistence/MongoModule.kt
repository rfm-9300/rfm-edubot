package com.rfm.edubot.persistence

import com.mongodb.client.model.IndexOptions
import com.rfm.edubot.config.AppConfig
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class MongoModule(config: AppConfig.MongoConfig) {
    val client: MongoClient = MongoClient.create(config.uri)
    private val log = LoggerFactory.getLogger("MongoModule")

    fun initialize() {
        log.info("Initializing MongoDB indexes")
        runBlocking {
            val db = client.getDatabase("wabot")

            db.createCollection("users")
            db.createCollection("conversations")
            db.createCollection("messages")
            db.createCollection("webhook_events")

            val users = db.getCollection<Document>("users")
            users.createIndex(Document("waId", 1), IndexOptions().unique(true))
            users.createIndex(Document("lastSeenAt", -1))

            val conversations = db.getCollection<Document>("conversations")
            conversations.createIndex(Document("userId", 1), IndexOptions().unique(true))
            conversations.createIndex(Document("waId", 1), IndexOptions().unique(true))
            conversations.createIndex(Document("lastMessageAt", -1))

            val messages = db.getCollection<Document>("messages")
            messages.createIndex(Document("conversationId", 1).append("createdAt", -1))
            try { messages.dropIndex("waMessageId_1") } catch (_: Exception) {}
            messages.createIndex(
                Document("waMessageId", 1),
                IndexOptions().unique(true).partialFilterExpression(
                    Document("waMessageId", Document("\$type", "string"))
                )
            )
            messages.createIndex(Document("createdAt", -1))

            val webhookEvents = db.getCollection<Document>("webhook_events")
            webhookEvents.createIndex(Document("eventId", 1), IndexOptions().unique(true))
            webhookEvents.createIndex(
                Document("receivedAt", 1),
                IndexOptions().expireAfter(7, TimeUnit.DAYS)
            )
        }
        log.info("MongoDB indexes initialized")
    }

    fun shutdown() {
        log.info("Closing MongoDB connection")
        client.close()
    }
}
