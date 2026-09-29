package com.rfm.edubot.bookings

import com.mongodb.MongoWriteException
import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.bookings.model.TimeSlot
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.MIN_BOOKING_MINUTES
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap

class BookingConflictException(message: String) : Exception(message)

/** A booking rule was broken. [code] is stable so the dashboard and the AI tools can explain it. */
class BookingRuleException(val code: String) : IllegalArgumentException(code)

/** CRM modules bookings feed into; each is null when that module is off for the tenant. */
class BookingCrmLink(
    val clients: ClientRepository? = null,
    val clientServices: ClientServiceRepository? = null,
)

data class NewBooking(
    val serviceId: String,
    val startAt: Instant,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val clientId: ObjectId? = null,
    val notes: String? = null,
    val status: BookingStatus = BookingStatus.PENDING,
    val source: BookingSource = BookingSource.DASHBOARD,
    /** Staff override of the service's duration. */
    val durationMinutes: Int? = null,
    /** Staff override of the catalog price. */
    val priceCents: Long? = null,
)

/** Fields left null are kept. [source] is who makes the change: customer changes must fit opening hours. */
data class BookingChange(
    val serviceId: String? = null,
    val startAt: Instant? = null,
    val durationMinutes: Int? = null,
    val status: BookingStatus? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val clientId: ObjectId? = null,
    val clearClientId: Boolean = false,
    val notes: String? = null,
    val priceCents: Long? = null,
    val source: BookingSource = BookingSource.DASHBOARD,
)

