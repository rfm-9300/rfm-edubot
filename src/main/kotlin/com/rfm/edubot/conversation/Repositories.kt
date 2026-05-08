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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.Date

class UserRepository(mongoModule: MongoModule) {
    private val collection = mongoModule.client.getDatabase("wabot").getCollection<Document>("users")
    private val log = LoggerFactory.getLogger("UserRepository")

    suspend fun findByWaId(waId: String): User? {
        val doc = collection.find(Filters.eq("waId", waId)).firstOrNull()
        return doc?.toUser()
    }

    suspend fun findOrCreate(waId: String, displayName: String? = null): User {
        val existing = findByWaId(waId)
        if (existing != null) {
            val now = SystemClock.now()
            collection.updateOne(
                Filters.eq("waId", waId),
                Updates.combine(
                    Updates.set("lastSeenAt", now.toDate()),
                    Updates.set("displayName", displayName ?: existing.displayName)
                )
            )
            return existing.copy(lastSeenAt = now, displayName = displayName ?: existing.displayName)
        }

        val now = SystemClock.now()
        val user = User(
            waId = waId,
            displayName = displayName,
            createdAt = now,
            lastSeenAt = now,
        )
        collection.insertOne(user.toDocument())
        log.info("Created new user: waId={}", waId)
        return user
    }

    private fun Document.toUser(): User {
        return User(
            id = getObjectId("_id"),
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

        return Document()
            .append("waId", waId)
            .append("displayName", displayName)
            .append("locale", locale)
            .append("status", status.name)
            .append("createdAt", createdAt.toDate())
            .append("lastSeenAt", lastSeenAt.toDate())
            .append("metadata", metadataDoc)
    }
}

class ConversationRepository(mongoModule: MongoModule) {
    private val collection = mongoModule.client.getDatabase("wabot").getCollection<Document>("conversations")
    private val log = LoggerFactory.getLogger("ConversationRepository")

    suspend fun findByWaId(waId: String): Conversation? {
        val doc = collection.find(Filters.eq("waId", waId)).firstOrNull()
        return doc?.toConversation()
    }

    suspend fun findById(id: ObjectId): Conversation? {
        val doc = collection.find(Filters.eq("_id", id)).firstOrNull()
        return doc?.toConversation()
    }

    suspend fun findOrCreate(userId: ObjectId, waId: String): Conversation {
        val existing = findByWaId(waId)
        if (existing != null) {
            return existing
        }

        val now = SystemClock.now()
        val conversation = Conversation(
            userId = userId,
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
            Filters.eq("_id", convoId),
            Updates.combine(updates)
        )
    }

    private fun Document.toConversation(): Conversation {
        return Conversation(
            id = getObjectId("_id"),
            userId = getObjectId("userId"),
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
        return Document()
            .append("userId", userId)
            .append("waId", waId)
            .append("state", state.name)
            .append("summary", summary)
            .append("summaryUpdatedAt", summaryUpdatedAt?.toDate())
            .append("lastMessageAt", lastMessageAt.toDate())
            .append("messageCount", messageCount)
            .append("systemPromptVersion", systemPromptVersion)
            .append("createdAt", createdAt.toDate())
    }
}

class MessageRepository(mongoModule: MongoModule) {
    private val collection = mongoModule.client.getDatabase("wabot").getCollection<Document>("messages")
    private val log = LoggerFactory.getLogger("MessageRepository")

    suspend fun insert(message: Message): Message {
        val doc = message.toDocument()
        collection.insertOne(doc)
        log.debug("Inserted message: id={}, role={}", message.id, message.role)
        return message
    }

    suspend fun lastN(conversationId: ObjectId, n: Int): List<Message> {
        return collection.find(Filters.eq("conversationId", conversationId))
            .sort(Document("createdAt", -1))
            .limit(n)
            .toList()
            .map { it.toMessage() }
            .reversed()
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
            conversationId = getObjectId("conversationId"),
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

        return Document()
            .append("conversationId", conversationId)
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
}

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
