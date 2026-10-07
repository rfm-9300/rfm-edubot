package com.rfm.edubot.notifications

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date

/** Who sees a notification: one dashboard user, the company's admins, or everyone in the company. */
enum class NotificationAudience { USER, ADMINS, ALL }

/**
 * An in-app notice for dashboard users. The dashboard writes the sentence from [kind] and [params] in the
 * reader's language; [body] only carries text a person or an agent wrote (a team.notify message).
 */
data class Notification(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val audience: NotificationAudience,
    val userId: String? = null,
    val kind: String,
    val params: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** Dashboard view to open, e.g. `agents` or `invoices`. */
    val link: String? = null,
    val subject: SubjectRef? = null,
    /** The item to open inside [link]: `approval:ID`, `run:ID`, `task:ID`, `agent:ID` or `inbox`. */
    val ref: String? = null,
    val readBy: List<String> = emptyList(),
    val createdAt: Instant,
)

object NotificationKinds {
    const val AGENT_APPROVAL = "agent_approval"
    const val AGENT_FAILED = "agent_failed"
    const val AGENT_PAUSED = "agent_paused"
    const val AGENT_TASK = "agent_task"
    const val AGENT_NOTICE = "agent_notice"
    const val INTEGRATION_RECONNECT = "integration_reconnect"
    /** An employee registered a service; its `ref` is `submission:ID`. */
    const val SERVICE_SUBMITTED = "service_submitted"
    /** An employee set up a phone for the time clock (replacing any other); its `ref` is `employee:ID`. */
    const val TIME_DEVICE_ENROLLED = "time_device_enrolled"
    /** An employee forgot to clock out and said when they finished; its `ref` is `shift:ID`. */
    const val TIME_MISSED_CLOCK_OUT = "time_missed_clock_out"
}

class NotificationRepository(mongo: MongoModule, private val clock: () -> Instant = SystemClock::now) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    suspend fun insert(notification: Notification): Notification {
        collection.insertOne(notification.toDocument())
        return notification
    }

    suspend fun notify(
        tenantId: ObjectId,
        kind: String,
        audience: NotificationAudience = NotificationAudience.ADMINS,
        userId: String? = null,
        params: Map<String, String> = emptyMap(),
        body: String? = null,
        link: String? = null,
        subject: SubjectRef? = null,
        ref: String? = null,
    ): Notification = insert(
        Notification(
            tenantId = tenantId,
            audience = audience,
            userId = userId,
            kind = kind,
            params = params,
            body = body?.take(MAX_BODY),
            link = link,
            subject = subject,
            ref = ref,
            createdAt = clock(),
        ),
    )

    suspend fun listFor(tenantId: ObjectId, reader: String, isAdmin: Boolean, limit: Int = 50): List<Notification> =
        collection.find(visibleTo(tenantId, reader, isAdmin)).sort(Document("createdAt", -1)).limit(limit.coerceIn(1, 200)).toList().map { it.toNotification() }

    suspend fun unreadCount(tenantId: ObjectId, reader: String, isAdmin: Boolean): Long =
        collection.countDocuments(Filters.and(visibleTo(tenantId, reader, isAdmin), Filters.ne("readBy", reader)))

    suspend fun markRead(tenantId: ObjectId, id: ObjectId, reader: String, isAdmin: Boolean): Boolean =
        collection.updateOne(
            Filters.and(visibleTo(tenantId, reader, isAdmin), Filters.eq("_id", id)),
            Updates.addToSet("readBy", reader),
        ).matchedCount > 0

    suspend fun markAllRead(tenantId: ObjectId, reader: String, isAdmin: Boolean): Long =
        collection.updateMany(
            Filters.and(visibleTo(tenantId, reader, isAdmin), Filters.ne("readBy", reader)),
            Updates.addToSet("readBy", reader),
        ).modifiedCount

    private fun visibleTo(tenantId: ObjectId, reader: String, isAdmin: Boolean): Bson {
        val audiences = mutableListOf<Bson>(
            Filters.eq("audience", NotificationAudience.ALL.name),
            Filters.and(Filters.eq("audience", NotificationAudience.USER.name), Filters.eq("userId", reader)),
        )
        if (isAdmin) audiences += Filters.eq("audience", NotificationAudience.ADMINS.name)
        return Filters.and(Filters.eq("tenantId", tenantId), Filters.or(audiences))
    }

    private fun Notification.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("audience", audience.name)
        .append("userId", userId)
        .append("kind", kind)
        .append("params", Document(params))
        .append("body", body)
        .append("link", link)
        .append("subject", subject?.let { Document("type", it.type).append("id", it.id) })
        .append("ref", ref)
        .append("readBy", readBy)
        .append("createdAt", Date(createdAt.toEpochMilliseconds()))

    private fun Document.toNotification() = Notification(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        audience = runCatching { NotificationAudience.valueOf(getString("audience")) }.getOrDefault(NotificationAudience.ADMINS),
        userId = getString("userId"),
        kind = getString("kind").orEmpty(),
        params = get("params", Document::class.java)?.entries?.associate { (key, value) -> key to value.toString() }.orEmpty(),
        body = getString("body"),
        link = getString("link"),
        subject = get("subject", Document::class.java)?.let { SubjectRef(it.getString("type").orEmpty(), it.getString("id").orEmpty()) },
        ref = getString("ref"),
        readBy = getList("readBy", String::class.java).orEmpty(),
        createdAt = Instant.fromEpochMilliseconds(getDate("createdAt").time),
    )

    companion object {
        const val COLLECTION = "notifications"
        private const val MAX_BODY = 2000
    }
}
