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

class SupplierRepository(private val mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.suppliers")
    private val sequences = SequenceRepository(mongoModule, tenantId)

    suspend fun findById(id: ObjectId): Supplier? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toSupplier()

    suspend fun search(query: String, archived: Boolean = false): List<Supplier> {
        val trimmed = query.trim()
        val text = if (trimmed.isBlank()) {
            null
        } else {
            Filters.or(
                Filters.regex("number", ".*${Regex.escape(trimmed)}.*", "i"),
                Filters.regex("name", ".*${Regex.escape(trimmed)}.*", "i"),
                Filters.regex("phone", ".*${Regex.escape(trimmed)}.*", "i"),
                Filters.regex("address", ".*${Regex.escape(trimmed)}.*", "i"),
                Filters.regex("type", ".*${Regex.escape(trimmed)}.*", "i"),
            )
        }
        val filter = Filters.and(listOfNotNull(Filters.eq("tenantId", tenantId), archivedFilter(archived), text))
        return collection.find(filter).sort(Document("name", 1)).limit(100).toList().map { it.toSupplier() }
    }

    /** Only a supplier with no payments can be deleted; the others get archived. */
    suspend fun delete(id: ObjectId): DirectoryDelete =
        collection.deleteUnreferenced(mongoModule, tenantId, id, "supplierId", listOf("crm.payments"))

    suspend fun setArchived(id: ObjectId, archived: Boolean): Supplier? = collection.setArchived(tenantId, id, archived)?.toSupplier()

    /**
     * [address] is replaced as given (null clears it). [type] is only written when not null, so callers
     * that don't send it keep the stored value; a blank string clears it.
     */
    suspend fun update(id: ObjectId, name: String, phone: String, address: String?, type: String? = null): Supplier? {
        val now = SystemClock.now()
        val updates = mutableListOf(
            Updates.set("name", name.trim()),
            Updates.set("phone", phone.trim()),
            Updates.set("address", address.cleaned()),
            Updates.set("updatedAt", now.toDate()),
        )
        type?.let { updates += Updates.set("type", it.cleaned()) }
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(updates),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toSupplier()
    }

    suspend fun create(name: String, phone: String, address: String? = null, type: String? = null): Supplier {
        val now = SystemClock.now()
        val number = "FOR-${sequences.next("supplier_number").toString().padStart(3, '0')}"
        val supplier = Supplier(
            tenantId = tenantId,
            number = number,
            name = name.trim(),
            phone = phone.trim(),
            address = address.cleaned(),
            type = type.cleaned(),
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
        type = getString("type"),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
        archivedAt = getDate("archivedAt")?.toInstantValue(),
    )

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun Supplier.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("number", number)
        .append("name", name)
        .append("phone", phone)
        .append("address", address)
        .append("type", type)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}
