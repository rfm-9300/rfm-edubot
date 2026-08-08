package com.rfm.edubot.bookings

import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.Instant
import org.bson.types.ObjectId

data class BookingDeps(
    val tenant: Tenant,
    val services: BookingServiceRepository,
    val availability: AvailabilityRepository,
    val bookings: BookingRepository,
    val scheduler: BookingScheduler,
    val source: BookingSource,
)

fun bookingDeps(mongo: MongoModule, tenant: Tenant, source: BookingSource): BookingDeps {
    val services = BookingServiceRepository(mongo, tenant.id)
    val availability = AvailabilityRepository(mongo, tenant.id)
    val bookings = BookingRepository(mongo, tenant.id)
    return BookingDeps(
        tenant = tenant,
        services = services,
        availability = availability,
        bookings = bookings,
        scheduler = BookingScheduler(services, availability, bookings, tenant.timezone),
        source = source,
    )
}

fun Route.installBookingRoutes(resolve: suspend ApplicationCall.() -> BookingDeps?) {
    route("/bookings") {
        get("/services") {
            val deps = call.resolve() ?: return@get
            val activeOnly = call.request.queryParameters["active"] == "true"
            call.respond(deps.services.list(activeOnly = activeOnly).map { it.dto() })
        }
        post("/services") {
            val deps = call.resolve() ?: return@post
            val request = call.receive<CreateBookingServiceRequest>()
            if (request.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name is required"))
            if (request.durationMinutes < 5) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "durationMinutes must be >= 5"))
            call.respond(HttpStatusCode.Created, deps.services.create(request.name, request.durationMinutes).dto())
        }
        post("/services/{id}") {
            val deps = call.resolve() ?: return@post
            val id = call.objectIdParam("id") ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            val request = call.receive<UpdateBookingServiceRequest>()
            val updated = deps.services.update(id, request.name, request.durationMinutes, request.active)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(updated.dto())
        }
        delete("/services/{id}") {
            val deps = call.resolve() ?: return@delete
            val id = call.objectIdParam("id") ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            val updated = deps.services.deactivate(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(updated.dto())
        }

        get("/availability") {
            val deps = call.resolve() ?: return@get
            call.respond(deps.availability.list().map { it.dto() })
        }
        put("/availability") {
            val deps = call.resolve() ?: return@put
            val request = call.receive<ReplaceAvailabilityRequest>()
            val invalid = request.rules.any {
                it.dayOfWeek !in 1..7 || !it.startLocal.matches(TIME_RE) || !it.endLocal.matches(TIME_RE) || it.endLocal <= it.startLocal
            }
            if (invalid) return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid availability rules"))
            val saved = deps.availability.replaceAll(request.rules.map { it.toRule(deps.tenant.id) })
            call.respond(saved.map { it.dto() })
        }

        get("/slots") {
            val deps = call.resolve() ?: return@get
            val serviceId = call.request.queryParameters["serviceId"]?.let { runCatching { ObjectId(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "serviceId is required"))
            val from = call.parseInstantParam("from") ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "from is required"))
            val to = call.parseInstantParam("to") ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "to is required"))
            call.respond(deps.scheduler.availableSlots(serviceId, from, to).map { it.dto() })
        }

        get {
            val deps = call.resolve() ?: return@get
            val from = call.parseInstantParam("from")
            val to = call.parseInstantParam("to")
            val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let {
                runCatching { BookingStatus.valueOf(it.uppercase()) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid status"))
            }
            val q = call.request.queryParameters["q"]
            call.respond(deps.bookings.list(from = from, to = to, status = status, query = q).map { it.dto() })
        }
        post {
            val deps = call.resolve() ?: return@post
            val request = call.receive<CreateBookingRequest>()
            if (request.contactName.isBlank() || request.contactPhone.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "contactName and contactPhone are required"))
            }
            val serviceId = runCatching { ObjectId(request.serviceId) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid serviceId"))
            val startAt = runCatching { BookingScheduler.parseLocalDateTime(request.startAt, deps.tenant.timezone) }.getOrNull()
                ?: runCatching { Instant.parse(request.startAt) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid startAt"))
            val status = request.status?.let { runCatching { BookingStatus.valueOf(it.uppercase()) }.getOrNull() } ?: BookingStatus.PENDING
            val clientId = request.clientId?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
            try {
                val booking = deps.scheduler.create(
                    serviceId = serviceId,
                    contactName = request.contactName,
                    contactPhone = request.contactPhone,
                    startAt = startAt,
                    clientId = clientId,
                    notes = request.notes,
                    status = status,
                    source = deps.source,
                )
                call.respond(HttpStatusCode.Created, booking.dto())
            } catch (e: BookingConflictException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid booking")))
            }
        }
        post("/{id}") {
            val deps = call.resolve() ?: return@post
            val id = call.objectIdParam("id") ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid id"))
            val request = call.receive<UpdateBookingRequest>()
            val existing = deps.bookings.findById(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            try {
                var booking = existing
                if (request.startAt != null || request.serviceId != null) {
                    val startAt = request.startAt?.let {
                        runCatching { BookingScheduler.parseLocalDateTime(it, deps.tenant.timezone) }.getOrNull()
                            ?: runCatching { Instant.parse(it) }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid startAt"))
                    } ?: existing.startAt
                    val serviceId = request.serviceId?.let { runCatching { ObjectId(it) }.getOrNull() }
                        ?: existing.serviceId
                    booking = deps.scheduler.reschedule(id, startAt, serviceId)
                }
                val status = request.status?.let { runCatching { BookingStatus.valueOf(it.uppercase()) }.getOrNull() }
                val clientId = request.clientId?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
                booking = deps.bookings.update(
                    id = id,
                    contactName = request.contactName,
                    contactPhone = request.contactPhone,
                    clientId = clientId,
                    clearClientId = request.clearClientId,
                    notes = request.notes,
                    status = status,
                ) ?: booking
                call.respond(booking.dto())
            } catch (e: BookingConflictException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "invalid booking")))
            }
        }
    }
}

private val TIME_RE = Regex("""^\d{2}:\d{2}$""")

private fun ApplicationCall.objectIdParam(name: String): ObjectId? =
    parameters[name]?.let { runCatching { ObjectId(it) }.getOrNull() }

private fun ApplicationCall.parseInstantParam(name: String): Instant? {
    val raw = request.queryParameters[name]?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(raw) }.getOrNull()
}
