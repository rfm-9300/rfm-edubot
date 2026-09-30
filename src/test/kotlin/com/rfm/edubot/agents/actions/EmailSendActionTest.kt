package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentSettings
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.model.PlatformAgentLimits
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepResult
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.StepStatus
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SchemaValidator
import com.rfm.edubot.agents.runtime.AgentContextBuilder
import com.rfm.edubot.agents.runtime.AgentServices
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.agents.store.AgentSettingsRepository
import com.rfm.edubot.agents.store.OutboundLogRepository
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.DashboardUserRepository
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.events.ActorContext
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.integrations.email.EmailService
import com.rfm.edubot.integrations.google.FakeGoogle
import com.rfm.edubot.integrations.google.GmailClient
import com.rfm.edubot.integrations.google.GoogleIntegration
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.integrations.google.GoogleTokenProvider
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class EmailSendActionTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("agent_email")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    /** Thursday 1 October 2026, 11:00 in Lisbon. */
    private var now = Instant.parse("2026-10-01T10:00:00Z")
    private val clock = { now }
    private val cipher = TokenCipher.fromConfig(Base64.getEncoder().encodeToString(Random(11).nextBytes(32)))!!
    private val google = FakeGoogle()
    private val connections = IntegrationConnectionRepository(mongo, clock)
    private val settings = AgentSettingsRepository(mongo, clock)
    private val messages = EmailMessageRepository(mongo, clock)

    private val emailService: EmailService = run {
        val oauth = google.client()
        val tokens = GoogleTokenProvider(connections, oauth, cipher, NotificationRepository(mongo, clock), clock)
        EmailService(
            GoogleIntegration({ google.config }, cipher, oauth, connections, tokens, GmailClient(google.http)),
            messages, OutboundLogRepository(mongo, clock), DomainEventLog(mongo, clock), settings, clock,
        )
    }

    private fun services(withEmail: Boolean = true) = AgentServices(mongo = mongo, email = if (withEmail) emailService else null, clock = clock)

    private fun tenant() = Tenant(
        slug = "t-${ObjectId().toHexString().takeLast(8)}",
        name = "Obras Silva",
        channels = emptyList(),
        timezone = "Europe/Lisbon",
        enabledModules = listOf(DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES, DashboardModules.AGENTS),
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun gmail(tenant: Tenant) = connections.connect(
        tenantId = tenant.id,
        provider = IntegrationProviders.GOOGLE,
        accountEmail = "obras@example.pt",
        scopes = listOf(GoogleScopes.GMAIL_SEND),
        accessToken = cipher.seal("access-1"),
        refreshToken = cipher.seal("refresh-1"),
        accessTokenExpiresAt = now + 12.hours,
        connectedByUserId = "u1",
        connectedByEmail = "admin@example.pt",
    )

    private suspend fun quoteFor(tenant: Tenant, email: String? = "ana@example.pt", phone: String = "+351 911 000 111"): Pair<ObjectId, SubjectRef> {
        val client = ClientRepository(mongo, tenant.id).create("Ana Ribeiro", phone, email = email)
        val quote = QuoteRepository(mongo, tenant.id).create(client.id, listOf(lineItem("Telhado", unitPriceEur = 1_200.0)), null, null)
        return client.id to SubjectRef.of(SubjectTypes.QUOTE, quote.id)
    }

    private suspend fun context(
        tenant: Tenant,
        subject: SubjectRef?,
        stepId: String = "s1",
        attempts: Int = 0,
        services: AgentServices = services(),
        runId: ObjectId = ObjectId(),
    ): RunContext {
        val built = AgentContextBuilder(mongo, clock).build(tenant, subject)
        val step = StepSpec(stepId, "email.send")
        val run = AgentRun(
            id = runId,
            tenantId = tenant.id,
            agentId = ObjectId(),
            agentName = "Orçamentos",
            agentVersion = 1,
            definition = AgentDefinition(steps = listOf(step)),
            trigger = RunTrigger(type = "manual", firedAt = now),
            subject = subject,
            subjectLabel = built.label,
            clientId = built.clientId,
            dedupeKey = "k",
            steps = if (attempts > 0) listOf(StepResult(stepId, "email.send", StepStatus.WAITING, attempts = attempts)) else emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        return RunContext(tenant, run, step, built.variables, AgentSettings(tenant.id), services, now, Autonomy.AUTO)
    }

    private fun input(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("subject", "Orçamento ORC-001")
        put("text", "Olá Ana,\n\nSegue o orçamento.")
        block()
    }

    /** Runs the step as the executor does: defaults filled, types coerced, signed by the run. */
    private suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult = withContext(ActorContext(ctx.actor)) {
        EmailSendAction.execute(SchemaValidator.coerce(EmailSendAction.inputSchema, Schema.withDefaults(EmailSendAction.inputSchema, input)), ctx)
    }

    private suspend fun preview(input: JsonObject, ctx: RunContext) =
        EmailSendAction.preview(SchemaValidator.coerce(EmailSendAction.inputSchema, Schema.withDefaults(EmailSendAction.inputSchema, input)), ctx)

    @Test
    fun `the client gets the quote with its PDF from the company's Gmail, once`(): Unit = runBlocking {
        val tenant = tenant()
        gmail(tenant)
        val (clientId, quote) = quoteFor(tenant)
        val ctx = context(tenant, quote)
        val email = input { put("attachPdf", "quote"); put("cc", "Socio@Obras.pt; ana@example.pt") }

        val first = execute(email, ctx)
        val again = execute(email, ctx)

        val done = assertIs<ActionResult.Done>(first)
        assertEquals("ana@example.pt", done.output["to"]!!.jsonPrimitive.content)
        assertEquals("gm-1", done.output["messageId"]!!.jsonPrimitive.content)
        assertEquals("th-1", done.output["threadId"]!!.jsonPrimitive.content)
        assertEquals("already_sent", assertIs<ActionResult.Done>(again).note)
        val message = google.sends.single().parsed()
        assertEquals(listOf("ana@example.pt"), message.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address })
        assertEquals(listOf("socio@obras.pt"), message.getRecipients(Message.RecipientType.CC).map { (it as InternetAddress).address })
        assertEquals("Orçamento ORC-001", message.subject)
        assertEquals("ORC-001.pdf", (message.content as Multipart).getBodyPart(1).fileName)

        val stored = messages.forClient(tenant.id, clientId).single()
        assertEquals(quote, stored.record)
        assertEquals("AGENT", stored.sentByType)
        assertEquals("Orçamentos", stored.sentByName)
        assertEquals(ctx.run.id.toHexString(), stored.runId)
        assertEquals(OutboundStatus.SENT, ctx.services.outboundLog.find(ctx.idempotencyKey)?.status)
    }

    @Test
    fun `a team member or a typed address can be written to, and without an address the step is skipped`(): Unit = runBlocking {
        val tenant = tenant()
        gmail(tenant)
        val users = DashboardUserRepository(mongo)
        val rui = users.create(DashboardUser(tenantId = tenant.id, email = "rui@obras.pt", passwordHash = null, createdAt = now))
        val stranger = users.create(DashboardUser(tenantId = ObjectId(), email = "outsider@elsewhere.pt", passwordHash = null, createdAt = now))
        val (_, quote) = quoteFor(tenant, email = null)

        val toRui = execute(input { put("to", "user"); put("userId", rui.id.toHexString()) }, context(tenant, quote, "s1"))
        val toBooks = execute(input { put("to", "email"); put("email", " Contas@Obras.pt ") }, context(tenant, quote, "s2"))

        assertEquals("rui@obras.pt", assertIs<ActionResult.Done>(toRui).output["to"]!!.jsonPrimitive.content)
        assertEquals("Contas@Obras.pt", assertIs<ActionResult.Done>(toBooks).output["to"]!!.jsonPrimitive.content)
        assertEquals(listOf("rui@obras.pt", "contas@obras.pt"), google.sends.map { (it.parsed().getRecipients(Message.RecipientType.TO).single() as InternetAddress).address })

        assertEquals(ActionResult.Skipped("no_email"), execute(input(), context(tenant, quote, "s3")), "the client has no email")
        assertEquals(ActionResult.Skipped("no_email"), execute(input { put("to", "user"); put("userId", stranger.id.toHexString()) }, context(tenant, quote, "s4")), "another company's user")
        assertEquals(ActionResult.Failed("invalid_recipient"), execute(input { put("to", "email"); put("email", "contas at obras") }, context(tenant, quote, "s5")))
        assertEquals(ActionResult.Skipped("empty_subject"), execute(buildJsonObject { put("text", "Olá") }, context(tenant, quote, "s6")))
        assertEquals(ActionResult.Failed("no_email_account"), execute(input { put("to", "email"); put("email", "contas@obras.pt") }, context(tenant, quote, "s7", services = services(withEmail = false))))
        assertEquals(2, google.sends.size)
    }

    @Test
    fun `the daily allowance holds the step until the company's midnight once, then fails it`(): Unit = runBlocking {
        val tenant = tenant()
        val connection = gmail(tenant)
        settings.savePlatform(tenant.id, PlatformAgentLimits(emailSendsPerDay = 1))
        val (_, quote) = quoteFor(tenant)

        assertIs<ActionResult.Done>(execute(input(), context(tenant, quote, "s1")))
        val wait = assertIs<ActionResult.Wait>(execute(input(), context(tenant, quote, "s2")))
        // Midnight on 2 October in Lisbon (UTC+1 until late October).
        assertEquals(Instant.parse("2026-10-01T23:00:00Z"), wait.until)
        assertEquals("daily_send_limit", wait.note)
        assertTrue(wait.retrySameStep)
        assertEquals(ActionResult.Failed("daily_send_limit"), execute(input(), context(tenant, quote, "s2", attempts = 1)), "still no allowance after waiting")

        now = wait.until
        assertIs<ActionResult.Done>(execute(input(), context(tenant, quote, "s2", attempts = 1)))
        assertEquals(2, google.sends.size)
        assertEquals(1, connections.findById(connection.id)!!.sentOn("2026-10-02"))
    }

    @Test
    fun `the preview shows who gets what and warns when nothing can go out`(): Unit = runBlocking {
        val tenant = tenant()
        val (_, quote) = quoteFor(tenant)
        val email = input { put("attachPdf", "auto"); put("cc", "socio@obras.pt, geral@obras.pt"); put("replyTo", "geral@obras.pt") }

        val noAccount = preview(email, context(tenant, quote))
        assertEquals(listOf("no_email_account"), noAccount.warnings)
        assertEquals(listOf("no_email_account"), preview(email, context(tenant, quote, services = services(withEmail = false))).warnings)

        gmail(tenant)
        val shown = preview(email, context(tenant, quote))
        assertEquals("email", shown.channel)
        assertEquals(listOf("ana@example.pt"), shown.recipients)
        assertEquals("Orçamento ORC-001", shown.subject)
        assertEquals(listOf("ORC-001.pdf"), shown.attachments)
        assertEquals(mapOf("cc" to "socio@obras.pt, geral@obras.pt", "replyTo" to "geral@obras.pt"), shown.fields)
        assertEquals(listOf("subject", "text"), shown.editable)
        assertTrue(shown.warnings.isEmpty(), shown.warnings.toString())

        val (_, noEmail) = quoteFor(tenant, email = null, phone = "+351 922 000 222")
        assertEquals(listOf("no_email"), preview(input(), context(tenant, noEmail)).warnings)
        assertTrue(google.sends.isEmpty(), "previews never send")
    }
}
