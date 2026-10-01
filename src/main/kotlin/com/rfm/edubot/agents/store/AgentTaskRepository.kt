package com.rfm.edubot.agents.store

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.agents.model.AgentTask
import com.rfm.edubot.agents.model.TaskStatus
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** To-dos for people (`agent_tasks`), created by agents (`team.task.create`) or by hand. */
class AgentTaskRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun insert(task: AgentTask): AgentTask {
        collection.insertOne(task.toDocument())
        return task
    }

    suspend fun findById(tenantId: ObjectId, id: ObjectId): AgentTask? =
        collection.find(scoped(tenantId, Filters.eq("_id", id))).firstOrNull()?.toTask()

    suspend fun list(
        tenantId: ObjectId,
        status: TaskStatus? = TaskStatus.OPEN,
        assigneeUserId: String? = null,
        subject: SubjectRef? = null,
        limit: Int = 200,
    ): List<AgentTask> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        status?.let { filters += Filters.eq("status", it.name) }
        assigneeUserId?.let { filters += Filters.eq("assigneeUserId", it) }
        subject?.let {
            filters += Filters.eq("subject.type", it.type)
            filters += Filters.eq("subject.id", it.id)
        }
        return collection.find(Filters.and(filters)).sort(Document("dueAt", 1).append("createdAt", -1)).limit(limit.coerceIn(1, 500)).toList().map { it.toTask() }
    }

    /** Open tasks about [clientId] or one of its documents, earliest due first. */
    suspend fun openForClient(tenantId: ObjectId, clientId: ObjectId, limit: Int = 50): List<AgentTask> =
        collection.find(
            scoped(
                tenantId,
                Filters.and(
                    Filters.eq("status", TaskStatus.OPEN.name),
                    Filters.or(
                        Filters.eq("clientId", clientId),
                        Filters.and(Filters.eq("subject.type", SubjectTypes.CLIENT), Filters.eq("subject.id", clientId.toHexString())),
                    ),
                ),
            ),
        ).sort(Document("dueAt", 1).append("createdAt", -1)).limit(limit.coerceIn(1, 200)).toList().map { it.toTask() }

    /** Open tasks due before [before], earliest first. Tasks without a due date are never due. */
    suspend fun dueBefore(tenantId: ObjectId, before: Instant, limit: Int = 3): List<AgentTask> =
        collection.find(scoped(tenantId, Filters.and(Filters.eq("status", TaskStatus.OPEN.name), Filters.lt("dueAt", before.toDate()))))
            .sort(Document("dueAt", 1)).limit(limit.coerceIn(1, 50)).toList().map { it.toTask() }

    suspend fun countOpen(tenantId: ObjectId, dueBefore: Instant? = null): Long {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId), Filters.eq("status", TaskStatus.OPEN.name))
        dueBefore?.let { filters += Filters.lt("dueAt", it.toDate()) }
        return collection.countDocuments(Filters.and(filters))
    }

    /** Null arguments keep the stored values; [clearDue] removes the due date. */
    suspend fun update(
        tenantId: ObjectId,
        id: ObjectId,
        title: String? = null,
        detail: String? = null,
        status: TaskStatus? = null,
        assigneeUserId: String? = null,
        assigneeName: String? = null,
        clearAssignee: Boolean = false,
        dueAt: Instant? = null,
        clearDue: Boolean = false,
        by: String? = null,
    ): AgentTask? {
        val now = clock()
        val updates = mutableListOf<Bson>(Updates.set("updatedAt", now.toDate()))
        title?.let { updates += Updates.set("title", it) }
        detail?.let { updates += Updates.set("detail", it) }
        if (clearAssignee) {
            updates += Updates.unset("assigneeUserId")
            updates += Updates.unset("assigneeName")
        } else {
            assigneeUserId?.let { updates += Updates.set("assigneeUserId", it) }
            assigneeName?.let { updates += Updates.set("assigneeName", it) }
        }
        if (clearDue) updates += Updates.unset("dueAt") else dueAt?.let { updates += Updates.set("dueAt", it.toDate()) }
        status?.let {
            updates += Updates.set("status", it.name)
            if (it == TaskStatus.OPEN) {
                updates += Updates.unset("completedAt")
                updates += Updates.unset("completedBy")
            } else {
                updates += Updates.set("completedAt", now.toDate())
                updates += Updates.set("completedBy", by)
            }
        }
        return collection.findOneAndUpdate(
            scoped(tenantId, Filters.eq("_id", id)),
            Updates.combine(updates),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toTask()
    }

    private fun scoped(tenantId: ObjectId, filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    private fun AgentTask.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("title", title)
        .append("detail", detail)
        .append("subject", subject?.let { Document("type", it.type).append("id", it.id) })
        .append("subjectLabel", subjectLabel)
        .append("clientId", clientId)
        .append("assigneeUserId", assigneeUserId)
        .append("assigneeName", assigneeName)
        .append("dueAt", dueAt?.toDate())
        .append("status", status.name)
        .append("agentId", agentId)
        .append("agentName", agentName)
        .append("runId", runId)
        .append("createdBy", createdBy)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())
        .append("completedAt", completedAt?.toDate())
        .append("completedBy", completedBy)

    private fun Document.toTask() = AgentTask(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        title = getString("title").orEmpty(),
        detail = getString("detail"),
        subject = get("subject", Document::class.java)?.let { SubjectRef(it.getString("type").orEmpty(), it.getString("id").orEmpty()) },
        subjectLabel = getString("subjectLabel"),
        clientId = get("clientId", ObjectId::class.java),
        assigneeUserId = getString("assigneeUserId"),
        assigneeName = getString("assigneeName"),
        dueAt = instant("dueAt"),
        status = runCatching { TaskStatus.valueOf(getString("status")) }.getOrDefault(TaskStatus.OPEN),
        agentId = get("agentId", ObjectId::class.java),
        agentName = getString("agentName"),
        runId = get("runId", ObjectId::class.java),
        createdBy = getString("createdBy"),
        createdAt = instant("createdAt") ?: Instant.fromEpochMilliseconds(0),
        updatedAt = instant("updatedAt") ?: Instant.fromEpochMilliseconds(0),
        completedAt = instant("completedAt"),
        completedBy = getString("completedBy"),
    )

    companion object {
        const val COLLECTION = "agent_tasks"
    }
}
