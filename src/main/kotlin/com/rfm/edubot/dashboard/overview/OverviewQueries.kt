package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.rfm.edubot.persistence.MongoModule
import java.util.Date
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

/** The queries Home's cards share: a collection, its totals by status or period, and the names behind ids. */
internal abstract class OverviewQueries(protected val mongo: MongoModule) {
    protected suspend fun sumByStatus(collection: String, tenantId: ObjectId): Map<String, StatusSum> {
        val pipeline = listOf(
            Document("\$match", Document("tenantId", tenantId)),
            Document(
                "\$group",
                Document("_id", "\$status")
                    .append("cents", Document("\$sum", "\$totalCents"))
                    .append("count", Document("\$sum", 1)),
            ),
        )
        return coll(collection).aggregate<Document>(pipeline).toList().associate { doc ->
            (doc.get("_id")?.toString() ?: "") to StatusSum(doc.get("cents").asLong(), doc.get("count").asLong().toInt())
        }
    }

    protected suspend fun sumPaid(collection: String, tenantId: ObjectId, from: Instant, to: Instant): Pair<Long, Int> {
        val docs = coll(collection).find(paidBetween(tenantId, from, to)).toList()
        return docs.sumOf { it.get("totalCents").asLong() } to docs.size
    }

    /** PAID in [from, to) by `paidAt`; legacy rows without `paidAt` fall back to `updatedAt`. */
    protected fun paidBetween(tenantId: ObjectId, from: Instant, to: Instant): Bson = Filters.and(
        Filters.eq("tenantId", tenantId),
        Filters.eq("status", "PAID"),
        Filters.or(
            Filters.and(Filters.gte("paidAt", Date(from.toEpochMilliseconds())), Filters.lt("paidAt", Date(to.toEpochMilliseconds()))),
            Filters.and(Filters.exists("paidAt", false), Filters.gte("updatedAt", Date(from.toEpochMilliseconds())), Filters.lt("updatedAt", Date(to.toEpochMilliseconds()))),
        ),
    )

    protected suspend fun sumCreated(collection: String, tenantId: ObjectId, from: Instant, to: Instant): Long {
        val pipeline = listOf(
            Document(
                "\$match",
                Document("tenantId", tenantId)
                    .append("createdAt", Document("\$gte", Date(from.toEpochMilliseconds())).append("\$lt", Date(to.toEpochMilliseconds())))
                    .append("status", Document("\$ne", "CANCELLED")),
            ),
            Document("\$group", Document("_id", null).append("cents", Document("\$sum", "\$totalCents"))),
        )
        return coll(collection).aggregate<Document>(pipeline).toList().firstOrNull()?.get("cents").asLong()
    }

    protected suspend fun clientNames(tenantId: ObjectId, ids: Collection<ObjectId>): Map<ObjectId, String> {
        if (ids.isEmpty()) return emptyMap()
        return coll("crm.clients").find(Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("_id", ids.toList())))
            .toList()
            .associate { it.getObjectId("_id") to (it.getString("name") ?: "") }
    }

    protected suspend fun employeeNames(tenantId: ObjectId, ids: Collection<ObjectId>): Map<ObjectId, String> {
        if (ids.isEmpty()) return emptyMap()
        return coll("crm.employees").find(Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("_id", ids.toList())))
            .toList()
            .associate { it.getObjectId("_id") to (it.getString("name") ?: "") }
    }

    protected suspend fun supplierNames(tenantId: ObjectId, ids: Collection<ObjectId>): Map<ObjectId, String> {
        if (ids.isEmpty()) return emptyMap()
        return coll("crm.suppliers").find(Filters.and(Filters.eq("tenantId", tenantId), Filters.`in`("_id", ids.toList())))
            .toList()
            .associate { it.getObjectId("_id") to (it.getString("name") ?: "") }
    }

    protected fun coll(name: String) = mongo.database.getCollection<Document>(name)

    protected data class StatusSum(val cents: Long = 0, val count: Int = 0)
}

internal fun Any?.asLong(): Long = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Double -> this.toLong()
    is Number -> this.toLong()
    else -> 0L
}

internal fun Document.getObjectIdOrNull(field: String): ObjectId? = get(field, ObjectId::class.java)

internal fun Document.localDate(field: String): LocalDate? =
    getString(field)?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun Document.installments(): List<Document> = getList("installments", Document::class.java).orEmpty()

/** An invoice's unpaid parts with their due dates: its unpaid installments, or the whole total when paid in one go. */
internal fun Document.openParts(): List<Pair<LocalDate?, Long>> {
    val installments = installments()
    if (installments.isEmpty()) return listOf(localDate("dueDate") to get("totalCents").asLong())
    return installments.filter { it.getDate("paidAt") == null }.map { it.localDate("dueDate") to it.get("amountCents").asLong() }
}

/** Like `Invoice.receipts()`: legacy invoices paid without `paidAt` count on `updatedAt`. */
internal fun Document.receipts(): List<Pair<Instant, Long>> {
    val paid = getString("status") == "PAID"
    val paidAt = (getDate("paidAt") ?: getDate("updatedAt"))?.takeIf { paid }
    val installments = installments()
    if (installments.isEmpty()) return listOfNotNull(paidAt?.let { Instant.fromEpochMilliseconds(it.time) to get("totalCents").asLong() })
    return installments.mapNotNull { part ->
        val at = part.getDate("paidAt") ?: paidAt ?: return@mapNotNull null
        Instant.fromEpochMilliseconds(at.time) to part.get("amountCents").asLong()
    }
}
