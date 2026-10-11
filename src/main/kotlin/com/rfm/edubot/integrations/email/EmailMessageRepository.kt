package com.rfm.edubot.integrations.email

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.CountOptions
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

    /** Whether [record]'s document went to [recipient] since [since], so an agent doesn't send it again after a person just did. */
    suspend fun documentSent(tenantId: ObjectId, record: SubjectRef, recipient: String, since: Instant): Boolean =
        collection.countDocuments(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("direction", EmailDirection.OUTBOUND.name),
                Filters.eq("record.type", record.type),
                Filters.eq("record.id", record.id),
                Filters.eq("to", EmailAddresses.normalize(recipient) ?: recipient.trim().lowercase()),
                Filters.gte("date", since.toDate()),
                Filters.exists("attachments.0", true),
            ),
            CountOptions().limit(1),
        ) > 0

    suspend fun inThread(tenantId: ObjectId, threadId: String): List<EmailMessage> =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("threadId", threadId)))
            .sort(Document("date", 1)).toList().map { it.toMessage() }

    suspend fun idsForConnection(tenantId: ObjectId, connectionId: ObjectId): List<ObjectId> =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("connectionId", connectionId)))
            .projection(Document("_id", 1)).toList().map { it.getObjectId("_id") }

    /** Disconnecting an account forgets its mail. */
    suspend fun deleteForConnection(tenantId: ObjectId, connectionId: ObjectId): Long =
        collection.deleteMany(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("connectionId", connectionId))).deletedCount

    /** Emails dated before [before] that still hold their text, as (company, email) ids. */
    suspend fun textDue(before: Instant, limit: Int): List<Pair<ObjectId, ObjectId>> =
        collection.find(Filters.and(Filters.eq("bodyPurgedAt", null), Filters.lt("date", before.toDate())))
            .projection(Document("tenantId", 1)).limit(limit).toList().map { it.getObjectId("tenantId") to it.getObjectId("_id") }

    /**
     * Drops the text of these emails, the snippet included (it is the text's start), and what the model read
     * in it; subject, addresses, attachment names and what the team did stay as the record of what was sent.
     */
    suspend fun purgeBodies(ids: Collection<ObjectId>): Long =
        collection.updateMany(
            Filters.and(Filters.`in`("_id", ids), Filters.eq("bodyPurgedAt", null)),
            Updates.combine(Updates.unset("bodyText"), Updates.unset("insights"), Updates.set("snippet", ""), Updates.set("bodyPurgedAt", clock().toDate())),
        ).modifiedCount

    /**
     * The Email page's list: one row per Gmail thread (a message without one is its own thread), newest
     * activity first. A search keeps the threads with a message whose subject, addresses, names or snippet
     * match, and shows them whole.
     */
    suspend fun threads(
        tenantId: ObjectId,
        filter: EmailThreadFilter = EmailThreadFilter.ALL,
        connectionId: ObjectId? = null,
        query: String? = null,
        limit: Int = 100,
    ): List<EmailThreadSummary> {
        val base = Filters.and(listOfNotNull(Filters.eq("tenantId", tenantId), connectionId?.let { Filters.eq("connectionId", it) }))
        val scope = query?.trim()?.takeIf { it.isNotEmpty() }?.let { matching(base, it.take(MAX_QUERY)) ?: return emptyList() } ?: base
        val pipeline = listOfNotNull(
            Document("\$match", scope),
            Document("\$sort", Document("date", -1)),
            Document("\$addFields", Document("intent", "\$insights.intent")),
            Document("\$unset", listOf("bodyText", "insights", "actions")),
            Document("\$group", threadGroup()),
            filter.match?.let { Document("\$match", it) },
            Document("\$sort", Document("date", -1)),
            Document("\$limit", limit.coerceIn(1, MAX_THREADS)),
        )
        return collection.aggregate(pipeline).toList().map { it.toThreadSummary() }
    }

    /** Threads with received mail nobody on the team has opened yet; machines' mail doesn't count. */
    suspend fun unreadThreads(tenantId: ObjectId): Int {
        val pipeline = listOf(
            Document("\$match", Filters.and(Filters.eq("tenantId", tenantId), unreadFilter())),
            Document("\$group", Document("_id", threadKey())),
            Document("\$count", "n"),
        )
        return collection.aggregate(pipeline).firstOrNull()?.getInteger("n") ?: 0
    }

    /** [message]'s whole thread in its account, oldest first. */
    suspend fun thread(message: EmailMessage): List<EmailMessage> {
        val threadId = message.threadId ?: return listOf(message)
        return collection.find(
            Filters.and(Filters.eq("tenantId", message.tenantId), Filters.eq("connectionId", message.connectionId), Filters.eq("threadId", threadId)),
        ).sort(Document("date", 1)).limit(MAX_THREAD_MESSAGES).toList().map { it.toMessage() }
    }

    /** Marks the received ones among [ids] read; returns how many weren't yet. */
    suspend fun markRead(tenantId: ObjectId, ids: Collection<ObjectId>): Long {
        if (ids.isEmpty()) return 0
        return collection.updateMany(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("_id", ids), Filters.eq("direction", EmailDirection.INBOUND.name), Filters.eq("readAt", null)),
            Updates.set("readAt", clock().toDate()),
        ).modifiedCount
    }

    suspend fun markUnread(tenantId: ObjectId, id: ObjectId): Boolean =
        collection.updateOne(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id)), Updates.unset("readAt")).matchedCount > 0

    /** Keeps the model's reading of an email, unless its text was dropped meanwhile. */
    suspend fun saveInsights(tenantId: ObjectId, id: ObjectId, insights: EmailInsights): Boolean =
        collection.updateOne(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id), Filters.eq("bodyPurgedAt", null)),
            Updates.set("insights", insights.toDocument()),
        ).modifiedCount > 0

    /** Records [action] on the email, replacing an earlier one of the same type about the same record. */
    suspend fun recordAction(tenantId: ObjectId, id: ObjectId, action: EmailAction): EmailMessage? {
        val message = find(tenantId, id) ?: return null
        val actions = message.actions.filterNot { it.type == action.type && it.recordId == action.recordId } + action
        collection.updateOne(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id)),
            Updates.set("actions", actions.takeLast(MAX_ACTIONS).map { it.toDocument() }),
        )
        return message.copy(actions = actions.takeLast(MAX_ACTIONS))
    }

    /**
     * Files the mail exchanged with [addresses] under [clientId] once the sender became a client: what they
     * wrote, and what was sent to them. Mail already filed under a client stays where it is.
     */
    suspend fun linkToClient(tenantId: ObjectId, addresses: Collection<String>, clientId: ObjectId): Long {
        val normalized = addresses.mapNotNull(EmailAddresses::normalize).distinct()
        if (normalized.isEmpty()) return 0
        return collection.updateMany(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("clientId", null),
                Filters.or(
                    Filters.and(Filters.eq("direction", EmailDirection.INBOUND.name), Filters.or(Filters.`in`("from", normalized), Filters.`in`("replyTo", normalized))),
                    Filters.and(Filters.eq("direction", EmailDirection.OUTBOUND.name), Filters.`in`("to", normalized)),
                ),
            ),
            Updates.set("clientId", clientId),
        ).modifiedCount
    }

    /** The threads a search finds, as a filter on their messages; null when none match. */
    private suspend fun matching(base: org.bson.conversions.Bson, query: String): org.bson.conversions.Bson? {
        val pattern = Regex.escape(query)
        val text = Filters.or(
            listOf("subject", "from", "fromName", "to", "snippet").map { Filters.regex(it, pattern, "i") },
        )
        val hits = collection.aggregate(
            listOf(
                Document("\$match", Filters.and(base, text)),
                Document("\$group", Document("_id", threadKey()).append("date", Document("\$max", "\$date"))),
                Document("\$sort", Document("date", -1)),
                Document("\$limit", MAX_THREADS),
            ),
        ).toList().mapNotNull { it.get("_id", Document::class.java)?.get("t") }
        if (hits.isEmpty()) return null
        val threadIds = hits.filterIsInstance<String>()
        val lone = hits.filterIsInstance<ObjectId>()
        return Filters.and(base, Filters.or(Filters.`in`("threadId", threadIds), Filters.`in`("_id", lone)))
    }

    private fun threadKey() = Document("c", "\$connectionId").append("t", Document("\$ifNull", listOf("\$threadId", "\$_id")))

    private fun unreadFilter() = Filters.and(
        Filters.eq("direction", EmailDirection.INBOUND.name),
        Filters.eq("readAt", null),
        Filters.ne("automated", true),
    )

    private fun inbound() = Document("\$eq", listOf("\$direction", EmailDirection.INBOUND.name))

    private fun count(condition: Document) = Document("\$sum", Document("\$cond", listOf(condition, 1, 0)))

    private fun threadGroup(): Document {
        val unread = Document(
            "\$and",
            listOf(inbound(), Document("\$eq", listOf(Document("\$ifNull", listOf("\$readAt", null)), null)), Document("\$ne", listOf("\$automated", true))),
        )
        val fromPerson = Document("\$and", listOf(inbound(), Document("\$ne", listOf("\$automated", true))))
        return Document("_id", threadKey())
            .append("latest", Document("\$first", "\$\$ROOT"))
            .append("count", Document("\$sum", 1))
            .append("unread", count(unread))
            .append("inbound", count(inbound()))
            .append("fromPeople", count(fromPerson))
            .append("attachments", Document("\$max", Document("\$size", Document("\$ifNull", listOf("\$attachments", emptyList<Any>())))))
            .append("clientId", Document("\$max", "\$clientId"))
            .append("intents", Document("\$push", "\$intent"))
            .append("date", Document("\$max", "\$date"))
    }

    private fun Document.toThreadSummary(): EmailThreadSummary {
        val latest = get("latest", Document::class.java)!!.toMessage()
        return EmailThreadSummary(
            latest = latest,
            count = getInteger("count") ?: 1,
            unread = getInteger("unread") ?: 0,
            inbound = getInteger("inbound") ?: 0,
            fromPeople = getInteger("fromPeople") ?: 0,
            hasAttachments = (getInteger("attachments") ?: 0) > 0,
            clientId = get("clientId", ObjectId::class.java),
            intent = getList("intents", String::class.java).orEmpty().firstOrNull(),
        )
    }

    private fun EmailMessage.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("connectionId", connectionId)
        .append("providerMessageId", providerMessageId)
        .append("threadId", threadId)
        .append("messageIdHeader", messageIdHeader)
        .append("direction", direction.name)
        .append("from", from)
        .append("fromName", fromName)
        .apply { if (replyTo != null) append("replyTo", replyTo) }
        .append("to", to)
        .append("cc", cc)
        .append("bcc", bcc)
        .append("subject", subject)
        .append("snippet", snippet)
        .apply { if (bodyText != null) append("bodyText", bodyText.take(EmailMessage.MAX_BODY)) }
        .append("attachments", attachments.map { Document("filename", it.filename).append("mimeType", it.mimeType).append("size", it.size) })
        .apply { if (automated) append("automated", true) }
        .append("clientId", clientId)
        .append("record", record?.toDocument())
        .append("sentByType", sentByType)
        .append("sentById", sentById)
        .append("sentByName", sentByName)
        .append("runId", runId)
        .append("date", date.toDate())
        .append("createdAt", createdAt.toDate())
        .apply { if (readAt != null) append("readAt", readAt.toDate()) }
        .apply { if (insights != null) append("insights", insights.toDocument()) }
        .apply { if (actions.isNotEmpty()) append("actions", actions.map { it.toDocument() }) }

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
        replyTo = getString("replyTo"),
        to = getList("to", String::class.java).orEmpty(),
        cc = getList("cc", String::class.java).orEmpty(),
        bcc = getList("bcc", String::class.java).orEmpty(),
        subject = getString("subject").orEmpty(),
        snippet = getString("snippet").orEmpty(),
        bodyText = getString("bodyText"),
        attachments = getList("attachments", Document::class.java).orEmpty().map {
            EmailAttachmentInfo(it.getString("filename").orEmpty(), it.getString("mimeType").orEmpty(), it.getInteger("size") ?: 0)
        },
        automated = getBoolean("automated", false),
        clientId = get("clientId", ObjectId::class.java),
        record = get("record", Document::class.java)?.toSubjectRef()?.takeIf { it.type.isNotEmpty() && it.id.isNotEmpty() },
        sentByType = getString("sentByType"),
        sentById = getString("sentById"),
        sentByName = getString("sentByName"),
        runId = getString("runId"),
        date = instant("date") ?: Instant.fromEpochMilliseconds(0),
        createdAt = instant("createdAt") ?: Instant.fromEpochMilliseconds(0),
        bodyPurgedAt = instant("bodyPurgedAt"),
        readAt = instant("readAt"),
        insights = get("insights", Document::class.java)?.toInsights(),
        actions = getList("actions", Document::class.java).orEmpty().mapNotNull { it.toAction() },
    )

    private fun EmailInsights.toDocument() = Document("summary", summary)
        .append("intent", intent)
        .apply { if (accepted != null) append("accepted", accepted) }
        .append(
            "contact",
            Document().apply {
                contact.name?.let { append("name", it) }
                contact.company?.let { append("company", it) }
                contact.phone?.let { append("phone", it) }
                contact.email?.let { append("email", it) }
                contact.taxId?.let { append("taxId", it) }
                contact.address?.let { append("address", it) }
                contact.postalCode?.let { append("postalCode", it) }
                contact.city?.let { append("city", it) }
            },
        )
        .apply { request?.let { append("request", it) } }
        .append("items", items.map { item -> Document("description", item.description).append("quantity", item.quantity).append("unit", item.unit) })
        .apply { date?.let { append("date", it) } }
        .apply { time?.let { append("time", it) } }
        .apply { dueDate?.let { append("dueDate", it) } }
        .apply { amountCents?.let { append("amountCents", it) } }
        .apply { documentNumber?.let { append("documentNumber", it) } }
        .apply { serviceName?.let { append("serviceName", it) } }
        .apply { reply?.let { append("reply", it) } }
        .append("generatedAt", generatedAt.toDate())

    private fun Document.toInsights(): EmailInsights? {
        val contact = get("contact", Document::class.java)
        return EmailInsights(
            summary = getString("summary") ?: return null,
            intent = getString("intent")?.takeIf { it in EmailIntents.all } ?: EmailIntents.OTHER,
            accepted = get("accepted") as? Boolean,
            contact = EmailContact(
                name = contact?.getString("name"),
                company = contact?.getString("company"),
                phone = contact?.getString("phone"),
                email = contact?.getString("email"),
                taxId = contact?.getString("taxId"),
                address = contact?.getString("address"),
                postalCode = contact?.getString("postalCode"),
                city = contact?.getString("city"),
            ),
            request = getString("request"),
            items = getList("items", Document::class.java).orEmpty().mapNotNull { item ->
                item.getString("description")?.let { EmailItem(it, (item.get("quantity") as? Number)?.toDouble(), item.getString("unit")) }
            },
            date = getString("date"),
            time = getString("time"),
            dueDate = getString("dueDate"),
            amountCents = (get("amountCents") as? Number)?.toLong(),
            documentNumber = getString("documentNumber"),
            serviceName = getString("serviceName"),
            reply = getString("reply"),
            generatedAt = instant("generatedAt") ?: Instant.fromEpochMilliseconds(0),
        )
    }

    private fun EmailAction.toDocument() = Document("type", type)
        .append("status", status.name)
        .append("recordType", recordType)
        .append("recordId", recordId)
        .append("recordLabel", recordLabel)
        .append("byName", byName)
        .append("at", at.toDate())

    private fun Document.toAction(): EmailAction? {
        val type = getString("type") ?: return null
        val status = runCatching { EmailActionStatus.valueOf(getString("status")) }.getOrNull() ?: return null
        return EmailAction(
            type = type,
            status = status,
            recordType = getString("recordType"),
            recordId = getString("recordId"),
            recordLabel = getString("recordLabel"),
            byName = getString("byName"),
            at = instant("at") ?: Instant.fromEpochMilliseconds(0),
        )
    }

    companion object {
        const val COLLECTION = "email_messages"
        const val MAX_THREADS = 200
        const val MAX_THREAD_MESSAGES = 100
        private const val MAX_QUERY = 100
        private const val MAX_ACTIONS = 30
    }
}

/** Which threads the Email page lists, as a condition on the grouped thread. */
enum class EmailThreadFilter(val match: Document?) {
    ALL(null),
    UNREAD(Document("unread", Document("\$gt", 0))),
    CLIENTS(Document("clientId", Document("\$ne", null))),
    /** People not on file wrote in: leads, mostly. */
    NEW(Document("clientId", null).append("fromPeople", Document("\$gt", 0))),
    ;

    companion object {
        fun of(raw: String?): EmailThreadFilter = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALL
    }
}

/** One row of the Email page's list: the thread's newest message (without its text) and counts over the rest. */
data class EmailThreadSummary(
    val latest: EmailMessage,
    val count: Int,
    val unread: Int,
    val inbound: Int,
    /** Received messages a person wrote, not a machine. */
    val fromPeople: Int,
    val hasAttachments: Boolean,
    /** The client any of its messages is filed under. */
    val clientId: ObjectId?,
    /** What the model last read in it, newest first. */
    val intent: String?,
)
