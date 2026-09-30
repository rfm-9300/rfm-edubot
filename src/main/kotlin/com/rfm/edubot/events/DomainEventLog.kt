package com.rfm.edubot.events

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.BsonJson
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The domain event outbox (`domain_events`). Repositories append to it after their own write; the
 * agent dispatcher claims pending events from it; record drawers read it as an activity timeline.
 */
class DomainEventLog(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** Best effort: a failure is logged and never fails the business write that caused the event. */
    suspend fun append(
        tenantId: ObjectId,
        type: String,
        subject: SubjectRef,
        payload: JsonObject = JsonObject(emptyMap()),
        related: List<SubjectRef> = emptyList(),
    ): DomainEvent? {
        if (type in DomainEventTypes.onDemand && !DomainEventInterest.wants(tenantId, type)) return null
        return try {
            val actor = currentActor()
            val event = DomainEvent(
                tenantId = tenantId,
                type = type,
                subject = subject,
                related = related.filter { it != subject }.distinct(),
                payload = payload,
                actor = actor.actor,
                depth = actor.depth,
                occurredAt = clock(),
            )
            collection.insertOne(event.toDocument())
            DomainEventSignal.nudge()
            event
        } catch (e: Exception) {
            log.warn("Could not record domain event {} on {} {}: {}", type, subject.type, subject.id, e.message)
            null
        }
    }

    /** Claims the oldest pending event; a claim older than [staleBefore] is taken over (the dispatcher died mid-way). */
    suspend fun claimNext(staleBefore: Instant): DomainEvent? {
        val doc = collection.findOneAndUpdate(
            Filters.or(
                Filters.eq("dispatch.status", PENDING),
                Filters.and(Filters.eq("dispatch.status", CLAIMED), Filters.lt("dispatch.claimedAt", staleBefore.toDate())),
            ),
            Updates.combine(
                Updates.set("dispatch.status", CLAIMED),
                Updates.set("dispatch.claimedAt", clock().toDate()),
                Updates.inc("dispatch.attempts", 1),
            ),
            FindOneAndUpdateOptions().sort(Document("occurredAt", 1)).returnDocument(ReturnDocument.AFTER),
        ) ?: return null
        val attempts = doc.get("dispatch", Document::class.java)?.getInteger("attempts") ?: 1
        if (attempts > MAX_DISPATCH_ATTEMPTS) {
            markFailed(doc.getObjectId("_id"), "too_many_attempts")
            return claimNext(staleBefore)
        }
        return doc.toEvent()
    }

    suspend fun markDone(id: ObjectId) {
        collection.updateOne(
            Filters.eq("_id", id),
            Updates.combine(Updates.set("dispatch.status", DONE), Updates.set("dispatch.doneAt", clock().toDate())),
        )
    }

    suspend fun markFailed(id: ObjectId, error: String) {
        collection.updateOne(
            Filters.eq("_id", id),
            Updates.combine(Updates.set("dispatch.status", FAILED), Updates.set("dispatch.error", error.take(500))),
        )
    }

    suspend fun findById(tenantId: ObjectId, id: ObjectId): DomainEvent? =
        collection.find(Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("_id", id))).firstOrNull()?.toEvent()

    /** Events on [subject] or mentioning it (an invoice's events show on its client), newest first. */
    suspend fun timeline(tenantId: ObjectId, subject: SubjectRef, limit: Int = 50): List<DomainEvent> =
        collection.find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.or(
                    Filters.and(Filters.eq("subject.type", subject.type), Filters.eq("subject.id", subject.id)),
                    Filters.elemMatch("related", Filters.and(Filters.eq("type", subject.type), Filters.eq("id", subject.id))),
                ),
            ),
        ).sort(Document("occurredAt", -1)).limit(limit.coerceIn(1, 200)).toList().map { it.toEvent() }

    /** Recent events caused by agents, for the Home activity and the Agents module. */
    suspend fun recentByActorType(tenantId: ObjectId, type: ActorType, since: Instant, limit: Int = 50): List<DomainEvent> =
        collection.find(
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("actor.type", type.name), Filters.gte("occurredAt", since.toDate())),
        ).sort(Document("occurredAt", -1)).limit(limit).toList().map { it.toEvent() }

    private fun DomainEvent.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("type", type)
        .append("subject", subject.toDocument())
        .append("related", related.map { it.toDocument() })
        .append("payload", BsonJson.toDocument(payload))
        .append(
            "actor",
            Document("type", actor.type.name).append("id", actor.id).append("name", actor.name).append("runId", actor.runId),
        )
        .append("depth", depth)
        .append("occurredAt", occurredAt.toDate())
        .append("dispatch", Document("status", PENDING).append("attempts", 0))

    companion object {
        const val COLLECTION = "domain_events"
        const val PENDING = "PENDING"
        const val CLAIMED = "CLAIMED"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
        private const val MAX_DISPATCH_ATTEMPTS = 5
        private val log = LoggerFactory.getLogger("DomainEventLog")
    }
}

internal fun SubjectRef.toDocument(): Document = Document("type", type).append("id", id)

internal fun Document.toSubjectRef(): SubjectRef = SubjectRef(getString("type").orEmpty(), getString("id").orEmpty())

internal fun Document.toEvent(): DomainEvent {
    val actorDoc = get("actor", Document::class.java)
    return DomainEvent(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        type = getString("type"),
        subject = get("subject", Document::class.java)?.toSubjectRef() ?: SubjectRef(SubjectTypes.NONE, ""),
        related = getList("related", Document::class.java).orEmpty().map { it.toSubjectRef() },
        payload = BsonJson.toJsonObject(get("payload", Document::class.java)),
        actor = Actor(
            type = actorDoc?.getString("type")?.let { name -> ActorType.entries.firstOrNull { it.name == name } } ?: ActorType.SYSTEM,
            id = actorDoc?.getString("id"),
            name = actorDoc?.getString("name"),
            runId = actorDoc?.getString("runId"),
        ),
        depth = getInteger("depth") ?: 0,
        occurredAt = Instant.fromEpochMilliseconds(getDate("occurredAt").time),
    )
}

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())

/** Wakes the dispatcher right after an event is written instead of waiting for its next poll. */
object DomainEventSignal {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    fun nudge() {
        channel.trySend(Unit)
    }

    suspend fun await(timeout: Duration) {
        withTimeoutOrNull(timeout) { channel.receive() }
    }
}

/**
 * Which on-demand event types each company's active agents listen for. The agent dispatcher keeps it
 * current; the pipeline asks it before writing a `message.received` event for every chat message.
 */
object DomainEventInterest {
    private val byTenant = ConcurrentHashMap<ObjectId, Set<String>>()

    fun wants(tenantId: ObjectId, type: String): Boolean = byTenant[tenantId]?.contains(type) == true

    fun set(tenantId: ObjectId, types: Set<String>) {
        if (types.isEmpty()) byTenant.remove(tenantId) else byTenant[tenantId] = types
    }
}
