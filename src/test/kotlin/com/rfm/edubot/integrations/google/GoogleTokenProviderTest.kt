package com.rfm.edubot.integrations.google

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.integrations.ConnectionStatus
import com.rfm.edubot.integrations.IntegrationConnection
import com.rfm.edubot.integrations.IntegrationConnectionRepository
import com.rfm.edubot.integrations.IntegrationProviders
import com.rfm.edubot.integrations.TokenCipher
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GoogleTokenProviderTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("google_tokens")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private fun key(seed: Int) = Base64.getEncoder().encodeToString(Random(seed).nextBytes(32))

    private val cipher = TokenCipher.fromConfig(key(1))!!
    private val clock = Instant.fromEpochMilliseconds(1_800_000_000_000)
    private val connections = IntegrationConnectionRepository(mongo) { clock }
    private val notifications = NotificationRepository(mongo) { clock }

    private suspend fun connection(expiresIn: Duration, sealWith: TokenCipher = cipher): IntegrationConnection = connections.connect(
        tenantId = ObjectId(),
        provider = IntegrationProviders.GOOGLE,
        accountEmail = "obras-${ObjectId().toHexString().takeLast(6)}@example.pt",
        scopes = listOf(GoogleScopes.GMAIL_SEND),
        accessToken = sealWith.seal("access-1"),
        refreshToken = sealWith.seal("refresh-1"),
        accessTokenExpiresAt = clock + expiresIn,
        connectedByUserId = "u1",
        connectedByEmail = "admin@example.pt",
    )

    private fun provider(google: FakeGoogle, cipher: TokenCipher? = this.cipher) =
        GoogleTokenProvider(connections, google.client(), cipher, notifications) { clock }

    @Test
    fun `a token with time left is used without asking google`(): Unit = runBlocking {
        val google = FakeGoogle()
        val token = provider(google).accessToken(connection(expiresIn = 1.hours))
        assertEquals(GoogleTokenProvider.Token.Ok("access-1"), token)
        assertEquals(0, google.tokenCalls.size)
    }

    @Test
    fun `a token about to expire is refreshed once and the new one is kept`(): Unit = runBlocking {
        val google = FakeGoogle()
        val tokens = provider(google)
        val stale = connection(expiresIn = 4.minutes)
        assertEquals(GoogleTokenProvider.Token.Ok("access-refreshed"), tokens.accessToken(stale))
        assertEquals("refresh-1", google.tokenCalls.single()["refresh_token"])

        val saved = connections.findById(stale.id)!!
        assertEquals("access-refreshed", cipher.open(saved.accessToken!!))
        assertEquals(clock + 3599.seconds, saved.accessTokenExpiresAt)
        assertEquals(GoogleTokenProvider.Token.Ok("access-refreshed"), tokens.accessToken(saved))
        assertEquals(1, google.tokenCalls.size, "the refreshed token is reused")
    }

    @Test
    fun `callers at the same time share one refresh`(): Unit = runBlocking {
        val google = FakeGoogle().apply { tokenDelayMs = 100 }
        val tokens = provider(google)
        val stale = connection(expiresIn = 1.minutes)
        val results = coroutineScope { (1..5).map { async { tokens.accessToken(stale) } }.awaitAll() }
        assertEquals(List(5) { GoogleTokenProvider.Token.Ok("access-refreshed") }, results)
        assertEquals(1, google.tokenCalls.size)
    }

    @Test
    fun `a grant google no longer honours asks for a reconnect and tells the admins once`(): Unit = runBlocking {
        val google = FakeGoogle()
        google.onToken = { HttpStatusCode.BadRequest to """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""" }
        val tokens = provider(google)
        val stale = connection(expiresIn = 0.minutes)
        assertEquals(GoogleTokenProvider.Token.NeedsReconnect, tokens.accessToken(stale))

        val saved = connections.findById(stale.id)!!
        assertEquals(ConnectionStatus.NEEDS_RECONNECT, saved.status)
        assertEquals("invalid_grant", saved.lastError)
        assertNull(saved.accessToken)
        val notice = notifications.listFor(stale.tenantId, reader = "admin-1", isAdmin = true).single()
        assertEquals(NotificationKinds.INTEGRATION_RECONNECT, notice.kind)
        assertEquals(NotificationAudience.ADMINS, notice.audience)
        assertEquals(mapOf("integration" to "GMAIL", "account" to stale.accountEmail), notice.params)
        assertEquals(DashboardModules.SETTINGS, notice.link)
        assertEquals(0, notifications.listFor(stale.tenantId, reader = "member-1", isAdmin = false).size)

        assertEquals(GoogleTokenProvider.Token.NeedsReconnect, tokens.accessToken(stale))
        assertEquals(1, google.tokenCalls.size, "a connection waiting for a person isn't retried")
        assertEquals(1, notifications.listFor(stale.tenantId, reader = "admin-1", isAdmin = true).size)
    }

    @Test
    fun `a passing failure keeps the connection`(): Unit = runBlocking {
        val google = FakeGoogle()
        google.onToken = { HttpStatusCode.ServiceUnavailable to "unavailable" }
        val stale = connection(expiresIn = 2.minutes)
        assertIs<GoogleTokenProvider.Token.Unavailable>(provider(google).accessToken(stale))
        assertEquals(ConnectionStatus.ACTIVE, connections.findById(stale.id)!!.status)
        assertEquals(0, notifications.listFor(stale.tenantId, reader = "admin-1", isAdmin = true).size)
    }

    @Test
    fun `after a key rotation a refresh seals both tokens with the new key`(): Unit = runBlocking {
        val stale = connection(expiresIn = 1.minutes, sealWith = cipher)
        val rotated = TokenCipher.fromConfig("${key(2)},${key(1)}")!!
        assertEquals(GoogleTokenProvider.Token.Ok("access-refreshed"), provider(FakeGoogle(), rotated).accessToken(stale))
        val saved = connections.findById(stale.id)!!
        assertFalse(rotated.isStale(saved.refreshToken!!))
        assertFalse(rotated.isStale(saved.accessToken!!))
        assertEquals("refresh-1", rotated.open(saved.refreshToken!!))
    }

    @Test
    fun `without the key nothing is handed out`(): Unit = runBlocking {
        val google = FakeGoogle()
        assertEquals(GoogleTokenProvider.Token.Unavailable("not_configured"), provider(google, cipher = null).accessToken(connection(expiresIn = 1.hours)))
        assertEquals(0, google.tokenCalls.size)
    }
}
