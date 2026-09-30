package com.rfm.edubot.integrations.google

import com.mongodb.client.model.Filters
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.events.DomainEvent
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.events.toEvent
import com.rfm.edubot.integrations.ConnectionStatus
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.integrations.email.EmailDirection
import com.rfm.edubot.integrations.email.EmailMessage
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import com.rfm.edubot.testing.TestMongo
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class GmailSyncWorkerTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("gmail_sync")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val cipher = TokenCipher.fromConfig(Base64.getEncoder().encodeToString(Random(11).nextBytes(32)))!!
    private var now = Instant.parse("2026-09-30T10:00:00Z")
    private val clock = { now }
    private val connections = IntegrationConnectionRepository(mongo, clock)
    private val notifications = NotificationRepository(mongo, clock)
    private val messages = EmailMessageRepository(mongo, clock)
    private val google = FakeGoogle()
    private val mailbox = google.mailbox
    private var inboxEnabled = true

    /** The companies this test knows; accounts other tests left in the database belong to none of them. */
    private val tenants = mutableMapOf<ObjectId, Tenant>()
    private val unreadable = mutableSetOf<ObjectId>()

    private val worker = run {
        val oauth = google.client()
        GmailSyncWorker(
            google = GoogleIntegration(
                { google.config.copy(inboxEnabled = inboxEnabled) },
                cipher,
                oauth,
                connections,
                GoogleTokenProvider(connections, oauth, cipher, notifications, clock),
                GmailClient(google.http),
            ),
            messages = messages,
            events = DomainEventLog(mongo, clock),
            mongo = mongo,
            tenants = { id -> if (id in unreadable) error("tenant store unavailable") else tenants[id] },
            clock = clock,
        )
    }

    private fun company(status: TenantStatus = TenantStatus.ACTIVE, documentEmail: String = "") = Tenant(
        slug = "t-${ObjectId().toHexString().takeLast(8)}",
        name = "Obras Silva",
        channels = emptyList(),
        timezone = "Europe/Lisbon",
        documentTemplate = DocumentTemplate(email = documentEmail),
        status = status,
        createdAt = now,
        updatedAt = now,
    ).also { tenants[it.id] = it }

    private suspend fun account(
        tenant: Tenant,
        email: String = "obras@example.pt",
        scopes: List<String> = GoogleScopes.inbox,
        inboxSync: Boolean = true,
    ): IntegrationConnection {
        val connection = connections.connect(
            tenantId = tenant.id,
            provider = IntegrationProviders.GOOGLE,
            accountEmail = email,
            scopes = scopes,
            accessToken = cipher.seal("access-1"),
            refreshToken = cipher.seal("refresh-1"),
            accessTokenExpiresAt = now + 1.hours,
            connectedByUserId = "u1",
            connectedByEmail = "admin@example.pt",
        )
        return if (inboxSync) connections.setInboxSync(tenant.id, connection.id, true)!! else connection
    }

    /** An account that already read its inbox up to history record 500. */
    private suspend fun following(tenant: Tenant, email: String = "obras@example.pt"): IntegrationConnection {
        val connection = account(tenant, email)
        connections.inboxSynced(connection.id, "500")
        return fresh(connection)
    }

    private suspend fun fresh(connection: IntegrationConnection) = connections.findById(connection.id)!!

    private suspend fun storedIds(tenant: Tenant): List<String> =
        mongo.database.getCollection<Document>(EmailMessageRepository.COLLECTION).find(Filters.eq("tenantId", tenant.id))
            .toList().map { it.getString("providerMessageId") }.sorted()

    private suspend fun received(tenant: Tenant): List<DomainEvent> =
        mongo.database.getCollection<Document>(DomainEventLog.COLLECTION)
            .find(Filters.and(Filters.eq("tenantId", tenant.id), Filters.eq("type", DomainEventTypes.EMAIL_RECEIVED)))
            .sort(Document("_id", 1)).toList().map { it.toEvent() }

    private fun refreshes() = google.tokenCalls.count { it["grant_type"] == "refresh_token" }

    @Test
    fun `the first sync reads the inbox since inbox sync was turned on, the next ones follow its history`(): Unit = runBlocking {
        val tenant = company()
        val maria = ClientRepository(mongo, tenant.id).create(name = "Maria Silva", phone = "912345678", email = "Maria@Cliente.pt")
        val enabledAt = now
        val connection = account(tenant)
        now += 1.hours
        mailbox.messages["m1"] = GmailFixtures.message("m1")
        mailbox.messages["m2"] = GmailFixtures.message("m2", from = "João Costa <joao@exemplo.pt>", subject = "Horário", text = "Estão abertos ao sábado?")
        mailbox.listed += listOf("m2", "m1")

        assertEquals(2, worker.sync(connection))

        assertEquals(listOf("in:inbox after:${enabledAt.epochSeconds}"), mailbox.searches)
        assertEquals(listOf("m1", "m2"), mailbox.fetched(), "the oldest first, so events come in the order the mail did")
        val first = messages.findByProviderId(tenant.id, connection.id, "m1")!!
        assertEquals(EmailDirection.INBOUND, first.direction)
        assertEquals("maria@cliente.pt", first.from)
        assertEquals("Maria Silva", first.fromName)
        assertEquals(maria.id, first.clientId)
        assertEquals("Pedido de orçamento", first.subject)
        assertEquals("Olá, preciso de um orçamento para pintar a sala.\nObrigada, Maria", first.bodyText)
        assertEquals(listOf("obras@example.pt"), first.to)
        assertEquals("th-m1", first.threadId)
        assertEquals("<m1@mail.cliente.pt>", first.messageIdHeader)
        assertEquals(Instant.fromEpochMilliseconds(1_790_000_000_000), first.date)
        assertFalse(first.automated)
        assertNull(messages.findByProviderId(tenant.id, connection.id, "m2")!!.clientId)

        val events = received(tenant)
        assertEquals(2, events.size)
        val announced = events.first()
        assertEquals(SubjectRef.of(SubjectTypes.EMAIL, first.id), announced.subject)
        assertEquals(listOf(SubjectRef.of(SubjectTypes.CLIENT, maria.id)), announced.related)
        assertEquals("maria@cliente.pt", announced.payload["from"]!!.jsonPrimitive.content)
        assertEquals("Pedido de orçamento", announced.payload["subject"]!!.jsonPrimitive.content)
        assertEquals(maria.id.toHexString(), announced.payload["clientId"]!!.jsonPrimitive.content)
        assertEquals(connection.id.toHexString(), announced.payload["connectionId"]!!.jsonPrimitive.content)
        assertFalse(announced.payload["automated"]!!.jsonPrimitive.boolean)
        assertFalse(announced.payload["hasAttachments"]!!.jsonPrimitive.boolean)
        assertTrue(announced.payload.keys.none { it in setOf("text", "snippet", "bodyText") }, "the text stays out of the event log")
        assertNull(events.last().payload["clientId"])
        assertEquals(emptyList(), events.last().related)
        assertEquals("500", fresh(connection).inbox.historyId)
        assertEquals(now, fresh(connection).inbox.lastSyncedAt)

        mailbox.receive(501, "m3", GmailFixtures.message("m3", subject = "Re: Pedido de orçamento"))
        assertEquals(1, worker.sync(fresh(connection)))
        assertEquals(listOf("m1", "m2", "m3"), mailbox.fetched())
        assertEquals("501", fresh(connection).inbox.historyId)
        assertEquals(maria.id, messages.findByProviderId(tenant.id, connection.id, "m3")!!.clientId)

        assertEquals(0, worker.sync(fresh(connection)))
        assertEquals(1, mailbox.searches.size, "following the history never lists the inbox again")
        assertEquals(3, received(tenant).size)
    }

    @Test
    fun `mail already kept is neither read nor announced again`(): Unit = runBlocking {
        val tenant = company()
        val connection = account(tenant)
        mailbox.messages["m1"] = GmailFixtures.message("m1")
        mailbox.listed += "m1"
        // It arrives after the catch-up took the mailbox's position, so the history brings it again.
        mailbox.onRead = { path, _ ->
            if (path == "messages" && mailbox.history.isEmpty()) {
                mailbox.history += 501L to listOf("m1")
                mailbox.historyId = "501"
            }
            null
        }
        assertEquals(1, worker.sync(connection))
        assertEquals("500", fresh(connection).inbox.historyId)

        assertEquals(0, worker.sync(fresh(connection)))
        assertEquals(listOf("m1"), mailbox.fetched())
        assertEquals("501", fresh(connection).inbox.historyId)

        // Another sync stored it between the check and the insert: the unique index keeps one, announced once.
        mailbox.receive(502, "m2")
        mailbox.onRead = { path, _ ->
            if (path == "messages/m2") {
                runBlocking {
                    messages.insert(
                        EmailMessage(
                            tenantId = tenant.id,
                            connectionId = connection.id,
                            providerMessageId = "m2",
                            direction = EmailDirection.INBOUND,
                            from = "maria@cliente.pt",
                            to = listOf("obras@example.pt"),
                            subject = "Pedido de orçamento",
                            snippet = "",
                            date = now,
                            createdAt = now,
                        ),
                    )
                }
            }
            null
        }
        assertEquals(0, worker.sync(fresh(connection)))
        assertEquals(listOf("m1", "m2"), storedIds(tenant))
        assertEquals(1, received(tenant).size)
        assertEquals("502", fresh(connection).inbox.historyId)
    }

    @Test
    fun `senders are matched to clients by their address, or by where they asked replies to go`(): Unit = runBlocking {
        val tenant = company()
        val rui = ClientRepository(mongo, tenant.id).create(name = "Rui Costa", phone = "913000000", email = "rui@obras-rui.pt")
        val connection = following(tenant)
        mailbox.receive(501, "form", GmailFixtures.message("form", from = "Formulário do site <formularios@site-obras.pt>", headers = mapOf("Reply-To" to "Rui Costa <Rui@Obras-Rui.pt>")))
        mailbox.receive(502, "direct", GmailFixtures.message("direct", from = "Rui Costa <rui@obras-rui.pt>", headers = mapOf("Reply-To" to "rui@obras-rui.pt")))

        assertEquals(2, worker.sync(connection))

        val viaForm = messages.findByProviderId(tenant.id, connection.id, "form")!!
        assertEquals("formularios@site-obras.pt", viaForm.from)
        assertEquals("rui@obras-rui.pt", viaForm.replyTo)
        assertEquals(rui.id, viaForm.clientId)
        val direct = messages.findByProviderId(tenant.id, connection.id, "direct")!!
        assertEquals(rui.id, direct.clientId)
        assertNull(direct.replyTo, "a reply-to that is the sender adds nothing")
        assertEquals(listOf(SubjectRef.of(SubjectTypes.CLIENT, rui.id)), received(tenant).first().related)
    }

    @Test
    fun `the company's own mail, spam and strangers' newsletters are left alone`(): Unit = runBlocking {
        val tenant = company(documentEmail = "Geral@Obras-Silva.pt")
        ClientRepository(mongo, tenant.id).create(name = "Loja do Bairro", phone = "914000000", email = "loja@bairro.pt")
        val connection = following(tenant)
        account(tenant, email = "faturacao@example.pt", scopes = GoogleScopes.send, inboxSync = false)
        mailbox.receive(501, "own", GmailFixtures.message("own", from = "Obras Silva <obras@example.pt>"))
        mailbox.receive(502, "colleague", GmailFixtures.message("colleague", from = "faturacao@example.pt"))
        mailbox.receive(503, "documents", GmailFixtures.message("documents", from = "geral@obras-silva.pt"))
        mailbox.receive(504, "spam", GmailFixtures.message("spam", labels = listOf("SPAM", "UNREAD")))
        mailbox.receive(505, "promo", GmailFixtures.message("promo", from = "Tintas Promo <promo@tintas.pt>", labels = listOf("INBOX", "CATEGORY_PROMOTIONS")))
        mailbox.receive(506, "client-promo", GmailFixtures.message("client-promo", from = "loja@bairro.pt", labels = listOf("INBOX", "CATEGORY_PROMOTIONS")))
        mailbox.receive(507, "stranger", GmailFixtures.message("stranger", from = "Ana <ana@exemplo.pt>"))

        assertEquals(2, worker.sync(connection))

        assertEquals(listOf("client-promo", "stranger"), storedIds(tenant))
        assertEquals(2, received(tenant).size)
        assertEquals("507", fresh(connection).inbox.historyId)
    }

    @Test
    fun `automatic replies are kept without a word, other machine mail is announced as automated`(): Unit = runBlocking {
        val tenant = company()
        val connection = following(tenant)
        mailbox.receive(501, "away", GmailFixtures.message("away", subject = "Ausente do escritório: Orçamento", headers = mapOf("Auto-Submitted" to "auto-replied")))
        mailbox.receive(
            502,
            "bill",
            GmailFixtures.message(
                "bill",
                from = "EDP Comercial <noreply@edp.pt>",
                subject = "A sua fatura de setembro",
                attachments = listOf(GmailFixtures.Attachment("fatura-setembro.pdf", "application/pdf")),
            ),
        )

        assertEquals(2, worker.sync(connection))

        assertTrue(messages.findByProviderId(tenant.id, connection.id, "away")!!.automated)
        val bill = messages.findByProviderId(tenant.id, connection.id, "bill")!!
        assertTrue(bill.automated)
        assertEquals(listOf("fatura-setembro.pdf"), bill.attachments.map { it.filename })
        val announced = received(tenant).single()
        assertEquals(SubjectRef.of(SubjectTypes.EMAIL, bill.id), announced.subject)
        assertTrue(announced.payload["automated"]!!.jsonPrimitive.boolean)
        assertTrue(announced.payload["hasAttachments"]!!.jsonPrimitive.boolean)
        assertTrue(announced.payload["hasPdf"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a history Gmail no longer keeps is caught up from the last week at most, never from before inbox sync was on`(): Unit = runBlocking {
        val start = now
        suspend fun paused(enabled: Instant, synced: Instant, email: String): IntegrationConnection {
            now = enabled
            val connection = account(company(), email)
            now = synced
            connections.inboxSynced(connection.id, "400")
            now = start
            return fresh(connection)
        }
        val away = paused(enabled = start - 30.days, synced = start - 20.days, email = "ferias@example.pt")
        val daily = paused(enabled = start - 3.days, synced = start - 1.days, email = "loja@example.pt")
        val justOn = paused(enabled = start - 2.days, synced = start - 2.days + 30.minutes, email = "novo@example.pt")
        mailbox.onRead = { path, _ -> if (path == "history") HttpStatusCode.NotFound to google.googleError(404, "notFound") else null }
        mailbox.messages["m9"] = GmailFixtures.message("m9")
        mailbox.listed += "m9"

        assertEquals(1, worker.sync(away))
        assertEquals("in:inbox after:${(start - 7.days).epochSeconds}", mailbox.searches.last())
        assertEquals("500", fresh(away).inbox.historyId)
        assertNull(fresh(away).inbox.lastError)

        worker.sync(daily)
        assertEquals("in:inbox after:${(start - 1.days - 1.hours).epochSeconds}", mailbox.searches.last(), "with an hour's margin before the last read")

        worker.sync(justOn)
        assertEquals("in:inbox after:${(start - 2.days).epochSeconds}", mailbox.searches.last())
    }

    @Test
    fun `a token Gmail refuses is renewed once, and a second refusal waits for the next sync`(): Unit = runBlocking {
        val tenant = company()
        val connection = following(tenant)
        mailbox.receive(501, "m1")
        mailbox.onRead = { _, token -> if (token == "access-1") HttpStatusCode.Unauthorized to google.googleError(401, "authError") else null }

        assertEquals(1, worker.sync(connection))
        assertEquals(1, refreshes())
        assertEquals("501", fresh(connection).inbox.historyId)

        mailbox.receive(502, "m2")
        mailbox.onRead = { _, _ -> HttpStatusCode.Unauthorized to google.googleError(401, "authError") }
        assertEquals(0, worker.sync(fresh(connection)))
        assertEquals(2, refreshes())
        val refused = fresh(connection)
        assertEquals(GmailClient.UNAUTHORIZED, refused.inbox.lastError)
        assertEquals("501", refused.inbox.historyId)
        assertEquals(ConnectionStatus.ACTIVE, refused.status)
    }

    @Test
    fun `an account that may not read its inbox waits until it allows it again, and keeps sending`(): Unit = runBlocking {
        val sendOnly = account(company(), scopes = GoogleScopes.send)
        assertEquals(0, worker.sync(sendOnly))
        assertTrue(mailbox.reads.isEmpty())
        assertEquals(GmailSyncWorker.MISSING_SCOPE, fresh(sendOnly).inbox.lastError)

        val blockedCompany = company()
        val blocked = account(blockedCompany, email = "loja@example.pt")
        mailbox.onRead = { _, _ -> HttpStatusCode.Forbidden to google.googleError(403, "insufficientPermissions") }
        assertEquals(0, worker.sync(blocked))
        val refused = fresh(blocked)
        assertEquals(GmailSyncWorker.MISSING_SCOPE, refused.inbox.lastError)
        assertNull(refused.inbox.historyId)
        assertEquals(ConnectionStatus.ACTIVE, refused.status, "sending doesn't depend on reading")
        assertEquals(0, notifications.listFor(blockedCompany.id, reader = "admin-1", isAdmin = true).size)

        mailbox.onRead = { _, _ -> null }
        worker.sync(refused)
        assertNull(fresh(blocked).inbox.lastError)
        assertEquals("500", fresh(blocked).inbox.historyId)
    }

    @Test
    fun `a read that fails keeps what came before it and says why`(): Unit = runBlocking {
        val tenant = company()
        val connection = following(tenant)
        mailbox.receive(501, "m1")
        mailbox.receive(502, "m2")
        mailbox.receive(503, "m3")
        mailbox.onRead = { path, _ -> if (path == "messages/m2") HttpStatusCode.InternalServerError to google.googleError(500, "backendError") else null }

        assertEquals(1, worker.sync(connection))
        assertEquals("501", fresh(connection).inbox.historyId)
        assertEquals(GmailClient.READ_FAILED, fresh(connection).inbox.lastError)

        mailbox.onRead = { _, _ -> null }
        mailbox.history += 504L to listOf("deleted-meanwhile")
        mailbox.historyId = "504"
        assertEquals(2, worker.sync(fresh(connection)))
        assertEquals("504", fresh(connection).inbox.historyId)
        assertNull(fresh(connection).inbox.lastError)
        assertEquals(listOf("m1", "m2", "m3"), storedIds(tenant))
    }

    @Test
    fun `a sync reads at most fifty messages, and the next one picks up where it stopped`(): Unit = runBlocking {
        val tenant = company()
        val connection = following(tenant)
        mailbox.pageSize = 20
        (1..60).forEach { mailbox.receive(500L + it, "m$it") }

        assertEquals(GmailSyncWorker.MAX_FETCHES, worker.sync(connection))
        assertEquals("550", fresh(connection).inbox.historyId)
        assertEquals(10, worker.sync(fresh(connection)))
        assertEquals("560", fresh(connection).inbox.historyId)
        assertEquals(60, storedIds(tenant).size)
        assertEquals(60, mailbox.fetched().size)

        // Catching up reads the newest fifty; older mail stays unread.
        val other = company()
        mailbox.listed += (60 downTo 1).map { "m$it" }
        assertEquals(GmailSyncWorker.MAX_FETCHES, worker.sync(account(other, email = "loja@example.pt")))
        assertEquals((11..60).map { "m$it" }.sorted(), storedIds(other))
    }

    @Test
    fun `nothing is read while the platform keeps inboxes off or for a suspended company, and one failing account doesn't stop the rest`(): Unit = runBlocking {
        val suspended = account(company(status = TenantStatus.SUSPENDED), email = "suspensa@example.pt")
        val failing = company()
        val failingAccount = account(failing, email = "falha@example.pt")
        unreadable += failing.id
        val working = company()
        val workingAccount = account(working, email = "loja@example.pt")
        mailbox.messages["m1"] = GmailFixtures.message("m1")
        mailbox.listed += "m1"

        inboxEnabled = false
        assertEquals(0, worker.syncAll())
        assertTrue(mailbox.reads.isEmpty())

        inboxEnabled = true
        assertEquals(1, worker.syncAll())
        assertNull(fresh(suspended).inbox.historyId)
        assertNull(fresh(suspended).inbox.lastError)
        assertEquals(GmailSyncWorker.SYNC_FAILED, fresh(failingAccount).inbox.lastError)
        assertEquals("500", fresh(workingAccount).inbox.historyId)
        assertEquals(listOf("m1"), storedIds(working))
    }
}
