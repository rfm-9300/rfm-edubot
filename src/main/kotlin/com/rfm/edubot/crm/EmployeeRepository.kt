package com.rfm.edubot.crm

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

class EmployeeRepository(private val mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("crm.employees")
    private val sequences = SequenceRepository(mongoModule, tenantId)

    suspend fun findById(id: ObjectId): Employee? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toEmployee()

    suspend fun search(query: String, archived: Boolean = false): List<Employee> {
        val trimmed = query.trim()
        val text = if (trimmed.isBlank()) {
            null
        } else {
            Filters.or(
                Filters.regex("number", ".*${Regex.escape(trimmed)}.*", "i"),
                Filters.regex("name", ".*${Regex.escape(trimmed)}.*", "i"),
                Filters.regex("phone", ".*${Regex.escape(trimmed)}.*", "i"),
                Filters.regex("role", ".*${Regex.escape(trimmed)}.*", "i"),
            )
        }
        val filter = Filters.and(listOfNotNull(Filters.eq("tenantId", tenantId), archivedFilter(archived), text))
        return collection.find(filter).sort(Document("name", 1)).limit(100).toList().map { it.toEmployee() }
    }

    /** Only an employee with no payments can be deleted; the others get archived. */
    suspend fun delete(id: ObjectId): DirectoryDelete =
        collection.deleteUnreferenced(mongoModule, tenantId, id, "employeeId", listOf("crm.payments"))

    suspend fun setArchived(id: ObjectId, archived: Boolean): Employee? = collection.setArchived(tenantId, id, archived)?.toEmployee()

    suspend fun update(id: ObjectId, name: String, phone: String, role: String?): Employee? {
        val now = SystemClock.now()
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(
                Updates.set("name", name.trim()),
                Updates.set("phone", phone.trim()),
                Updates.set("role", role?.trim()?.takeIf { it.isNotBlank() }),
                Updates.set("updatedAt", now.toDate()),
            ),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toEmployee()
    }

    suspend fun create(name: String, phone: String, role: String? = null): Employee {
        val now = SystemClock.now()
        val number = "COL-${sequences.next("employee_number").toString().padStart(3, '0')}"
        val employee = Employee(
            tenantId = tenantId,
            number = number,
            name = name.trim(),
            phone = phone.trim(),
            role = role?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(employee.toDocument())
        return employee
    }

    private fun Document.toEmployee() = Employee(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number") ?: "COL-???",
        name = getString("name"),
        phone = getString("phone") ?: "",
        role = getString("role"),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
        archivedAt = getDate("archivedAt")?.toInstantValue(),
    )

    private fun Employee.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("number", number)
        .append("name", name)
        .append("phone", phone)
        .append("role", role)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}
