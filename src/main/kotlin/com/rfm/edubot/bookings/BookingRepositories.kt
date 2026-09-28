package com.rfm.edubot.bookings

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.bookings.model.BookableService
import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.crm.MIN_BOOKING_MINUTES
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.isBookable
import com.rfm.edubot.crm.isService
import com.rfm.edubot.persistence.MongoModule
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.Date
import kotlin.math.roundToLong

/**
 * Bookable services are the tenant's catalog services (`crm.standard_items`, type service) — the same
 * list quotes, invoices and Serviços use. A service is offered for booking once it is flagged bookable
 * and has a duration.
 */
class BookableServiceRepository(mongoModule: MongoModule, tenantId: ObjectId) {
    private val items = StandardItemRepository(mongoModule, tenantId)

    /** Every catalog service; [activeOnly] keeps the ones offered for booking. */
    suspend fun list(activeOnly: Boolean = false): List<BookableService> =
        items.search().filter { it.isService() }.map { it.toBookable() }.filter { !activeOnly || it.active }

    suspend fun findById(id: String): BookableService? =
        items.findById(id)?.takeIf { it.isService() }?.toBookable()

    suspend fun create(name: String, durationMinutes: Int, priceCents: Long, category: String, unit: String): BookableService {
        val item = StandardItem(
            id = items.freeServiceId(name),
            type = "service",
            category = category.trim(),
            description = name.trim(),
            unit = unit.trim(),
            defaultUnitPriceEur = priceCents / 100.0,
            durationMinutes = durationMinutes.coerceAtLeast(MIN_BOOKING_MINUTES),
            bookable = true,
        )
        items.create(item)
        return item.toBookable()
    }

    suspend fun update(id: String, name: String?, durationMinutes: Int?, priceCents: Long?, active: Boolean?): BookableService? {
        val existing = items.findById(id)?.takeIf { it.isService() } ?: return null
        val nextDuration = durationMinutes?.coerceAtLeast(MIN_BOOKING_MINUTES) ?: existing.durationMinutes
        val next = existing.copy(
            description = name?.trim()?.takeIf { it.isNotBlank() } ?: existing.description,
            durationMinutes = if (active == true && nextDuration == null) DEFAULT_DURATION_MINUTES else nextDuration,
            defaultUnitPriceEur = priceCents?.let { it / 100.0 } ?: existing.defaultUnitPriceEur,
            bookable = active ?: existing.bookable,
        )
        return items.update(id, next)?.toBookable()
    }

    private fun StandardItem.toBookable() = BookableService(
        id = id,
        name = description,
        category = category,
        unit = unit,
        durationMinutes = durationMinutes,
        priceCents = (defaultUnitPriceEur * 100).roundToLong(),
        active = isBookable(),
    )

    companion object {
        const val DEFAULT_DURATION_MINUTES = 30
    }
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

class BookingRepository(private val mongoModule: MongoModule, val tenantId: ObjectId) {
    private val collection = mongoModule.database.getCollection<Document>("bookings.appointments")

    suspend fun findById(id: ObjectId): Booking? =
        collection.find(scoped(Filters.eq("_id", id))).firstOrNull()?.toBooking()?.let { withServiceNames(listOf(it)).single() }

