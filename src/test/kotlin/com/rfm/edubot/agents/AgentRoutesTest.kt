package com.rfm.edubot.agents

import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.agents.actions.AgentActions
import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentTask
import com.rfm.edubot.agents.model.Approvers
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepResult
import com.rfm.edubot.agents.model.StepStatus
import com.rfm.edubot.agents.registry.AgentRegistry
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.dashboardToken
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.events.Actor
import com.rfm.edubot.events.ActorContext
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.notificationRoutes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class AgentRoutesTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("agent_routes")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val now = Clock.System.now()
    private val tenants get() = TenantRepository(mongo)
    private val users get() = DashboardUserRepository(mongo)
    private val admin = AppConfig.AdminConfig(jwtSecret = "test-secret")
    private val runtimeConfig = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k"),
            mongo = AppConfig.MongoConfig(uri = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = admin,
            pdfStoragePath = "/tmp/pdfs",
        ),
    )
    private val services = AgentServices(mongo)
    private val module = AgentsModule(mongo, AgentRegistry(AgentActions.builtIn, TriggerTypes.all), services)
    private val runtime = AgentRuntime(module, { tenants.findById(it) }, CoroutineScope(Dispatchers.Default + SupervisorJob()))
    private val json = Json { ignoreUnknownKeys = true }

    private class Company(val tenant: Tenant, val adminToken: String, val memberToken: String)

    private suspend fun company(modules: List<String> = listOf(DashboardModules.CLIENTS, DashboardModules.INVOICES, DashboardModules.AGENTS)): Company {
        val tenant = tenants.create(
            Tenant(slug = "t-${ObjectId().toHexString().takeLast(8)}", name = "Obras", channels = emptyList(), enabledModules = modules, createdAt = now, updatedAt = now),
        )
        val adminUser = users.create(DashboardUser(tenantId = tenant.id, email = "admin-${ObjectId()}@example.pt", passwordHash = null, role = DashboardUserRole.TENANT_ADMIN, createdAt = now))
        val memberUser = users.create(DashboardUser(tenantId = tenant.id, email = "member-${ObjectId()}@example.pt", passwordHash = null, role = DashboardUserRole.TENANT_MEMBER, createdAt = now))
        return Company(tenant, dashboardToken(admin, adminUser, "tenant", 1), dashboardToken(admin, memberUser, "tenant", 1))
    }

    private fun routes(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            configureSerialization()
            configureAdminAuth(runtimeConfig, tenants, users)
            routing {
                agentRoutes(module, runtime, tenants)
                notificationRoutes(module.notifications)
            }
        }
        block(client)
    }

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: JsonObject? = null): HttpResponse = when (method) {
        "GET" -> get(path) { bearerAuth(token) }
        "PUT" -> put(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body.toString()) }
        else -> post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody((body ?: JsonObject(emptyMap())).toString()) }
    }

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private val validDefinition = buildJsonObject {
        put("triggers", json.parseToJsonElement("""[{"id":"t1","type":"event","config":{"event":"invoice.created"}}]"""))
        put("steps", json.parseToJsonElement("""[{"id":"s1","action":"team.notify","input":{"message":"Nova fatura {{invoice.number}}"}}]"""))
    }

    @Test
    fun `the module gate and the admin role decide who may do what`() = routes { http ->
        val company = company()
        val without = company(listOf(DashboardModules.CLIENTS))
        assertEquals(HttpStatusCode.Forbidden, http.send("GET", "/app/api/agents", without.adminToken).status)

        assertEquals(HttpStatusCode.Forbidden, http.send("POST", "/app/api/agents", company.memberToken, buildJsonObject { put("name", "X") }).status)
        val created = http.send("POST", "/app/api/agents", company.adminToken, buildJsonObject { put("name", "Faturas") })
        assertEquals(HttpStatusCode.Created, created.status)
        val draft = created.obj()
        assertEquals("DRAFT", draft["status"]!!.jsonPrimitive.content)
        assertTrue(draft["problems"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }.containsAll(listOf("no_trigger", "no_steps")))
        val id = draft["id"]!!.jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, http.send("GET", "/app/api/agents", company.memberToken).status, "members see the agents")
        assertEquals(HttpStatusCode.UnprocessableEntity, http.send("POST", "/app/api/agents/$id/activate", company.adminToken).status, "a draft with problems can't run")

        val saved = http.send("PUT", "/app/api/agents/$id", company.adminToken, buildJsonObject { put("definition", validDefinition) }).obj()
        assertEquals(0, saved["problems"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.Forbidden, http.send("POST", "/app/api/agents/$id/activate", company.memberToken).status)
        val active = http.send("POST", "/app/api/agents/$id/activate", company.adminToken).obj()
        assertEquals("ACTIVE", active["status"]!!.jsonPrimitive.content)

        val other = company()
        assertEquals(HttpStatusCode.NotFound, http.send("GET", "/app/api/agents/$id", other.adminToken).status, "another company never sees it")
    }

    @Test
    fun `the backoffice limit caps active agents`() = routes { http ->
        val company = company()
        module.settings.savePlatform(company.tenant.id, module.settings.get(company.tenant.id).platform.copy(maxActiveAgents = 1))
        val ids = (1..2).map {
            val created = http.send("POST", "/app/api/agents", company.adminToken, buildJsonObject { put("name", "A$it"); put("definition", validDefinition) }).obj()
            created["id"]!!.jsonPrimitive.content
        }
        assertEquals(HttpStatusCode.OK, http.send("POST", "/app/api/agents/${ids[0]}/activate", company.adminToken).status)
        val second = http.send("POST", "/app/api/agents/${ids[1]}/activate", company.adminToken)
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals("agent_limit", second.obj()["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `members decide approvals unless the agent asks for admins, and a decision happens once`() = routes { http ->
        val company = company()
        fun approval(approvers: Approvers) = AgentApproval(
            tenantId = company.tenant.id, agentId = ObjectId(), agentName = "Lembretes", runId = ObjectId(), stepId = "s1",
            action = "team.notify", input = buildJsonObject { put("message", "Olá") },
            preview = ActionPreview(kind = "notify", body = "Olá", editable = listOf("message")),
            approvers = approvers, createdAt = now, expiresAt = now + 3.days,
        )
        val open = runBlocking { module.approvals.insert(approval(Approvers.ANY_MEMBER)) }
        val adminsOnly = runBlocking { module.approvals.insert(approval(Approvers.ADMINS)) }

        val listed = json.parseToJsonElement(http.send("GET", "/app/api/agents/approvals", company.memberToken).bodyAsText()).jsonArray
        assertEquals(setOf(true, false), listed.map { it.jsonObject["canDecide"]!!.jsonPrimitive.content.toBoolean() }.toSet())
        assertEquals(HttpStatusCode.Forbidden, http.send("POST", "/app/api/agents/approvals/${adminsOnly.id}/approve", company.memberToken).status)
        val approved = http.send("POST", "/app/api/agents/approvals/${open.id}/approve", company.memberToken, buildJsonObject { put("input", buildJsonObject { put("message", "Olá, Ana"); put("audience", "everyone") }) })
        assertEquals(HttpStatusCode.OK, approved.status)
        val input = approved.obj()["input"]!!.jsonObject
        assertEquals("Olá, Ana", input["message"]!!.jsonPrimitive.content)
        assertEquals(null, input["audience"], "fields that aren't editable are ignored")
        assertEquals(HttpStatusCode.Conflict, http.send("POST", "/app/api/agents/approvals/${open.id}/reject", company.adminToken).status)
    }

    @Test
    fun `notifications reach their readers and can be marked read`() = routes { http ->
        val company = company()
        runBlocking { module.notifications.notify(company.tenant.id, "agent_failed", params = mapOf("agent" to "Lembretes")) }

        val adminView = http.send("GET", "/app/api/notifications", company.adminToken).obj()
        assertEquals(1L, adminView["unread"]!!.jsonPrimitive.content.toLong())
        assertEquals(0L, http.send("GET", "/app/api/notifications", company.memberToken).obj()["unread"]!!.jsonPrimitive.content.toLong(), "admin notices stay with admins")
        http.send("POST", "/app/api/notifications/read-all", company.adminToken)
        assertEquals(0L, http.send("GET", "/app/api/notifications", company.adminToken).obj()["unread"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `approvals and tasks open by id, and notifications and record activity say what to open`() = routes { http ->
        val company = company()
        val other = company()
        val approval = runBlocking {
            module.approvals.insert(
                AgentApproval(
                    tenantId = company.tenant.id, agentId = ObjectId(), agentName = "Lembretes", runId = ObjectId(), stepId = "s1",
                    action = "team.notify", input = buildJsonObject { put("message", "Olá") },
                    preview = ActionPreview(kind = "notify", body = "Olá"),
                    approvers = Approvers.ANY_MEMBER, createdAt = now, expiresAt = now + 3.days,
                ),
            )
        }
        val path = "/app/api/agents/approvals/${approval.id}"
        assertEquals(true, http.send("GET", path, company.memberToken).obj()["canDecide"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound, http.send("GET", path, other.adminToken).status)
        assertEquals(HttpStatusCode.BadRequest, http.send("GET", "/app/api/agents/approvals/nope", company.adminToken).status)
        http.send("POST", "$path/reject", company.adminToken)
        val decided = http.send("GET", path, company.adminToken).obj()
        assertEquals("REJECTED", decided["status"]!!.jsonPrimitive.content)
        assertEquals(false, decided["canDecide"]!!.jsonPrimitive.boolean, "a decided approval is read-only")

        val task = http.send("POST", "/app/api/agents/tasks", company.memberToken, buildJsonObject { put("title", "Ligar à Ana") }).obj()
        val taskId = task["id"]!!.jsonPrimitive.content
        assertEquals("Ligar à Ana", http.send("GET", "/app/api/agents/tasks/$taskId", company.memberToken).obj()["title"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, http.send("GET", "/app/api/agents/tasks/$taskId", other.adminToken).status)

        runBlocking { module.notifications.notify(company.tenant.id, "agent_task", params = mapOf("title" to "Ligar à Ana"), link = "agents", ref = "task:$taskId") }
        val notice = http.send("GET", "/app/api/notifications", company.adminToken).obj()["items"]!!.jsonArray.first().jsonObject
        assertEquals("task:$taskId", notice["ref"]!!.jsonPrimitive.content)

        val clientId = ObjectId().toHexString()
        val runId = ObjectId()
        runBlocking {
            withContext(ActorContext(Actor.agent(ObjectId(), runId, "Lembretes"))) {
                DomainEventLog(mongo).append(company.tenant.id, DomainEventTypes.CLIENT_UPDATED, SubjectRef(SubjectTypes.CLIENT, clientId))
            }
        }
        val activity = http.send("GET", "/app/api/agents/subjects/client/$clientId", company.adminToken).obj()["activity"]!!.jsonArray.single().jsonObject
        assertEquals(runId.toHexString(), activity["runId"]!!.jsonPrimitive.content, "the record's timeline links each agent change to its run")
        assertEquals("Lembretes", activity["agentName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a record lists its upcoming runs and what finished runs did`() = routes { http ->
        val company = company()
        val subject = SubjectRef(SubjectTypes.QUOTE, ObjectId().toHexString())
        fun run(status: RunStatus, vararg steps: StepResult) = AgentRun(
            tenantId = company.tenant.id, agentId = ObjectId(), agentName = "Seguimento", agentVersion = 1, definition = AgentDefinition(),
            trigger = RunTrigger(type = "event", firedAt = now), subject = subject, dedupeKey = ObjectId().toHexString(), status = status,
            steps = steps.toList(), createdAt = now, updatedAt = now,
        )
        runBlocking {
            module.runs.insertIfAbsent(
                run(
                    RunStatus.SUCCEEDED,
                    StepResult("s1", "flow.wait", StepStatus.DONE),
                    StepResult("s2", "whatsapp.send", StepStatus.DONE),
                    StepResult("s3", "team.notify", StepStatus.SKIPPED),
                    StepResult("s4", "whatsapp.send", StepStatus.DONE),
                ),
            )
            module.runs.insertIfAbsent(run(RunStatus.WAITING, StepResult("s1", "flow.wait", StepStatus.WAITING)))
        }
        val view = http.send("GET", "/app/api/agents/subjects/quote/${subject.id}", company.memberToken).obj()
        assertEquals("WAITING", view["upcoming"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(0, view["upcoming"]!!.jsonArray.single().jsonObject["done"]!!.jsonArray.size, "an empty list still reaches the page")
        val done = view["recent"]!!.jsonArray.single().jsonObject["done"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("whatsapp.send"), done, "each action once, without flow steps or skipped ones")
    }

    @Test
    fun `a client's record covers work on its documents, and a document shows the runs that changed it`() = routes { http ->
        val company = company()
        val clientId = ObjectId()
        val invoice = SubjectRef(SubjectTypes.INVOICE, ObjectId().toHexString())
        val quote = SubjectRef(SubjectTypes.QUOTE, ObjectId().toHexString())
        fun run(subject: SubjectRef, status: RunStatus, client: ObjectId?) = AgentRun(
            tenantId = company.tenant.id, agentId = ObjectId(), agentName = "Lembrete", agentVersion = 1, definition = AgentDefinition(),
            trigger = RunTrigger(type = "event", firedAt = now), subject = subject, clientId = client, dedupeKey = ObjectId().toHexString(),
            status = status, createdAt = now, updatedAt = now,
        )
        val reminder = run(invoice, RunStatus.WAITING, clientId)
        val invoicing = run(quote, RunStatus.SUCCEEDED, null)
        runBlocking {
            module.runs.insertIfAbsent(reminder)
            module.runs.insertIfAbsent(invoicing)
            module.runs.insertIfAbsent(run(invoice, RunStatus.SUCCEEDED, clientId).copy(dryRun = true))
            module.tasks.insert(AgentTask(tenantId = company.tenant.id, title = "Ligar à Ana", subject = invoice, clientId = clientId, createdAt = now, updatedAt = now))
            withContext(ActorContext(Actor.agent(invoicing.agentId, invoicing.id, "Faturar"))) {
                DomainEventLog(mongo).append(company.tenant.id, DomainEventTypes.INVOICE_CREATED, invoice)
            }
        }
        val client = http.send("GET", "/app/api/agents/subjects/client/$clientId", company.memberToken).obj()
        assertEquals(reminder.id.toHexString(), client["upcoming"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content, "the reminder on the client's invoice, not the test run")
        assertEquals(0, client["recent"]!!.jsonArray.size)
        assertEquals("Ligar à Ana", client["tasks"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)

        val onInvoice = http.send("GET", "/app/api/agents/subjects/invoice/${invoice.id}", company.memberToken).obj()
        assertEquals(invoicing.id.toHexString(), onInvoice["recent"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content, "the quote's run that issued this invoice")
        assertEquals(reminder.id.toHexString(), onInvoice["upcoming"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `any member can pause automations for a client, and the record says so`() = routes { http ->
        val company = company()
        val client = runBlocking { ClientRepository(mongo, company.tenant.id).create("Ana Silva", "+351910000001") }
        val path = "/app/api/agents/subjects/client/${client.id}/automation"
        val paused = http.send("PUT", path, company.memberToken, buildJsonObject { put("paused", true) })
        assertEquals(HttpStatusCode.OK, paused.status)
        assertEquals(true, paused.obj()["automationPaused"]!!.jsonPrimitive.boolean)
        assertEquals(true, runBlocking { ClientRepository(mongo, company.tenant.id).findById(client.id)!!.automationPaused })
        assertEquals(true, http.send("GET", "/app/api/agents/subjects/client/${client.id}", company.memberToken).obj()["automationPaused"]!!.jsonPrimitive.boolean)

        assertEquals(false, http.send("PUT", path, company.adminToken, buildJsonObject { put("paused", false) }).obj()["automationPaused"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.BadRequest, http.send("PUT", path, company.adminToken, buildJsonObject { put("paused", "maybe") }).status)
        assertEquals(HttpStatusCode.NotFound, http.send("PUT", "/app/api/agents/subjects/client/${client.id}/automation", company().adminToken, buildJsonObject { put("paused", true) }).status, "another company's client")
        val noClients = company(listOf(DashboardModules.INVOICES, DashboardModules.AGENTS))
        assertEquals(HttpStatusCode.Forbidden, http.send("PUT", path, noClients.adminToken, buildJsonObject { put("paused", true) }).status)
    }

    @Test
    fun `a template becomes a draft in the company's language, and bad answers are refused`() = routes { http ->
        val company = company()
        val created = http.send(
            "POST", "/app/api/agents", company.adminToken,
            buildJsonObject {
                put("templateKey", "weekly_cash_briefing")
                put("params", buildJsonObject { put("weekday", 1); put("time", "18:00") })
            },
        )
        assertEquals(HttpStatusCode.Created, created.status)
        val agent = created.obj()
        assertEquals("Resumo semanal de tesouraria", agent["name"]!!.jsonPrimitive.content)
        assertEquals("weekly_cash_briefing", agent["templateKey"]!!.jsonPrimitive.content)
        assertEquals(0, agent["problems"]!!.jsonArray.size)
        val id = agent["id"]!!.jsonPrimitive.content
        assertEquals("ACTIVE", http.send("POST", "/app/api/agents/$id/activate", company.adminToken).obj()["status"]!!.jsonPrimitive.content)

        val refused = http.send(
            "POST", "/app/api/agents", company.adminToken,
            buildJsonObject { put("templateKey", "weekly_cash_briefing"); put("params", buildJsonObject { put("time", "7pm") }) },
        )
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals("invalid_params", refused.obj()["error"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, http.send("POST", "/app/api/agents", company.adminToken, buildJsonObject { put("templateKey", "nope") }).status)

        val templates = http.send("GET", "/app/api/agents/catalog", company.adminToken).obj()["templates"]!!.jsonArray.map { it.jsonObject }
        fun available(key: String) = templates.single { it["key"]!!.jsonPrimitive.content == key }["available"]!!.jsonPrimitive.content.toBoolean()
        assertTrue(available("weekly_cash_briefing"))
        assertTrue(!available("invoice_due_reminder"), "no WhatsApp connected")
    }

    @Test
    fun `the catalog says what a company can't use yet and why`() = routes { http ->
        val company = company()
        val catalog = http.send("GET", "/app/api/agents/catalog", company.adminToken).obj()
        val whatsapp = catalog["actions"]!!.jsonArray.map { it.jsonObject }.single { it["key"]!!.jsonPrimitive.content == "whatsapp.send" }
        assertEquals("false", whatsapp["available"]!!.jsonPrimitive.content)
        assertEquals("needs_integration:WHATSAPP", whatsapp["reason"]!!.jsonPrimitive.content)
        val bookingEvent = catalog["events"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "booking.created" }
        assertEquals("needs_module:bookings", bookingEvent["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `settings, test runs and approvals carry every field the dashboard reads, defaults included`() = routes { http ->
        val company = company()
        val id = http.send("POST", "/app/api/agents", company.adminToken, buildJsonObject { put("name", "Faturas"); put("definition", validDefinition) }).obj()["id"]!!.jsonPrimitive.content
        val test = http.send("POST", "/app/api/agents/$id/test", company.adminToken)
        assertEquals(HttpStatusCode.OK, test.status)
        val planned = test.obj()["plan"]!!.jsonArray.single().jsonObject
        assertEquals("s1", planned["id"]!!.jsonPrimitive.content)
        assertEquals("team.notify", planned["action"]!!.jsonPrimitive.content)
        val listed = json.parseToJsonElement(http.send("GET", "/app/api/agents/runs?tests=1&agentId=$id", company.adminToken).bodyAsText()).jsonArray.single().jsonObject
        assertEquals(null, listed["plan"], "run lists leave the plan out")

        runBlocking {
            module.approvals.insert(
                AgentApproval(
                    tenantId = company.tenant.id, agentId = ObjectId(), agentName = "Lembretes", runId = ObjectId(), stepId = "s1",
                    action = "team.notify", input = buildJsonObject { put("message", "Olá") },
                    preview = ActionPreview(kind = "notify", body = "Olá"),
                    approvers = Approvers.ANY_MEMBER, createdAt = now, expiresAt = now + 3.days,
                ),
            )
        }
        val preview = json.parseToJsonElement(http.send("GET", "/app/api/agents/approvals", company.adminToken).bodyAsText()).jsonArray.single().jsonObject["preview"]!!.jsonObject
        assertEquals(setOf("kind", "recipients", "body", "attachments", "fields", "editable", "warnings"), preview.keys)

        val settings = http.send("GET", "/app/api/agents/settings", company.memberToken).obj()
        val defaults = settings["company"]!!.jsonObject
        assertEquals(false, defaults["paused"]!!.jsonPrimitive.boolean)
        assertEquals("APPROVE", defaults["defaultAutonomy"]!!.jsonPrimitive.content)
        assertEquals(2, defaults["perRecipientDailyCap"]!!.jsonPrimitive.int)
        assertEquals(3, defaults["approvalExpiryDays"]!!.jsonPrimitive.int)
        assertEquals(25, settings["platform"]!!.jsonObject["maxActiveAgents"]!!.jsonPrimitive.int)
        assertEquals(false, settings["canManage"]!!.jsonPrimitive.boolean)

        val pause = buildJsonObject { put("settings", buildJsonObject { put("paused", true); put("perRecipientDailyCap", 99) }) }
        assertEquals(HttpStatusCode.Forbidden, http.send("PUT", "/app/api/agents/settings", company.memberToken, pause).status)
        val saved = http.send("PUT", "/app/api/agents/settings", company.adminToken, pause).obj()
        assertEquals(20, saved["company"]!!.jsonObject["perRecipientDailyCap"]!!.jsonPrimitive.int, "caps stay in range")
        val companyPaused = http.send("GET", "/app/api/agents/overview", company.memberToken).obj()
        assertEquals(true, companyPaused["paused"]!!.jsonPrimitive.boolean)
        assertEquals("company", companyPaused["pausedBy"]!!.jsonPrimitive.content)

        module.settings.savePlatform(company.tenant.id, module.settings.get(company.tenant.id).platform.copy(agentsPaused = true))
        assertEquals("platform", http.send("GET", "/app/api/agents/overview", company.memberToken).obj()["pausedBy"]!!.jsonPrimitive.content, "the backoffice pause wins")
    }
}
