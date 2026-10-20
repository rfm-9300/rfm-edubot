package com.rfm.edubot.instagram

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import com.rfm.edubot.webhook.dto.InstagramCommentFrom
import com.rfm.edubot.webhook.dto.InstagramCommentMedia
import com.rfm.edubot.webhook.dto.InstagramCommentValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals

/** `/app/api/instagram` over HTTP: its gate, isolation between companies' comments, and its error answers. */
class InstagramApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("instagram_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val base = "/app/api/instagram"
    private val offline = HttpClient(MockEngine) { engine { addHandler { respond("{}", HttpStatusCode.ServiceUnavailable) } } }

    private fun account() = ChannelBinding(platform = Platform.INSTAGRAM, externalId = "ig-${ObjectId()}", accessToken = "token", displayName = "obras.silva")

    /** A customer's comment on a post of [company]'s account; returns the comment's and the post's ids. */
    private suspend fun DashboardApi.comment(company: TestCompany): Pair<String, String> {
        val commentId = "c-${ObjectId()}"
        val mediaId = "m-${ObjectId()}"
        val accountId = company.tenant.channels.single { it.platform == Platform.INSTAGRAM }.externalId
        val value = InstagramCommentValue(id = commentId, text = "Quanto custa?", from = InstagramCommentFrom("u-1", "maria"), media = InstagramCommentMedia(mediaId))
        InstagramSocialService(mongo, offline).ingestComment(company.tenant, accountId, value, Clock.System.now())
        return commentId to mediaId
    }

    @Test
    fun `instagram answers 403 without its module`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.CONVERSATIONS), channels = listOf(account()))
        val (commentId, mediaId) = comment(off)
        assertEquals(HttpStatusCode.Forbidden, off.get(base).status)
        assertEquals(HttpStatusCode.Forbidden, off.get("$base/media/$mediaId").status)
        assertEquals(HttpStatusCode.Forbidden, off.post("$base/comments/$commentId/replies") { put("message", "Olá") }.status)
        val on = company(listOf(DashboardModules.INSTAGRAM), channels = listOf(account()))
        comment(on)
        assertEquals(1, on.get(base).obj["unrepliedCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a company never reads or answers another company's comments`() = dashboardApiTest(mongo) {
        val a = company(channels = listOf(account()))
        val b = company(channels = listOf(account()))
        val (commentId, mediaId) = comment(a)
        b.get(base).obj.let {
            assertEquals(0, it["unrepliedCount"]!!.jsonPrimitive.int)
            assertEquals("[]", it["comments"].toString())
        }
        b.get("$base/media/$mediaId?refresh=0").let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("media not found", it.error)
        }
        b.post("$base/comments/$commentId/replies") { put("message", "Olá") }.let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("comment not found", it.error)
        }
        assertEquals(1, a.get(base).obj["unrepliedCount"]!!.jsonPrimitive.int)
        assertEquals(HttpStatusCode.OK, a.get("$base/media/$mediaId?refresh=0").status)
    }

    @Test
    fun `instagram answers stable errors`() = dashboardApiTest(mongo) {
        val unconnected = company()
        assertEquals(false, unconnected.get(base).obj["connected"]!!.jsonPrimitive.boolean)
        assertEquals("instagram not connected", unconnected.get("$base/media/m-1").error)
        assertEquals("instagram not connected", unconnected.post("$base/comments/c-1/replies") { put("message", "Olá") }.error)
        val a = company(channels = listOf(account()))
        val (commentId, _) = comment(a)
        assertEquals("message is required", a.post("$base/comments/$commentId/replies") { put("message", " ") }.error)
        assertEquals("invalid_body", a.sendMalformed("POST", "$base/comments/$commentId/replies").error)
        assertEquals("invalid_body", a.post("$base/comments/$commentId/replies").error, "message is required by the body too")
    }
}
