package com.rfm.edubot.plugins

import com.rfm.edubot.conversation.model.UserStatus
import com.rfm.edubot.http.dateValue
import com.rfm.edubot.http.enumValue
import com.rfm.edubot.http.idParam
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The error contract: what the API answers for requests it can't read, and for its own failures. */
class StatusPagesTest {

    @Serializable
    private data class Draft(val name: String, val count: Int)

    private fun server(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                post("/drafts") { call.respond(call.receive<Draft>()) }
                get("/drafts/{id}") { call.respond(mapOf("id" to call.idParam("id").toHexString())) }
                get("/due") { call.respond(mapOf("on" to dateValue(call.request.queryParameters["on"].orEmpty(), "on").toString())) }
                get("/contacts") { call.respond(mapOf("status" to enumValue<UserStatus>(call.request.queryParameters["status"].orEmpty(), "status").name)) }
                get("/pages") { call.respond(mapOf("page" to call.request.queryParameters.getOrFail<Int>("page"))) }
                post("/upload") { throw PayloadTooLargeException(1024) }
                get("/broken") { error("the database is down") }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.postDraft(body: String, type: ContentType = ContentType.Application.Json): HttpResponse =
        client.post("/drafts") {
            contentType(type)
            setBody(body)
        }

    private suspend fun assertAnswer(status: HttpStatusCode, body: String, response: HttpResponse) {
        assertEquals(status, response.status)
        assertEquals(body, response.bodyAsText())
    }

    @Test
    fun `a body that isn't the request's JSON answers 400 invalid_body`() = server {
        assertAnswer(HttpStatusCode.OK, """{"name":"Pintura","count":2}""", postDraft("""{"name":"Pintura","count":2}"""))
        for (body in listOf("""{"name": """, """{"name":"Pintura"}""", """{"name":"Pintura","count":"two"}""", "[]", "")) {
            assertAnswer(HttpStatusCode.BadRequest, """{"error":"invalid_body"}""", postDraft(body))
        }
    }

    @Test
    fun `a body in a format the API doesn't read answers 415`() = server {
        assertAnswer(HttpStatusCode.UnsupportedMediaType, """{"error":"unsupported_media_type"}""", postDraft("name=Pintura", ContentType.Application.FormUrlEncoded))
        assertAnswer(HttpStatusCode.UnsupportedMediaType, """{"error":"unsupported_media_type"}""", postDraft("Pintura", ContentType.Text.Plain))
    }

    @Test
    fun `ids, dates and statuses the API can't read answer 400 with what they are about`() = server {
        val id = ObjectId().toHexString()
        assertAnswer(HttpStatusCode.OK, """{"id":"$id"}""", client.get("/drafts/$id"))
        assertAnswer(HttpStatusCode.BadRequest, """{"error":"invalid_id","detail":"id"}""", client.get("/drafts/nope"))
        assertAnswer(HttpStatusCode.OK, """{"on":"2026-12-31"}""", client.get("/due?on=2026-12-31"))
        assertAnswer(HttpStatusCode.BadRequest, """{"error":"invalid_date","detail":"on"}""", client.get("/due?on=31/12/2026"))
        assertAnswer(HttpStatusCode.OK, """{"status":"BLOCKED"}""", client.get("/contacts?status=BLOCKED"))
        assertAnswer(HttpStatusCode.BadRequest, """{"error":"invalid_status","detail":"status"}""", client.get("/contacts?status=blocked"))
    }

    @Test
    fun `a missing or unreadable query parameter answers 400 invalid_request`() = server {
        assertAnswer(HttpStatusCode.OK, """{"page":2}""", client.get("/pages?page=2"))
        assertAnswer(HttpStatusCode.BadRequest, """{"error":"invalid_request"}""", client.get("/pages"))
        assertAnswer(HttpStatusCode.BadRequest, """{"error":"invalid_request"}""", client.get("/pages?page=two"))
    }

    @Test
    fun `a body over the limit answers 413, and anything else unexpected 500 without its message`() = server {
        assertAnswer(HttpStatusCode.PayloadTooLarge, """{"error":"payload_too_large"}""", client.post("/upload"))
        assertAnswer(HttpStatusCode.InternalServerError, """{"error":"Internal server error"}""", client.get("/broken"))
    }
}
