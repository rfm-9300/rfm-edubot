package com.rfm.edubot.dashboard

import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals

/** `/app/api/overview` over HTTP: Home opens for every company's team, and counts only that company's records. */
class OverviewApiTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("overview_api")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val home = "/app/api/overview"

    @Test
    fun `Home opens for a company with no modules, and for its team only`() = dashboardApiTest(mongo) {
        val bare = company(emptyList())
        assertEquals(HttpStatusCode.OK, bare.get(home).status)
        assertEquals(HttpStatusCode.OK, bare.get("$home?extended=1").status)
        assertEquals(HttpStatusCode.OK, bare.get(home, bare.memberToken).status)
        assertEquals(HttpStatusCode.OK, bare.get(home, bare.operatorToken).status)
        assertEquals(HttpStatusCode.Unauthorized, call("GET", home, null).status)
        assertEquals(HttpStatusCode.Unauthorized, call("GET", home, "not-a-token").status)
        val employee = EmployeeRepository(mongo, bare.tenant.id).create("Rui Costa", "+351 930 000 002")
        assertEquals(HttpStatusCode.Unauthorized, bare.get(home, employeeToken(bare, employee.id)).status)
    }

    @Test
    fun `Home counts only the company's own records`() = dashboardApiTest(mongo) {
        val a = company()
        val b = company()
        val clientId = a.post("/app/api/crm/clients") { put("name", "Casa Martins"); put("phone", "+351 912 345 678"); put("taxId", "1"); put("address", "Rua 1") }.text("id")!!
        val quote = a.post("/app/api/crm/quotes") {
            put("clientId", clientId)
            putJsonArray("items") { addJsonObject { put("description", "Pintura"); put("unitPriceEur", 300.0) } }
        }
        assertEquals(HttpStatusCode.Created, quote.status, quote.toString())
        val user = UserRepository(mongo, a.tenant.id).findOrCreate("351912345678", "Maria Silva")
        ConversationRepository(mongo, a.tenant.id).findOrCreate(user.id, user.waId)

        val mine = a.get(home).obj
        val theirs = b.get(home).obj
        for ((field, count) in listOf("quotes" to 1L, "users" to 1L, "conversations" to 1L)) {
            assertEquals(count, mine[field]!!.jsonPrimitive.long, field)
            assertEquals(0L, theirs[field]!!.jsonPrimitive.long, field)
        }
    }
}
