package com.rfm.edubot.bookings

import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.bookings.model.BookingStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

class BookingTools(
    private val services: BookingServiceRepository,
    private val availability: AvailabilityRepository,
    private val bookings: BookingRepository,
    private val scheduler: BookingScheduler,
    private val timezoneId: String,
    private val source: BookingSource = BookingSource.WHATSAPP,
) {
    val definitions: List<ToolDefinition> = listOf(
        tool("list_booking_services", "List bookable services with duration in minutes", obj("active_only" to "boolean")),
        tool("list_availability", "List weekly availability windows (day_of_week 1=Mon..7=Sun, local HH:mm)", obj()),
        tool(
            "list_available_slots",
            "List free booking slots for a service between from and to (ISO-8601 or local YYYY-MM-DDTHH:mm in tenant timezone)",
            obj("service_id" to "string", "from" to "string", "to" to "string"),
            listOf("service_id", "from", "to"),
        ),
        tool(
            "list_bookings",
            "List bookings optionally filtered by from/to/status/query",
            obj("from" to "string", "to" to "string", "status" to "string", "query" to "string"),
        ),
        tool(
            "create_booking",
            "Create a booking. start_at is ISO-8601 or local YYYY-MM-DDTHH:mm. Prefer CONFIRMED after user confirmation.",
            obj(
                "service_id" to "string",
                "contact_name" to "string",
                "contact_phone" to "string",
                "start_at" to "string",
                "client_id" to "string",
                "notes" to "string",
                "status" to "string",
            ),
            listOf("service_id", "contact_name", "contact_phone", "start_at"),
        ),
        tool("cancel_booking", "Cancel a booking by id", obj("booking_id" to "string"), listOf("booking_id")),
        tool("confirm_booking", "Mark a pending booking as confirmed", obj("booking_id" to "string"), listOf("booking_id")),
    )

    val readOnlyDefinitions: List<ToolDefinition> = definitions.filter { it.name in READ_ONLY_TOOL_NAMES }

    fun knows(name: String): Boolean = name in TOOL_NAMES

    suspend fun execute(call: ToolCall): JsonObject = when (call.name) {
        "list_booking_services" -> listServices(call.arguments)
        "list_availability" -> listAvailability()
        "list_available_slots" -> listSlots(call.arguments)
        "list_bookings" -> listBookings(call.arguments)
        "create_booking" -> createBooking(call.arguments)
        "cancel_booking" -> setStatus(call.arguments, BookingStatus.CANCELLED)
        "confirm_booking" -> setStatus(call.arguments, BookingStatus.CONFIRMED)
        else -> buildJsonObject { put("error", "Unknown tool: ${call.name}") }
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
                            put("id", service.id.toHexString())
                            put("name", service.name)
                            put("duration_minutes", service.durationMinutes)
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
        val serviceId = ObjectId(args.string("service_id"))
        val from = parseInstant(args.string("from"))
        val to = parseInstant(args.string("to"))
        val slots = scheduler.availableSlots(serviceId, from, to).take(40)
        return buildJsonObject {
            put("timezone", timezoneId)
            put("slots", buildJsonArray {
                slots.forEach { slot ->
                    add(
                        buildJsonObject {
                            put("start_at", slot.startAt.toString())
                            put("end_at", slot.endAt.toString())
                            put("start_local", slot.startAt.toLocalDateTime(TimeZone.of(timezoneId)).toString())
                        }
                    )
                }
            })
        }
    }

    private suspend fun listBookings(args: JsonObject): JsonObject {
        val from = args.optionalString("from")?.let { parseInstant(it) }
        val to = args.optionalString("to")?.let { parseInstant(it) }
        val status = args.optionalString("status")?.let { BookingStatus.valueOf(it.uppercase()) }
        val query = args.optionalString("query")
        val items = bookings.list(from = from, to = to, status = status, query = query).take(50)
        return buildJsonObject {
            put("bookings", buildJsonArray {
                items.forEach { booking ->
                    add(
                        buildJsonObject {
                            put("id", booking.id.toHexString())
                            put("service_id", booking.serviceId.toHexString())
                            put("contact_name", booking.contactName)
                            put("contact_phone", booking.contactPhone)
                            put("start_at", booking.startAt.toString())
                            put("end_at", booking.endAt.toString())
                            put("status", booking.status.name)
                            put("notes", booking.notes)
                        }
                    )
                }
            })
        }
    }

    private suspend fun createBooking(args: JsonObject): JsonObject {
        return try {
            val booking = scheduler.create(
                serviceId = ObjectId(args.string("service_id")),
                contactName = args.string("contact_name"),
                contactPhone = args.string("contact_phone"),
                startAt = parseInstant(args.string("start_at")),
                clientId = args.optionalString("client_id")?.takeIf { ObjectId.isValid(it) }?.let { ObjectId(it) },
                notes = args.optionalString("notes"),
                status = args.optionalString("status")?.let { BookingStatus.valueOf(it.uppercase()) } ?: BookingStatus.CONFIRMED,
                source = source,
            )
            buildJsonObject {
                put("created", true)
                put("type", "booking")
                put("id", booking.id.toHexString())
                put("start_at", booking.startAt.toString())
                put("end_at", booking.endAt.toString())
                put("status", booking.status.name)
            }
        } catch (e: BookingConflictException) {
            buildJsonObject { put("error", "conflict"); put("message", e.message) }
        } catch (e: Exception) {
            buildJsonObject { put("error", "failed"); put("message", e.message ?: "create failed") }
        }
    }

    private suspend fun setStatus(args: JsonObject, status: BookingStatus): JsonObject {
        val id = ObjectId(args.string("booking_id"))
        val updated = bookings.update(id, status = status)
            ?: return buildJsonObject { put("error", "not_found") }
        return buildJsonObject {
            put("updated", true)
            put("type", "booking")
            put("id", updated.id.toHexString())
            put("status", updated.status.name)
        }
    }

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrElse {
            BookingScheduler.parseLocalDateTime(value, timezoneId)
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
        val WRITE_TOOL_NAMES = setOf("create_booking", "cancel_booking", "confirm_booking")
        val TOOL_NAMES = READ_ONLY_TOOL_NAMES + WRITE_TOOL_NAMES
    }
}
