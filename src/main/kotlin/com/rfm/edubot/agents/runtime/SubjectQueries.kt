package com.rfm.edubot.agents.runtime

import com.mongodb.client.model.Filters
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

/** A record a sweep found, with the date or moment its dedupe key is built from. */
data class SweepHit(val id: ObjectId, val stamp: String)

/** The record lookups behind date-offset, inactivity and per-record schedule triggers. */
class SubjectQueries(private val mongo: MongoModule) {
    private fun collection(name: String) = mongo.database.getCollection<Document>(name)

    /** Records whose date field (stored as `YYYY-MM-DD`) is one of [dates]. */
    suspend fun dueOn(tenantId: ObjectId, entity: String, dates: Collection<LocalDate>, statuses: List<String>, limit: Int = 500): List<SweepHit> {
        val (name, field) = when (entity) {
            SubjectTypes.INVOICE -> "crm.invoices" to "dueDate"
            SubjectTypes.PAYMENT -> "crm.payments" to "dueDate"
            SubjectTypes.QUOTE -> "crm.quotes" to "validUntil"
            else -> return emptyList()
        }
        if (dates.isEmpty()) return emptyList()
        val filters = mutableListOf(Filters.eq("tenantId", tenantId), Filters.`in`(field, dates.map { it.toString() }))
        if (statuses.isNotEmpty()) filters += Filters.`in`("status", statuses)
        return collection(name).find(Filters.and(filters)).limit(limit).toList()
            .map { SweepHit(it.getObjectId("_id"), it.getString(field).orEmpty()) }
    }

    suspend fun bookingsStartingBetween(tenantId: ObjectId, from: Instant, to: Instant, statuses: List<String>, limit: Int = 500): List<SweepHit> {
        val filters = mutableListOf(
            Filters.eq("tenantId", tenantId),
            Filters.gt("startAt", Date(from.toEpochMilliseconds())),
            Filters.lte("startAt", Date(to.toEpochMilliseconds())),
        )
        if (statuses.isNotEmpty()) filters += Filters.`in`("status", statuses)
        return collection("bookings.appointments").find(Filters.and(filters)).limit(limit).toList()
            .map { SweepHit(it.getObjectId("_id"), it.getDate("startAt").time.toString()) }
    }

    /** Quotes still marked sent since at least [before] (and not older than [notBefore]). */
    suspend fun quotesSentBetween(tenantId: ObjectId, notBefore: Instant, before: Instant, limit: Int = 500): List<SweepHit> =
        collection("crm.quotes").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("status", "SENT"),
                Filters.or(
                    Filters.and(Filters.gt("sentAt", Date(notBefore.toEpochMilliseconds())), Filters.lte("sentAt", Date(before.toEpochMilliseconds()))),
                    Filters.and(
                        Filters.exists("sentAt", false),
                        Filters.gt("updatedAt", Date(notBefore.toEpochMilliseconds())),
                        Filters.lte("updatedAt", Date(before.toEpochMilliseconds())),
                    ),
                ),
            ),
        ).limit(limit).toList().map { doc ->
            val at = doc.getDate("sentAt") ?: doc.getDate("updatedAt")
            SweepHit(doc.getObjectId("_id"), at.time.toString())
        }

    /** Conversations whose customer wrote between [notBefore] and [before] and got no answer since. */
    suspend fun conversationsWaiting(tenantId: ObjectId, notBefore: Instant, before: Instant, limit: Int = 300): List<SweepHit> {
        val candidates = collection("conversations").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.gt("lastInboundAt", Date(notBefore.toEpochMilliseconds())),
                Filters.lte("lastInboundAt", Date(before.toEpochMilliseconds())),
            ),
        ).limit(limit).toList()
        if (candidates.isEmpty()) return emptyList()
        val last = MessageRepository(mongo, tenantId).lastByConversationIds(candidates.map { it.getObjectId("_id") })
        return candidates.filter { last[it.getObjectId("_id")]?.role == UserRole.USER }
            .map { SweepHit(it.getObjectId("_id"), it.getDate("lastInboundAt").time.toString()) }
    }

    /** Clients whose newest quote, invoice or booking (or their creation) is older than [before]. */
    suspend fun quietClients(tenantId: ObjectId, before: Instant, limit: Int = 1000): List<SweepHit> {
        val clients = collection("crm.clients").find(Filters.and(Filters.eq("tenantId", tenantId), Filters.exists("archivedAt", false)))
            .limit(limit).toList()
        if (clients.isEmpty()) return emptyList()
        val latest = mutableMapOf<ObjectId, Long>()
        for (name in listOf("crm.quotes", "crm.invoices", "bookings.appointments")) {
            val pipeline = listOf(
                Document("\$match", Document("tenantId", tenantId).append("clientId", Document("\$ne", null))),
                Document("\$group", Document("_id", "\$clientId").append("at", Document("\$max", "\$createdAt"))),
            )
            collection(name).aggregate<Document>(pipeline).toList().forEach { group ->
                val clientId = group.get("_id") as? ObjectId ?: return@forEach
                val at = (group.get("at") as? Date)?.time ?: return@forEach
                latest[clientId] = maxOf(latest[clientId] ?: 0L, at)
            }
        }
        return clients.mapNotNull { client ->
            val id = client.getObjectId("_id")
            val at = latest[id] ?: client.getDate("createdAt")?.time ?: return@mapNotNull null
            if (at <= before.toEpochMilliseconds()) SweepHit(id, at.toString()) else null
        }
    }

    /** Candidates for a schedule that runs once per record; the agent's conditions narrow them. */
    suspend fun forEachCandidates(tenantId: ObjectId, entity: String, now: Instant, limit: Int = 300): List<ObjectId> {
        val (name, filter) = when (entity) {
            SubjectTypes.CLIENT -> "crm.clients" to Filters.and(Filters.eq("tenantId", tenantId), Filters.exists("archivedAt", false))
            SubjectTypes.INVOICE -> "crm.invoices" to Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("status", listOf("PENDING", "OVERDUE")))
            SubjectTypes.PAYMENT -> "crm.payments" to Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("status", listOf("PENDING", "OVERDUE")))
            SubjectTypes.QUOTE -> "crm.quotes" to Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("status", listOf("PENDENTE", "SENT")))
            SubjectTypes.BOOKING -> "bookings.appointments" to Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("status", listOf("PENDING", "CONFIRMED")),
                Filters.gte("startAt", Date(now.toEpochMilliseconds())),
                Filters.lt("startAt", Date(now.toEpochMilliseconds() + 7L * 24 * 3600 * 1000)),
            )
            else -> return emptyList()
        }
        return collection(name).find(filter).limit(limit).toList().map { it.getObjectId("_id") }
    }
}
