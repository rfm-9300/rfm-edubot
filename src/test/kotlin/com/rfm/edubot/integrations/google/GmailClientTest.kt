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

    @Test
    fun `history lists the inbox messages each change added`(): Unit = runBlocking {
        val body = """{"history":[
            {"id":"601","messages":[{"id":"m1"}],"messagesAdded":[{"message":{"id":"m1","threadId":"t1","labelIds":["INBOX","UNREAD"]}}]},
            {"id":"602","labelsAdded":[{"message":{"id":"m1"},"labelIds":["STARRED"]}]},
            {"id":"603","messagesAdded":[{"message":{"id":"m2","labelIds":["SENT"]}},{"message":{"id":"m3","labelIds":["INBOX"]}},{"message":{"id":"m3","labelIds":["INBOX"]}}]}
        ],"nextPageToken":"page-2","historyId":"610"}"""

        val history = gmail { json(body) }.history("access-1", "600", pageToken = "page-1") as GmailClient.Read.Ok

        assertEquals(
            GmailClient.History(
                listOf(GmailClient.HistoryRecord("601", listOf("m1")), GmailClient.HistoryRecord("602", emptyList()), GmailClient.HistoryRecord("603", listOf("m3"))),
                historyId = "610",
                nextPageToken = "page-2",
            ),
            history.value,
        )
        val url = requests.single().url
        assertEquals("${GmailClient.BASE_URL}/users/me/history", url.toString().substringBefore('?'))
        assertEquals("600", url.parameters["startHistoryId"])
        assertEquals("messageAdded", url.parameters["historyTypes"])
        assertEquals("INBOX", url.parameters["labelId"])
        assertEquals("page-1", url.parameters["pageToken"])
        assertEquals("Bearer access-1", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `reading refusals say whether to renew the token, reconnect, start over or retry`(): Unit = runBlocking {
        suspend fun failure(status: HttpStatusCode, body: String) = gmail { json(body, status) }.history("t", "1") as GmailClient.Read.Failed

        assertEquals(GmailClient.Read.Failed("unauthorized", true, 401), failure(HttpStatusCode.Unauthorized, googleError(401, "authError")))
        assertEquals(GmailClient.Read.Failed("needs_reconnect", false, 403), failure(HttpStatusCode.Forbidden, googleError(403, "insufficientPermissions")))
        assertEquals(GmailClient.Read.Failed("not_found", false, 404), failure(HttpStatusCode.NotFound, googleError(404, "notFound")))
        assertEquals(GmailClient.Read.Failed("rate_limited", true, 429), failure(HttpStatusCode.TooManyRequests, googleError(429, "rateLimitExceeded")))
        assertEquals("rate_limited", failure(HttpStatusCode.Forbidden, googleError(403, "userRateLimitExceeded")).key)
        assertEquals(GmailClient.Read.Failed("read_failed", true, 503), failure(HttpStatusCode.ServiceUnavailable, "<html>down</html>"))
        assertEquals(GmailClient.Read.Failed("read_failed", false, 400), failure(HttpStatusCode.BadRequest, googleError(400, "failedPrecondition")))
        assertEquals(GmailClient.Read.Failed("read_failed", false, 200), failure(HttpStatusCode.OK, "not json"))
        assertEquals(GmailClient.Read.Failed("read_failed", true), gmail { throw IOException("reset") }.history("t", "1"))
    }

    @Test
    fun `the inbox is listed after a moment and a message is read in full`(): Unit = runBlocking {
        val since = kotlinx.datetime.Instant.parse("2026-09-30T08:00:00Z")
        val page = gmail { json("""{"messages":[{"id":"m9","threadId":"t9"},{"id":"m8","threadId":"t8"}],"nextPageToken":"n2","resultSizeEstimate":2}""") }
            .inbox("access-1", since) as GmailClient.Read.Ok
        assertEquals(GmailClient.MessagePage(listOf("m9", "m8"), "n2"), page.value)
        assertEquals("in:inbox after:${since.epochSeconds}", requests.single().url.parameters["q"])
        assertEquals("${GmailClient.BASE_URL}/users/me/messages", requests.single().url.toString().substringBefore('?'))

        requests.clear()
        val message = gmail { json(GmailFixtures.message("m9")) }.message("access-1", "m9") as GmailClient.Read.Ok
        assertEquals("m9", message.value.id)
        assertEquals("maria@cliente.pt", message.value.from?.email)
        assertEquals("${GmailClient.BASE_URL}/users/me/messages/m9", requests.single().url.toString().substringBefore('?'))
        assertEquals("full", requests.single().url.parameters["format"])

        requests.clear()
        assertEquals(GmailClient.Read.Failed("not_found", false), gmail { error("not called") }.message("t", "../profile"))
        assertTrue(requests.isEmpty())
        assertEquals("read_failed", (gmail { json("""{"id":"m1"}""") }.message("t", "m1") as GmailClient.Read.Failed).key, "no payload")
    }
}
