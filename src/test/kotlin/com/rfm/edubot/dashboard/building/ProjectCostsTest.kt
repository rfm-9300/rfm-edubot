package com.rfm.edubot.dashboard.building

import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestCompany
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import com.rfm.edubot.timesheets.ShiftRepository
import com.rfm.edubot.timesheets.model.Shift
import com.rfm.edubot.timesheets.model.ShiftStatus
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** A project's costs: the bills linked to it, the hours clocked at its sites, its budget and its margin. */
class ProjectCostsTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("building_costs")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private val api = BUILDING_API

    private suspend fun DashboardApi.supplier(company: TestCompany, name: String): String =
        company.post("/app/api/crm/suppliers") { put("name", name); put("phone", "+351 21${(1..7).map { (0..9).random() }.joinToString("")}") }.text("id")!!

    private suspend fun DashboardApi.employee(company: TestCompany, name: String): String =
        company.post("/app/api/crm/employees") { put("name", name); put("phone", "+351 93${(1..7).map { (0..9).random() }.joinToString("")}") }.text("id")!!

    private suspend fun DashboardApi.bill(company: TestCompany, payee: Pair<String, String>, amount: Double, projectId: String?) =
        company.post("/app/api/crm/payments") {
            put(payee.first, payee.second); put("dueDate", company.day(14))
            projectId?.let { put("projectId", it) }
            putJsonArray("items") { addJsonObject { put("description", "Material"); put("quantity", 1.0); put("unitPriceEur", amount) } }
        }

    private suspend fun DashboardApi.site(company: TestCompany, name: String): String =
        company.post("/app/api/timesheets/sites") { put("name", name); put("latitude", 38.7); put("longitude", -9.1) }.text("id")!!

    /** A finished shift of [minutes] at [siteId] on [day]. */
    private suspend fun shift(company: TestCompany, employeeId: String, siteId: String, day: LocalDate, minutes: Int) {
        val start = Clock.System.now() - (minutes + 60).minutes
        ShiftRepository(mongo, company.tenant.id).insert(
            Shift(
                tenantId = company.tenant.id, employeeId = ObjectId(employeeId), status = ShiftStatus.CLOSED, startAt = start,
                endAt = start + minutes.minutes, day = day, siteId = ObjectId(siteId), workedMinutes = minutes, createdAt = start, updatedAt = start,
            ),
        )
    }

    private fun JsonObject.eurOrNull(name: String): Double? = (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()

    @Test
    fun `a project's costs are its bills and its hours, against its budget and its contract`() = dashboardApiTest(mongo) {
        val a = company()
        val clientId = client(a)
        val id = project(a) {
            put("clientId", clientId); put("status", "ACTIVE"); put("costBudgetEur", 6000.0); put("laborRateEur", 20.0)
            putJsonArray("contract") { addJsonObject { put("description", "Moradia"); put("quantity", 1.0); put("unitPriceEur", 10000.0) } }
        }.text("id")!!
        val measurement = a.post("$api/measurements") { put("projectId", id) }
        a.patch("$api/measurements/${measurement.text("id")}") { putJsonArray("lines") { addJsonObject { put("key", measurement.list("lines").single().str("key")); put("toDateQty", 0.5) } } }
        a.post("$api/measurements/${measurement.text("id")}/approve")
        a.post("$api/measurements/${measurement.text("id")}/invoice") { put("dueDate", a.day(30)) }

        val betao = supplier(a, "Betões Lda")
        val paid = bill(a, "supplierId" to betao, 1500.0, id).text("id")!!
        a.patch("/app/api/crm/payments/$paid/paid")
        bill(a, "supplierId" to betao, 500.0, id)
        val cancelled = bill(a, "supplierId" to betao, 300.0, id).text("id")!!
        a.post("/app/api/crm/payments/$cancelled/cancel")
        bill(a, "supplierId" to betao, 999.0, projectId = null)
        val joao = employee(a, "João Pedreiro")
        val bonus = bill(a, "employeeId" to joao, 200.0, id)
        assertEquals("${a.get("$api/projects/$id").text("number")} · Moradia Silva", bonus.text("projectLabel"))

        val site = site(a, "Obra da Moradia")
        site(a, "Escritório")
        assertEquals(listOf(site), a.patch("$api/projects/$id") { putJsonArray("siteIds") { add(JsonPrimitive(site)) } }.obj["siteIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        shift(a, joao, site, LocalDate.parse(a.day(-1)), 480)
        shift(a, joao, site, LocalDate.parse(a.day(-2)), 90)

        val costs = a.get("$api/projects/$id/costs")
        assertEquals(HttpStatusCode.OK, costs.status, costs.toString())
        assertEquals(listOf(10000.0, 5000.0, 5000.0, 0.0), listOf("currentContractEur", "measuredEur", "invoicedEur", "receivedEur").map { costs.eur(it) })
        val bills = costs.obj["bills"]!!.jsonObject
        assertEquals(listOf(2200.0, 1500.0, 700.0), listOf("totalEur", "paidEur", "toPayEur").map { bills.eur(it) }, "the cancelled bill and the other project's don't count")
        assertEquals(listOf("Betões Lda" to 2000.0, "João Pedreiro" to 200.0), bills.list("byPayee").map { it.str("name") to it.eur("totalEur") })
        assertEquals(3, bills.list("payments").size)
        val labour = costs.obj["labour"]!!.jsonObject
        assertEquals(listOf(9.5, 20.0, 190.0), listOf("hours", "rateEur", "costEur").map { labour.eur(it) })
        assertEquals(listOf("João Pedreiro"), labour.list("byEmployee").map { it.str("name") })
        assertEquals(listOf("Obra da Moradia"), labour.list("sites").map { it.str("name") })
        assertEquals(2390.0, costs.eur("totalCostEur"))
        assertEquals(39.8, costs.eur("budgetUsedPct"))
        assertEquals(4000.0 to 40.0, costs.eur("expectedMarginEur") to costs.eur("expectedMarginPct"), "the budget is what the job is expected to cost")
        assertEquals(2610.0 to 52.2, costs.eur("marginToDateEur") to costs.eur("marginToDatePct"))

        a.patch("/app/api/crm/payments/$paid/project") { put("projectId", "") }
        assertEquals(700.0, a.get("$api/projects/$id/costs").obj["bills"]!!.jsonObject.eur("totalEur"), "an unlinked bill leaves the project's costs")
        a.patch("$api/projects/$id") { put("costBudgetEur", 0.0) }
        val noBudget = a.get("$api/projects/$id/costs")
        assertNull(noBudget.obj.eurOrNull("costBudgetEur"))
        assertEquals(10000.0 - 890.0, noBudget.eur("expectedMarginEur"), "without a budget, the costs so far")
    }

    @Test
    fun `a work site is one project's, and a bill names a project the company has`() = dashboardApiTest(mongo) {
        val a = company()
        val first = project(a).text("id")!!
        val second = project(a) { put("name", "Armazém") }.text("id")!!
        val site = site(a, "Estaleiro")
        a.patch("$api/projects/$first") { putJsonArray("siteIds") { add(JsonPrimitive(site)) } }
        assertEquals("site_in_use", a.patch("$api/projects/$second") { putJsonArray("siteIds") { add(JsonPrimitive(site)) } }.error)
        assertEquals("site_not_found", a.patch("$api/projects/$second") { putJsonArray("siteIds") { add(JsonPrimitive(ObjectId().toHexString())) } }.error)
        assertEquals("invalid_rate", a.patch("$api/projects/$second") { put("laborRateEur", -1.0) }.error)
        assertEquals("invalid_budget", a.patch("$api/projects/$second") { put("costBudgetEur", Double.MAX_VALUE) }.error)

        val supplier = supplier(a, "Ferragens")
        assertEquals(HttpStatusCode.BadRequest, bill(a, "supplierId" to supplier, 10.0, ObjectId().toHexString()).status)
        val linked = bill(a, "supplierId" to supplier, 10.0, second).text("id")!!
        a.delete("$api/projects/$second")
        assertNull(a.get("/app/api/crm/payments/$linked").text("projectId"), "a removed project's bills stay, for no project")

        val noProjects = company(listOf(DashboardModules.SUPPLIERS, DashboardModules.PAYMENTS))
        val theirs = supplier(noProjects, "Tintas")
        assertEquals(HttpStatusCode.Forbidden, bill(noProjects, "supplierId" to theirs, 10.0, ObjectId().toHexString()).status)
    }

    @Test
    fun `without payments or the time clock, the costs leave the bills or the hours out`() = dashboardApiTest(mongo) {
        val a = company(listOf(DashboardModules.PROJECTS, DashboardModules.MEASUREMENTS))
        val id = project(a).text("id")!!
        val costs = a.get("$api/projects/$id/costs")
        assertEquals(HttpStatusCode.OK, costs.status)
        assertNull(costs.obj["bills"]?.takeIf { it !is JsonNull })
        assertNull(costs.obj["labour"]?.takeIf { it !is JsonNull })
        assertEquals(HttpStatusCode.NotFound, a.get("$api/projects/${ObjectId()}/costs").status)
    }
}
