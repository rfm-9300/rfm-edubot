package com.rfm.edubot.persistence

import com.mongodb.client.model.IndexOptions
import com.rfm.edubot.config.AppConfig
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class MongoModule(config: AppConfig.MongoConfig) {
    val client: MongoClient = MongoClient.create(config.uri)
    val database = client.getDatabase(config.database)
    private val log = LoggerFactory.getLogger("MongoModule")

    fun initialize() {
        log.info("Initializing MongoDB indexes")
        runBlocking {
            val db = database

            listOf(
                "users",
                "conversations",
                "messages",
                "webhook_events",
                "tenants",
                "dashboard_users",
                "tenant_persona",
                "persona_sources",
                "dashboard_assistant_threads",
                "dashboard_assistant_messages",
                "crm.clients",
                "crm.quotes",
                "crm.invoices",
                "crm.sequences",
                "crm.standard_items",
            ).forEach { name -> try { db.createCollection(name) } catch (_: Exception) {} }

            val tenants = db.getCollection<Document>("tenants")
            tenants.createIndex(Document("phoneNumberId", 1), IndexOptions().unique(true).sparse(true))
            tenants.createIndex(Document("slug", 1), IndexOptions().unique(true))
            tenants.createIndex(Document("channels.platform", 1).append("channels.externalId", 1))

            val dashboardUsers = db.getCollection<Document>("dashboard_users")
            dashboardUsers.createIndex(Document("email", 1), IndexOptions().unique(true))
            dashboardUsers.createIndex(Document("tenantId", 1))

            val tenantPersona = db.getCollection<Document>("tenant_persona")
            tenantPersona.createIndex(Document("tenantId", 1), IndexOptions().unique(true))

            val personaSources = db.getCollection<Document>("persona_sources")
            personaSources.createIndex(Document("tenantId", 1).append("createdAt", -1))
            personaSources.createIndex(Document("tenantId", 1).append("compiledIntoVersion", 1))

            val assistantThreads = db.getCollection<Document>("dashboard_assistant_threads")
            assistantThreads.createIndex(Document("tenantId", 1).append("ownerKey", 1).append("updatedAt", -1))

            val assistantMessages = db.getCollection<Document>("dashboard_assistant_messages")
            assistantMessages.createIndex(Document("tenantId", 1).append("ownerKey", 1).append("threadId", 1).append("createdAt", 1))
            assistantMessages.createIndex(Document("action.id", 1), IndexOptions().unique(true).sparse(true))

            val users = db.getCollection<Document>("users")
            users.dropIndexIfExists("waId_1")
            users.dropIndexIfExists("tenantId_1_waId_1")
            users.createIndex(Document("tenantId", 1).append("channel", 1).append("waId", 1), IndexOptions().unique(true))
            users.createIndex(Document("tenantId", 1).append("lastSeenAt", -1))

            val conversations = db.getCollection<Document>("conversations")
            conversations.dropIndexIfExists("userId_1")
            conversations.dropIndexIfExists("waId_1")
            conversations.dropIndexIfExists("tenantId_1_waId_1")
            conversations.createIndex(Document("tenantId", 1).append("userId", 1), IndexOptions().unique(true))
            conversations.createIndex(Document("tenantId", 1).append("channel", 1).append("waId", 1), IndexOptions().unique(true))
            conversations.createIndex(Document("tenantId", 1).append("lastMessageAt", -1))

            val messages = db.getCollection<Document>("messages")
            messages.createIndex(Document("tenantId", 1).append("conversationId", 1).append("createdAt", -1))
            messages.dropIndexIfExists("waMessageId_1")
            messages.createIndex(
                Document("tenantId", 1).append("waMessageId", 1),
                IndexOptions().unique(true).partialFilterExpression(
                    Document("waMessageId", Document("\$type", "string"))
                )
            )
            messages.createIndex(Document("tenantId", 1).append("channel", 1).append("waId", 1).append("createdAt", -1))
            messages.createIndex(Document("tenantId", 1).append("createdAt", -1))

            val webhookEvents = db.getCollection<Document>("webhook_events")
            webhookEvents.createIndex(Document("eventId", 1), IndexOptions().unique(true))
            webhookEvents.createIndex(Document("tenantId", 1))
            webhookEvents.createIndex(
                Document("receivedAt", 1),
                IndexOptions().expireAfter(7, TimeUnit.DAYS)
            )

            val crmClients = db.getCollection<Document>("crm.clients")
            crmClients.dropIndexIfExists("phone_1")
            crmClients.createIndex(Document("tenantId", 1).append("phone", 1), IndexOptions().unique(true))
            crmClients.createIndex(Document("name", "text"))

            val crmQuotes = db.getCollection<Document>("crm.quotes")
            crmQuotes.dropIndexIfExists("number_1")
            crmQuotes.createIndex(Document("tenantId", 1).append("clientId", 1))
            crmQuotes.createIndex(Document("tenantId", 1).append("status", 1))
            crmQuotes.createIndex(Document("tenantId", 1).append("number", 1), IndexOptions().unique(true))

            val crmInvoices = db.getCollection<Document>("crm.invoices")
            crmInvoices.dropIndexIfExists("number_1")
            crmInvoices.createIndex(Document("tenantId", 1).append("clientId", 1))
            crmInvoices.createIndex(Document("tenantId", 1).append("status", 1))
            crmInvoices.createIndex(Document("tenantId", 1).append("dueDate", 1))
            crmInvoices.createIndex(Document("tenantId", 1).append("number", 1), IndexOptions().unique(true))

            val crmSequences = db.getCollection<Document>("crm.sequences")
            crmSequences.dropIndexIfExists("name_1")
            crmSequences.createIndex(Document("tenantId", 1).append("name", 1), IndexOptions().unique(true))

            val crmStandardItems = db.getCollection<Document>("crm.standard_items")
            crmStandardItems.dropIndexIfExists("id_1")
            crmStandardItems.createIndex(Document("tenantId", 1).append("id", 1), IndexOptions().unique(true))
            crmStandardItems.createIndex(Document("tenantId", 1).append("type", 1).append("category", 1))

            val bookingServices = db.getCollection<Document>("bookings.services")
            bookingServices.createIndex(Document("tenantId", 1).append("active", 1))
            bookingServices.createIndex(Document("tenantId", 1).append("name", 1))

            val bookingAvailability = db.getCollection<Document>("bookings.availability")
            bookingAvailability.createIndex(Document("tenantId", 1).append("dayOfWeek", 1))

            val bookingAppointments = db.getCollection<Document>("bookings.appointments")
            bookingAppointments.createIndex(Document("tenantId", 1).append("startAt", 1))
            bookingAppointments.createIndex(Document("tenantId", 1).append("status", 1).append("startAt", 1))
            bookingAppointments.createIndex(Document("tenantId", 1).append("clientId", 1))

            val instagramMedia = db.getCollection<Document>("instagram.media")
            instagramMedia.createIndex(Document("tenantId", 1).append("mediaId", 1), IndexOptions().unique(true))
            instagramMedia.createIndex(Document("tenantId", 1).append("publishedAt", -1))

            val instagramComments = db.getCollection<Document>("instagram.comments")
            instagramComments.createIndex(Document("tenantId", 1).append("commentId", 1), IndexOptions().unique(true))
            instagramComments.createIndex(Document("tenantId", 1).append("mediaId", 1).append("createdAt", 1))
            instagramComments.createIndex(
                Document("tenantId", 1).append("fromAccount", 1).append("hidden", 1).append("parentCommentId", 1).append("repliedAt", 1).append("createdAt", -1),
            )
        }
        log.info("MongoDB indexes initialized")
    }

    fun shutdown() {
        log.info("Closing MongoDB connection")
        client.close()
    }

    private suspend fun MongoCollection<Document>.dropIndexIfExists(name: String) {
        try { dropIndex(name) } catch (_: Exception) {}
    }
}
