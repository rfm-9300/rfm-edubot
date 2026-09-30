package com.rfm.edubot.conversation

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.ConversationState
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageAuthor
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.conversation.model.TokenUsage
import com.rfm.edubot.conversation.model.User
import com.rfm.edubot.conversation.model.UserStatus
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.Date

class UserRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("users")
    private val log = LoggerFactory.getLogger("UserRepository")

    suspend fun findByWaId(waId: String, channel: Platform = Platform.WHATSAPP): User? {
        val doc = collection.find(scoped(channel, Filters.eq("waId", waId))).firstOrNull()
        return doc?.toUser()
    }

    suspend fun findOrCreate(waId: String, displayName: String? = null, channel: Platform = Platform.WHATSAPP): User {
        val existing = findByWaId(waId, channel)
        if (existing != null) {
            val now = SystemClock.now()
            collection.updateOne(
                scoped(channel, Filters.eq("waId", waId)),
                Updates.combine(
                    Updates.set("lastSeenAt", now.toDate()),
                    Updates.set("displayName", displayName ?: existing.displayName)
                )
            )
            return existing.copy(lastSeenAt = now, displayName = displayName ?: existing.displayName)
        }

        val now = SystemClock.now()
        val user = User(
            tenantId = tenantId,
            channel = channel,
            waId = waId,
            displayName = displayName,
            createdAt = now,
            lastSeenAt = now,
        )
        collection.insertOne(user.toDocument())
        log.info("Created new user: waId={}", waId)
        return user
    }

    suspend fun list(query: String? = null, limit: Int = 100): List<User> {
        val term = query?.trim()?.takeIf { it.isNotBlank() }
        val filter = if (term == null) {
            Filters.eq("tenantId", tenantId)
        } else {
            scoped(
                Filters.or(
                    Filters.regex("waId", ".*${Regex.escape(term)}.*", "i"),
                    Filters.regex("displayName", ".*${Regex.escape(term)}.*", "i"),
                )
            )
        }
        return collection.find(filter).sort(Document("lastSeenAt", -1)).limit(limit).toList().map { it.toUser() }
    }

    suspend fun displayNamesByIds(ids: Collection<ObjectId>): Map<ObjectId, String?> {
        if (ids.isEmpty()) return emptyMap()
        return collection.find(scoped(Filters.`in`("_id", ids))).toList()
            .associate { it.getObjectId("_id") to it.getString("displayName") }
    }

    suspend fun setStatus(id: ObjectId, status: UserStatus): User? {
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.set("status", status.name),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toUser()
    }

    private fun Document.toUser(): User {
        return User(
            id = getObjectId("_id"),
            tenantId = getObjectId("tenantId"),
            channel = getPlatform(),
            waId = getString("waId")!!,
            displayName = getString("displayName"),
            locale = getString("locale") ?: "pt_BR",
            status = UserStatus.valueOf(getString("status") ?: "ACTIVE"),
            createdAt = getInstant("createdAt"),
            lastSeenAt = getInstant("lastSeenAt"),
            metadata = getMetadataMap(),
        )
    }

    private fun Document.getMetadataMap(): Map<String, String> {
        val metadataDoc = get("metadata", Document::class.java) ?: return emptyMap()
        return metadataDoc.keys.associateWith { key -> metadataDoc.getString(key) ?: "" }
    }

    private fun User.toDocument(): Document {
        val metadataDoc = Document()
        metadata.forEach { (key, value) -> metadataDoc.append(key, value) }

        return Document("_id", id)
            .append("tenantId", tenantId)
            .append("channel", channel.name)
            .append("waId", waId)
            .append("displayName", displayName)
            .append("locale", locale)
            .append("status", status.name)
            .append("createdAt", createdAt.toDate())
            .append("lastSeenAt", lastSeenAt.toDate())
            .append("metadata", metadataDoc)
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
    private fun scoped(channel: Platform, filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("channel", channel.name), filter)
}

class ConversationRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("conversations")
    private val log = LoggerFactory.getLogger("ConversationRepository")

    suspend fun findByWaId(waId: String, channel: Platform = Platform.WHATSAPP): Conversation? {
        val doc = collection.find(scoped(channel, Filters.eq("waId", waId))).firstOrNull()
        return doc?.toConversation()
    }

    suspend fun findById(id: ObjectId): Conversation? {
        val doc = collection.find(scoped(Filters.eq("_id", id))).firstOrNull()
        return doc?.toConversation()
    }

    suspend fun findOrCreate(userId: ObjectId, waId: String, channel: Platform = Platform.WHATSAPP): Conversation {
        val existing = findByWaId(waId, channel)
        if (existing != null) {
            return existing
        }

        val now = SystemClock.now()
        val conversation = Conversation(
            tenantId = tenantId,
            userId = userId,
            channel = channel,
            waId = waId,
            lastMessageAt = now,
            createdAt = now,
        )
        collection.insertOne(conversation.toDocument())
        log.info("Created new conversation: waId={}", waId)
        return conversation
    }

    suspend fun bumpActivity(convoId: ObjectId, tokenUsage: TokenUsage? = null) {
        val now = SystemClock.now()
        val updates = mutableListOf(
            Updates.set("lastMessageAt", now.toDate()),
            Updates.inc("messageCount", 1),
        )

        tokenUsage?.let {
            updates.add(Updates.inc("tokenBudget.used", it.prompt + it.completion))
        }

        collection.updateOne(
            scoped(Filters.eq("_id", convoId)),
            Updates.combine(updates)
        )
    }

    /** [userIds] are contacts whose name matched [query]; their conversations match too. */
    suspend fun list(query: String? = null, limit: Int = 100, userIds: Collection<ObjectId> = emptyList()): List<Conversation> {
        val term = query?.trim()?.takeIf { it.isNotBlank() }
        val filter = if (term == null) {
            Filters.eq("tenantId", tenantId)
        } else {
            val byWaId = Filters.regex("waId", ".*${Regex.escape(term)}.*", "i")
            scoped(if (userIds.isEmpty()) byWaId else Filters.or(byWaId, Filters.`in`("userId", userIds)))
        }
        return collection.find(filter).sort(Document("lastMessageAt", -1)).limit(limit).toList().map { it.toConversation() }
    }

    suspend fun recordInbound(convoId: ObjectId, at: kotlinx.datetime.Instant = SystemClock.now()) {
        collection.updateOne(
            scoped(Filters.eq("_id", convoId)),
            Updates.combine(Updates.set("lastInboundAt", at.toDate()), Updates.inc("unreadCount", 1)),
        )
    }

    suspend fun markRead(convoId: ObjectId): Conversation? {
        collection.updateOne(scoped(Filters.eq("_id", convoId)), Updates.set("unreadCount", 0))
        return findById(convoId)
    }

    private fun Document.toConversation(): Conversation {
        return Conversation(
            id = getObjectId("_id"),
            tenantId = getObjectId("tenantId"),
            userId = getObjectId("userId"),
            channel = getPlatform(),
            waId = getString("waId")!!,
            state = ConversationState.valueOf(getString("state") ?: "ACTIVE"),
            summary = getString("summary"),
            summaryUpdatedAt = getInstantOrNull("summaryUpdatedAt"),
            lastMessageAt = getInstant("lastMessageAt"),
            messageCount = getInteger("messageCount") ?: 0,
            systemPromptVersion = getString("systemPromptVersion") ?: "v1",
            autoReplyEnabled = getBoolean("autoReplyEnabled") ?: true,
            createdAt = getInstant("createdAt"),
            lastInboundAt = getInstantOrNull("lastInboundAt"),
            unreadCount = getInteger("unreadCount") ?: 0,
            autoReplyPausedAt = getInstantOrNull("autoReplyPausedAt"),
            autoReplyPausedBy = getString("autoReplyPausedBy"),
        )
    }

    private fun Conversation.toDocument(): Document {
        return Document("_id", id)
            .append("tenantId", tenantId)
            .append("userId", userId)
            .append("channel", channel.name)
            .append("waId", waId)
            .append("state", state.name)
            .append("summary", summary)
            .append("summaryUpdatedAt", summaryUpdatedAt?.toDate())
            .append("lastMessageAt", lastMessageAt.toDate())
            .append("messageCount", messageCount)
            .append("systemPromptVersion", systemPromptVersion)
            .append("autoReplyEnabled", autoReplyEnabled)
            .append("createdAt", createdAt.toDate())
            .append("lastInboundAt", lastInboundAt?.toDate())
            .append("unreadCount", unreadCount)
            .append("autoReplyPausedAt", autoReplyPausedAt?.toDate())
            .append("autoReplyPausedBy", autoReplyPausedBy)
    }

    suspend fun setAutoReplyEnabled(convoId: ObjectId, enabled: Boolean, pausedBy: String? = null): Conversation? {
        val update = if (enabled) {
            Updates.combine(Updates.set("autoReplyEnabled", true), Updates.unset("autoReplyPausedAt"), Updates.unset("autoReplyPausedBy"))
        } else {
            Updates.combine(
                Updates.set("autoReplyEnabled", false),
                Updates.set("autoReplyPausedAt", SystemClock.now().toDate()),
                Updates.set("autoReplyPausedBy", pausedBy),
            )
        }
        collection.updateOne(scoped(Filters.eq("_id", convoId)), update)
        return findById(convoId)
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
    private fun scoped(channel: Platform, filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("channel", channel.name), filter)
}

class MessageRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("messages")
    private val log = LoggerFactory.getLogger("MessageRepository")

    suspend fun insert(message: Message): Message {
        val doc = message.toDocument()
        collection.insertOne(doc)
        log.debug("Inserted message: id={}, role={}", message.id, message.role)
        return message
    }

    /** Like [insert], but returns false instead of throwing when this tenant already stored the same `waMessageId`. */
    suspend fun insertIfAbsent(message: Message): Boolean = try {
        insert(message)
        true
    } catch (e: MongoWriteException) {
        if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
        log.info("Message already stored: waMessageId={}", message.waMessageId)
        false
    }

    suspend fun lastN(conversationId: ObjectId, n: Int): List<Message> {
        return collection.find(scoped(Filters.eq("conversationId", conversationId)))
            .sort(Document("createdAt", -1))
            .limit(n)
            .toList()
            .map { it.toMessage() }
            .reversed()
    }

    suspend fun lastNByWaId(waId: String, n: Int, channel: Platform = Platform.WHATSAPP): List<Message> {
        return collection.find(scoped(channel, Filters.eq("waId", waId)))
            .sort(Document("createdAt", -1))
            .limit(n)
            .toList()
            .map { it.toMessage() }
            .reversed()
    }

    suspend fun lastByConversationIds(ids: Collection<ObjectId>): Map<ObjectId, Message> {
        if (ids.isEmpty()) return emptyMap()
        val pipeline = listOf(
            Document("\$match", Document("tenantId", tenantId).append("conversationId", Document("\$in", ids.toList()))),
            Document("\$sort", Document("createdAt", -1)),
            Document("\$group", Document("_id", "\$conversationId").append("doc", Document("\$first", "\$\$ROOT"))),
        )
        return collection.aggregate<Document>(pipeline).toList().mapNotNull { group ->
            val doc = group.get("doc", Document::class.java) ?: return@mapNotNull null
            doc.getObjectId("conversationId") to doc.toMessage()
        }.toMap()
    }

    /** The newest [limit] messages of a conversation, oldest first. */
    suspend fun threadByConversation(conversationId: ObjectId, limit: Int = 200): List<Message> {
        return collection.find(scoped(Filters.eq("conversationId", conversationId)))
            .sort(Document("createdAt", -1))
            .limit(limit)
            .toList()
            .map { it.toMessage() }
            .reversed()
    }

    /** Messages created, or whose delivery status changed, after [since]; oldest first. */
    suspend fun changedSince(conversationId: ObjectId, since: kotlinx.datetime.Instant, limit: Int = 200): List<Message> {
        val after = since.toDate()
        return collection.find(
            scoped(
                Filters.and(
                    Filters.eq("conversationId", conversationId),
                    Filters.or(Filters.gt("createdAt", after), Filters.gt("statusAt", after)),
                )
            )
        )
            .sort(Document("createdAt", 1))
            .limit(limit)
            .toList()
            .map { it.toMessage() }
    }

    suspend fun findById(id: ObjectId): Message? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toMessage()

    /** When each conversation's customer last wrote; for conversations stored before `lastInboundAt` existed. */
    suspend fun lastInboundByConversationIds(ids: Collection<ObjectId>): Map<ObjectId, kotlinx.datetime.Instant> {
        if (ids.isEmpty()) return emptyMap()
        val pipeline = listOf(
            Document("\$match", Document("tenantId", tenantId).append("conversationId", Document("\$in", ids.toList())).append("role", UserRole.USER.name)),
            Document("\$group", Document("_id", "\$conversationId").append("at", Document("\$max", "\$createdAt"))),
        )
        return collection.aggregate<Document>(pipeline).toList().associate { group ->
            group.getObjectId("_id") to kotlinx.datetime.Instant.fromEpochMilliseconds(group.get("at", Date::class.java).time)
        }
    }

    suspend fun lastInboundAt(conversationId: ObjectId): kotlinx.datetime.Instant? =
        collection.find(scoped(Filters.and(Filters.eq("conversationId", conversationId), Filters.eq("role", UserRole.USER.name))))
            .sort(Document("createdAt", -1))
            .limit(1)
            .firstOrNull()
            ?.getInstant("createdAt")

    /**
     * Applies a WhatsApp status webhook to the outbound message with that id. Webhooks arrive late,
     * out of order or twice, so a status only moves forward (SENT → DELIVERED → READ) and FAILED sticks.
     * Returns null when no message changed.
     */
    suspend fun applyDeliveryStatus(waMessageId: String, status: MessageStatus, errorCode: Int? = null, errorText: String? = null): Message? {
        val from = when (status) {
            MessageStatus.DELIVERED -> listOf(MessageStatus.SENT)
            MessageStatus.READ, MessageStatus.FAILED -> listOf(MessageStatus.SENT, MessageStatus.DELIVERED)
            else -> return null
        }
        val updates = mutableListOf(Updates.set("status", status.name), Updates.set("statusAt", SystemClock.now().toDate()))
        if (status == MessageStatus.FAILED) {
            updates += Updates.set("errorCode", errorCode)
            updates += Updates.set("errorText", errorText)
        }
        return collection.findOneAndUpdate(
            scoped(Filters.and(Filters.eq("waMessageId", waMessageId), Filters.`in`("status", from.map { it.name }))),
            Updates.combine(updates),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toMessage()
    }

    /** A stored reply whose send call failed; it has no WhatsApp id, so no status webhook will change it. */
    suspend fun markSendFailed(id: ObjectId, errorCode: Int?, errorText: String?): Message? =
        collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(
                Updates.set("status", MessageStatus.FAILED.name),
                Updates.set("statusAt", SystemClock.now().toDate()),
                Updates.set("errorCode", errorCode),
                Updates.set("errorText", errorText),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toMessage()

    /**
     * A failed message sent again: it takes the new WhatsApp id and starts over as SENT. Without an id
     * no status webhook can follow, so it is marked DELIVERED like other untracked outbound messages.
     */
    suspend fun markResent(id: ObjectId, waMessageId: String?): Message? =
        collection.findOneAndUpdate(
            scoped(Filters.and(Filters.eq("_id", id), Filters.eq("status", MessageStatus.FAILED.name))),
            Updates.combine(
                if (waMessageId != null) Updates.set("waMessageId", waMessageId) else Updates.unset("waMessageId"),
                Updates.set("status", (if (waMessageId != null) MessageStatus.SENT else MessageStatus.DELIVERED).name),
                Updates.set("statusAt", SystemClock.now().toDate()),
                Updates.unset("errorCode"),
                Updates.unset("errorText"),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toMessage()

    private fun Document.toMessage(): Message {
        val contentDoc = get("content", Document::class.java)!!
        val content = when (contentDoc.getString("type")) {
            "text" -> MessageContent.Text(contentDoc.getString("text")!!)
            "image" -> MessageContent.Image(contentDoc.getString("mediaId")!!, contentDoc.getString("caption"))
            "audio" -> MessageContent.Audio(contentDoc.getString("mediaId")!!, contentDoc.getString("transcription"))
            "document" -> MessageContent.Document(contentDoc.getString("mediaId")!!, contentDoc.getString("fileName"), contentDoc.getString("caption"))
            "video" -> MessageContent.Video(contentDoc.getString("mediaId")!!, contentDoc.getString("caption"))
            "template" -> MessageContent.Template(
                contentDoc.getString("name").orEmpty(),
                contentDoc.getString("language").orEmpty(),
                contentDoc.getString("text").orEmpty(),
            )
            else -> MessageContent.Text("")
        }

        val tokensDoc = get("tokens", Document::class.java)
        val tokens = tokensDoc?.let {
            TokenUsage(
                prompt = it.getInteger("prompt") ?: 0,
                completion = it.getInteger("completion") ?: 0,
            )
        }

        return Message(
            id = getObjectId("_id"),
            tenantId = getObjectId("tenantId"),
            conversationId = getObjectId("conversationId"),
            channel = getPlatform(),
            waId = getString("waId")!!,
            role = UserRole.valueOf(getString("role")!!),
            waMessageId = getString("waMessageId"),
            content = content,
            tokens = tokens,
            model = getString("model"),
            costUsd = getDouble("costUsd") ?: 0.0,
            status = MessageStatus.valueOf(getString("status") ?: "RECEIVED"),
            createdAt = getInstant("createdAt"),
            author = getString("author")?.let { name -> MessageAuthor.entries.firstOrNull { it.name == name } },
            agentUserId = getString("agentUserId"),
            agentName = getString("agentName"),
            statusAt = getInstantOrNull("statusAt"),
            errorCode = getInteger("errorCode"),
            errorText = getString("errorText"),
        )
    }

    private fun Message.toDocument(): Document {
        val contentDoc = when (content) {
            is MessageContent.Text -> Document("type", "text").append("text", content.body)
            is MessageContent.Image -> Document("type", "image").append("mediaId", content.mediaId).append("caption", content.caption)
            is MessageContent.Audio -> Document("type", "audio").append("mediaId", content.mediaId).append("transcription", content.transcription)
            is MessageContent.Document -> Document("type", "document").append("mediaId", content.mediaId).append("fileName", content.fileName).append("caption", content.caption)
            is MessageContent.Video -> Document("type", "video").append("mediaId", content.mediaId).append("caption", content.caption)
            is MessageContent.Template -> Document("type", "template")
                .append("name", content.name)
                .append("language", content.language)
                .append("text", content.body)
        }

        val tokensDoc = tokens?.let {
            Document("prompt", it.prompt).append("completion", it.completion)
        }

        return Document("_id", id)
            .append("tenantId", tenantId)
            .append("conversationId", conversationId)
            .append("channel", channel.name)
            .append("waId", waId)
            .append("role", role.name)
            .append("waMessageId", waMessageId)
            .append("content", contentDoc)
            .append("tokens", tokensDoc)
            .append("model", model)
            .append("costUsd", costUsd)
            .append("status", status.name)
            .append("createdAt", createdAt.toDate())
            .append("author", author?.name)
            .append("agentUserId", agentUserId)
            .append("agentName", agentName)
            .append("statusAt", statusAt?.toDate())
            .append("errorCode", errorCode)
            .append("errorText", errorText)
    }

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
    private fun scoped(channel: Platform, filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("channel", channel.name), filter)
}

private fun Document.getPlatform(): Platform = getString("channel")?.let { Platform.valueOf(it) } ?: Platform.WHATSAPP

private fun Document.getObjectId(field: String): ObjectId {
    return get(field, ObjectId::class.java)!!
}

private fun Document.getInteger(field: String): Int? {
    return get(field, Int::class.java)
}

private fun Document.getDouble(field: String): Double? {
    val value = get(field, Double::class.java)
    return value ?: get(field, Int::class.java)?.toDouble()
}

private fun Document.getInstant(field: String): kotlinx.datetime.Instant {
    val date = get(field, Date::class.java)!!
    return kotlinx.datetime.Instant.fromEpochMilliseconds(date.time)
}

private fun Document.getInstantOrNull(field: String): kotlinx.datetime.Instant? {
    val date = get(field, Date::class.java)
    return date?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it.time) }
}

private fun kotlinx.datetime.Instant.toDate(): Date {
    return Date(this.toEpochMilliseconds())
}
