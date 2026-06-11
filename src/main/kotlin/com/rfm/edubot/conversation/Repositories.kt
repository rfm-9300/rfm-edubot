package com.rfm.edubot.conversation

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.ConversationState
import com.rfm.edubot.conversation.model.Message
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

    suspend fun list(query: String? = null, limit: Int = 100): List<Conversation> {
        val term = query?.trim()?.takeIf { it.isNotBlank() }
        val filter = if (term == null) {
            Filters.eq("tenantId", tenantId)
        } else {
            scoped(Filters.regex("waId", ".*${Regex.escape(term)}.*", "i"))
        }
        return collection.find(filter).sort(Document("lastMessageAt", -1)).limit(limit).toList().map { it.toConversation() }
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
            createdAt = getInstant("createdAt"),
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
            .append("createdAt", createdAt.toDate())
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

    suspend fun threadByConversation(conversationId: ObjectId, limit: Int = 200): List<Message> {
        return collection.find(scoped(Filters.eq("conversationId", conversationId)))
            .sort(Document("createdAt", 1))
            .limit(limit)
            .toList()
            .map { it.toMessage() }
    }

    private fun Document.toMessage(): Message {
        val contentDoc = get("content", Document::class.java)!!
        val content = when (contentDoc.getString("type")) {
            "text" -> MessageContent.Text(contentDoc.getString("text")!!)
            "image" -> MessageContent.Image(contentDoc.getString("mediaId")!!, contentDoc.getString("caption"))
            "audio" -> MessageContent.Audio(contentDoc.getString("mediaId")!!, contentDoc.getString("transcription"))
            "document" -> MessageContent.Document(contentDoc.getString("mediaId")!!)
            "video" -> MessageContent.Video(contentDoc.getString("mediaId")!!)
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
        )
    }

    private fun Message.toDocument(): Document {
        val contentDoc = when (content) {
            is MessageContent.Text -> Document("type", "text").append("text", content.body)
            is MessageContent.Image -> Document("type", "image").append("mediaId", content.mediaId).append("caption", content.caption)
            is MessageContent.Audio -> Document("type", "audio").append("mediaId", content.mediaId).append("transcription", content.transcription)
            is MessageContent.Document -> Document("type", "document").append("mediaId", content.mediaId)
            is MessageContent.Video -> Document("type", "video").append("mediaId", content.mediaId)
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
