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
                "tenant_usage",
                "admin_emails",
            ).forEach { name -> try { db.createCollection(name) } catch (_: Exception) {} }

            val tenants = db.getCollection<Document>("tenants")
            tenants.createIndex(Document("phoneNumberId", 1), IndexOptions().unique(true).sparse(true))
            tenants.createIndex(Document("slug", 1), IndexOptions().unique(true))
            tenants.createIndex(Document("channels.platform", 1).append("channels.externalId", 1))
            tenants.createIndex(
                Document("parentTenantId", 1),
                IndexOptions().partialFilterExpression(Document("parentTenantId", Document("\$type", "objectId"))),
            )

            val dashboardUsers = db.getCollection<Document>("dashboard_users")
            dashboardUsers.createIndex(Document("email", 1), IndexOptions().unique(true))
            dashboardUsers.createIndex(
                Document("googleUid", 1),
                IndexOptions().unique(true).partialFilterExpression(Document("googleUid", Document("\$type", "string"))),
            )
            dashboardUsers.createIndex(Document("tenantId", 1))
            dashboardUsers.createIndex(
                Document("employeeId", 1),
                IndexOptions().unique(true).partialFilterExpression(Document("employeeId", Document("\$type", "objectId"))),
            )

            db.getCollection<Document>("admin_emails").createIndex(Document("email", 1), IndexOptions().unique(true))

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
            crmClients.createIndex(
                Document("tenantId", 1).append("email", 1),
                IndexOptions().partialFilterExpression(Document("email", Document("\$type", "string"))),
            )

            val crmQuotes = db.getCollection<Document>("crm.quotes")
            crmQuotes.dropIndexIfExists("number_1")
            crmQuotes.createIndex(Document("tenantId", 1).append("clientId", 1))
            crmQuotes.createIndex(Document("tenantId", 1).append("status", 1))
            crmQuotes.createIndex(Document("tenantId", 1).append("number", 1), IndexOptions().unique(true))
            crmQuotes.createIndex(Document("tenantId", 1).append("status", 1).append("validUntil", 1))

            val domainEvents = db.getCollection<Document>("domain_events")
            domainEvents.createIndex(Document("dispatch.status", 1).append("occurredAt", 1))
            domainEvents.createIndex(Document("tenantId", 1).append("subject.type", 1).append("subject.id", 1).append("occurredAt", -1))
            domainEvents.createIndex(Document("tenantId", 1).append("related.type", 1).append("related.id", 1).append("occurredAt", -1))
            domainEvents.createIndex(Document("tenantId", 1).append("actor.type", 1).append("occurredAt", -1))
            domainEvents.createIndex(Document("occurredAt", 1), IndexOptions().expireAfter(365, TimeUnit.DAYS))

            val agents = db.getCollection<Document>("agents")
            agents.createIndex(Document("tenantId", 1).append("status", 1).append("updatedAt", -1))
            agents.createIndex(Document("tenantId", 1).append("eventTypes", 1))
            agents.createIndex(Document("status", 1).append("triggerTypes", 1))
            agents.createIndex(Document("status", 1).append("nextFireAt", 1))

            val agentRuns = db.getCollection<Document>("agent_runs")
            agentRuns.createIndex(Document("tenantId", 1).append("agentId", 1).append("dedupeKey", 1), IndexOptions().unique(true))
            agentRuns.createIndex(Document("status", 1).append("resumeAt", 1))
            agentRuns.createIndex(Document("status", 1).append("claimedAt", 1))
            agentRuns.createIndex(Document("tenantId", 1).append("agentId", 1).append("createdAt", -1))
            agentRuns.createIndex(Document("tenantId", 1).append("status", 1).append("createdAt", -1))
            agentRuns.createIndex(Document("tenantId", 1).append("subject.type", 1).append("subject.id", 1).append("status", 1))
            agentRuns.createIndex(Document("tenantId", 1).append("clientId", 1).append("createdAt", -1))
            agentRuns.createIndex(Document("finishedAt", 1), IndexOptions().expireAfter(180, TimeUnit.DAYS))

            val agentApprovals = db.getCollection<Document>("agent_approvals")
            agentApprovals.createIndex(Document("tenantId", 1).append("status", 1).append("createdAt", -1))
            agentApprovals.createIndex(Document("runId", 1).append("stepId", 1).append("seq", 1), IndexOptions().unique(true))
            agentApprovals.createIndex(Document("status", 1).append("expiresAt", 1))
            agentApprovals.createIndex(Document("tenantId", 1).append("subject.type", 1).append("subject.id", 1))

            val agentTasks = db.getCollection<Document>("agent_tasks")
            agentTasks.createIndex(Document("tenantId", 1).append("status", 1).append("dueAt", 1))
            agentTasks.createIndex(Document("tenantId", 1).append("assigneeUserId", 1).append("status", 1))
            agentTasks.createIndex(Document("tenantId", 1).append("subject.type", 1).append("subject.id", 1))
            agentTasks.createIndex(Document("tenantId", 1).append("clientId", 1).append("status", 1))

            val notifications = db.getCollection<Document>("notifications")
            notifications.createIndex(Document("tenantId", 1).append("audience", 1).append("userId", 1).append("createdAt", -1))
            notifications.createIndex(Document("createdAt", 1), IndexOptions().expireAfter(90, TimeUnit.DAYS))

            val outboundLog = db.getCollection<Document>("outbound_log")
            outboundLog.createIndex(Document("idempotencyKey", 1), IndexOptions().unique(true))
            outboundLog.createIndex(Document("tenantId", 1).append("recipient", 1).append("at", -1))
            outboundLog.createIndex(Document("tenantId", 1).append("channel", 1).append("at", -1))
            outboundLog.createIndex(Document("at", 1), IndexOptions().expireAfter(30, TimeUnit.DAYS))

            val integrationConnections = db.getCollection<Document>("integration_connections")
            integrationConnections.createIndex(Document("tenantId", 1).append("provider", 1).append("accountEmail", 1), IndexOptions().unique(true))
            integrationConnections.createIndex(Document("provider", 1).append("status", 1))

            val emailMessages = db.getCollection<Document>("email_messages")
            emailMessages.createIndex(Document("tenantId", 1).append("connectionId", 1).append("providerMessageId", 1), IndexOptions().unique(true))
            emailMessages.createIndex(Document("tenantId", 1).append("clientId", 1).append("date", -1))
            emailMessages.createIndex(Document("tenantId", 1).append("threadId", 1))
            emailMessages.createIndex(Document("bodyPurgedAt", 1).append("date", 1))

            val crmInvoices = db.getCollection<Document>("crm.invoices")
            crmInvoices.dropIndexIfExists("number_1")
            crmInvoices.createIndex(Document("tenantId", 1).append("clientId", 1))
            crmInvoices.createIndex(Document("tenantId", 1).append("status", 1))
            crmInvoices.createIndex(Document("tenantId", 1).append("dueDate", 1))
            crmInvoices.createIndex(Document("tenantId", 1).append("number", 1), IndexOptions().unique(true))

            val crmSequences = db.getCollection<Document>("crm.sequences")
            crmSequences.dropIndexIfExists("name_1")
            crmSequences.createIndex(Document("tenantId", 1).append("name", 1), IndexOptions().unique(true))

            val crmClientServices = db.getCollection<Document>("crm.client_services")
            crmClientServices.createIndex(Document("tenantId", 1).append("clientId", 1).append("status", 1))
            crmClientServices.createIndex(Document("tenantId", 1).append("status", 1).append("createdAt", -1))
            crmClientServices.createIndex(Document("tenantId", 1).append("invoiceId", 1))
            crmClientServices.createIndex(
                Document("tenantId", 1).append("bookingId", 1),
                IndexOptions().partialFilterExpression(Document("bookingId", Document("\$type", "objectId"))),
            )
            crmClientServices.createIndex(
                Document("tenantId", 1).append("employeeId", 1),
                IndexOptions().partialFilterExpression(Document("employeeId", Document("\$type", "objectId"))),
            )

            val crmSuppliers = db.getCollection<Document>("crm.suppliers")
            crmSuppliers.createIndex(Document("tenantId", 1).append("phone", 1), IndexOptions().unique(true))
            crmSuppliers.createIndex(Document("tenantId", 1).append("name", 1))
            crmSuppliers.createIndex(Document("tenantId", 1).append("number", 1), IndexOptions().unique(true))

            val crmEmployees = db.getCollection<Document>("crm.employees")
            crmEmployees.createIndex(Document("tenantId", 1).append("phone", 1), IndexOptions().unique(true))
            crmEmployees.createIndex(Document("tenantId", 1).append("name", 1))
            crmEmployees.createIndex(Document("tenantId", 1).append("number", 1), IndexOptions().unique(true))

            val serviceSubmissions = db.getCollection<Document>("crm.service_submissions")
            serviceSubmissions.createIndex(Document("tenantId", 1).append("employeeId", 1).append("createdAt", -1))
            serviceSubmissions.createIndex(Document("tenantId", 1).append("status", 1).append("createdAt", -1))

            val shifts = db.getCollection<Document>("timesheets.shifts")
            // One open shift per employee: two clock-ins at once leave one shift.
            shifts.createIndex(
                Document("tenantId", 1).append("employeeId", 1),
                IndexOptions().unique(true).name("one_open_shift").partialFilterExpression(Document("status", "OPEN")),
            )
            shifts.createIndex(Document("tenantId", 1).append("day", -1))
            shifts.createIndex(Document("tenantId", 1).append("employeeId", 1).append("day", -1))
            shifts.createIndex(Document("tenantId", 1).append("status", 1).append("review.status", 1))
            shifts.createIndex(Document("punches.at", 1))
            db.getCollection<Document>("timesheets.sites").createIndex(Document("tenantId", 1).append("name", 1))
            val timeDevices = db.getCollection<Document>("timesheets.devices")
            timeDevices.createIndex(Document("tenantId", 1).append("employeeId", 1).append("keyId", 1), IndexOptions().unique(true))
            timeDevices.createIndex(Document("tenantId", 1).append("employeeId", 1).append("active", 1))
            db.getCollection<Document>("timesheets.challenges").createIndex(Document("createdAt", 1), IndexOptions().expireAfter(5, TimeUnit.MINUTES))

            val crmPayments = db.getCollection<Document>("crm.payments")
            crmPayments.createIndex(Document("tenantId", 1).append("supplierId", 1))
            crmPayments.createIndex(Document("tenantId", 1).append("employeeId", 1))
            crmPayments.createIndex(Document("tenantId", 1).append("clientId", 1))
            crmPayments.createIndex(Document("tenantId", 1).append("status", 1).append("dueDate", 1))
            crmPayments.createIndex(Document("tenantId", 1).append("number", 1), IndexOptions().unique(true))

            val crmStandardItems = db.getCollection<Document>("crm.standard_items")
            crmStandardItems.dropIndexIfExists("id_1")
            crmStandardItems.createIndex(Document("tenantId", 1).append("id", 1), IndexOptions().unique(true))
            crmStandardItems.createIndex(
                Document("tenantId", 1).append("code", 1),
                IndexOptions().unique(true).partialFilterExpression(Document("code", Document("\$type", "string"))),
            )
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

            val tenantUsage = db.getCollection<Document>("tenant_usage")
            tenantUsage.createIndex(Document("tenantId", 1).append("period", 1), IndexOptions().unique(true))

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
