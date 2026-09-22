package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.model.Supplier
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

class SupplierRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.suppliers")
    private val sequences = SequenceRepository(mongoModule, tenantId)

    suspend fun findById(id: ObjectId): Supplier? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toSupplier()

    suspend fun search(query: String): List<Supplier> {
        val trimmed = query.trim()
        val filter = if (trimmed.isBlank()) {
            Filters.eq("tenantId", tenantId)
        } else {
            scoped(
                Filters.or(
                    Filters.regex("number", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("name", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("phone", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("address", ".*${Regex.escape(trimmed)}.*", "i"),
                ),
            )
        }
        return collection.find(filter).sort(Document("name", 1)).limit(100).toList().map { it.toSupplier() }
    }

    suspend fun update(id: ObjectId, name: String, phone: String, address: String?): Supplier? {
        val now = SystemClock.now()
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(
                Updates.set("name", name.trim()),
                Updates.set("phone", phone.trim()),
                Updates.set("address", address?.trim()?.takeIf { it.isNotBlank() }),
                Updates.set("updatedAt", now.toDate()),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toSupplier()
    }

    suspend fun create(name: String, phone: String, address: String? = null): Supplier {
        val now = SystemClock.now()
        val number = "FOR-${sequences.next("supplier_number").toString().padStart(3, '0')}"
        val supplier = Supplier(
            tenantId = tenantId,
            number = number,
            name = name.trim(),
            phone = phone.trim(),
            address = address?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(supplier.toDocument())
        return supplier
    }

    private fun Document.toSupplier() = Supplier(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number") ?: "FOR-???",
        name = getString("name"),
        phone = getString("phone") ?: "",
        address = getString("address"),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun Supplier.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("number", number)
        .append("name", name)
        .append("phone", phone)
        .append("address", address)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}
