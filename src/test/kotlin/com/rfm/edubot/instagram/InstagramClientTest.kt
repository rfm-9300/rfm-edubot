package com.rfm.edubot.instagram

import com.rfm.edubot.channel.OutboundDeliveryException
import com.rfm.edubot.channel.QuickReply
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    fun `send text reports Graph delivery failure`() = runBlocking<Unit> {
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
    fun `send text rejects account without token`() = runBlocking<Unit> {
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

    @Test
    fun `list media uses the Instagram user token`() = runBlocking {
        var requestedPath = ""
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestedPath = request.url.encodedPath
                    respond(
                        """{"data":[{"id":"media-1","caption":"Hello","media_type":"IMAGE","media_url":"https://cdn.example/1.jpg","permalink":"https://instagram.com/p/1","timestamp":"2026-01-01T12:00:00+0000","comments_count":2}]}""",
                        headers = jsonHeaders,
                    )
                }
            }
        }

        val result = InstagramClient("account-token", "ig-account-1", httpClient = http).listMedia()

        assertEquals("/v21.0/me/media", requestedPath)
        val media = (result as InstagramGraphResult.Ok).value.single()
        assertEquals("media-1", media.id)
        assertEquals("Hello", media.caption)
        assertEquals(2, media.commentsCount)
    }

    @Test
    fun `reply to comment posts to the comments replies edge`() = runBlocking {
        var requestedPath = ""
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestedPath = request.url.encodedPath
                    respond("""{"id":"reply-1"}""", headers = jsonHeaders)
                }
            }
        }

        val result = InstagramClient("account-token", "ig-account-1", httpClient = http).replyToComment("comment-1", "Thanks")

        assertEquals("/v21.0/comment-1/replies", requestedPath)
        assertEquals("reply-1", (result as InstagramGraphResult.Ok).value)
    }

    @Test
    fun `list media reports a missing comments permission as reconnect`() = runBlocking {
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

        val result = InstagramClient("account-token", "ig-account-1", httpClient = http).listMedia()
        assertEquals(InstagramGraphResult.PermissionDenied, result)
    }

    @Test
    fun `quick replies go under the text as text replies with their payloads`() = runBlocking {
        val bodies = mutableListOf<JsonElement>()
        val client = InstagramClient("account-token", "ig-account-1", httpClient = recording(bodies))

        client.sendQuickReplies("user-1", "Qual serviço?", listOf(QuickReply("Unhas", "flow:servicos:unhas"), QuickReply("Pele", "flow:servicos:pele")))

        val expected = """
            {"recipient":{"id":"user-1"},"message":{"text":"Qual serviço?","quick_replies":[
              {"content_type":"text","title":"Unhas","payload":"flow:servicos:unhas"},
              {"content_type":"text","title":"Pele","payload":"flow:servicos:pele"}
            ]}}
        """
        assertEquals(Json.parseToJsonElement(expected), bodies.single())
    }

    @Test
    fun `plain text carries no quick replies`() = runBlocking {
        val bodies = mutableListOf<JsonElement>()

        InstagramClient("account-token", "ig-account-1", httpClient = recording(bodies)).sendText("user-1", "Olá")

        assertEquals(Json.parseToJsonElement("""{"recipient":{"id":"user-1"},"message":{"text":"Olá"}}"""), bodies.single())
    }

    private fun recording(bodies: MutableList<JsonElement>) = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                bodies += Json.parseToJsonElement((request.body as TextContent).text)
                respond("""{"recipient_id":"user-1","message_id":"message-1"}""", headers = jsonHeaders)
            }
        }
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
}
