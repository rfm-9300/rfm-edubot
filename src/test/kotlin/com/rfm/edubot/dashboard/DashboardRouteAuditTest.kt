package com.rfm.edubot.dashboard

import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.DashboardApi
import com.rfm.edubot.testing.TestMongo
import com.rfm.edubot.testing.dashboardApiTest
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every route under `/app/api`, read from the route tree the application mounts: each one is behind a module
 * gate, open to every session on purpose, or an employee's own page, and each gate refuses a company without
 * one of its modules before the handler runs. A route added without a gate fails here.
 */
class DashboardRouteAuditTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("route_audit")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }

        private const val PORTAL = "/app/api/portal/"
    }

    private sealed interface Guard {
        data class Gated(val modules: Set<String>, val denial: ModuleDenial) : Guard
        data class Open(val reason: String) : Guard
        data object EmployeePage : Guard
        data object None : Guard
    }

    private data class Endpoint(val method: String, val path: String, val guard: Guard)

    private fun DashboardApi.endpoints(): List<Endpoint> = routes.getAllRoutes().mapNotNull { route ->
        val parts = route.toString().split('/').filter { it.isNotEmpty() }
        val method = parts.lastOrNull { it.startsWith("(method:") }?.removePrefix("(method:")?.removeSuffix(")") ?: return@mapNotNull null
        val path = "/" + parts.filterNot { it.startsWith("(") }.joinToString("/")
        if (!path.startsWith("/app/api/")) return@mapNotNull null
        Endpoint(method, path, guardOf(route, path))
    }.distinct()

    private fun guardOf(route: RoutingNode, path: String): Guard {
        generateSequence(route) { it.parent }.forEach { node ->
            when (val selector = node.selector) {
                is ModuleGateSelector -> return Guard.Gated(selector.modules, selector.denial)
                is OpenRouteSelector -> return Guard.Open(selector.reason)
                else -> Unit
            }
        }
        return if (path.startsWith(PORTAL)) Guard.EmployeePage else Guard.None
    }

    /** What has to go for [module] to be off: modules that bring it along go with it. */
    private fun without(module: String): List<String> {
        val bringers = mapOf(
            DashboardModules.SERVICES to listOf(DashboardModules.CLIENTS),
            DashboardModules.SUPPLIERS to listOf(DashboardModules.PAYMENTS),
            DashboardModules.EMPLOYEES to listOf(DashboardModules.TIMESHEETS),
            DashboardModules.PROJECTS to DashboardModules.building,
        )
        return DashboardModules.catalog - (bringers[module].orEmpty() + module).toSet()
    }

    private fun Endpoint.url() = path.replace(Regex("""\{[^}]+}"""), "0123456789abcdef01234567")

    @Test
    fun `every endpoint is gated by its modules, open on purpose or an employee's own page`() = dashboardApiTest(mongo) {
        val endpoints = endpoints()
        assertTrue(endpoints.size > 200, "the audit reads the whole API: found ${endpoints.size} endpoints")
        val unguarded = endpoints.filter { it.guard == Guard.None }
        if (unguarded.isNotEmpty()) {
            fail("Routes without a module gate; put them under requireModules or, if every session needs them, openToEverySession:\n" + unguarded.joinToString("\n") { "${it.method} ${it.path}" })
        }
        val reasons = endpoints.mapNotNull { (it.guard as? Guard.Open)?.reason }
        assertTrue(reasons.all { it.isNotBlank() }, "an open route says why")
        assertTrue(endpoints.none { (it.guard as? Guard.Gated)?.modules?.isEmpty() == true }, "a gate names at least one module")
    }

    @Test
    fun `each gate refuses a company without one of its modules before the handler runs`() = dashboardApiTest(mongo) {
        val gated = endpoints().mapNotNull { e -> (e.guard as? Guard.Gated)?.let { e to it } }
        val companies = gated.flatMap { (_, gate) -> gate.modules }.toSet().filter { it !in DashboardModules.alwaysOn }
            .associateWith { company(without(it)) }
        val wrong = mutableListOf<String>()
        for ((endpoint, gate) in gated) {
            for (module in gate.modules.filter { it in companies }) {
                val company = companies.getValue(module)
                val reply = call(endpoint.method, endpoint.url(), company.token, if (endpoint.method in setOf("POST", "PUT", "PATCH")) "{}" else null)
                val expected = gate.denial.body?.error
                if (reply.status != HttpStatusCode.Forbidden || reply.error != expected || (expected == null && reply.body.isNotEmpty())) {
                    wrong += "${endpoint.method} ${endpoint.path} without $module: $reply"
                }
            }
        }
        assertTrue(wrong.isEmpty(), "Gates that let a company through without the module:\n" + wrong.joinToString("\n"))
    }

    @Test
    fun `an employee's pages refuse everyone else in the team`() = dashboardApiTest(mongo) {
        val company = company()
        val pages = endpoints().filter { it.guard == Guard.EmployeePage }
        assertTrue(pages.isNotEmpty())
        for (page in pages) {
            for (token in listOf(company.token, company.operatorToken)) {
                val reply = call(page.method, page.url(), token, if (page.method in setOf("POST", "PUT", "PATCH")) "{}" else null)
                assertEquals(HttpStatusCode.Forbidden, reply.status, "${page.method} ${page.path}")
                assertEquals("employees_only", reply.error, "${page.method} ${page.path}")
            }
        }
    }

    @Test
    fun `an employee's sign-in reaches only their own pages, their account and the session`() = dashboardApiTest(mongo) {
        val company = company()
        val employee = EmployeeRepository(mongo, company.tenant.id).create("Rui Costa", "+351 930 000 001")
        val token = employeeToken(company, employee.id)
        val team = endpoints().filter { !EmployeePortal.allowsPath(it.url()) }
        assertTrue(team.size > 200)
        for (endpoint in team) {
            val reply = call(endpoint.method, endpoint.url(), token, if (endpoint.method in setOf("POST", "PUT", "PATCH")) "{}" else null)
            assertEquals(HttpStatusCode.Unauthorized, reply.status, "${endpoint.method} ${endpoint.path}")
        }
    }

    @Test
    fun `a company with every module gets past every gate`() = dashboardApiTest(mongo) {
        val company = company()
        val gated = endpoints().filter { it.guard is Guard.Gated }
        for (endpoint in gated) {
            val reply = call(endpoint.method, endpoint.url(), company.token, if (endpoint.method in setOf("POST", "PUT", "PATCH")) "{}" else null)
            val gate = endpoint.guard as Guard.Gated
            val refusedByGate = reply.status == HttpStatusCode.Forbidden && reply.error == gate.denial.body?.error
            assertTrue(!refusedByGate, "${endpoint.method} ${endpoint.path} refused a company with every module: $reply")
        }
    }
}
