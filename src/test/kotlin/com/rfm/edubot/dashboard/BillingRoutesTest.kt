package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Invoices' tax office code, installments, cancel and delete, payments' cancel and delete, and suppliers' usual services, over HTTP. */
class BillingRoutesTest {
    private val mongo = TestMongo.module("billing_routes")
    private val users = DashboardUserRepository(mongo)
    private val tenants = TenantRepository(mongo)
    private val now = Clock.System.now()
    private val json = Json { ignoreUnknownKeys = true }
    private val runtime = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
            mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = AppConfig.AdminConfig(jwtSecret = "test-secret"),
            pdfStoragePath = "/tmp/pdfs",
        ),
    )
    private val password = ObjectId().toHexString()

    private fun routesTest(block: suspend (HttpClient, String, Client) -> Unit): Unit = testApplication {
        val lookup: EmployeeLookup = { tenantId, id -> EmployeeRepository(mongo, tenantId).findById(id) }
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, users, lookup)
            routing {
                dashboardAccountRoutes(tenants, users, runtime, employees = lookup)
                dashboardRoutes(
                    mongo = mongo,
                    tenantRepository = tenants,
                    dashboardUsers = users,
                    pipelineFactory = mockk(relaxed = true),
                    personaCompiler = mockk(relaxed = true),
                    aiClient = mockk(relaxed = true),
                    runtimeConfig = runtime,
                    channelBindingService = mockk(relaxed = true),
                    instagramSocial = mockk(relaxed = true),
                )
            }
        }
        val id = ObjectId()
        val tenant = tenants.create(
            Tenant(id = id, slug = "t-${id.toHexString()}", name = "Obras Silva", channels = emptyList(), status = TenantStatus.ACTIVE, createdAt = now, updatedAt = now),
        )
        val admin = users.create(
            DashboardUser(
                tenantId = tenant.id, email = "admin-${id.toHexString()}@obras.test",
                passwordHash = BCrypt.withDefaults().hashToString(4, password.toCharArray()), role = DashboardUserRole.TENANT_ADMIN, createdAt = now,
            ),
        )
        val customer = ClientRepository(mongo, tenant.id).create("Casa Martins", "+351 920 ${id.toHexString().takeLast(6)}", "Rua do Sol 4", taxId = "123456789")
        val http = this.client
        val token = http.send("POST", "/app/auth/login", null, buildJsonObject { put("email", admin.email); put("password", password) }).obj().text("token")!!
        block(http, token, customer)
    }

    private suspend fun HttpClient.send(method: String, path: String, token: String?, body: JsonObject? = null): HttpResponse {
        val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { bearerAuth(it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return when (method) {
            "GET" -> get(path, request)
            "PATCH" -> patch(path, request)
            "PUT" -> put(path, request)
            "DELETE" -> delete(path, request)
            else -> post(path, request)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.error(): String? = obj()["error"]?.jsonPrimitive?.content
    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content
    private fun JsonObject.number(name: String): Double = this[name]!!.jsonPrimitive.double
    private fun JsonObject.list(name: String): JsonArray = (this[name] ?: JsonArray(emptyList())).jsonArray

    private fun invoiceBody(client: Client, eur: Double = 400.0, code: String? = null) = buildJsonObject {
        put("clientId", client.id.toHexString())
        put("dueDate", "2026-10-20")
        code?.let { put("taxOfficeCode", it) }
        putJsonArray("items") { addJsonObject { put("description", "Pintura"); put("unitPriceEur", eur) } }
    }

    private fun installments(vararg parts: Pair<Double, String>) = buildJsonObject {
        putJsonArray("installments") { parts.forEach { (eur, day) -> addJsonObject { put("amountEur", eur); put("dueDate", day) } } }
    }

    @Test
    fun `an invoice takes a tax office code and is paid in installments`() = routesTest { http, token, client ->
        assertEquals("tax_office_code_too_long", http.send("POST", "/app/api/crm/invoices", token, invoiceBody(client, code = "x".repeat(81))).error())
        val created = http.send("POST", "/app/api/crm/invoices", token, invoiceBody(client, code = "JJ4XTRK3-12"))
        assertEquals(HttpStatusCode.Created, created.status)
        val invoice = created.obj()
        val path = "/app/api/crm/invoices/${invoice.text("id")}"
        assertEquals("JJ4XTRK3-12", invoice.text("taxOfficeCode"))
        assertEquals(0.0, invoice.number("paidEur"), "sent even when nothing was received")
        assertEquals(400.0, invoice.number("outstandingEur"))

        assertEquals("installments_total_mismatch", http.send("PUT", "$path/installments", token, installments(200.0 to "2026-10-06", 150.0 to "2026-11-06")).error())
        assertEquals("installment_invalid", http.send("PUT", "$path/installments", token, installments(200.0 to "6/10", 200.0 to "2026-11-06")).error())
        val split = http.send("PUT", "$path/installments", token, installments(200.0 to "2026-11-06", 200.0 to "2026-10-06")).obj()
        assertEquals(listOf("2026-10-06", "2026-11-06"), split.list("installments").map { it.jsonObject.text("dueDate") })
        assertEquals("2026-10-06", split.text("dueDate"))

        val first = http.send("PATCH", "$path/installments/0/paid", token)
        assertEquals(HttpStatusCode.OK, first.status)
        val afterFirst = first.obj()
        assertEquals(200.0, afterFirst.number("paidEur"))
        assertEquals(200.0, afterFirst.number("outstandingEur"))
        assertEquals("2026-11-06", afterFirst.text("dueDate"))
        assertEquals(HttpStatusCode.Conflict, http.send("PATCH", "$path/installments/0/paid", token).status)
        assertEquals("installments_paid", http.send("POST", "$path/cancel", token).error())

        assertNull(http.send("PATCH", "$path/tax-office-code", token, buildJsonObject { put("taxOfficeCode", "") }).obj().text("taxOfficeCode"))
        assertEquals("PAID", http.send("PATCH", "$path/installments/1/paid", token).obj().text("status"))
    }

    @Test
    fun `cancelling or deleting an invoice frees its services and its quote`() = routesTest { http, token, client ->
        val service = http.send(
            "POST", "/app/api/crm/services", token,
            buildJsonObject { put("clientId", client.id.toHexString()); put("name", "Limpeza"); put("unitPriceEur", 80.0) },
        ).obj()
        val billed = http.send(
            "POST", "/app/api/crm/services/invoice", token,
            buildJsonObject { put("clientId", client.id.toHexString()); put("dueDate", "2026-10-20"); putJsonArray("serviceIds") { add(service.text("id")!!) } },
        ).obj()
        assertEquals("INVOICED", http.send("GET", "/app/api/crm/services/${service.text("id")}", token).obj().text("status"))
        assertEquals(HttpStatusCode.OK, http.send("DELETE", "/app/api/crm/invoices/${billed.text("id")}", token).status)
        assertEquals(HttpStatusCode.NotFound, http.send("GET", "/app/api/crm/invoices/${billed.text("id")}", token).status)
        val reopened = http.send("GET", "/app/api/crm/services/${service.text("id")}", token).obj()
        assertEquals("OPEN", reopened.text("status"))
        assertNull(reopened.text("invoiceId"))

        val quote = http.send(
            "POST", "/app/api/crm/quotes", token,
            buildJsonObject {
                put("clientId", client.id.toHexString())
                putJsonArray("items") { addJsonObject { put("description", "Pintura"); put("unitPriceEur", 300.0) } }
            },
        ).obj()
        val convert = "/app/api/crm/quotes/${quote.text("id")}/invoice"
        val due = buildJsonObject { put("dueDate", "2026-10-30") }
        val first = http.send("POST", convert, token, due).obj()
        assertEquals("already_invoiced", http.send("POST", convert, token, due).error())
        assertEquals("CANCELLED", http.send("POST", "/app/api/crm/invoices/${first.text("id")}/cancel", token).obj().text("status"))
        assertEquals("invoice_cancelled", http.send("POST", "/app/api/crm/invoices/${first.text("id")}/cancel", token).error())
        assertEquals(HttpStatusCode.Created, http.send("POST", convert, token, due).status, "a cancelled invoice frees its quote")
    }

    @Test
    fun `a payment is cancelled while unpaid and deleted whatever its state`() = routesTest { http, token, _ ->
        val supplier = http.send(
            "POST", "/app/api/crm/suppliers", token,
            buildJsonObject { put("name", "Tintas Norte"); put("phone", "+351 220 ${ObjectId().toHexString().takeLast(6)}") },
        ).obj()
        fun bill() = buildJsonObject {
            put("supplierId", supplier.text("id"))
            put("dueDate", "2026-10-20")
            putJsonArray("items") { addJsonObject { put("description", "Tinta"); put("unitPriceEur", 54.9) } }
        }
        val open = http.send("POST", "/app/api/crm/payments", token, bill()).obj()
        val openPath = "/app/api/crm/payments/${open.text("id")}"
        assertEquals("CANCELLED", http.send("POST", "$openPath/cancel", token).obj().text("status"))
        assertEquals("payment_cancelled", http.send("POST", "$openPath/cancel", token).error())
        assertEquals("CANCELLED", http.send("PATCH", "$openPath/paid", token).obj().text("status"), "a cancelled payment isn't marked paid")

        val paid = http.send("POST", "/app/api/crm/payments", token, bill()).obj()
        val paidPath = "/app/api/crm/payments/${paid.text("id")}"
        assertEquals("PAID", http.send("PATCH", "$paidPath/paid", token).obj().text("status"))
        val refused = http.send("POST", "$paidPath/cancel", token)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("payment_paid", refused.error())
        assertEquals(HttpStatusCode.OK, http.send("DELETE", paidPath, token).status)
        assertEquals(HttpStatusCode.NotFound, http.send("GET", paidPath, token).status)
        assertEquals(HttpStatusCode.NotFound, http.send("DELETE", paidPath, token).status)
    }

    @Test
    fun `a supplier's usual services are saved, kept when omitted and checked`() = routesTest { http, token, _ ->
        val body = buildJsonObject {
            put("name", "Luz & Cia")
            put("phone", "+351 220 ${ObjectId().toHexString().takeLast(6)}")
            putJsonArray("services") {
                addJsonObject { put("description", "Instalação elétrica"); put("unit", "h"); put("unitPriceEur", 25.0) }
                addJsonObject { put("description", "Certificação") }
            }
        }
        val created = http.send("POST", "/app/api/crm/suppliers", token, body)
        assertEquals(HttpStatusCode.Created, created.status)
        val supplier = created.obj()
        val services = supplier.list("services").map { it.jsonObject }
        assertEquals(listOf("Instalação elétrica", "Certificação"), services.map { it.text("description") })
        assertEquals(25.0, services[0].number("unitPriceEur"))
        assertNull(services[1]["unitPriceEur"], "a price that varies stays empty")

        val path = "/app/api/crm/suppliers/${supplier.text("id")}"
        val renamed = buildJsonObject { put("name", "Luz & Cia, Lda."); put("phone", body.text("phone")) }
        assertEquals(2, http.send("PATCH", path, token, renamed).obj().list("services").size, "a write without services keeps them")
        val cleared = buildJsonObject { renamed.forEach { (k, v) -> put(k, v) }; putJsonArray("services") {} }
        assertEquals(0, http.send("PATCH", path, token, cleared).obj().list("services").size)

        val negative = buildJsonObject {
            renamed.forEach { (k, v) -> put(k, v) }
            putJsonArray("services") { addJsonObject { put("description", "Instalação"); put("unitPriceEur", -5.0) } }
        }
        assertEquals("service_price_invalid", http.send("PATCH", path, token, negative).error())
    }
}
