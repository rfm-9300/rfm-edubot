package com.rfm.edubot.instagram

import com.rfm.edubot.channel.OutboundDeliveryException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstagramClientTest {
    @Test
    fun `send text uses selected Instagram account`() = runBlocking {
        var requestedPath = ""
        var authorization = ""
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestedPath = request.url.encodedPath
                    authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                    respond("""{"recipient_id":"user-1","message_id":"message-1"}""", headers = jsonHeaders)
                }
            }
        }

        InstagramClient("account-token", "ig-account-1", httpClient = http).sendText("user-1", "Hello")

        assertEquals("/v21.0/ig-account-1/messages", requestedPath)
        assertEquals("Bearer account-token", authorization)
    }

    @Test
    fun `send text reports Graph delivery failure`() = runBlocking {
        val http = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        """{"error":{"message":"Missing permission","code":10}}""",
                        status = HttpStatusCode.Forbidden,
                        headers = jsonHeaders,
                    )
                }
            }
        }

        assertFailsWith<OutboundDeliveryException> {
            InstagramClient("account-token", "ig-account-1", httpClient = http).sendText("user-1", "Hello")
        }
    }

    @Test
    fun `send text rejects account without token`() = runBlocking {
        assertFailsWith<OutboundDeliveryException> {
            InstagramClient("", "ig-account-1").sendText("user-1", "Hello")
        }
    }

    @Test
    fun `profile lookup returns the Instagram name`() = runBlocking {
        var requestedPath = ""
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestedPath = request.url.encodedPath
                    respond("""{"name":"Ada Lovelace","username":"ada"}""", headers = jsonHeaders)
                }
            }
        }

        val name = InstagramClient("account-token", "ig-account-1", httpClient = http).profileName("user-1")

        assertEquals("/v21.0/user-1", requestedPath)
        assertEquals("Ada Lovelace", name)
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
}
