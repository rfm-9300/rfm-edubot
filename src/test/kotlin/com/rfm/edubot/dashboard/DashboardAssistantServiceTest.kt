package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.OpenRouterFunctionCall
import com.rfm.edubot.ai.OpenRouterToolCall
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.bookings.AvailabilityRepository
import com.rfm.edubot.bookings.BookingServiceRepository
import com.rfm.edubot.bookings.model.AvailabilityRule
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItem
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardAssistantServiceTest {
    private val mongo = connectMongoOrNull()
    private val tenantId = ObjectId()
    private val ownerKey = "owner-assistant-test"
    private val tenant = Tenant(
        id = tenantId,
        slug = "assistant-test",
        name = "Assistant Test",
        channels = emptyList(),
        enabledModules = DashboardModules.catalog,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
    private val enabled = DashboardModules.catalog
    private val script = ArrayDeque<AiResponse>()
    private val offeredTools = mutableListOf<List<String>>()
    private val capturedToolResults = mutableListOf<JsonObject>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var service: DashboardAssistantService
    private var callSeq = 0
    private lateinit var threadId: ObjectId

    @BeforeTest
    fun setUp() {
        assumeTrue(mongo != null, "MongoDB is not reachable on localhost:27017")
        runBlocking {
            mongo!!.initialize()
            clearCollections()
            StandardItemRepository(mongo, tenantId).create(
                StandardItem("paint-1", "service", "painting", "Interior painting", "m2", 18.0),
            )
            BookingServiceRepository(mongo, tenantId).create("Consult", 60)
            AvailabilityRepository(mongo, tenantId).replaceAll(
                listOf(AvailabilityRule(tenantId = tenantId, dayOfWeek = 1, startLocal = "09:00", endLocal = "17:00")),
            )
        }
        script.clear()
        offeredTools.clear()
        capturedToolResults.clear()
        callSeq = 0
        service = DashboardAssistantService(
            mongo!!,
            AssistantChatCompleter { messages, tools, _ ->
                offeredTools += tools.map { it.name }
                messages.lastOrNull { it.role == "tool" }?.content?.let { payload ->
                    capturedToolResults += json.decodeFromString<JsonObject>(payload)
                }
                check(script.isNotEmpty()) { "scripted assistant exhausted" }
                script.removeFirst()
            },
        )
    }

    @AfterTest
    fun tearDown() {
        if (mongo != null) runBlocking { clearCollections() }
    }

    @Test
    fun `assistant can read and write every CRM and bookings feature`() = runBlocking {
        threadId = service.repository.createThread(tenantId, ownerKey, "Coverage").id

        askRead("catalog templates", "list_service_templates", buildJsonObject { put("query", "") }, "Found templates.")
        val templates = lastToolJson()
        assertTrue((templates["templates"] as JsonArray).isNotEmpty())

        askRead("catalog items", "list_standard_items", buildJsonObject { put("query", "paint") }, "Found catalog items.")
        val items = lastToolJson()["items"]!!.jsonArray
        assertEquals("Interior painting", items.first().jsonObject["description"]?.jsonPrimitive?.content)

        askRead("search clients", "search_clients", buildJsonObject { put("query", "Ana") }, "No clients yet.")
        assertEquals(0, lastToolJson()["clients"]!!.jsonArray.size)

        val clientAction = askWrite(
            "create client",
            "create_client",
            buildJsonObject {
                put("name", "Ana Costa")
                put("phone", "+351910000001")
                put("address", "Lisboa")
            },
            "Created Ana Costa.",
        )
        assertEquals("CONFIRMED", clientAction.status)
        val clientId = clientAction.result!!.jsonObject("client")["id"]!!.jsonPrimitive.content
        val clients = ClientRepository(mongo!!, tenantId).search("Ana")
        assertEquals(1, clients.size)
        assertEquals(clientId, clients.first().id.toHexString())

        val quoteAction = askWrite(
            "create quote",
            "create_quote",
            documentArgs(clientId, dueDate = null),
            "Created the quote.",
        )
        assertEquals("CONFIRMED", quoteAction.status)
        val quoteId = quoteAction.result!!.string("id")
        assertEquals("quote", quoteAction.result!!.string("type"))

        askRead("list quotes", "list_quotes", buildJsonObject { put("client_id", clientId) }, "One quote.")
        assertEquals(1, lastToolJson()["quotes"]!!.jsonArray.size)

        askRead("sum quotes", "sum_quotes_by_client", buildJsonObject {}, "Quote totals.")
        assertEquals("Ana Costa", lastToolJson()["totals"]!!.jsonArray.first().jsonObject["client_name"]!!.jsonPrimitive.content)

        val updateAction = askWrite(
            "update quote",
            "update_quote",
            buildJsonObject {
                put("quote_id", quoteId)
                put("status", "ACEITO")
            },
            "Marked the quote accepted.",
        )
        assertEquals("ACEITO", updateAction.result!!.string("status"))
        assertEquals("ACEITO", QuoteRepository(mongo, tenantId).findById(ObjectId(quoteId))?.status?.name)

        val invoiceAction = askWrite(
            "create invoice",
            "create_invoice",
            documentArgs(clientId, dueDate = "2026-10-01", quoteId = quoteId),
            "Created the invoice.",
        )
        assertEquals("CONFIRMED", invoiceAction.status)
        val invoiceId = invoiceAction.result!!.string("id")
        assertEquals("invoice", invoiceAction.result!!.string("type"))

        askRead("list invoices", "list_invoices", buildJsonObject { put("client_id", clientId) }, "One invoice.")
        assertEquals(1, lastToolJson()["invoices"]!!.jsonArray.size)

        askRead("sum invoices", "sum_invoices_by_client", buildJsonObject {}, "Invoice totals.")
        val invoiceTotals = lastToolJson()["totals"]!!.jsonArray.first().jsonObject
        assertEquals("Ana Costa", invoiceTotals["client_name"]!!.jsonPrimitive.content)
        assertTrue(invoiceTotals["pending_eur"]!!.jsonPrimitive.content.toDouble() > 0.0)

        val paidAction = askWrite(
            "mark invoice paid",
            "mark_invoice_paid",
            buildJsonObject { put("invoice_id", invoiceId) },
            "Marked the invoice paid.",
        )
        assertEquals("PAID", paidAction.result!!.string("status"))
        assertEquals("PAID", InvoiceRepository(mongo, tenantId).findById(ObjectId(invoiceId))?.status?.name)

        askRead("booking services", "list_booking_services", buildJsonObject { put("active_only", true) }, "Consult is bookable.")
        val serviceId = lastToolJson()["services"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

        askRead("availability", "list_availability", buildJsonObject {}, "Weekday hours.")
        assertEquals("Europe/Lisbon", lastToolJson()["timezone"]!!.jsonPrimitive.content)
        assertEquals(1, lastToolJson()["rules"]!!.jsonArray.size)

        askRead(
            "slots",
            "list_available_slots",
            buildJsonObject {
                put("service_id", serviceId)
                put("from", "2026-08-10T00:00:00Z")
                put("to", "2026-08-11T00:00:00Z")
            },
            "Monday slots.",
        )
        assertTrue(lastToolJson()["slots"]!!.jsonArray.isNotEmpty())

        askRead("list bookings", "list_bookings", buildJsonObject {}, "No bookings yet.")
        assertEquals(0, lastToolJson()["bookings"]!!.jsonArray.size)

        val bookingAction = askWrite(
            "create booking",
            "create_booking",
            buildJsonObject {
                put("service_id", serviceId)
                put("contact_name", "Ana Costa")
                put("contact_phone", "+351910000001")
                put("start_at", "2026-08-10T10:00")
                put("client_id", clientId)
                put("status", "PENDING")
            },
            "Booked Monday at 10.",
        )
        assertEquals("CONFIRMED", bookingAction.status)
        val bookingId = bookingAction.result!!.string("id")
        assertEquals("PENDING", bookingAction.result!!.string("status"))

        val confirmBooking = askWrite(
            "confirm booking",
            "confirm_booking",
            buildJsonObject { put("booking_id", bookingId) },
            "Booking confirmed.",
        )
        assertEquals("CONFIRMED", confirmBooking.result!!.string("status"))

        val cancelBooking = askWrite(
            "cancel booking",
            "cancel_booking",
            buildJsonObject { put("booking_id", bookingId) },
            "Booking cancelled.",
        )
        assertEquals("CANCELLED", cancelBooking.result!!.string("status"))

        offeredTools.forEach { names ->
            assertTrue(names.containsAll(DashboardAssistantToolPolicy.moduleByTool.keys), "full catalog should offer every CRM and booking tool")
        }
    }

    @Test
    fun `assistant cannot operate modules that have no tools and cannot write when a module is off`() = runBlocking {
        threadId = service.repository.createThread(tenantId, ownerKey, "Gaps").id
        val crmOnly = listOf(DashboardModules.OVERVIEW, DashboardModules.AI_ASSISTANT, DashboardModules.CLIENTS, DashboardModules.SERVICES)

        script += toolUse("list_invoices", buildJsonObject {})
        script += text("Invoices are not enabled.")
        service.reply(tenant, ownerKey, threadId, crmOnly, "Show my invoices")
        assertEquals("tool_not_allowed", lastToolJson()["error"]!!.jsonPrimitive.content)
        assertFalse(offeredTools.last().contains("list_invoices"))
        assertTrue("search_clients" in offeredTools.last())

        script += toolUse(
            "create_client",
            buildJsonObject {
                put("name", "Blocked")
                put("phone", "+351910000099")
            },
        )
        service.reply(tenant, ownerKey, threadId, enabled, "Create client Blocked")
        val pending = lastAction()
        assertEquals("PENDING", pending.status)

        script += text("That write is no longer allowed.")
        val noClients = listOf(DashboardModules.OVERVIEW, DashboardModules.AI_ASSISTANT)
        assertTrue(service.confirm(tenant, ownerKey, threadId, noClients, pending.id))
        assertEquals("FAILED", lastAction().status)
        assertEquals("action_not_allowed", lastAction().result!!.string("error"))
        assertTrue(ClientRepository(mongo!!, tenantId).search("Blocked").isEmpty())
    }

    @Test
    fun `cancelled writes do not change dashboard data`() = runBlocking {
        threadId = service.repository.createThread(tenantId, ownerKey, "Cancel").id
        script += toolUse(
            "create_client",
            buildJsonObject {
                put("name", "Cancelled Person")
                put("phone", "+351910000077")
            },
        )
        service.reply(tenant, ownerKey, threadId, enabled, "Create cancelled person")
        val pending = lastAction()
        assertTrue(service.repository.cancelAction(tenantId, ownerKey, threadId, pending.id))
        assertEquals("CANCELLED", service.repository.listMessages(tenantId, ownerKey, threadId).last { it.action != null }.action?.status)
        assertTrue(ClientRepository(mongo!!, tenantId).search("Cancelled Person").isEmpty())
    }

    private suspend fun askRead(prompt: String, tool: String, args: JsonObject, reply: String) {
        script += toolUse(tool, args)
        script += text(reply)
        service.reply(tenant, ownerKey, threadId, enabled, prompt)
        val last = service.repository.listMessages(tenantId, ownerKey, threadId).last { it.role == "assistant" }
        assertEquals(reply, last.content)
    }

    private suspend fun askWrite(prompt: String, tool: String, args: JsonObject, reply: String): AssistantAction {
        script += toolUse(tool, args)
        service.reply(tenant, ownerKey, threadId, enabled, prompt)
        val pending = lastAction()
        assertEquals(tool, pending.toolName)
        assertEquals("PENDING", pending.status)
        script += text(reply)
        assertTrue(service.confirm(tenant, ownerKey, threadId, enabled, pending.id))
        val confirmed = lastAction()
        assertEquals(reply, service.repository.listMessages(tenantId, ownerKey, threadId).last { it.role == "assistant" }.content)
        return confirmed
    }

    private suspend fun lastAction(): AssistantAction =
        service.repository.listMessages(tenantId, ownerKey, threadId).last { it.action != null }.action!!

    private fun lastToolJson(): JsonObject = capturedToolResults.last()

    private fun toolUse(name: String, args: JsonObject): AiResponse {
        val id = "call-${++callSeq}"
        return AiResponse.ToolUse(
            calls = listOf(ToolCall(id, name, args)),
            usage = null,
            responseId = id,
            message = ChatMessage(
                role = "assistant",
                toolCalls = listOf(OpenRouterToolCall(id, function = OpenRouterFunctionCall(name, args.toString()))),
            ),
        )
    }

    private fun text(content: String) = AiResponse.Text(content, null, "text-${++callSeq}")

    private fun documentArgs(clientId: String, dueDate: String?, quoteId: String? = null) = buildJsonObject {
        put("client_id", clientId)
        quoteId?.let { put("quote_id", it) }
        dueDate?.let { put("due_date", it) }
        put(
            "items",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("description", "Interior painting")
                        put("quantity", 2.0)
                        put("price_eur", 50.0)
                    },
                )
            },
        )
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.jsonObject(key: String): JsonObject = getValue(key).jsonObject

    private suspend fun clearCollections() {
        listOf(
            "dashboard_assistant_threads",
            "dashboard_assistant_messages",
            "crm.clients",
            "crm.quotes",
            "crm.invoices",
            "crm.sequences",
            "crm.standard_items",
            "bookings.services",
            "bookings.availability",
            "bookings.appointments",
        ).forEach { name ->
            mongo!!.database.getCollection<Document>(name).deleteMany(Document())
        }
    }

    companion object {
        fun connectMongoOrNull(): MongoModule? {
            val uri = System.getenv("TEST_MONGO_URI")
                ?: "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=2000"
            return try {
                val module = MongoModule(AppConfig.MongoConfig(uri = uri, database = "wabot_assistant_test"))
                runBlocking { module.database.runCommand(Document("ping", 1)) }
                module
            } catch (_: Exception) {
                null
            }
        }
    }
}
