package com.rfm.edubot.dashboard.settings

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.model.TenantLocales
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Settings page's API over HTTP: the website widget, channels, language, Home's cards and the document studio. */
class SettingsApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("settings_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val studio = "/app/api/settings/document-template"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private suspend fun DashboardApi.locale(company: TestCompany): String =
        company.get("/app/api/me").obj["tenant"]!!.jsonObject["locale"]!!.jsonPrimitive.content

    @Test
    fun `settings answer 403 without the settings module`() = dashboardApiTest(mongo) {
        val off = company(listOf(DashboardModules.CLIENTS, DashboardModules.QUOTES))
        for (path in listOf("/app/api/web-widget", "/app/api/settings/overview", studio, "$studio/logo", "$studio/presets")) {
            assertEquals(HttpStatusCode.Forbidden, off.get(path).status, path)
        }
        assertEquals(HttpStatusCode.Forbidden, off.post("/app/api/web-widget").status)
        assertEquals(HttpStatusCode.Forbidden, off.post("/app/api/settings/locale") { put("locale", "en") }.status)
        assertEquals(HttpStatusCode.Forbidden, off.put("/app/api/settings/overview").status)
        assertEquals(HttpStatusCode.Forbidden, off.put(studio) { put("companyName", "Outra") }.status)
        assertEquals(HttpStatusCode.Forbidden, off.upload("$studio/logo", "logo.png", png).status)
        assertEquals(HttpStatusCode.Forbidden, off.post("$studio/presets") { put("name", "Azul") }.status)
        assertEquals(HttpStatusCode.Forbidden, off.delete("/app/api/channels/WEB/web_1").status)
        assertEquals(TenantLocales.DEFAULT, locale(off), "the refusals changed nothing")
    }

    @Test
    fun `a company's settings never reach another company`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val key = a.post("/app/api/web-widget") { putJsonArray("allowedOrigins") { add(JsonPrimitive("https://obras.example")) } }.text("publicKey")
        assertNotNull(key)
        assertNull(b.get("/app/api/web-widget").text("publicKey"))
        assertEquals(HttpStatusCode.OK, b.delete("/app/api/channels/WEB/$key").status, "b disconnects nothing of its own")
        assertEquals(key, a.get("/app/api/web-widget").text("publicKey"))

        assertEquals("en", a.post("/app/api/settings/locale") { put("locale", "en") }.text("locale"))
        assertEquals("en", locale(a))
        assertEquals(TenantLocales.DEFAULT, locale(b))
        val hidden = a.put("/app/api/settings/overview") { putJsonArray("hidden") { add(JsonPrimitive("pipeline")) } }.obj["hidden"]!!.jsonArray
        assertEquals(listOf("pipeline"), hidden.map { it.jsonPrimitive.content })
        assertTrue(b.get("/app/api/settings/overview").obj["hidden"]?.jsonArray.isNullOrEmpty())

        assertEquals("Obras Silva Lda", a.put(studio) { put("companyName", "Obras Silva Lda") }.text("companyName"))
        assertEquals(HttpStatusCode.OK, a.upload("$studio/logo", "logo.png", png).status)
        assertEquals(HttpStatusCode.OK, a.get("$studio/logo").status)
        assertEquals(HttpStatusCode.NotFound, b.get("$studio/logo").status)
        b.get(studio).let {
            assertEquals("Obras Silva", it.text("companyName"))
            assertFalse(it.obj["hasLogo"]!!.jsonPrimitive.boolean)
        }

        val presetId = a.post("$studio/presets") { put("name", "Azul") }.array.single { !it.jsonObject["builtIn"]!!.jsonPrimitive.boolean }.jsonObject["id"]!!.jsonPrimitive.content
        assertFalse(b.get("$studio/presets").array.any { it.jsonObject["id"]!!.jsonPrimitive.content == presetId })
        assertEquals(HttpStatusCode.NotFound, b.post("$studio/presets/$presetId/apply").status)
        assertEquals(HttpStatusCode.OK, b.delete("$studio/presets/$presetId").status, "b removes nothing of its own")
        assertTrue(a.get("$studio/presets").array.any { it.jsonObject["id"]!!.jsonPrimitive.content == presetId })
    }

    @Test
    fun `settings answer stable error codes`() = dashboardApiTest(mongo) {
        val a = company()
        assertEquals("unsupported locale", a.post("/app/api/settings/locale") { put("locale", "xx") }.error)
        assertEquals("invalid_body", a.sendMalformed("POST", "/app/api/settings/locale").error)
        assertEquals("invalid_body", a.post("/app/api/settings/locale").error, "locale is required")
        assertEquals("invalid platform", a.delete("/app/api/channels/FAX/1").error)
        assertEquals("invalid_body", a.sendMalformed("PUT", "/app/api/settings/overview").error)
        assertEquals("invalid_body", a.sendMalformed("PUT", studio).error)
        assertEquals(HttpStatusCode.OK, call("POST", "/app/api/web-widget", a.token, "{\"allowedOrigins\": ").status, "the widget treats an unreadable body as no origins")

        call("POST", "$studio/logo", a.token, "{}").let {
            assertEquals(HttpStatusCode.UnsupportedMediaType, it.status)
            assertEquals("unsupported_media_type", it.error)
        }
        a.upload("$studio/logo", "logo.png", "GIF89a".toByteArray()).let {
            assertEquals(HttpStatusCode.UnsupportedMediaType, it.status)
            assertEquals("logo_type", it.error, "the file's bytes decide its type, not its name")
        }
        assertEquals("empty_file", a.upload("$studio/logo", "logo.png", ByteArray(0)).error)
        a.upload("$studio/logo", "logo.png", ByteArray(2 * 1024 * 1024 + 1)).let {
            assertEquals(HttpStatusCode.PayloadTooLarge, it.status)
            assertEquals("logo_too_large", it.error)
        }
        assertEquals(HttpStatusCode.NotFound, a.get("$studio/logo").status)

        assertEquals("name required", a.post("$studio/presets") { put("name", "  ") }.error)
        assertEquals("invalid_body", a.sendMalformed("POST", "$studio/presets").error)
        assertEquals(HttpStatusCode.NotFound, a.post("$studio/presets/nope/apply").status)
    }
}
