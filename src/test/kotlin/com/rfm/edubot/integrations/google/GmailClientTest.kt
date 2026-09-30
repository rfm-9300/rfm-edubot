package com.rfm.edubot.integrations.google

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GmailClientTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun gmail(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = GmailClient(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request
                    answer(request)
                }
            }
        },
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun googleError(code: Int, reason: String, message: String = "refused") =
        """{"error":{"code":$code,"message":"$message","errors":[{"message":"$message","domain":"global","reason":"$reason"}],"status":"X"}}"""

    @Test
    fun `a message goes out as url-safe base64 in its thread`(): Unit = runBlocking {
        val raw = "Subject: Olá\r\n\r\n?>>?".toByteArray()
        val client = gmail { json("""{"id":"m-1","threadId":"t-1","labelIds":["SENT"]}""") }

        assertEquals(GmailClient.Send.Sent("m-1", "t-1"), client.send("access-1", raw, threadId = "t-1"))

        val request = requests.single()
        assertEquals("${GmailClient.BASE_URL}/users/me/messages/send", request.url.toString())
        assertEquals("Bearer access-1", request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        val encoded = body["raw"]!!.jsonPrimitive.content
        assertTrue('+' !in encoded && '/' !in encoded && '=' !in encoded, encoded)
        assertContentEquals(raw, Base64.getUrlDecoder().decode(encoded))
        assertEquals("t-1", body["threadId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `google's refusals become keys the dashboard explains`(): Unit = runBlocking {
        suspend fun failure(status: HttpStatusCode, body: String) = gmail { json(body, status) }.send("t", ByteArray(10)) as GmailClient.Send.Failed

        assertEquals(GmailClient.Send.Failed("unauthorized", true, 401), failure(HttpStatusCode.Unauthorized, googleError(401, "authError")))
        assertEquals("needs_reconnect", failure(HttpStatusCode.Forbidden, googleError(403, "insufficientPermissions")).key)
        assertEquals("daily_send_limit", failure(HttpStatusCode.Forbidden, googleError(403, "dailyLimitExceeded")).key)
        assertEquals("daily_send_limit", failure(HttpStatusCode.TooManyRequests, googleError(429, "rateLimitExceeded", "Daily user sending limit exceeded")).key)
        val rate = failure(HttpStatusCode.TooManyRequests, googleError(429, "rateLimitExceeded", "User-rate limit exceeded"))
        assertEquals("rate_limited", rate.key)
        assertTrue(rate.retryable)
        assertEquals("invalid_recipient", failure(HttpStatusCode.BadRequest, googleError(400, "invalidArgument", "Invalid To header")).key)
        assertEquals("invalid_message", failure(HttpStatusCode.BadRequest, googleError(400, "invalidArgument", "Missing draft message")).key)
        val outage = failure(HttpStatusCode.ServiceUnavailable, "<html>down</html>")
        assertEquals("send_failed", outage.key)
        assertTrue(outage.retryable)
        assertEquals("send_in_doubt", failure(HttpStatusCode.OK, "{}").key, "accepted without an id: never sent twice")
    }

    @Test
    fun `an unreachable gmail can be retried and an oversized message isn't sent`(): Unit = runBlocking {
        assertEquals(GmailClient.Send.Failed("send_failed", true), gmail { throw IOException("reset") }.send("t", ByteArray(10)))
        requests.clear()
        val big = gmail { error("not called") }.send("t", ByteArray(GmailClient.MAX_RAW_BYTES + 1))
        assertEquals("message_too_large", (big as GmailClient.Send.Failed).key)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the profile names the account the token acts for`(): Unit = runBlocking {
        val profile = gmail { json("""{"emailAddress":"Geral@Obras-Silva.pt","messagesTotal":10,"threadsTotal":4,"historyId":"991"}""") }.profile("access-1")
        assertEquals(GmailClient.Profile("geral@obras-silva.pt", "991"), profile)
        assertEquals("${GmailClient.BASE_URL}/users/me/profile", requests.single().url.toString())
        assertNull(gmail { json(googleError(401, "authError"), HttpStatusCode.Unauthorized) }.profile("expired"))
    }
}
