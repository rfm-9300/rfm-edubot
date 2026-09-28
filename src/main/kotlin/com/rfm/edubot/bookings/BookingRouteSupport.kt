package com.rfm.edubot.bookings

import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.MIN_BOOKING_MINUTES
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantLocales
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
import kotlin.math.roundToLong

data class BookingDeps(
    val tenant: Tenant,
    val services: BookableServiceRepository,
    val availability: AvailabilityRepository,
    val bookings: BookingRepository,
    val scheduler: BookingScheduler,
    val source: BookingSource,
) {
    fun tools(source: BookingSource = this.source) = BookingTools(services, availability, bookings, scheduler, tenant.timezone, source)
}

/** Wires bookings to the tenant's CRM: clients get linked and completed bookings billed only when those modules are on. */
fun bookingDeps(mongo: MongoModule, tenant: Tenant, source: BookingSource): BookingDeps {
    val modules = DashboardModules.effectiveFor(tenant)
    val services = BookableServiceRepository(mongo, tenant.id)
    val availability = AvailabilityRepository(mongo, tenant.id)
    val bookings = BookingRepository(mongo, tenant.id)
    val crm = BookingCrmLink(
        clients = ClientRepository(mongo, tenant.id).takeIf { DashboardModules.CLIENTS in modules },
        clientServices = ClientServiceRepository(mongo, tenant.id).takeIf { DashboardModules.SERVICES in modules },
    )
    return BookingDeps(
        tenant = tenant,
        services = services,
        availability = availability,
        bookings = bookings,
        scheduler = BookingScheduler(services, availability, bookings, tenant.timezone, crm),
        source = source,
    )
}

/** Catalog fields for services created from Bookings (or migrated from the old booking list), in the tenant's language. */
internal object BookingCatalogDefaults {
    fun category(locale: String?): String = when (TenantLocales.normalize(locale)) {
        "en" -> "Bookings"
        "es" -> "Reservas"
        else -> "Marcações"
    }

