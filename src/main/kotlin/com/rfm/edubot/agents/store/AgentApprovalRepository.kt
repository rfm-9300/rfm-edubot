package com.rfm.edubot.agents.store

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.model.Approvers
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.BsonJson
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** Actions waiting for a person (`agent_approvals`). Deciding is an atomic PENDING → decided claim. */
class AgentApprovalRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun insert(approval: AgentApproval): AgentApproval {
        collection.insertOne(approval.toDocument())
        return approval
    }

    suspend fun findById(tenantId: ObjectId, id: ObjectId): AgentApproval? =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id))).firstOrNull()?.toApproval()

    suspend fun list(tenantId: ObjectId, status: ApprovalStatus? = ApprovalStatus.PENDING, agentId: ObjectId? = null, limit: Int = 100): List<AgentApproval> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId), Filters.ne("dryRun", true))
        status?.let { filters += Filters.eq("status", it.name) }
        agentId?.let { filters += Filters.eq("agentId", it) }
        return collection.find(Filters.and(filters)).sort(Document("createdAt", -1)).limit(limit.coerceIn(1, 500)).toList().map { it.toApproval() }
    }

    suspend fun countPending(tenantId: ObjectId): Long =
        collection.countDocuments(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("status", ApprovalStatus.PENDING.name), Filters.ne("dryRun", true)))

    suspend fun forRun(runId: ObjectId): List<AgentApproval> =
        collection.find(Filters.eq("runId", runId)).sort(Document("createdAt", 1)).toList().map { it.toApproval() }

    /**
     * Approves a pending approval, with the person's edits in [editedInput] when they changed it.
     * Null when it was already decided or expired, so a double click can't run an action twice.
     */
    suspend fun approve(tenantId: ObjectId, id: ObjectId, by: String?, byName: String?, editedInput: JsonObject?): AgentApproval? {
        val updates = mutableListOf(
            Updates.set("status", ApprovalStatus.APPROVED.name),
            Updates.set("decidedAt", clock().toDate()),
            Updates.set("decidedBy", by),
            Updates.set("decidedByName", byName),
            Updates.set("edited", editedInput != null),
        )
        editedInput?.let { updates += Updates.set("input", BsonJson.toDocument(it)) }
        return decide(tenantId, id, Updates.combine(updates))
    }

    suspend fun reject(tenantId: ObjectId, id: ObjectId, by: String?, byName: String?, reason: String?): AgentApproval? =
        decide(
            tenantId,
            id,
            Updates.combine(
                Updates.set("status", ApprovalStatus.REJECTED.name),
                Updates.set("decidedAt", clock().toDate()),
                Updates.set("decidedBy", by),
                Updates.set("decidedByName", byName),
                Updates.set("reason", reason?.take(500)),
            ),
        )

    /** Expires one overdue approval; the scheduler calls it until none is left. */
    suspend fun expireNext(now: Instant): AgentApproval? =
        collection.findOneAndUpdate(
            Filters.and(Filters.eq("status", ApprovalStatus.PENDING.name), Filters.lte("expiresAt", now.toDate())),
            Updates.combine(Updates.set("status", ApprovalStatus.EXPIRED.name), Updates.set("decidedAt", now.toDate())),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toApproval()

    suspend fun cancelForRun(runId: ObjectId) {
        collection.updateMany(
            Filters.and(Filters.eq("runId", runId), Filters.eq("status", ApprovalStatus.PENDING.name)),
            Updates.combine(Updates.set("status", ApprovalStatus.CANCELLED.name), Updates.set("decidedAt", clock().toDate())),
        )
    }

    private suspend fun decide(tenantId: ObjectId, id: ObjectId, update: Bson): AgentApproval? =
        collection.findOneAndUpdate(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id), Filters.eq("status", ApprovalStatus.PENDING.name)),
            update,
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toApproval()

    private fun AgentApproval.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("agentId", agentId)
        .append("agentName", agentName)
        .append("runId", runId)
        .append("stepId", stepId)
        .append("action", action)
        .append("input", BsonJson.toDocument(input))
        .append("preview", AgentJson.toDocument(ActionPreview.serializer(), preview))
        .append("subject", subject?.let { Document("type", it.type).append("id", it.id) })
        .append("subjectLabel", subjectLabel)
        .append("approvers", approvers.name)
        .append("status", status.name)
        .append("createdAt", createdAt.toDate())
        .append("expiresAt", expiresAt.toDate())
        .append("decidedAt", decidedAt?.toDate())
        .append("decidedBy", decidedBy)
        .append("decidedByName", decidedByName)
        .append("edited", edited)
        .append("reason", reason)
        .append("dryRun", dryRun)

    private fun Document.toApproval() = AgentApproval(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        agentId = getObjectId("agentId"),
        agentName = getString("agentName").orEmpty(),
        runId = getObjectId("runId"),
        stepId = getString("stepId").orEmpty(),
        action = getString("action").orEmpty(),
        input = BsonJson.toJsonObject(get("input", Document::class.java)),
        preview = AgentJson.fromDocument(ActionPreview.serializer(), get("preview", Document::class.java), ActionPreview(kind = "generic")),
        subject = get("subject", Document::class.java)?.let { SubjectRef(it.getString("type").orEmpty(), it.getString("id").orEmpty()) },
        subjectLabel = getString("subjectLabel"),
        approvers = runCatching { Approvers.valueOf(getString("approvers")) }.getOrDefault(Approvers.ANY_MEMBER),
        status = runCatching { ApprovalStatus.valueOf(getString("status")) }.getOrDefault(ApprovalStatus.PENDING),
        createdAt = instant("createdAt") ?: Instant.fromEpochMilliseconds(0),
        expiresAt = instant("expiresAt") ?: Instant.fromEpochMilliseconds(0),
        decidedAt = instant("decidedAt"),
        decidedBy = getString("decidedBy"),
        decidedByName = getString("decidedByName"),
        edited = getBoolean("edited") ?: false,
        reason = getString("reason"),
        dryRun = getBoolean("dryRun") ?: false,
    )

    companion object {
        const val COLLECTION = "agent_approvals"
    }
}
