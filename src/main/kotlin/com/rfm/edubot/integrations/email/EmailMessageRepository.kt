package com.rfm.edubot.integrations.email

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.store.instant
import com.rfm.edubot.agents.store.toDate
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.toDocument
import com.rfm.edubot.events.toSubjectRef
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId

class EmailMessageRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** Stores [message]; one Gmail message is stored once per account, so a second insert returns the first. */
    suspend fun insert(message: EmailMessage): EmailMessage = try {
        collection.insertOne(message.toDocument())
        message
    } catch (e: MongoWriteException) {
        if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
        findByProviderId(message.tenantId, message.connectionId, message.providerMessageId) ?: message
    }

    suspend fun find(tenantId: ObjectId, id: ObjectId): EmailMessage? =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id))).firstOrNull()?.toMessage()

    suspend fun findByProviderId(tenantId: ObjectId, connectionId: ObjectId, providerMessageId: String): EmailMessage? =
        collection.find(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("connectionId", connectionId), Filters.eq("providerMessageId", providerMessageId)),
        ).firstOrNull()?.toMessage()

    /** A client's emails, newest first. */
    suspend fun forClient(tenantId: ObjectId, clientId: ObjectId, limit: Int = 50): List<EmailMessage> =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("clientId", clientId)))
            .sort(Document("date", -1)).limit(limit.coerceIn(1, 200)).toList().map { it.toMessage() }

    suspend fun inThread(tenantId: ObjectId, threadId: String): List<EmailMessage> =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("threadId", threadId)))
            .sort(Document("date", 1)).toList().map { it.toMessage() }

    /** Disconnecting an account forgets its mail. */
    suspend fun deleteForConnection(tenantId: ObjectId, connectionId: ObjectId): Long =
        collection.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("connectionId", connectionId))).deletedCount

    /** Drops the bodies of emails dated before [before]; subject, addresses and snippet stay. */
    suspend fun purgeBodies(before: Instant): Long =
        collection.updateMany(
            Filters.and(Filters.lt("date", before.toDate()), Filters.exists("bodyText", true)),
            Updates.combine(Updates.unset("bodyText"), Updates.set("bodyPurgedAt", clock().toDate())),
        ).modifiedCount

    private fun EmailMessage.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("connectionId", connectionId)
        .append("providerMessageId", providerMessageId)
        .append("threadId", threadId)
        .append("messageIdHeader", messageIdHeader)
        .append("direction", direction.name)
        .append("from", from)
        .append("fromName", fromName)
        .append("to", to)
        .append("cc", cc)
        .append("bcc", bcc)
        .append("subject", subject)
        .append("snippet", snippet)
        .apply { if (bodyText != null) append("bodyText", bodyText.take(EmailMessage.MAX_BODY)) }
        .append("attachments", attachments.map { Document("filename", it.filename).append("mimeType", it.mimeType).append("size", it.size) })
        .append("clientId", clientId)
        .append("record", record?.toDocument())
        .append("sentByType", sentByType)
        .append("sentById", sentById)
        .append("sentByName", sentByName)
        .append("runId", runId)
        .append("date", date.toDate())
        .append("createdAt", createdAt.toDate())

    private fun Document.toMessage() = EmailMessage(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        connectionId = getObjectId("connectionId"),
        providerMessageId = getString("providerMessageId").orEmpty(),
        threadId = getString("threadId"),
        messageIdHeader = getString("messageIdHeader"),
        direction = runCatching { EmailDirection.valueOf(getString("direction")) }.getOrDefault(EmailDirection.OUTBOUND),
        from = getString("from").orEmpty(),
        fromName = getString("fromName"),
        to = getList("to", String::class.java).orEmpty(),
        cc = getList("cc", String::class.java).orEmpty(),
        bcc = getList("bcc", String::class.java).orEmpty(),
        subject = getString("subject").orEmpty(),
        snippet = getString("snippet").orEmpty(),
        bodyText = getString("bodyText"),
        attachments = getList("attachments", Document::class.java).orEmpty().map {
            EmailAttachmentInfo(it.getString("filename").orEmpty(), it.getString("mimeType").orEmpty(), it.getInteger("size") ?: 0)
        },
        clientId = get("clientId", ObjectId::class.java),
        record = get("record", Document::class.java)?.toSubjectRef()?.takeIf { it.type.isNotEmpty() && it.id.isNotEmpty() },
        sentByType = getString("sentByType"),
        sentById = getString("sentById"),
        sentByName = getString("sentByName"),
        runId = getString("runId"),
        date = instant("date") ?: Instant.fromEpochMilliseconds(0),
        createdAt = instant("createdAt") ?: Instant.fromEpochMilliseconds(0),
        bodyPurgedAt = instant("bodyPurgedAt"),
    )

    companion object {
        const val COLLECTION = "email_messages"
    }
}
