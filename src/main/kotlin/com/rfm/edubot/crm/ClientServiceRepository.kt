package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.model.ClientService
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

enum class ClientServiceDelete { Removed, NotFound, Invoiced }

class ClientServiceRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.client_services")

    suspend fun findById(id: ObjectId): ClientService? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toClientService()

    suspend fun findByIds(ids: List<ObjectId>): List<ClientService> {
        if (ids.isEmpty()) return emptyList()
        return collection.find(scoped(Filters.`in`("_id", ids))).toList().map { it.toClientService() }
    }

    suspend fun list(clientId: ObjectId? = null, status: ClientServiceStatus? = null): List<ClientService> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        clientId?.let { filters.add(Filters.eq("clientId", it)) }
        status?.let { filters.add(Filters.eq("status", it.name)) }
        return collection.find(Filters.and(filters))
            .sort(Document("performedAt", -1).append("createdAt", -1))
            .limit(200)
            .toList()
            .map { it.toClientService() }
    }

    suspend fun create(
        clientId: ObjectId,
        name: String,
        notes: String?,
        quantity: Double,
        unit: String,
        unitPriceCents: Long,
        bookingServiceId: ObjectId?,
        catalogItemId: String?,
        performedAt: LocalDate?,
    ): ClientService {
        val now = SystemClock.now()
        val qty = quantity.takeIf { it > 0 } ?: 1.0
        val service = ClientService(
            tenantId = tenantId,
            clientId = clientId,
            name = name.trim(),
            notes = notes?.trim()?.takeIf { it.isNotBlank() },
            quantity = qty,
            unit = unit.trim(),
            unitPriceCents = unitPriceCents,
            totalCents = clientServiceTotals(qty, unitPriceCents),
            bookingServiceId = bookingServiceId,
            catalogItemId = catalogItemId?.trim()?.takeIf { it.isNotBlank() },
            performedAt = performedAt,
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(service.toDocument())
        return service
    }

    suspend fun update(
        id: ObjectId,
        name: String?,
        notes: String?,
        quantity: Double?,
        unit: String?,
        unitPriceCents: Long?,
        performedAt: LocalDate?,
        status: ClientServiceStatus?,
    ): ClientService? {
        val existing = findById(id) ?: return null
        if (existing.status == ClientServiceStatus.INVOICED) return existing
        val now = SystemClock.now()
        val nextName = name?.trim()?.takeIf { it.isNotBlank() } ?: existing.name
        val nextQty = quantity?.takeIf { it > 0 } ?: existing.quantity
        val nextUnit = unit?.trim() ?: existing.unit
        val nextPrice = unitPriceCents ?: existing.unitPriceCents
        val ops = mutableListOf<Bson>(
            Updates.set("name", nextName),
            Updates.set("notes", notes?.trim()?.takeIf { it.isNotBlank() }),
            Updates.set("quantity", nextQty),
            Updates.set("unit", nextUnit),
            Updates.set("unitPriceCents", nextPrice),
            Updates.set("totalCents", clientServiceTotals(nextQty, nextPrice)),
            Updates.set("updatedAt", now.toDate()),
        )
        performedAt?.let { ops.add(Updates.set("performedAt", it.toString())) }
        status?.takeIf { it != ClientServiceStatus.INVOICED }?.let { ops.add(Updates.set("status", it.name)) }
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(ops),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toClientService()
    }

    suspend fun delete(id: ObjectId): ClientServiceDelete {
        val existing = findById(id) ?: return ClientServiceDelete.NotFound
        if (existing.status == ClientServiceStatus.INVOICED) return ClientServiceDelete.Invoiced
        val removed = collection.deleteOne(scoped(Filters.eq("_id", id))).deletedCount > 0
        return if (removed) ClientServiceDelete.Removed else ClientServiceDelete.NotFound
    }

    suspend fun markInvoiced(ids: List<ObjectId>, invoiceId: ObjectId): Int {
        if (ids.isEmpty()) return 0
        val now = SystemClock.now()
        val result = collection.updateMany(
            scoped(
                Filters.and(
                    Filters.`in`("_id", ids),
                    Filters.eq("status", ClientServiceStatus.OPEN.name),
                ),
            ),
            Updates.combine(
                Updates.set("status", ClientServiceStatus.INVOICED.name),
                Updates.set("invoiceId", invoiceId),
                Updates.set("updatedAt", now.toDate()),
            ),
        )
        return result.modifiedCount.toInt()
    }

    private fun Document.toClientService() = ClientService(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        clientId = getObjectId("clientId"),
        name = getString("name"),
        notes = getString("notes"),
        quantity = getDoubleValue("quantity").takeIf { it > 0 } ?: 1.0,
        unit = getString("unit") ?: "",
        unitPriceCents = getLongValue("unitPriceCents"),
        totalCents = getLongValue("totalCents"),
        status = runCatching { ClientServiceStatus.valueOf(getString("status") ?: "OPEN") }.getOrDefault(ClientServiceStatus.OPEN),
        invoiceId = get("invoiceId", ObjectId::class.java),
        bookingServiceId = get("bookingServiceId", ObjectId::class.java),
        catalogItemId = getString("catalogItemId"),
        bookingId = get("bookingId", ObjectId::class.java),
        performedAt = getString("performedAt")?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun ClientService.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("clientId", clientId)
        .append("name", name)
        .append("notes", notes)
        .append("quantity", quantity)
        .append("unit", unit)
        .append("unitPriceCents", unitPriceCents)
        .append("totalCents", totalCents)
        .append("status", status.name)
        .append("invoiceId", invoiceId)
        .append("bookingServiceId", bookingServiceId)
        .append("catalogItemId", catalogItemId)
        .append("bookingId", bookingId)
        .append("performedAt", performedAt?.toString())
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}
