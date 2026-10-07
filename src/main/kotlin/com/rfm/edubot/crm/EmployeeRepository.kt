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
import kotlinx.datetime.LocalDate
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
                Filters.regex("taxId", ".*${Regex.escape(trimmed)}.*", "i"),
            )
        }
        val filter = Filters.and(listOfNotNull(Filters.eq("tenantId", tenantId), archivedFilter(archived), text))
        return collection.find(filter).sort(Document("name", 1)).limit(100).toList().map { it.toEmployee() }
    }

    /**
     * Only an employee with no payments, registered services, services done or clocked shifts can be deleted;
     * the others get archived. Shifts are the working-time record the company keeps for five years.
     */
    suspend fun delete(id: ObjectId): DirectoryDelete =
        collection.deleteUnreferenced(
            mongoModule, tenantId, id, "employeeId",
            listOf("crm.payments", ServiceSubmissionRepository.COLLECTION, "crm.client_services", SHIFTS_COLLECTION),
        )

    suspend fun setArchived(id: ObjectId, archived: Boolean): Employee? = collection.setArchived(tenantId, id, archived)?.toEmployee()

    /**
     * [role] is replaced as given (null clears it). [birthDate] (`yyyy-MM-dd`), [address] and [taxId] are
     * only written when not null, so callers that don't send them keep the stored values; a blank string
     * clears them.
     */
    suspend fun update(
        id: ObjectId,
        name: String,
        phone: String,
        role: String?,
        birthDate: String? = null,
        address: String? = null,
        taxId: String? = null,
    ): Employee? {
        val now = SystemClock.now()
        val updates = mutableListOf(
            Updates.set("name", name.trim()),
            Updates.set("phone", phone.trim()),
            Updates.set("role", role.cleaned()),
            Updates.set("updatedAt", now.toDate()),
        )
        birthDate?.let { updates += Updates.set("birthDate", it.isoDate()?.toString()) }
        address?.let { updates += Updates.set("address", it.cleaned()) }
        taxId?.let { updates += Updates.set("taxId", it.cleaned()) }
        val doc = collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(updates),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )
        return doc?.toEmployee()
    }

    suspend fun create(
        name: String,
        phone: String,
        role: String? = null,
        birthDate: String? = null,
        address: String? = null,
        taxId: String? = null,
    ): Employee {
        val now = SystemClock.now()
        val number = "COL-${sequences.next("employee_number").toString().padStart(3, '0')}"
        val employee = Employee(
            tenantId = tenantId,
            number = number,
            name = name.trim(),
            phone = phone.trim(),
            role = role.cleaned(),
            birthDate = birthDate.isoDate(),
            address = address.cleaned(),
            taxId = taxId.cleaned(),
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(employee.toDocument())
        return employee
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    /** Callers validate the date first; a malformed one throws rather than being stored. */
    private fun String?.isoDate(): LocalDate? = cleaned()?.let { LocalDate.parse(it) }

    private fun Document.toEmployee() = Employee(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        number = getString("number") ?: "COL-???",
        name = getString("name"),
        phone = getString("phone") ?: "",
        role = getString("role"),
        birthDate = getString("birthDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        address = getString("address"),
        taxId = getString("taxId"),
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
        .append("birthDate", birthDate?.toString())
        .append("address", address)
        .append("taxId", taxId)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)

    private companion object {
        /** `timesheets.ShiftRepository.COLLECTION`, named here so the CRM doesn't depend on the time clock. */
        const val SHIFTS_COLLECTION = "timesheets.shifts"
    }
}
