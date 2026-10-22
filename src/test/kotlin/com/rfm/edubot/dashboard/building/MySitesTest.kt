package com.rfm.edubot.dashboard.building

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** My sites: a site manager's own sign-in, for the projects they run, and nothing else. */
class MySitesTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_my_sites")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API
    private val portal = "/app/api/portal"

    /** An employee of [company] named [name], and a sign-in of their own. */
    private suspend fun DashboardApi.siteManager(company: TestCompany, name: String): Pair<String, String> {
        val id = company.post("/app/api/crm/employees") { put("name", name); put("phone", "+351 96${(1..7).map { (0..9).random() }.joinToString("")}") }.text("id")!!
        return id to employeeToken(company, ObjectId(id))
    }

    @Test
    fun `a site manager writes the diary, logs issues and adds photos on the sites they run`() = dashboardApiTest(mongo) {
        val a = company()
        val (joao, token) = siteManager(a, "João Encarregado")
        val (other, _) = siteManager(a, "Rui")
        val mine = project(a) {
            put("managerId", joao); put("status", "ACTIVE")
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unitPriceEur", 90000.0) } }
        }.text("id")!!
        val theirs = project(a) { put("name", "Armazém"); put("managerId", other); put("status", "ACTIVE") }.text("id")!!
        project(a) { put("name", "Moradia antiga"); put("managerId", joao); put("status", "COMPLETED") }

        val sites = a.get("$portal/sites", token)
        assertEquals(HttpStatusCode.OK, sites.status, sites.toString())
        assertEquals(listOf(mine), sites.ids(), "only the running projects they manage")
        assertNull(sites.array.single().jsonObject.str("todayEntryId"))
        assertEquals("employees_only", a.get("$portal/sites").error, "the team uses the Building pages")
        assertNotEquals(HttpStatusCode.OK, a.get("$api/projects", token).status, "an employee never reaches a company module")

        val entry = a.post("$portal/sites/$mine/diary", token) { put("workDone", "Assentamento de tijolo no piso 1"); put("workers", 5) }
        assertEquals(HttpStatusCode.Created, entry.status, entry.toString())
        assertEquals("João Encarregado", entry.text("createdBy"))
        assertEquals(entry.text("id"), a.get("$portal/sites", token).array.single().jsonObject.str("todayEntryId"))
        assertEquals("Assentamento do piso 1", a.patch("$portal/sites/$mine/diary/${entry.text("id")}", token) { put("workDone", "Assentamento do piso 1") }.text("workDone"))
        assertEquals(HttpStatusCode.NotFound, a.post("$portal/sites/$theirs/diary", token) { put("workDone", "Nada") }.status)

        val issue = a.post("$portal/sites/$mine/issues", token) { put("title", "Fissura na laje"); put("priority", "HIGH") }
        assertEquals(HttpStatusCode.Created, issue.status, issue.toString())
        assertEquals("IN_PROGRESS", a.patch("$portal/sites/$mine/issues/${issue.text("id")}", token) { put("status", "IN_PROGRESS") }.text("status"))

        val photo = a.upload("$portal/sites/$mine/photos", "laje.jpg", JPEG_BYTES, mapOf("logId" to entry.text("id")!!), token = token)
        assertEquals(HttpStatusCode.Created, photo.status, photo.toString())
        assertEquals(HttpStatusCode.OK, a.download("$portal/photos/${photo.text("id")}?size=thumb", token).status)
        val elsewhere = a.upload("$api/projects/$theirs/photos", "armazem.jpg", JPEG_BYTES).text("id")!!
        assertEquals(HttpStatusCode.NotFound, a.download("$portal/photos/$elsewhere", token).status, "photos of a site they don't run")

        val detail = a.get("$portal/sites/$mine", token)
        assertEquals(HttpStatusCode.OK, detail.status)
        assertEquals(listOf("Fissura na laje"), detail.list("issues").map { it.str("title") })
        assertEquals(listOf("Assentamento do piso 1"), detail.list("diary").map { it.str("workDone") })
        assertFalse("Eur" in detail.body, "no money on My sites")
        assertTrue(detail.obj["canDiary"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound, a.get("$portal/sites/$theirs", token).status)
    }

    @Test
    fun `My sites needs the diary or the issues, and each write needs its own`() = dashboardApiTest(mongo) {
        val clock = company(listOf(DashboardModules.EMPLOYEES, DashboardModules.TIMESHEETS))
        val (_, clockToken) = siteManager(clock, "Ana")
        assertEquals("page_unavailable", clock.get("$portal/sites", clockToken).error)

        val diaryOnly = company(listOf(DashboardModules.EMPLOYEES, DashboardModules.SITE_DIARY))
        val (manager, token) = siteManager(diaryOnly, "Paulo")
        val id = project(diaryOnly) { put("managerId", manager); put("status", "ACTIVE") }.text("id")!!
        assertEquals(HttpStatusCode.OK, diaryOnly.get("$portal/sites", token).status)
        assertEquals("page_unavailable", diaryOnly.post("$portal/sites/$id/issues", token) { put("title", "Fuga") }.error)
        assertEquals(HttpStatusCode.Created, diaryOnly.post("$portal/sites/$id/diary", token) { put("workDone", "Limpeza") }.status)
    }
}
