package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.TokenStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingTokenStore(private var token: String?) : TokenStore {
    var cleared = false
        private set

    override suspend fun read(): String? = token

    override suspend fun write(token: String) {
        this.token = token
    }

    override suspend fun clear() {
        token = null
        cleared = true
    }
}

class DashboardHttpClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun client(
        token: String? = "jwt",
        handler: MockRequestHandler,
    ): Triple<DashboardHttpClient, SessionTokens, RecordingTokenStore> {
        val store = RecordingTokenStore(token)
        val tokens = SessionTokens(store)
        val engine = MockEngine(handler)
        return Triple(DashboardHttpClient("https://example.test/", tokens, engine), tokens, store)
    }

    @Test
    fun `attaches the bearer token without the caller passing it`() = runTest {
        var seen: String? = null
        val (http, _, _) = client { request ->
            seen = request.headers[HttpHeaders.Authorization]
            respond("""{"locale":"pt-PT"}""", HttpStatusCode.OK, jsonHeaders)
        }
        KtorSettingsApi(http).updateLocale("pt-PT")
        assertEquals("Bearer jwt", seen)
    }

    @Test
    fun `trims a trailing slash off the base url instead of doubling it`() = runTest {
        var path: String? = null
        val (http, _, _) = client { request ->
            path = request.url.encodedPath
            respond("""{"locale":"en"}""", HttpStatusCode.OK, jsonHeaders)
        }
        KtorSettingsApi(http).updateLocale("en")
        assertEquals("/app/api/settings/locale", path)
    }

    @Test
    fun `a 400 keeps the backend's own error code`() = runTest {
        val (http, _, _) = client {
            respond("""{"error":"tax_id_required"}""", HttpStatusCode.BadRequest, jsonHeaders)
        }
        val failure = assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        assertEquals(AppError.Rejected("tax_id_required", 400), failure.error)
    }

    @Test
    fun `a refused clock-in keeps the nearest site the backend named`() = runTest {
        val (http, _, _) = client {
            respond("""{"error":"outside_sites","siteName":"Obra Rua do Sol","distanceM":1410}""", HttpStatusCode.Conflict, jsonHeaders)
        }
        val failure = assertFailsWith<ApiException> {
            KtorTimeClockApi(http).punch(com.rfm.edubot.mobile.core.model.PunchRequest(type = "IN"))
        }
        assertEquals(
            AppError.Rejected("outside_sites", 409, mapOf("siteName" to "Obra Rua do Sol", "distanceM" to "1410")),
            failure.error,
        )
    }

    @Test
    fun `a punch is posted to the employee's own time clock`() = runTest {
        var path: String? = null
        var body: String? = null
        val (http, _, _) = client { request ->
            path = request.url.encodedPath
            body = (request.body as io.ktor.http.content.TextContent).text
            respond(
                """{"status":{"policy":{},"state":"WORKING","serverTime":"2026-10-07T08:00:00Z"},
                   "shift":{"id":"s1","employeeId":"e1","status":"OPEN","day":"2026-10-07","startAt":"2026-10-07T07:00:00Z","startTime":"08:00"}}""",
                HttpStatusCode.Created,
                jsonHeaders,
            )
        }
        val result = KtorTimeClockApi(http).punch(
            com.rfm.edubot.mobile.core.model.PunchRequest(
                type = "IN",
                location = com.rfm.edubot.mobile.core.model.PunchLocation(38.7223, -9.1393, 12.0),
                verification = com.rfm.edubot.mobile.core.model.PunchVerification("key", "nonce", "sig"),
            ),
        )
        assertEquals("/app/api/portal/time/punches", path)
        assertTrue(body!!.contains(""""channel":"APP""""))
        assertTrue(body!!.contains(""""keyId":"key""""))
        assertEquals("WORKING", result.status.state)
        assertEquals("08:00", result.shift.startTime)
    }

    @Test
    fun `a 400 with no code still reports a rejection`() = runTest {
        val (http, _, _) = client { respondError(HttpStatusCode.BadRequest) }
        val failure = assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        assertEquals(AppError.Rejected("", 400), failure.error)
    }

    @Test
    fun `a 403 is forbidden rather than an expired session`() = runTest {
        val (http, _, store) = client { respondError(HttpStatusCode.Forbidden) }
        val failure = assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        assertEquals(AppError.Forbidden, failure.error)
        assertTrue(!store.cleared, "a module the tenant does not have must not sign anybody out")
    }

    @Test
    fun `a 500 is reported as unavailable`() = runTest {
        val (http, _, _) = client { respondError(HttpStatusCode.InternalServerError) }
        val failure = assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        assertEquals(AppError.Unavailable(500), failure.error)
    }

    @Test
    fun `a 401 clears the token and announces the expiry once`() = runTest {
        val (http, tokens, store) = client { respondError(HttpStatusCode.Unauthorized) }
        val expiries = mutableListOf<Unit>()
        // The signal is for live subscribers only, so subscribe before anything can emit.
        backgroundScope.launch { tokens.expired.collect { expiries += it } }
        runCurrent()

        assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        runCurrent()

        assertTrue(store.cleared, "the rejected token should not stay on the device")
        assertNull(tokens.current())
        // A second call now has no token and fails before the network, so only one expiry fires.
        assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        runCurrent()
        assertEquals(1, expiries.size)
    }

    @Test
    fun `signing in does not report an expiry when the password is wrong`() = runTest {
        val (http, tokens, _) = client(token = null) { respondError(HttpStatusCode.Unauthorized) }
        val expiries = mutableListOf<Unit>()
        backgroundScope.launch { tokens.expired.collect { expiries += it } }
        runCurrent()

        val failure = assertFailsWith<ApiException> { KtorSessionApi(http).login("a@b.test", "nope") }
        runCurrent()

        assertEquals(AppError.Unauthorized, failure.error)
        assertTrue(expiries.isEmpty(), "a failed sign-in is not an expired session")
    }

    @Test
    fun `a call with no token fails before reaching the network`() = runTest {
        var reached = false
        val (http, _, _) = client(token = null) {
            reached = true
            respondOk()
        }
        assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        assertTrue(!reached)
    }

    @Test
    fun `an unreachable backend reads as offline rather than as a crash`() = runTest {
        val (http, _, _) = client { throw kotlinx.io.IOException("no route to host") }
        val failure = assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        assertTrue(failure.error is AppError.Offline)
    }

    @Test
    fun `a malformed body is a failure rather than an exception reaching the UI`() = runTest {
        val (http, _, _) = client { respond("not json", HttpStatusCode.OK, jsonHeaders) }
        val failure = assertFailsWith<ApiException> { KtorSettingsApi(http).account() }
        assertTrue(failure.error is AppError.Offline)
    }

    @Test
    fun `null query parameters are dropped rather than sent empty`() = runTest {
        var query: String? = null
        val (http, _, _) = client { request ->
            query = request.url.encodedQuery
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        }
        KtorCrmApi(http).quotes(clientId = null, status = "PENDENTE")
        assertEquals("status=PENDENTE", query)
    }

    @Test
    fun `unknown response fields do not break decoding`() = runTest {
        val (http, _, _) = client {
            respond(
                """{"id":"1","waId":"351900000000","channel":"WHATSAPP","state":"OPEN",
                   "lastMessageAt":"2026-01-01T00:00:00Z","somethingNew":true}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val conversation = KtorInboxApi(http).markRead("1")
        assertEquals("351900000000", conversation.waId)
    }

    @Test
    fun `the token survives being read concurrently`() = runTest {
        val store = RecordingTokenStore("jwt")
        val tokens = SessionTokens(store)
        assertEquals("jwt", tokens.current())
        tokens.adopt("second")
        assertEquals("second", tokens.current())
        tokens.forget()
        assertNull(tokens.current())
    }

    @Test
    fun `forgetting a token deliberately does not announce an expiry`() = runTest {
        val tokens = SessionTokens(RecordingTokenStore("jwt"))
        val expiries = mutableListOf<Unit>()
        backgroundScope.launch { tokens.expired.collect { expiries += it } }
        runCurrent()
        tokens.forget()
        runCurrent()
        assertTrue(expiries.isEmpty(), "signing out on purpose is not an expiry")
    }

    @Test
    fun `invalidating announces the expiry so the session can react`() = runTest {
        val tokens = SessionTokens(RecordingTokenStore("jwt"))
        val expiries = mutableListOf<Unit>()
        backgroundScope.launch { tokens.expired.collect { expiries += it } }
        runCurrent()
        tokens.invalidate()
        runCurrent()
        assertEquals(1, expiries.size)
        assertNull(tokens.current())
    }
}
