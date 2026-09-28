package com.rfm.edubot.bookings

import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.bookings.model.Booking
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

/** The customer a conversation is with, so a booking made in that chat defaults to them. */
data class BookingCallContext(
    val source: BookingSource,
    val customerName: String? = null,
    val customerPhone: String? = null,
)

class BookingTools(
    private val services: BookableServiceRepository,
    private val availability: AvailabilityRepository,
    private val bookings: BookingRepository,
    private val scheduler: BookingScheduler,
    private val timezoneId: String,
    private val source: BookingSource = BookingSource.WHATSAPP,
) {
    private val zone = TimeZone.of(TenantTimeZones.normalize(timezoneId))

    val definitions: List<ToolDefinition> = listOf(
        tool("list_booking_services", "List the services customers can book (from the catalog), with id, duration in minutes and price in EUR", obj("active_only" to "boolean")),
        tool("list_availability", "List weekly opening hours for bookings (day_of_week 1=Mon..7=Sun, local HH:mm)", obj()),
        tool(
            "list_available_slots",
            "List free start times for a service between from and to (local YYYY-MM-DDTHH:mm in the tenant timezone, or ISO-8601). Only offer times this returns.",
            obj("service_id" to "string", "from" to "string", "to" to "string"),
            listOf("service_id", "from", "to"),
        ),
        tool(
            "list_bookings",
            "List bookings, optionally filtered by from/to, status (PENDING, CONFIRMED, CANCELLED, COMPLETED, NO_SHOW), client_id or a name/phone query",
            obj("from" to "string", "to" to "string", "status" to "string", "query" to "string", "client_id" to "string"),
        ),
        tool(
            "create_booking",
            "Create a booking after the person confirmed service, day and time. start_at is local YYYY-MM-DDTHH:mm in the tenant timezone. " +
                "Pass client_id for an existing CRM client (from search_clients). In a customer conversation, contact_name and contact_phone default to that customer — pass them only when booking for someone else. Status defaults to CONFIRMED.",
            obj(
                "service_id" to "string",
                "start_at" to "string",
                "contact_name" to "string",
                "contact_phone" to "string",
                "client_id" to "string",
                "notes" to "string",
                "status" to "string",
            ),
            listOf("service_id", "start_at"),
        ),
        tool(
            "reschedule_booking",
            "Move an existing booking to a new start time (local YYYY-MM-DDTHH:mm), optionally changing the service. Use this instead of cancelling and re-creating.",
            obj("booking_id" to "string", "start_at" to "string", "service_id" to "string"),
            listOf("booking_id", "start_at"),
        ),
        tool("cancel_booking", "Cancel a booking by id", obj("booking_id" to "string"), listOf("booking_id")),
        tool("confirm_booking", "Mark a pending booking as confirmed", obj("booking_id" to "string"), listOf("booking_id")),
    )

    val readOnlyDefinitions: List<ToolDefinition> = definitions.filter { it.name in READ_ONLY_TOOL_NAMES }

    fun knows(name: String): Boolean = name in TOOL_NAMES

    suspend fun execute(call: ToolCall, context: BookingCallContext? = null): JsonObject = try {
        when (call.name) {
            "list_booking_services" -> listServices(call.arguments)
            "list_availability" -> listAvailability()
            "list_available_slots" -> listSlots(call.arguments)
            "list_bookings" -> listBookings(call.arguments)
            "create_booking" -> createBooking(call.arguments, context)
            "reschedule_booking" -> rescheduleBooking(call.arguments, context)
            "cancel_booking" -> setStatus(call.arguments, BookingStatus.CANCELLED, context)
            "confirm_booking" -> setStatus(call.arguments, BookingStatus.CONFIRMED, context)
            else -> buildJsonObject { put("error", "Unknown tool: ${call.name}") }
        }
    } catch (e: BookingConflictException) {
        buildJsonObject {
            put("error", BookingScheduler.CONFLICT)
            put("message", "That time overlaps another booking. Call list_available_slots and offer the free times instead.")
        }
    } catch (e: BookingRuleException) {
        buildJsonObject {
            put("error", e.code)
            put("message", ruleHint(e.code))
        }
    }

    private suspend fun listServices(args: JsonObject): JsonObject {
        val activeOnly = args["active_only"]?.jsonPrimitive?.booleanOrNull
            ?: args["active_only"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: true
        val items = services.list(activeOnly = activeOnly)
        return buildJsonObject {
            put("services", buildJsonArray {
                items.forEach { service ->
                    add(
                        buildJsonObject {
                            put("id", service.id)
                            put("name", service.name)
                            put("category", service.category)
                            service.durationMinutes?.let { put("duration_minutes", it) }
                            put("price_eur", service.priceCents / 100.0)
                            put("active", service.active)
                        }
                    )
                }
            })
        }
    }

    private suspend fun listAvailability(): JsonObject {
        val rules = availability.list()
        return buildJsonObject {
            put("timezone", timezoneId)
            put("rules", buildJsonArray {
                rules.forEach { rule ->
                    add(
                        buildJsonObject {
                            put("day_of_week", rule.dayOfWeek)
                            put("start_local", rule.startLocal)
                            put("end_local", rule.endLocal)
                        }
                    )
                }
            })
        }
    }

    private suspend fun listSlots(args: JsonObject): JsonObject {
        val slots = scheduler.availableSlots(args.string("service_id"), parseInstant(args.string("from")), parseInstant(args.string("to"))).take(40)
        return buildJsonObject {
            put("timezone", timezoneId)
            put("slots", buildJsonArray {
                slots.forEach { slot ->
                    add(
                        buildJsonObject {
                            put("start_at", slot.startAt.toString())
                            put("end_at", slot.endAt.toString())
                            put("start_local", local(slot.startAt))
                        }
                    )
                }
            })
        }
    }

    private suspend fun listBookings(args: JsonObject): JsonObject {
        val from = args.optionalString("from")?.let { parseInstant(it) }
        val to = args.optionalString("to")?.let { parseInstant(it) }
        val status = args.optionalString("status")?.let { parseStatus(it) ?: throw BookingRuleException("invalid_status") }
        val clientId = args.optionalString("client_id")?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId)
        val items = bookings.list(from = from, to = to, status = status, query = args.optionalString("query"), clientId = clientId).take(50)
        return buildJsonObject {
            put("timezone", timezoneId)
            put("bookings", buildJsonArray { items.forEach { add(it.toJson()) } })
        }
    }

    private suspend fun createBooking(args: JsonObject, context: BookingCallContext?): JsonObject {
        val clientId = args.optionalString("client_id")?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId)
        val forCustomer = clientId == null
        val booking = scheduler.create(
            NewBooking(
                serviceId = args.string("service_id"),
                startAt = parseInstant(args.string("start_at")),
                contactName = args.optionalString("contact_name") ?: context?.customerName?.takeIf { forCustomer },
                contactPhone = args.optionalString("contact_phone") ?: context?.customerPhone?.takeIf { forCustomer },
                clientId = clientId,
                notes = args.optionalString("notes"),
                status = args.optionalString("status")?.let(::parseStatus) ?: BookingStatus.CONFIRMED,
                source = context?.source ?: source,
            )
        )
        return buildJsonObject {
            put("created", true)
            put("type", "booking")
            booking.fill(this)
        }
    }

    private suspend fun rescheduleBooking(args: JsonObject, context: BookingCallContext?): JsonObject {
        val booking = scheduler.update(
            bookingId(args),
            BookingChange(
                startAt = parseInstant(args.string("start_at")),
                serviceId = args.optionalString("service_id"),
                source = context?.source ?: source,
            ),
        )
        return buildJsonObject {
            put("updated", true)
            put("type", "booking")
            booking.fill(this)
        }
    }

    private suspend fun setStatus(args: JsonObject, status: BookingStatus, context: BookingCallContext?): JsonObject {
        val booking = scheduler.setStatus(bookingId(args), status, context?.source ?: source)
        return buildJsonObject {
            put("updated", true)
            put("type", "booking")
            booking.fill(this)
        }
    }

    private fun Booking.toJson(): JsonObject = buildJsonObject { fill(this) }

    private fun Booking.fill(target: JsonObjectBuilder) {
        target.put("id", id.toHexString())
        target.put("service_id", serviceId)
        target.put("service_name", serviceName)
        priceCents?.let { target.put("price_eur", it / 100.0) }
        target.put("contact_name", contactName)
        target.put("contact_phone", contactPhone)
        clientId?.let { target.put("client_id", it.toHexString()) }
        target.put("start_local", local(startAt))
        target.put("end_local", local(endAt))
        target.put("start_at", startAt.toString())
        target.put("status", status.name)
        notes?.let { target.put("notes", it) }
    }

    private fun local(instant: Instant): String = instant.toLocalDateTime(zone).toString()

    private fun bookingId(args: JsonObject): ObjectId =
        args.string("booking_id").takeIf { ObjectId.isValid(it) }?.let(::ObjectId) ?: throw BookingRuleException(BookingScheduler.BOOKING_NOT_FOUND)

    private fun parseInstant(value: String): Instant =
        parseBookingTime(value, timezoneId) ?: throw BookingRuleException(BookingScheduler.INVALID_TIME)

    private fun ruleHint(code: String): String = when (code) {
        BookingScheduler.CONTACT_REQUIRED -> "Ask for the name and phone number of the person the booking is for, then retry with contact_name and contact_phone."
        BookingScheduler.OUTSIDE_HOURS -> "That time is outside opening hours. Call list_available_slots and offer one of the free times."
        BookingScheduler.IN_PAST -> "That time has already passed. Offer a future time from list_available_slots."
        BookingScheduler.SERVICE_NOT_FOUND, BookingScheduler.SERVICE_NOT_BOOKABLE -> "That service can't be booked. Call list_booking_services and use one of its ids."
        BookingScheduler.BOOKING_NOT_FOUND -> "No booking with that id. Call list_bookings to find the right one."
        BookingScheduler.CLIENT_NOT_FOUND -> "No client with that id. Call search_clients again, or pass contact_name and contact_phone instead."
        else -> "The booking could not be saved. Do not claim it succeeded."
    }

    private fun tool(name: String, description: String, properties: JsonObject, required: List<String> = emptyList()) = ToolDefinition(
        name = name,
        description = description,
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", properties)
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        },
    )

    private fun obj(vararg fields: Pair<String, String>) = buildJsonObject {
        fields.forEach { (name, type) -> put(name, buildJsonObject { put("type", type) }) }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing $key")

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        val READ_ONLY_TOOL_NAMES = setOf(
            "list_booking_services",
            "list_availability",
            "list_available_slots",
            "list_bookings",
        )
        val WRITE_TOOL_NAMES = setOf("create_booking", "reschedule_booking", "cancel_booking", "confirm_booking")
        val TOOL_NAMES = READ_ONLY_TOOL_NAMES + WRITE_TOOL_NAMES
        val MODULE_OF_TOOL: Map<String, String> = TOOL_NAMES.associateWith { DashboardModules.BOOKINGS }
    }
}
