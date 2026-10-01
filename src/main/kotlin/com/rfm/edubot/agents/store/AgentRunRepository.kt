package com.rfm.edubot.agents.store

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepResult
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.BsonJson
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.bson.BsonType
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** Agent runs (`agent_runs`): one per trigger firing, deduplicated by `(tenantId, agentId, dedupeKey)`. */
class AgentRunRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** Null when a run with the same dedupe key exists: the trigger already fired for that occurrence. */
    suspend fun insertIfAbsent(run: AgentRun): AgentRun? = try {
        collection.insertOne(run.toDocument())
        run
    } catch (e: MongoWriteException) {
        if (e.error.category != ErrorCategory.DUPLICATE_KEY) throw e
        null
    }

    suspend fun findById(tenantId: ObjectId, id: ObjectId): AgentRun? =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id))).firstOrNull()?.toRun()

    /** For the runtime, which only holds run ids it created itself. */
    suspend fun load(id: ObjectId): AgentRun? = collection.find(Filters.eq("_id", id)).firstOrNull()?.toRun()

    /** Moves a run from one of [from] to RUNNING; null when someone else got it first. */
    suspend fun claim(id: ObjectId, from: Set<RunStatus>): AgentRun? {
        val now = clock()
        return collection.findOneAndUpdate(
            Filters.and(Filters.eq("_id", id), Filters.`in`("status", from.map { it.name })),
            Updates.combine(
                Updates.set("status", RunStatus.RUNNING.name),
                Updates.set("claimedAt", now.toDate()),
                Updates.set("updatedAt", now.toDate()),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toRun()
    }

    /** Checkpoints a run after a step: everything that changes while it executes. */
    suspend fun save(run: AgentRun): AgentRun {
        val saved = run.copy(updatedAt = clock())
        collection.updateOne(
            Filters.eq("_id", run.id),
            Updates.combine(
                Updates.set("status", saved.status.name),
                Updates.set("currentStep", saved.currentStep),
                Updates.set("context", storedContext(saved.context)),
                Updates.set("steps", saved.steps.map { AgentJson.toDocument(StepResult.serializer(), it) }),
                Updates.set("resumeAt", saved.resumeAt?.toDate()),
                Updates.set("subjectLabel", saved.subjectLabel),
                Updates.set("promptTokens", saved.promptTokens),
                Updates.set("completionTokens", saved.completionTokens),
                Updates.set("updatedAt", saved.updatedAt.toDate()),
                Updates.set("startedAt", saved.startedAt?.toDate()),
                Updates.set("finishedAt", saved.finishedAt?.toDate()),
                Updates.set("error", saved.error),
                Updates.set("outcome", saved.outcome),
            ),
        )
        return saved
    }

    /** Ends an open run from outside the executor (exit rule, manual cancel, agent archived). */
    suspend fun cancelIfOpen(id: ObjectId, outcome: String): AgentRun? {
        val now = clock()
        return collection.findOneAndUpdate(
            Filters.and(Filters.eq("_id", id), Filters.`in`("status", CANCELLABLE)),
            Updates.combine(
                Updates.set("status", RunStatus.CANCELLED.name),
                Updates.set("outcome", outcome),
                Updates.set("finishedAt", now.toDate()),
                Updates.set("updatedAt", now.toDate()),
                Updates.unset("resumeAt"),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toRun()
    }

    suspend fun dueWaiting(now: Instant, limit: Int = 200): List<AgentRun> =
        collection.find(Filters.and(Filters.eq("status", RunStatus.WAITING.name), Filters.lte("resumeAt", now.toDate())))
            .sort(Document("resumeAt", 1)).limit(limit).toList().map { it.toRun() }

    /** Runs created but never picked up, e.g. queued just before a restart. */
    suspend fun queued(olderThan: Instant, limit: Int = 200): List<AgentRun> =
        collection.find(Filters.and(Filters.eq("status", RunStatus.QUEUED.name), Filters.lte("createdAt", olderThan.toDate())))
            .sort(Document("createdAt", 1)).limit(limit).toList().map { it.toRun() }

    /** Runs a crashed or stopped instance left RUNNING; the runtime decides whether each can safely resume. */
    suspend fun interrupted(claimedBefore: Instant, limit: Int = 200): List<AgentRun> =
        collection.find(Filters.and(Filters.eq("status", RunStatus.RUNNING.name), Filters.lt("claimedAt", claimedBefore.toDate())))
            .limit(limit).toList().map { it.toRun() }

    suspend fun openForSubject(tenantId: ObjectId, subject: SubjectRef): List<AgentRun> =
        collection.find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("subject.type", subject.type),
                Filters.eq("subject.id", subject.id),
                Filters.`in`("status", OPEN_STATUSES),
            ),
        ).sort(Document("createdAt", -1)).limit(50).toList().map { it.toRun() }

    suspend fun openForAgent(tenantId: ObjectId, agentId: ObjectId): List<AgentRun> =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("agentId", agentId), Filters.`in`("status", OPEN_STATUSES)))
            .limit(1000).toList().map { it.toRun() }

    suspend fun list(
        tenantId: ObjectId,
        agentId: ObjectId? = null,
        statuses: Collection<RunStatus> = emptyList(),
        subject: SubjectRef? = null,
        limit: Int = 50,
    ): List<AgentRun> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        agentId?.let { filters += Filters.eq("agentId", it) }
        if (statuses.isNotEmpty()) filters += Filters.`in`("status", statuses.map { it.name })
        subject?.let {
            filters += Filters.eq("subject.type", it.type)
            filters += Filters.eq("subject.id", it.id)
        }
        return collection.find(Filters.and(filters)).sort(Document("createdAt", -1)).limit(limit.coerceIn(1, 200)).toList().map { it.toRun() }
    }

    /** Real runs on [clientId] itself or on its quotes, invoices, bookings and chats, newest first. */
    suspend fun forClient(tenantId: ObjectId, clientId: ObjectId, limit: Int = 30): List<AgentRun> =
        collection.find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.or(
                    Filters.eq("clientId", clientId),
                    // Runs stored before runs carried a client id.
                    Filters.and(Filters.eq("subject.type", SubjectTypes.CLIENT), Filters.eq("subject.id", clientId.toHexString())),
                ),
                Filters.ne("dryRun", true),
            ),
        ).sort(Document("createdAt", -1)).limit(limit.coerceIn(1, 200)).toList().map { it.toRun() }

    suspend fun byIds(tenantId: ObjectId, ids: Collection<ObjectId>): List<AgentRun> {
        if (ids.isEmpty()) return emptyList()
        return collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("_id", ids))).toList().map { it.toRun() }
    }

    suspend fun countSince(tenantId: ObjectId, since: Instant, agentId: ObjectId? = null, includeDryRuns: Boolean = false): Long {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId), Filters.gte("createdAt", since.toDate()))
        agentId?.let { filters += Filters.eq("agentId", it) }
        if (!includeDryRuns) filters += Filters.ne("dryRun", true)
        return collection.countDocuments(Filters.and(filters))
    }

    /** Real runs that failed or stopped for review since [since], newest first. */
    suspend fun failedSince(tenantId: ObjectId, since: Instant, limit: Int = 3): List<AgentRun> =
        collection.find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("status", listOf(RunStatus.FAILED.name, RunStatus.NEEDS_REVIEW.name)),
                Filters.ne("dryRun", true),
                Filters.gte("createdAt", since.toDate()),
            ),
        ).sort(Document("createdAt", -1)).limit(limit.coerceIn(1, 50)).toList().map { it.toRun() }

    suspend fun countByStatus(tenantId: ObjectId, statuses: Collection<RunStatus>, since: Instant? = null): Long {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId), Filters.`in`("status", statuses.map { it.name }), Filters.ne("dryRun", true))
        since?.let { filters += Filters.gte("createdAt", it.toDate()) }
        return collection.countDocuments(Filters.and(filters))
    }

    /** The agent's latest real run on [subject], for its cooldown. */
    suspend fun lastForSubject(tenantId: ObjectId, agentId: ObjectId, subject: SubjectRef): AgentRun? =
        collection.find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("agentId", agentId),
                Filters.eq("subject.type", subject.type),
                Filters.eq("subject.id", subject.id),
                Filters.ne("dryRun", true),
            ),
        ).sort(Document("createdAt", -1)).limit(1).firstOrNull()?.toRun()

    /**
     * Forgets what runs about these emails copied of them (sender, subject, what their steps were given
     * and produced) because their account was disconnected. Runs still waiting end: nothing is left to act on.
     */
    suspend fun forgetEmails(tenantId: ObjectId, ids: Collection<String>) {
        val now = clock()
        ids.chunked(FORGET_CHUNK).forEach { chunk ->
            val about = aboutEmails(tenantId, chunk)
            collection.updateMany(
                Filters.and(about, Filters.`in`("status", CANCELLABLE)),
                Updates.combine(
                    Updates.set("status", RunStatus.CANCELLED.name),
                    Updates.set("outcome", RECORD_REMOVED),
                    Updates.set("finishedAt", now.toDate()),
                    Updates.set("updatedAt", now.toDate()),
                    Updates.unset("resumeAt"),
                ),
            )
            collection.updateMany(about, Updates.combine(Updates.unset("context.email"), Updates.unset("context.event"), Updates.unset("subjectLabel")))
            forgetStepData(about)
        }
    }

    /** The text of these emails was deleted (retention), so what steps were given and produced from it goes too. */
    suspend fun forgetEmailText(tenantId: ObjectId, ids: Collection<String>) {
        ids.chunked(FORGET_CHUNK).forEach { forgetStepData(aboutEmails(tenantId, it)) }
    }

    private fun aboutEmails(tenantId: ObjectId, ids: List<String>): Bson =
        Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("subject.type", SubjectTypes.EMAIL), Filters.`in`("subject.id", ids))

    private suspend fun forgetStepData(about: Bson) {
        // `$[]` fails on a document without a steps array.
        collection.updateMany(
            Filters.and(about, Filters.type("steps", BsonType.ARRAY)),
            Updates.combine(Updates.unset("steps.$[].input"), Updates.unset("steps.$[].output")),
        )
    }

    /**
     * A run keeps no email text: every step reads it again from the stored email, whose text is deleted
     * after the retention period.
     */
    private fun storedContext(context: JsonObject): Document {
        val email = context["email"] as? JsonObject ?: return BsonJson.toDocument(context)
        return BsonJson.toDocument(JsonObject(context + ("email" to JsonObject(email - EMAIL_TEXT))))
    }

    private fun AgentRun.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("agentId", agentId)
        .append("agentName", agentName)
        .append("agentVersion", agentVersion)
        .append("definition", AgentJson.toDocument(AgentDefinition.serializer(), definition))
        .append("trigger", AgentJson.toDocument(RunTrigger.serializer(), trigger))
        .append("subject", subject?.let { Document("type", it.type).append("id", it.id) })
        .append("subjectLabel", subjectLabel)
        .append("clientId", clientId)
        .append("dedupeKey", dedupeKey)
        .append("status", status.name)
        .append("currentStep", currentStep)
        .append("context", storedContext(context))
        .append("steps", steps.map { AgentJson.toDocument(StepResult.serializer(), it) })
        .append("resumeAt", resumeAt?.toDate())
        .append("depth", depth)
        .append("dryRun", dryRun)
        .append("promptTokens", promptTokens)
        .append("completionTokens", completionTokens)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())
        .append("startedAt", startedAt?.toDate())
        .append("finishedAt", finishedAt?.toDate())
        .append("claimedAt", claimedAt?.toDate())
        .append("error", error)
        .append("outcome", outcome)

    private fun Document.toRun(): AgentRun = AgentRun(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        agentId = getObjectId("agentId"),
        agentName = getString("agentName").orEmpty(),
        agentVersion = getInteger("agentVersion") ?: 1,
        definition = AgentJson.fromDocument(AgentDefinition.serializer(), get("definition", Document::class.java), AgentDefinition()),
        trigger = AgentJson.fromDocument(
            RunTrigger.serializer(),
            get("trigger", Document::class.java),
            RunTrigger(type = "unknown", firedAt = instant("createdAt") ?: Instant.fromEpochMilliseconds(0)),
        ),
        subject = get("subject", Document::class.java)?.let { SubjectRef(it.getString("type").orEmpty(), it.getString("id").orEmpty()) },
        subjectLabel = getString("subjectLabel"),
        clientId = get("clientId", ObjectId::class.java),
        dedupeKey = getString("dedupeKey").orEmpty(),
        status = runCatching { RunStatus.valueOf(getString("status")) }.getOrDefault(RunStatus.FAILED),
        currentStep = getInteger("currentStep") ?: 0,
        context = BsonJson.toJsonObject(get("context", Document::class.java)),
        steps = getList("steps", Document::class.java).orEmpty().mapNotNull {
            runCatching { AgentJson.json.decodeFromJsonElement(StepResult.serializer(), BsonJson.toJsonObject(it)) }.getOrNull()
        },
        resumeAt = instant("resumeAt"),
        depth = getInteger("depth") ?: 0,
        dryRun = getBoolean("dryRun") ?: false,
        promptTokens = getInteger("promptTokens") ?: 0,
        completionTokens = getInteger("completionTokens") ?: 0,
        createdAt = instant("createdAt") ?: Instant.fromEpochMilliseconds(0),
        updatedAt = instant("updatedAt") ?: Instant.fromEpochMilliseconds(0),
        startedAt = instant("startedAt"),
        finishedAt = instant("finishedAt"),
        claimedAt = instant("claimedAt"),
        error = getString("error"),
        outcome = getString("outcome"),
    )

    companion object {
        const val COLLECTION = "agent_runs"
        private val OPEN_STATUSES = RunStatus.entries.filter { it.open }.map { it.name }
        private val CANCELLABLE = listOf(RunStatus.QUEUED, RunStatus.WAITING, RunStatus.AWAITING_APPROVAL, RunStatus.NEEDS_REVIEW).map { it.name }
        private val EMAIL_TEXT = setOf("text", "snippet")
        private const val FORGET_CHUNK = 500

        /** What a run ends with when its record is gone; the executor says the same when it finds out itself. */
        const val RECORD_REMOVED = "record_removed"
    }
}
