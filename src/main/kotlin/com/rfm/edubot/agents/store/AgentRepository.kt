package com.rfm.edubot.agents.store

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentKind
import com.rfm.edubot.agents.model.AgentStats
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.BsonJson
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/**
 * Agent definitions (`agents`). Tenant-facing reads always pass the company's tenantId; the runtime's
 * cross-company reads (due schedules, sweeps) are the only unscoped queries.
 */
class AgentRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun insert(agent: Agent): Agent {
        collection.insertOne(agent.toDocument())
        return agent
    }

    suspend fun findById(tenantId: ObjectId, id: ObjectId): Agent? =
        collection.find(scoped(tenantId, Filters.eq("_id", id))).firstOrNull()?.toAgent()

    suspend fun list(tenantId: ObjectId, includeArchived: Boolean = false): List<Agent> {
        val filter = if (includeArchived) Filters.eq("tenantId", tenantId) else scoped(tenantId, Filters.ne("status", AgentStatus.ARCHIVED.name))
        return collection.find(filter).sort(Document("updatedAt", -1)).limit(500).toList().map { it.toAgent() }
    }

    suspend fun countActive(tenantId: ObjectId): Long =
        collection.countDocuments(scoped(tenantId, Filters.eq("status", AgentStatus.ACTIVE.name)))

    /** Replaces the editable parts; the version goes up so runs can tell which definition they started with. */
    suspend fun update(
        tenantId: ObjectId,
        id: ObjectId,
        name: String,
        description: String?,
        icon: String?,
        kind: AgentKind,
        definition: AgentDefinition,
        templateParams: JsonObject?,
    ): Agent? = collection.findOneAndUpdate(
        scoped(tenantId, Filters.eq("_id", id)),
        Updates.combine(
            Updates.set("name", name),
            Updates.set("description", description),
            Updates.set("icon", icon),
            Updates.set("kind", kind.name),
            Updates.set("definition", AgentJson.toDocument(AgentDefinition.serializer(), definition)),
            Updates.set("triggerTypes", definition.triggerTypes()),
            Updates.set("eventTypes", definition.eventTypes()),
            Updates.set("templateParams", templateParams?.let { BsonJson.toDocument(it) }),
            Updates.set("stats.approvalsInARow", 0),
            Updates.inc("version", 1),
            Updates.set("updatedAt", clock().toDate()),
        ),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
    )?.toAgent()

    suspend fun setStatus(tenantId: ObjectId, id: ObjectId, status: AgentStatus, pausedReason: String? = null): Agent? {
        val updates = mutableListOf<Bson>(Updates.set("status", status.name), Updates.set("updatedAt", clock().toDate()))
        updates += if (pausedReason != null) Updates.set("pausedReason", pausedReason) else Updates.unset("pausedReason")
        if (status == AgentStatus.ACTIVE) updates += Updates.set("stats.consecutiveFailures", 0)
        if (status != AgentStatus.ACTIVE) {
            updates += Updates.unset("nextFireAt")
            updates += Updates.set("scheduleState", Document())
        }
        return collection.findOneAndUpdate(
            scoped(tenantId, Filters.eq("_id", id)),
            Updates.combine(updates),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toAgent()
    }

    /** Stores the next fire time of each schedule trigger (on activation and after an edit). */
    suspend fun setSchedule(tenantId: ObjectId, id: ObjectId, state: Map<String, Instant>) {
        collection.updateOne(scoped(tenantId, Filters.eq("_id", id)), scheduleUpdate(state))
    }

    /**
     * Moves the schedule on from [expectedNext]. Only one tick can do that, so a true answer means the
     * fires that were due belong to this tick.
     */
    suspend fun advanceSchedule(tenantId: ObjectId, id: ObjectId, expectedNext: Instant, state: Map<String, Instant>): Boolean =
        collection.updateOne(
            scoped(tenantId, Filters.and(Filters.eq("_id", id), Filters.eq("nextFireAt", expectedNext.toDate()))),
            scheduleUpdate(state),
        ).modifiedCount > 0

    private fun scheduleUpdate(state: Map<String, Instant>): Bson {
        val next = state.values.minOrNull()
        return Updates.combine(
            Updates.set("scheduleState", Document(state.mapValues { it.value.toDate() })),
            if (next != null) Updates.set("nextFireAt", next.toDate()) else Updates.unset("nextFireAt"),
        )
    }

    suspend fun delete(tenantId: ObjectId, id: ObjectId): Boolean =
        collection.deleteOne(scoped(tenantId, Filters.eq("_id", id))).deletedCount > 0

    /** A company's active agents, for the dispatcher's trigger cache. */
    suspend fun activeFor(tenantId: ObjectId): List<Agent> =
        collection.find(scoped(tenantId, Filters.eq("status", AgentStatus.ACTIVE.name))).toList().map { it.toAgent() }

    /** Active agents of every company with a trigger of [type], for the scheduler's sweeps. */
    suspend fun activeWithTriggerType(type: String): List<Agent> =
        collection.find(Filters.and(Filters.eq("status", AgentStatus.ACTIVE.name), Filters.eq("triggerTypes", type))).toList().map { it.toAgent() }

    suspend fun dueSchedules(now: Instant, limit: Int = 200): List<Agent> =
        collection.find(Filters.and(Filters.eq("status", AgentStatus.ACTIVE.name), Filters.lte("nextFireAt", now.toDate())))
            .sort(Document("nextFireAt", 1)).limit(limit).toList().map { it.toAgent() }

    /** Companies with at least one active agent, so the dispatcher can warm its caches at boot. */
    suspend fun tenantsWithActiveAgents(): List<ObjectId> =
        collection.distinct<ObjectId>("tenantId", Filters.eq("status", AgentStatus.ACTIVE.name)).toList()

    suspend fun recordRun(tenantId: ObjectId, id: ObjectId, succeeded: Boolean): Agent? {
        val now = clock()
        return collection.findOneAndUpdate(
            scoped(tenantId, Filters.eq("_id", id)),
            Updates.combine(
                Updates.inc("stats.runs", 1L),
                Updates.inc(if (succeeded) "stats.succeeded" else "stats.failed", 1L),
                if (succeeded) Updates.set("stats.consecutiveFailures", 0) else Updates.inc("stats.consecutiveFailures", 1),
                Updates.set("stats.lastRunAt", now.toDate()),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toAgent()
    }

    /** Approvals without edits in a row suggest the agent can run on its own; an edit or rejection starts over. */
    suspend fun recordApproval(tenantId: ObjectId, id: ObjectId, cleanApproval: Boolean) {
        collection.updateOne(
            scoped(tenantId, Filters.eq("_id", id)),
            if (cleanApproval) Updates.inc("stats.approvalsInARow", 1) else Updates.set("stats.approvalsInARow", 0),
        )
    }

    private fun scoped(tenantId: ObjectId, filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    private fun Agent.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("name", name)
        .append("description", description)
        .append("icon", icon)
        .append("kind", kind.name)
        .append("templateKey", templateKey)
        .append("templateParams", templateParams?.let { BsonJson.toDocument(it) })
        .append("status", status.name)
        .append("definition", AgentJson.toDocument(AgentDefinition.serializer(), definition))
        .append("triggerTypes", definition.triggerTypes())
        .append("eventTypes", definition.eventTypes())
        .append("scheduleState", Document(scheduleState.mapValues { it.value.toDate() }))
        .append("nextFireAt", scheduleState.values.minOrNull()?.toDate())
        .append("version", version)
        .append("createdBy", createdBy)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())
        .append("stats", stats.toDocument())
        .append("pausedReason", pausedReason)

    private fun AgentStats.toDocument() = Document("runs", runs)
        .append("succeeded", succeeded)
        .append("failed", failed)
        .append("lastRunAt", lastRunAt?.toDate())
        .append("consecutiveFailures", consecutiveFailures)
        .append("approvalsInARow", approvalsInARow)

    private fun Document.toStats(): AgentStats = AgentStats(
        runs = (get("runs") as? Number)?.toLong() ?: 0,
        succeeded = (get("succeeded") as? Number)?.toLong() ?: 0,
        failed = (get("failed") as? Number)?.toLong() ?: 0,
        lastRunAt = instant("lastRunAt"),
        consecutiveFailures = (get("consecutiveFailures") as? Number)?.toInt() ?: 0,
        approvalsInARow = (get("approvalsInARow") as? Number)?.toInt() ?: 0,
    )

    private fun Document.toAgent() = Agent(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        name = getString("name").orEmpty(),
        description = getString("description"),
        icon = getString("icon"),
        kind = runCatching { AgentKind.valueOf(getString("kind")) }.getOrDefault(AgentKind.WORKFLOW),
        templateKey = getString("templateKey"),
        templateParams = get("templateParams", Document::class.java)?.let { BsonJson.toJsonObject(it) },
        status = runCatching { AgentStatus.valueOf(getString("status")) }.getOrDefault(AgentStatus.DRAFT),
        definition = AgentJson.fromDocument(AgentDefinition.serializer(), get("definition", Document::class.java), AgentDefinition()),
        version = getInteger("version") ?: 1,
        createdBy = getString("createdBy"),
        createdAt = instant("createdAt") ?: Instant.fromEpochMilliseconds(0),
        updatedAt = instant("updatedAt") ?: Instant.fromEpochMilliseconds(0),
        stats = get("stats", Document::class.java)?.toStats() ?: AgentStats(),
        pausedReason = getString("pausedReason"),
        scheduleState = get("scheduleState", Document::class.java)?.entries
            ?.mapNotNull { (key, value) -> (value as? java.util.Date)?.let { key to Instant.fromEpochMilliseconds(it.time) } }
            ?.toMap().orEmpty(),
    )

    companion object {
        const val COLLECTION = "agents"
    }
}

internal fun AgentDefinition.triggerTypes(): List<String> = triggers.map { it.type }.distinct()

/** The domain event types this definition's event triggers and exit rules listen for. */
internal fun AgentDefinition.eventTypes(): List<String> =
    (triggers.filter { it.type == "event" }.mapNotNull { it.config["event"]?.jsonPrimitive?.content } + exitRules.map { it.event }).distinct()
