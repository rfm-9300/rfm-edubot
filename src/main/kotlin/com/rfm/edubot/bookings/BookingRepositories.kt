package com.rfm.edubot.bookings

import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.bookings.model.BookingService
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date

class BookingServiceRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("bookings.services")

    suspend fun list(activeOnly: Boolean = false): List<BookingService> {
        val filter = if (activeOnly) {
            Filters.and(Filters.eq("tenantId", tenantId), Filters.eq("active", true))
        } else {
            Filters.eq("tenantId", tenantId)
        }
        return collection.find(filter).sort(Document("name", 1)).toList().map { it.toService() }
    }

    suspend fun findById(id: ObjectId): BookingService? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toService()

    suspend fun create(name: String, durationMinutes: Int): BookingService {
        val now = SystemClock.now()
        val service = BookingService(
            tenantId = tenantId,
            name = name.trim(),
            durationMinutes = durationMinutes.coerceAtLeast(5),
            active = true,
            createdAt = now,
            updatedAt = now,
        )
        collection.insertOne(service.toDocument())
        return service
    }

    suspend fun update(id: ObjectId, name: String?, durationMinutes: Int?, active: Boolean?): BookingService? {
        val ops = mutableListOf<Bson>(Updates.set("updatedAt", SystemClock.now().toDate()))
        name?.trim()?.takeIf { it.isNotBlank() }?.let { ops.add(Updates.set("name", it)) }
        durationMinutes?.let { ops.add(Updates.set("durationMinutes", it.coerceAtLeast(5))) }
        active?.let { ops.add(Updates.set("active", it)) }
        return collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(ops),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toService()
    }

    suspend fun deactivate(id: ObjectId): BookingService? = update(id, name = null, durationMinutes = null, active = false)

    private fun Document.toService() = BookingService(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        name = getString("name"),
        durationMinutes = getInteger("durationMinutes") ?: 30,
        active = getBoolean("active") ?: true,
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun BookingService.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("name", name)
        .append("durationMinutes", durationMinutes)
        .append("active", active)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

class AvailabilityRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("bookings.availability")

    suspend fun list(): List<AvailabilityRule> =
        collection.find(Filters.eq("tenantId", tenantId))
            .sort(Document("dayOfWeek", 1).append("startLocal", 1))
            .toList()
            .map { it.toRule() }

    suspend fun replaceAll(rules: List<AvailabilityRule>): List<AvailabilityRule> {
        collection.deleteMany(Filters.eq("tenantId", tenantId))
        if (rules.isEmpty()) return emptyList()
        val docs = rules.map { rule ->
            rule.copy(id = ObjectId(), tenantId = tenantId).toDocument()
        }
        collection.insertMany(docs)
        return list()
    }

    private fun Document.toRule() = AvailabilityRule(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        dayOfWeek = getInteger("dayOfWeek") ?: 1,
        startLocal = getString("startLocal") ?: "09:00",
        endLocal = getString("endLocal") ?: "17:00",
    )

    private fun AvailabilityRule.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("dayOfWeek", dayOfWeek)
        .append("startLocal", startLocal)
        .append("endLocal", endLocal)
}

class BookingRepository(mongoModule: MongoModule, private val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("bookings.appointments")

    suspend fun findById(id: ObjectId): Booking? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toBooking()

    suspend fun list(
        from: Instant? = null,
        to: Instant? = null,
        status: BookingStatus? = null,
        query: String? = null,
    ): List<Booking> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        from?.let { filters.add(Filters.gte("startAt", it.toDate())) }
        to?.let { filters.add(Filters.lt("startAt", it.toDate())) }
        status?.let { filters.add(Filters.eq("status", it.name)) }
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isNotBlank()) {
            filters.add(
                Filters.or(
                    Filters.regex("contactName", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("contactPhone", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("notes", ".*${Regex.escape(trimmed)}.*", "i"),
                )
            )
        }
        return collection.find(Filters.and(filters))
            .sort(Document("startAt", 1))
            .limit(500)
            .toList()
            .map { it.toBooking() }
    }

    suspend fun findOverlapping(startAt: Instant, endAt: Instant, excludeId: ObjectId? = null): List<Booking> {
        val filters = mutableListOf(
            Filters.eq("tenantId", tenantId),
            Filters.ne("status", BookingStatus.CANCELLED.name),
            Filters.lt("startAt", endAt.toDate()),
            Filters.gt("endAt", startAt.toDate()),
        )
        excludeId?.let { filters.add(Filters.ne("_id", it)) }
        return collection.find(Filters.and(filters)).toList().map { it.toBooking() }
    }

    suspend fun create(booking: Booking): Booking {
        collection.insertOne(booking.toDocument())
        return booking
    }

    suspend fun update(
        id: ObjectId,
        serviceId: ObjectId? = null,
        clientId: ObjectId? = null,
        clearClientId: Boolean = false,
        contactName: String? = null,
        contactPhone: String? = null,
        startAt: Instant? = null,
        endAt: Instant? = null,
        status: BookingStatus? = null,
        notes: String? = null,
    ): Booking? {
        val ops = mutableListOf<Bson>(Updates.set("updatedAt", SystemClock.now().toDate()))
        serviceId?.let { ops.add(Updates.set("serviceId", it)) }
        if (clearClientId) ops.add(Updates.unset("clientId")) else clientId?.let { ops.add(Updates.set("clientId", it)) }
        contactName?.trim()?.takeIf { it.isNotBlank() }?.let { ops.add(Updates.set("contactName", it)) }
        contactPhone?.trim()?.takeIf { it.isNotBlank() }?.let { ops.add(Updates.set("contactPhone", it)) }
        startAt?.let { ops.add(Updates.set("startAt", it.toDate())) }
        endAt?.let { ops.add(Updates.set("endAt", it.toDate())) }
        status?.let { ops.add(Updates.set("status", it.name)) }
        notes?.let { ops.add(Updates.set("notes", it.takeIf { n -> n.isNotBlank() })) }
        return collection.findOneAndUpdate(
            scoped(Filters.eq("_id", id)),
            Updates.combine(ops),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )?.toBooking()
    }

    private fun Document.toBooking() = Booking(
        id = getObjectId("_id"),
        tenantId = getObjectId("tenantId"),
        serviceId = getObjectId("serviceId"),
        clientId = getObjectId("clientId"),
        contactName = getString("contactName") ?: "",
        contactPhone = getString("contactPhone") ?: "",
        startAt = getInstant("startAt"),
        endAt = getInstant("endAt"),
        status = runCatching { BookingStatus.valueOf(getString("status") ?: BookingStatus.PENDING.name) }.getOrDefault(BookingStatus.PENDING),
        notes = getString("notes"),
        source = runCatching { BookingSource.valueOf(getString("source") ?: BookingSource.DASHBOARD.name) }.getOrDefault(BookingSource.DASHBOARD),
        createdAt = getInstant("createdAt"),
        updatedAt = getInstant("updatedAt"),
    )

    private fun Booking.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("serviceId", serviceId)
        .append("clientId", clientId)
        .append("contactName", contactName)
        .append("contactPhone", contactPhone)
        .append("startAt", startAt.toDate())
        .append("endAt", endAt.toDate())
        .append("status", status.name)
        .append("notes", notes)
        .append("source", source.name)
        .append("createdAt", createdAt.toDate())
        .append("updatedAt", updatedAt.toDate())

    private fun scoped(filter: Bson): Bson = Filters.and(Filters.eq("tenantId", tenantId), filter)
}

private fun Document.getInstant(field: String): Instant = Instant.fromEpochMilliseconds(getDate(field).time)

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