    suspend fun list(
        from: Instant? = null,
        to: Instant? = null,
        status: BookingStatus? = null,
        query: String? = null,
        clientId: ObjectId? = null,
    ): List<Booking> {
        val filters = mutableListOf<Bson>(Filters.eq("tenantId", tenantId))
        from?.let { filters.add(Filters.gte("startAt", it.toDate())) }
        to?.let { filters.add(Filters.lt("startAt", it.toDate())) }
        status?.let { filters.add(Filters.eq("status", it.name)) }
        clientId?.let { filters.add(Filters.eq("clientId", it)) }
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isNotBlank()) {
            filters.add(
                Filters.or(
                    Filters.regex("contactName", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("contactPhone", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("serviceName", ".*${Regex.escape(trimmed)}.*", "i"),
                    Filters.regex("notes", ".*${Regex.escape(trimmed)}.*", "i"),
                )
            )
        }
        val rows = collection.find(Filters.and(filters))
            .sort(Document("startAt", 1))
            .limit(500)
            .toList()
            .map { it.toBooking() }
        return withServiceNames(rows)
    }

    /** Bookings that hold time between [startAt] and [endAt] (cancelled and no-show ones don't). */
    suspend fun findOverlapping(startAt: Instant, endAt: Instant, excludeId: ObjectId? = null): List<Booking> {
        val filters = mutableListOf(
            Filters.eq("tenantId", tenantId),
            Filters.`in`("status", BookingStatus.entries.filter { it.blocksSlot }.map { it.name }),
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

    /** Writes every mutable field of [booking]; returns null when it no longer exists. */
    suspend fun save(booking: Booking): Booking? {
        val result = collection.updateOne(
            scoped(Filters.eq("_id", booking.id)),
            Updates.combine(
                Updates.set("catalogItemId", booking.serviceId),
                Updates.set("serviceName", booking.serviceName),
                Updates.set("priceCents", booking.priceCents),
                Updates.set("clientId", booking.clientId),
                Updates.set("clientServiceId", booking.clientServiceId),
                Updates.set("contactName", booking.contactName),
                Updates.set("contactPhone", booking.contactPhone),
                Updates.set("startAt", booking.startAt.toDate()),
                Updates.set("endAt", booking.endAt.toDate()),
                Updates.set("status", booking.status.name),
                Updates.set("notes", booking.notes),
                Updates.set("updatedAt", booking.updatedAt.toDate()),
            ),
        )
        return if (result.matchedCount > 0) booking else null
    }

    suspend fun setClientServiceId(id: ObjectId, clientServiceId: ObjectId) {
        collection.updateOne(scoped(Filters.eq("_id", id)), Updates.set("clientServiceId", clientServiceId))
    }

    /** Fills [Booking.serviceName] for rows written before the name was stored on the booking. */
    private suspend fun withServiceNames(rows: List<Booking>): List<Booking> {
        val missing = rows.filter { it.serviceName.isBlank() }
        if (missing.isEmpty()) return rows
        val catalogNames = StandardItemRepository(mongoModule, tenantId)
            .findByIds(missing.map { it.serviceId }.filter { it.isNotBlank() }.toSet())
            .associate { it.id to it.description }
        val legacyIds = missing.mapNotNull { it.legacyServiceId }.distinct()
        val legacyNames = if (legacyIds.isEmpty()) {
            emptyMap()
        } else {
            mongoModule.database.getCollection<Document>("bookings.services")
                .find(scoped(Filters.`in`("_id", legacyIds)))
                .toList()
                .associate { it.getObjectId("_id") to (it.getString("name") ?: "") }
        }
        return rows.map { booking ->
            if (booking.serviceName.isNotBlank()) {
                booking
            } else {
                booking.copy(serviceName = catalogNames[booking.serviceId] ?: booking.legacyServiceId?.let { legacyNames[it] }.orEmpty())
            }
        }
    }

    private fun Document.toBooking(): Booking {
        val legacyServiceId = get("serviceId") as? ObjectId
        return Booking(
            id = getObjectId("_id"),
            tenantId = getObjectId("tenantId"),
            serviceId = getString("catalogItemId")?.takeIf { it.isNotBlank() } ?: legacyServiceId?.toHexString().orEmpty(),
            serviceName = getString("serviceName").orEmpty(),
            priceCents = (get("priceCents") as? Number)?.toLong(),
            clientId = get("clientId") as? ObjectId,
            clientServiceId = get("clientServiceId") as? ObjectId,
            contactName = getString("contactName") ?: "",
            contactPhone = getString("contactPhone") ?: "",
            startAt = getInstant("startAt"),
            endAt = getInstant("endAt"),
            status = runCatching { BookingStatus.valueOf(getString("status") ?: BookingStatus.PENDING.name) }.getOrDefault(BookingStatus.PENDING),
            notes = getString("notes"),
            source = runCatching { BookingSource.valueOf(getString("source") ?: BookingSource.DASHBOARD.name) }.getOrDefault(BookingSource.DASHBOARD),
            createdAt = getInstant("createdAt"),
            updatedAt = getInstant("updatedAt"),
            legacyServiceId = legacyServiceId,
        )
    }

    private fun Booking.toDocument() = Document("_id", id)
        .append("tenantId", tenantId)
        .append("catalogItemId", serviceId)
        .append("serviceName", serviceName)
        .append("priceCents", priceCents)
        .append("clientId", clientId)
        .append("clientServiceId", clientServiceId)
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
