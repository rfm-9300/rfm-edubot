package com.rfm.edubot.flows

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

/**
 * Where a customer is in a flow: the step that waits for their reply, what they answered so far ([vars], keyed by
 * the question's `key`) and how many replies in a row weren't understood ([misses]).
 */
data class FlowSession(
    val flowId: String,
    val stepId: String,
    val vars: Map<String, String> = emptyMap(),
    val misses: Int = 0,
    val updatedAt: Instant,
) {
    /** Idle too long to carry on: a later message is a new beginning. */
    fun expired(now: Instant): Boolean = now.toEpochMilliseconds() - updatedAt.toEpochMilliseconds() > IDLE_MILLIS

    companion object {
        const val IDLE_MILLIS = 12L * 60 * 60 * 1000
    }
}

/** What a company's flows know about a conversation between messages; the engine only reads and returns, the pipeline stores. */
interface FlowSessions {
    suspend fun find(conversationId: ObjectId): FlowSession?
    suspend fun save(conversationId: ObjectId, session: FlowSession)
    suspend fun clear(conversationId: ObjectId)
}

/** `chat_flow_sessions`: one document per conversation, dropped by Mongo a week after its last message. */
class FlowSessionRepository(mongo: MongoModule, private val tenantId: ObjectId) : FlowSessions {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    override suspend fun find(conversationId: ObjectId): FlowSession? {
        val doc = collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("conversationId", conversationId))).firstOrNull() ?: return null
        val vars = (doc.get("vars") as? Document).orEmpty().mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
        return FlowSession(
            flowId = doc.getString("flowId") ?: return null,
            stepId = doc.getString("stepId") ?: return null,
            vars = vars,
            misses = doc.getInteger("misses") ?: 0,
            updatedAt = Instant.fromEpochMilliseconds((doc.getDate("updatedAt") ?: Date(0)).time),
        )
    }

    override suspend fun save(conversationId: ObjectId, session: FlowSession) {
        val updated = Date(session.updatedAt.toEpochMilliseconds())
        collection.updateOne(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("conversationId", conversationId)),
            Updates.combine(
                Updates.set("flowId", session.flowId),
                Updates.set("stepId", session.stepId),
                Updates.set("vars", Document(session.vars)),
                Updates.set("misses", session.misses),
                Updates.set("updatedAt", updated),
                Updates.set("expiresAt", Date(updated.time + KEEP_MILLIS)),
            ),
            UpdateOptions().upsert(true),
        )
    }

    override suspend fun clear(conversationId: ObjectId) {
        collection.deleteOne(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("conversationId", conversationId)))
    }

    companion object {
        const val COLLECTION = "chat_flow_sessions"
        private val KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

/** Not stored: for a test chat whose state travels with the page, and for tests. */
class MemoryFlowSessions : FlowSessions {
    private val sessions = mutableMapOf<ObjectId, FlowSession>()
    override suspend fun find(conversationId: ObjectId) = sessions[conversationId]
    override suspend fun save(conversationId: ObjectId, session: FlowSession) { sessions[conversationId] = session }
    override suspend fun clear(conversationId: ObjectId) { sessions.remove(conversationId) }
}