    fun unit(locale: String?): String = when (TenantLocales.normalize(locale)) {
        "en" -> "session"
        "es" -> "sesión"
        else -> "sessão"
    }
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
            if (request.name.isBlank()) return@post call.respondError(HttpStatusCode.BadRequest, "name_required")
            if (request.durationMinutes < MIN_BOOKING_MINUTES) return@post call.respondError(HttpStatusCode.BadRequest, BookingScheduler.INVALID_TIME)
            val created = deps.services.create(
                name = request.name,
                durationMinutes = request.durationMinutes,
                priceCents = request.priceEur?.toCents() ?: 0,
                category = request.category?.trim()?.takeIf { it.isNotBlank() } ?: BookingCatalogDefaults.category(deps.tenant.locale),
                unit = request.unit?.trim()?.takeIf { it.isNotBlank() } ?: BookingCatalogDefaults.unit(deps.tenant.locale),
            )
            call.respond(HttpStatusCode.Created, created.dto())
        }
        post("/services/{id}") {
            val deps = call.resolve() ?: return@post
            val id = call.parameters["id"]?.takeIf { it.isNotBlank() } ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_id")
            val request = call.receive<UpdateBookingServiceRequest>()
            if (request.durationMinutes != null && request.durationMinutes < MIN_BOOKING_MINUTES) {
                return@post call.respondError(HttpStatusCode.BadRequest, BookingScheduler.INVALID_TIME)
            }
            val updated = deps.services.update(id, request.name, request.durationMinutes, request.priceEur?.toCents(), request.active)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(updated.dto())
        }
        delete("/services/{id}") {
            val deps = call.resolve() ?: return@delete
            val id = call.parameters["id"]?.takeIf { it.isNotBlank() } ?: return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_id")
            val updated = deps.services.update(id, name = null, durationMinutes = null, priceCents = null, active = false)
                ?: return@delete call.respond(HttpStatusCode.NotFound)
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
            if (invalid) return@put call.respondError(HttpStatusCode.BadRequest, "invalid_availability")
            val saved = deps.availability.replaceAll(request.rules.map { it.toRule(deps.tenant.id) })
            call.respond(saved.map { it.dto() })
        }

        get("/slots") {
            val deps = call.resolve() ?: return@get
            val serviceId = call.request.queryParameters["serviceId"]?.takeIf { it.isNotBlank() }
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "serviceId_required")
            val from = call.parseInstantParam("from") ?: return@get call.respondError(HttpStatusCode.BadRequest, "from_required")
            val to = call.parseInstantParam("to") ?: return@get call.respondError(HttpStatusCode.BadRequest, "to_required")
            call.respond(deps.scheduler.availableSlots(serviceId, from, to).map { it.dto() })
        }

        get {
            val deps = call.resolve() ?: return@get
            val from = call.parseInstantParam("from")
            val to = call.parseInstantParam("to")
            val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let {
                parseStatus(it) ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_status")
            }
            val clientId = call.request.queryParameters["clientId"]?.takeIf { it.isNotBlank() }?.let {
                runCatching { ObjectId(it) }.getOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_id")
            }
            val q = call.request.queryParameters["q"]
            call.respond(deps.bookings.list(from = from, to = to, status = status, query = q, clientId = clientId).map { it.dto() })
        }
        get("/{id}") {
            val deps = call.resolve() ?: return@get
            val id = call.objectIdParam("id") ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_id")
            val booking = deps.bookings.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(booking.dto())
        }
        post {
            val deps = call.resolve() ?: return@post
            val request = call.receive<CreateBookingRequest>()
            if (request.serviceId.isBlank()) return@post call.respondError(HttpStatusCode.BadRequest, BookingScheduler.SERVICE_NOT_FOUND)
            val startAt = parseBookingTime(request.startAt, deps.tenant.timezone)
                ?: return@post call.respondError(HttpStatusCode.BadRequest, BookingScheduler.INVALID_TIME)
            val status = request.status?.takeIf { it.isNotBlank() }?.let {
                parseStatus(it) ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_status")
            } ?: BookingStatus.PENDING
            val clientId = request.clientId?.takeIf { it.isNotBlank() }?.let {
                runCatching { ObjectId(it) }.getOrNull() ?: return@post call.respondError(HttpStatusCode.BadRequest, BookingScheduler.CLIENT_NOT_FOUND)
            }
            try {
                val booking = deps.scheduler.create(
                    NewBooking(
                        serviceId = request.serviceId.trim(),
                        startAt = startAt,
                        contactName = request.contactName,
                        contactPhone = request.contactPhone,
                        clientId = clientId,
                        notes = request.notes,
                        status = status,
                        source = deps.source,
                        durationMinutes = request.durationMinutes,
                        priceCents = request.priceEur?.toCents(),
                    )
                )
                call.respond(HttpStatusCode.Created, booking.dto())
            } catch (e: BookingConflictException) {
                call.respondError(HttpStatusCode.Conflict, BookingScheduler.CONFLICT)
            } catch (e: BookingRuleException) {
                call.respondError(HttpStatusCode.BadRequest, e.code)
            }
        }
        post("/{id}") {
            val deps = call.resolve() ?: return@post
            val id = call.objectIdParam("id") ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_id")
            val request = call.receive<UpdateBookingRequest>()
            val startAt = request.startAt?.takeIf { it.isNotBlank() }?.let {
                parseBookingTime(it, deps.tenant.timezone) ?: return@post call.respondError(HttpStatusCode.BadRequest, BookingScheduler.INVALID_TIME)
            }
            val status = request.status?.takeIf { it.isNotBlank() }?.let {
                parseStatus(it) ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_status")
            }
            val clientId = request.clientId?.takeIf { it.isNotBlank() }?.let {
                runCatching { ObjectId(it) }.getOrNull() ?: return@post call.respondError(HttpStatusCode.BadRequest, BookingScheduler.CLIENT_NOT_FOUND)
            }
            try {
                val booking = deps.scheduler.update(
                    id,
                    BookingChange(
                        serviceId = request.serviceId?.trim()?.takeIf { it.isNotBlank() },
                        startAt = startAt,
                        durationMinutes = request.durationMinutes,
                        status = status,
                        contactName = request.contactName,
                        contactPhone = request.contactPhone,
                        clientId = clientId,
                        clearClientId = request.clearClientId,
                        notes = request.notes,
                        priceCents = request.priceEur?.toCents(),
                        source = deps.source,
                    ),
                )
                call.respond(booking.dto())
            } catch (e: BookingConflictException) {
                call.respondError(HttpStatusCode.Conflict, BookingScheduler.CONFLICT)
            } catch (e: BookingRuleException) {
                val code = if (e.code == BookingScheduler.BOOKING_NOT_FOUND) HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                call.respondError(code, e.code)
            }
        }
    }
}

/** An ISO instant with offset ("…Z"), or a wall-clock "YYYY-MM-DDTHH:mm" in the tenant timezone. */
fun parseBookingTime(value: String, timezoneId: String): Instant? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    return runCatching { Instant.parse(trimmed) }.getOrNull()
        ?: runCatching { BookingScheduler.parseLocalDateTime(trimmed, timezoneId) }.getOrNull()
}

fun parseStatus(value: String): BookingStatus? = runCatching { BookingStatus.valueOf(value.trim().uppercase()) }.getOrNull()

private val TIME_RE = Regex("""^\d{2}:\d{2}$""")

private fun Double.toCents(): Long = (this * 100).roundToLong().coerceAtLeast(0)

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String) = respond(status, mapOf("error" to code))

private fun ApplicationCall.objectIdParam(name: String): ObjectId? =
    parameters[name]?.let { runCatching { ObjectId(it) }.getOrNull() }

private fun ApplicationCall.parseInstantParam(name: String): Instant? {
    val raw = request.queryParameters[name]?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(raw) }.getOrNull()
}