class BookingScheduler(
    private val services: BookableServiceRepository,
    private val availability: AvailabilityRepository,
    private val bookings: BookingRepository,
    timezoneId: String,
    private val crm: BookingCrmLink = BookingCrmLink(),
    private val clock: () -> Instant = SystemClock::now,
) {
    private val zone: TimeZone = TimeZone.of(TenantTimeZones.normalize(timezoneId))

    /** Check-then-write on a tenant's calendar is serialized so two channels can't take the same slot (single app instance). */
    private val lock: Mutex = locks.computeIfAbsent(bookings.tenantId) { Mutex() }

    suspend fun availableSlots(serviceId: String, from: Instant, to: Instant): List<TimeSlot> {
        val service = services.findById(serviceId)?.takeIf { it.active } ?: return emptyList()
        val duration = service.durationMinutes ?: return emptyList()
        val rules = availability.list()
        if (rules.isEmpty()) return emptyList()
        val start = maxOf(from, clock())
        if (to <= start) return emptyList()
        return generateSlots(duration, rules, bookings.findOverlapping(start, to), start, to, zone)
    }

    suspend fun create(request: NewBooking): Booking {
        val service = services.findById(request.serviceId) ?: throw BookingRuleException(SERVICE_NOT_FOUND)
        if (!service.active) throw BookingRuleException(SERVICE_NOT_BOOKABLE)
        val staff = !request.source.isCustomer
        val duration = request.durationMinutes?.takeIf { staff } ?: service.durationMinutes
            ?: throw BookingRuleException(SERVICE_NOT_BOOKABLE)
        if (duration < MIN_BOOKING_MINUTES) throw BookingRuleException(INVALID_TIME)
        val startAt = request.startAt
        val endAt = startAt.plus(duration, DateTimeUnit.MINUTE)
        if (!staff) requireOpen(startAt, endAt)
        val client = request.clientId?.let { id -> crm.clients?.let { it.findById(id) ?: throw BookingRuleException(CLIENT_NOT_FOUND) } }
        val contactName = request.contactName?.trim()?.takeIf { it.isNotBlank() } ?: client?.name
        val contactPhone = request.contactPhone?.trim()?.takeIf { it.isNotBlank() } ?: client?.phone
        if (contactName.isNullOrBlank() || contactPhone.isNullOrBlank()) throw BookingRuleException(CONTACT_REQUIRED)
        val created = lock.withLock {
            if (bookings.findOverlapping(startAt, endAt).isNotEmpty()) throw BookingConflictException(CONFLICT)
            val now = clock()
            bookings.create(
                Booking(
                    tenantId = bookings.tenantId,
                    serviceId = service.id,
                    serviceName = service.name,
                    priceCents = request.priceCents?.takeIf { staff } ?: service.priceCents,
                    clientId = client?.id ?: linkClient(contactName, contactPhone),
                    contactName = contactName,
                    contactPhone = contactPhone,
                    startAt = startAt,
                    endAt = endAt,
                    status = request.status,
                    notes = request.notes?.trim()?.takeIf { it.isNotBlank() },
                    source = request.source,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        return if (created.status == BookingStatus.COMPLETED) billCompleted(created) else created
    }

    suspend fun update(id: ObjectId, change: BookingChange): Booking {
        val staff = !change.source.isCustomer
        val (saved, previous) = lock.withLock {
            val existing = bookings.findById(id) ?: throw BookingRuleException(BOOKING_NOT_FOUND)
            val newService = change.serviceId?.takeIf { it != existing.serviceId }?.let { serviceId ->
                services.findById(serviceId)?.takeIf { it.active } ?: throw BookingRuleException(SERVICE_NOT_BOOKABLE)
            }
            val currentMinutes = (existing.endAt - existing.startAt).inWholeMinutes.toInt()
            val duration = change.durationMinutes?.takeIf { staff } ?: newService?.durationMinutes ?: currentMinutes
            if (duration < MIN_BOOKING_MINUTES) throw BookingRuleException(INVALID_TIME)
            val startAt = change.startAt ?: existing.startAt
            val endAt = startAt.plus(duration, DateTimeUnit.MINUTE)
            val status = change.status ?: existing.status
            val timeChanged = startAt != existing.startAt || endAt != existing.endAt
            if (!staff && timeChanged) requireOpen(startAt, endAt)
            if (status.blocksSlot && (timeChanged || !existing.status.blocksSlot)) {
                if (bookings.findOverlapping(startAt, endAt, excludeId = id).isNotEmpty()) throw BookingConflictException(CONFLICT)
            }
            val client = change.clientId?.let { cid -> crm.clients?.let { it.findById(cid) ?: throw BookingRuleException(CLIENT_NOT_FOUND) } }
            val contactName = change.contactName?.trim()?.takeIf { it.isNotBlank() } ?: client?.name ?: existing.contactName
            val contactPhone = change.contactPhone?.trim()?.takeIf { it.isNotBlank() } ?: client?.phone ?: existing.contactPhone
            val clientId = when {
                client != null -> client.id
                change.clearClientId -> null
                existing.clientId != null -> existing.clientId
                else -> linkClient(contactName, contactPhone)
            }
            val next = existing.copy(
                serviceId = newService?.id ?: existing.serviceId,
                serviceName = newService?.name ?: existing.serviceName,
                priceCents = change.priceCents?.takeIf { staff } ?: newService?.priceCents ?: existing.priceCents,
                clientId = clientId,
                contactName = contactName,
                contactPhone = contactPhone,
                startAt = startAt,
                endAt = endAt,
                status = status,
                notes = if (change.notes != null) change.notes.trim().takeIf { it.isNotBlank() } else existing.notes,
                updatedAt = clock(),
            )
            (bookings.save(next) ?: throw BookingRuleException(BOOKING_NOT_FOUND)) to existing
        }
        return when {
            saved.status == BookingStatus.COMPLETED && previous.status != BookingStatus.COMPLETED -> billCompleted(saved)
            saved.status != BookingStatus.COMPLETED && previous.status == BookingStatus.COMPLETED -> unbill(saved)
            else -> saved
        }
    }

    suspend fun setStatus(id: ObjectId, status: BookingStatus, source: BookingSource = BookingSource.DASHBOARD): Booking =
        update(id, BookingChange(status = status, source = source))

    private suspend fun requireOpen(startAt: Instant, endAt: Instant) {
        if (startAt <= clock()) throw BookingRuleException(IN_PAST)
        if (!fitsAvailability(startAt, endAt, availability.list(), zone)) throw BookingRuleException(OUTSIDE_HOURS)
    }

    /**
     * The CRM client for this contact: matched by phone, or created so every booking has a customer record.
     * An archived client who books again is restored.
     */
    private suspend fun linkClient(name: String, phone: String): ObjectId? {
        val clients = crm.clients ?: return null
        clients.findByPhone(phone)?.let { found ->
            if (found.archivedAt != null) clients.setArchived(found.id, archived = false)
            return found.id
        }
        if (phone.count(Char::isDigit) < 6) return null
        return try {
            clients.create(name, phone).id
        } catch (e: MongoWriteException) {
            clients.findByPhone(phone)?.id
        }
    }

    /**
     * A completed booking becomes an open Serviços row on its client, ready to invoice. Bookings made
     * before prices were snapshotted have none, so they bill at the service's current catalog price.
     */
    private suspend fun billCompleted(booking: Booking): Booking {
        val rows = crm.clientServices ?: return booking
        val clientId = booking.clientId ?: return booking
        val performedAt = booking.startAt.toLocalDateTime(zone).date
        val row = booking.clientServiceId?.let { rows.findById(it) } ?: rows.findByBookingId(booking.id)
        val service = services.findById(booking.serviceId)
        val linked = when {
            row == null -> rows.create(
                clientId = clientId,
                name = booking.serviceName.ifBlank { booking.serviceId },
                notes = booking.notes,
                quantity = 1.0,
                unit = service?.unit.orEmpty(),
                unitPriceCents = booking.priceCents ?: service?.priceCents ?: 0,
                bookingServiceId = null,
                catalogItemId = booking.serviceId,
                performedAt = performedAt,
                bookingId = booking.id,
            )
            row.status == ClientServiceStatus.CANCELLED -> rows.update(
                id = row.id,
                name = booking.serviceName.ifBlank { row.name },
                notes = row.notes,
                quantity = row.quantity,
                unit = row.unit,
                unitPriceCents = booking.priceCents ?: row.unitPriceCents,
                performedAt = performedAt,
                status = ClientServiceStatus.OPEN,
            ) ?: row
            else -> row
        }
        if (booking.clientServiceId != linked.id) bookings.setClientServiceId(booking.id, linked.id)
        return booking.copy(clientServiceId = linked.id)
    }

    /** Undoing a completion cancels its Serviços row, unless it was already invoiced. */
    private suspend fun unbill(booking: Booking): Booking {
        val rows = crm.clientServices ?: return booking
        val row = booking.clientServiceId?.let { rows.findById(it) } ?: rows.findByBookingId(booking.id) ?: return booking
        if (row.status == ClientServiceStatus.OPEN) {
            rows.update(row.id, row.name, row.notes, row.quantity, row.unit, row.unitPriceCents, row.performedAt, ClientServiceStatus.CANCELLED)
        }
        return booking
    }

    companion object {
        const val SERVICE_NOT_FOUND = "service_not_found"
        const val SERVICE_NOT_BOOKABLE = "service_not_bookable"
        const val BOOKING_NOT_FOUND = "booking_not_found"
        const val CLIENT_NOT_FOUND = "client_not_found"
        const val CONTACT_REQUIRED = "contact_required"
        const val INVALID_TIME = "invalid_time"
        const val IN_PAST = "in_past"
        const val OUTSIDE_HOURS = "outside_hours"
        const val CONFLICT = "conflict"

        /** Slots that start mid-window (e.g. "from now") snap to this grid so times read like 10:30, not 10:17. */
        private const val SLOT_ALIGN_MINUTES = 15

        private val locks = ConcurrentHashMap<ObjectId, Mutex>()

        /**
         * Free slots of [durationMinutes] inside the weekly [rules]. After a clash the next candidate starts
         * where the clashing booking ends, so no gap is left behind a booking.
         */
        fun generateSlots(
            durationMinutes: Int,
            rules: List<AvailabilityRule>,
            existing: List<Booking>,
            from: Instant,
            to: Instant,
            zone: TimeZone,
        ): List<TimeSlot> {
            if (to <= from || durationMinutes <= 0) return emptyList()
            val rulesByDay = rules.groupBy { it.dayOfWeek }
            val blocking = existing.filter { it.status.blocksSlot }
            val slots = mutableListOf<TimeSlot>()
            var day = from.toLocalDateTime(zone).date
            val endDay = to.toLocalDateTime(zone).date
            while (day <= endDay) {
                for (rule in rulesByDay[day.dayOfWeek.ordinal + 1].orEmpty()) {
                    val windowStart = localInstant(day, rule.startLocal, zone) ?: continue
                    val windowEnd = localInstant(day, rule.endLocal, zone) ?: continue
                    if (windowEnd <= windowStart) continue
                    val hardEnd = minOf(windowEnd, to)
                    var cursor = if (from > windowStart) alignUp(from, windowStart, SLOT_ALIGN_MINUTES) else windowStart
                    while (cursor.plus(durationMinutes, DateTimeUnit.MINUTE) <= hardEnd) {
                        val slotEnd = cursor.plus(durationMinutes, DateTimeUnit.MINUTE)
                        val clashEnd = blocking.filter { it.startAt < slotEnd && it.endAt > cursor }.maxOfOrNull { it.endAt }
                        if (clashEnd == null) {
                            slots.add(TimeSlot(cursor, slotEnd))
                            cursor = slotEnd
                        } else {
                            cursor = clashEnd
                        }
                    }
                }
                day = day.plus(DatePeriod(days = 1))
            }
            return slots
        }

        /** True when [startAt]–[endAt] sits entirely inside one of that weekday's windows. */
        fun fitsAvailability(startAt: Instant, endAt: Instant, rules: List<AvailabilityRule>, zone: TimeZone): Boolean {
            val day = startAt.toLocalDateTime(zone).date
            return rules.filter { it.dayOfWeek == day.dayOfWeek.ordinal + 1 }.any { rule ->
                val windowStart = localInstant(day, rule.startLocal, zone) ?: return@any false
                val windowEnd = localInstant(day, rule.endLocal, zone) ?: return@any false
                startAt >= windowStart && endAt <= windowEnd
            }
        }

        fun parseLocalDateTime(value: String, timezoneId: String): Instant {
            val zone = TimeZone.of(TenantTimeZones.normalize(timezoneId))
            val normalized = value.trim().replace(' ', 'T')
            val local = when {
                normalized.length >= 19 -> LocalDateTime.parse(normalized.take(19))
                else -> LocalDateTime.parse(normalized)
            }
            return local.toInstant(zone)
        }

        private fun alignUp(at: Instant, origin: Instant, stepMinutes: Int): Instant {
            val stepMs = stepMinutes * 60_000L
            val offset = at.toEpochMilliseconds() - origin.toEpochMilliseconds()
            val steps = (offset + stepMs - 1) / stepMs
            return Instant.fromEpochMilliseconds(origin.toEpochMilliseconds() + steps * stepMs)
        }

        private fun localInstant(day: LocalDate, hhmm: String, zone: TimeZone): Instant? {
            val parts = hhmm.trim().split(':')
            if (parts.size < 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalDateTime(day, LocalTime(hour, minute)).toInstant(zone)
        }
    }
}
