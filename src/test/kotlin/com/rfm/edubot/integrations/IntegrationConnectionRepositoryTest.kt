package com.rfm.edubot.integrations

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewService
import com.rfm.edubot.integrations.google.GoogleScopes
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class IntegrationConnectionRepositoryTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("integration_connections")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val now: Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    private val connections = IntegrationConnectionRepository(mongo)

    private suspend fun connect(tenantId: ObjectId, email: String, access: String = "sealed-access") = connections.connect(
        tenantId = tenantId,
        provider = IntegrationProviders.GOOGLE,
        accountEmail = email,
        scopes = listOf(GoogleScopes.GMAIL_SEND),
        accessToken = access,
        refreshToken = "sealed-refresh",
        accessTokenExpiresAt = now + 1.hours,
        connectedByUserId = "u1",
        connectedByEmail = "admin@example.pt",
    )

    @Test
    fun `reconnecting an account refreshes its tokens and keeps its settings`(): Unit = runBlocking {
        val tenantId = ObjectId()
        val first = connect(tenantId, "Obras@Example.pt")
        assertEquals("obras@example.pt", first.accountEmail)
        assertTrue(first.isDefault, "a company's first account sends by default")
        connections.updateSettings(tenantId, first.id, EmailSettings(senderName = "Obras Silva", signature = "Até breve"))
        connections.markNeedsReconnect(first.id, "invalid_grant")

        val again = connect(tenantId, "obras@example.pt", access = "sealed-access-2")
        assertEquals(first.id, again.id)
        assertEquals(ConnectionStatus.ACTIVE, again.status)
        assertNull(again.lastError)
        assertEquals("sealed-access-2", again.accessToken)
        assertEquals("Obras Silva", again.settings.senderName)
        assertTrue(again.isDefault)

        val second = connect(tenantId, "faturas@example.pt")
        assertEquals(false, second.isDefault)
        assertEquals(listOf(first.id, second.id), connections.list(tenantId).map { it.id })
        assertEquals(first.id, connections.defaultFor(tenantId, IntegrationProviders.GOOGLE)?.id)
    }

    @Test
    fun `deleting the default sender hands it to the oldest remaining account`(): Unit = runBlocking {
        val tenantId = ObjectId()
        val a = connect(tenantId, "a@example.pt")
        val b = connect(tenantId, "b@example.pt")
        val c = connect(tenantId, "c@example.pt")
        assertEquals(a.id, connections.delete(tenantId, a.id)?.id)
        assertEquals(b.id, connections.defaultFor(tenantId, IntegrationProviders.GOOGLE)?.id)
        assertTrue(connections.findById(b.id)!!.isDefault)

        connections.makeDefault(tenantId, c.id)
        assertEquals(listOf(false, true), connections.list(tenantId).map { it.isDefault })
        assertNull(connections.delete(ObjectId(), c.id), "another company can't delete it")
    }

    @Test
    fun `an account is counted across every company that connected it`(): Unit = runBlocking {
        val email = "partilhada-${ObjectId().toHexString().takeLast(6)}@example.pt"
        connect(ObjectId(), email)
        connect(ObjectId(), email.uppercase())
        assertEquals(2, connections.countForAccount(IntegrationProviders.GOOGLE, email))
    }

    @Test
    fun `an account's daily allowance is claimed atomically and starts over each day`(): Unit = runBlocking {
        val connection = connect(ObjectId(), "envios@example.pt")
        val claims = coroutineScope { (1..12).map { async(Dispatchers.IO) { connections.claimSend(connection.id, "2026-09-30", 5) } }.awaitAll() }
        assertEquals(5, claims.count { it }, "never more than the allowance, however many race for it")
        assertEquals(5, connections.findById(connection.id)!!.sentOn("2026-09-30"))

        connections.releaseSend(connection.id, "2026-09-30")
        assertTrue(connections.claimSend(connection.id, "2026-09-30", 5), "a send given back can be used again")
        assertFalse(connections.claimSend(connection.id, "2026-09-30", 5))

        assertTrue(connections.claimSend(connection.id, "2026-10-01", 5))
        val nextDay = connections.findById(connection.id)!!
        assertEquals(1, nextDay.sentOn("2026-10-01"))
        assertEquals(0, nextDay.sentOn("2026-09-30"))
        connections.releaseSend(connection.id, "2026-09-30")
        assertEquals(1, connections.findById(connection.id)!!.sentOn("2026-10-01"), "yesterday's release leaves today alone")
        assertFalse(connections.claimSend(connection.id, "2026-10-01", 0), "an allowance of zero sends nothing")
    }

    @Test
    fun `a connection that needs a reconnect shows on Home until it is reconnected`(): Unit = runBlocking {
        val tenants = TenantRepository(mongo)
        fun company(modules: List<String>) = Tenant(
            slug = "t-${ObjectId().toHexString().takeLast(8)}", name = "Obras", channels = emptyList(), enabledModules = modules, createdAt = now, updatedAt = now,
        )
        val tenant = tenants.create(company(listOf(DashboardModules.SETTINGS, DashboardModules.CLIENTS)))
        val connection = connect(tenant.id, "obras@example.pt")
        assertTrue(OverviewService(mongo).build(tenant).attention.none { it.kind == OverviewMath.KIND_INTEGRATION_RECONNECT })

        assertTrue(connections.markNeedsReconnect(connection.id, "invalid_grant"))
        assertEquals(false, connections.markNeedsReconnect(connection.id, "invalid_grant"), "only the first one flips it")
        val overview = OverviewService(mongo).build(tenant)
        val item = overview.attention.single { it.kind == OverviewMath.KIND_INTEGRATION_RECONNECT }
        assertEquals(DashboardModules.SETTINGS, item.tab)
        assertEquals("obras@example.pt", item.detail)
        assertEquals(connection.id.toHexString(), item.id)
        assertEquals(OverviewMath.HEALTH_WATCH, overview.health)

        connect(tenant.id, "obras@example.pt")
        assertTrue(OverviewService(mongo).build(tenant).attention.none { it.kind == OverviewMath.KIND_INTEGRATION_RECONNECT })

        val withoutSettings = tenants.create(company(listOf(DashboardModules.CLIENTS)))
        connections.markNeedsReconnect(connect(withoutSettings.id, "obras@example.pt").id, "invalid_grant")
        assertTrue(OverviewService(mongo).build(withoutSettings).attention.none { it.kind == OverviewMath.KIND_INTEGRATION_RECONNECT })
    }
}
