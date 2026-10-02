package com.rfm.edubot.dashboard

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardAccessTest {
    private val now = Clock.System.now()
    private val tenants = mockk<TenantRepository>()
    private val users = mockk<DashboardUserRepository>()

    private fun tenant(status: TenantStatus = TenantStatus.ACTIVE) =
        Tenant(slug = "acme", name = "Acme", channels = emptyList(), status = status, createdAt = now, updatedAt = now)

    private fun user(tenant: Tenant, status: DashboardUserStatus = DashboardUserStatus.ACTIVE) =
        DashboardUser(tenantId = tenant.id, email = "owner@acme.test", passwordHash = "hash", status = status, createdAt = now)

    private fun token(typ: String, tenantId: String, subject: String? = null) = JWT.decode(
        JWT.create()
            .withClaim("typ", typ)
            .withClaim("tenantId", tenantId)
            .apply { if (subject != null) withSubject(subject) }
            .sign(Algorithm.HMAC256("test-secret")),
    )

    @Test
    fun `an active user of an active tenant is allowed`() {
        val t = tenant()
        assertTrue(DashboardAccessPolicy.allows(t, user(t), DashboardAccessPolicy.TENANT_USER))
    }

    @Test
    fun `tenant users lose access when the tenant is suspended or deleted`() {
        for (status in listOf(TenantStatus.SUSPENDED, TenantStatus.DELETED)) {
            val t = tenant(status)
            assertFalse(DashboardAccessPolicy.allows(t, user(t), DashboardAccessPolicy.TENANT_USER), "tenant $status")
        }
    }

    @Test
    fun `disabled, missing, and other-tenant users are rejected`() {
        val t = tenant()
        assertFalse(DashboardAccessPolicy.allows(t, user(t, DashboardUserStatus.DISABLED), DashboardAccessPolicy.TENANT_USER))
        assertFalse(DashboardAccessPolicy.allows(t, null, DashboardAccessPolicy.TENANT_USER))
        assertFalse(DashboardAccessPolicy.allows(t, user(tenant()), DashboardAccessPolicy.TENANT_USER))
    }

    @Test
    fun `a user of the first company opens the tenant's other active companies, and no one else's`() {
        val first = tenant()
        val second = tenant().copy(parentTenantId = first.id)
        val owner = user(first)
        assertTrue(DashboardAccessPolicy.allows(second, owner, DashboardAccessPolicy.TENANT_USER))
        assertFalse(DashboardAccessPolicy.allows(second.copy(status = TenantStatus.SUSPENDED), owner, DashboardAccessPolicy.TENANT_USER))
        assertFalse(DashboardAccessPolicy.allows(tenant().copy(parentTenantId = ObjectId()), owner, DashboardAccessPolicy.TENANT_USER))
        assertFalse(DashboardAccessPolicy.allows(first, user(tenant()), DashboardAccessPolicy.TENANT_USER))
    }

    @Test
    fun `operators can open active and suspended tenants but not deleted ones`() {
        assertTrue(DashboardAccessPolicy.allows(tenant(TenantStatus.ACTIVE), null, DashboardAccessPolicy.OPERATOR_IMPERSONATION))
        assertTrue(DashboardAccessPolicy.allows(tenant(TenantStatus.SUSPENDED), null, DashboardAccessPolicy.OPERATOR_IMPERSONATION))
        assertFalse(DashboardAccessPolicy.allows(tenant(TenantStatus.DELETED), null, DashboardAccessPolicy.OPERATOR_IMPERSONATION))
    }

    @Test
    fun `unknown principal types are rejected`() {
        val t = tenant()
        assertFalse(DashboardAccessPolicy.allows(t, user(t), "admin"))
    }

    @Test
    fun `a valid tenant token resolves to its tenant and user`() = runBlocking {
        val t = tenant()
        val u = user(t)
        coEvery { tenants.findById(t.id) } returns t
        coEvery { users.findById(u.id) } returns u

        val context = resolveDashboardContext(token("tenant", t.id.toHexString(), u.id.toHexString()), tenants, users)

        assertEquals(DashboardContext(t, u, "tenant"), context)
    }

    @Test
    fun `an unexpired token stops resolving once the tenant is suspended or the user is disabled`() = runBlocking {
        val suspended = tenant(TenantStatus.SUSPENDED)
        val suspendedUser = user(suspended)
        coEvery { tenants.findById(suspended.id) } returns suspended
        coEvery { users.findById(suspendedUser.id) } returns suspendedUser
        assertNull(resolveDashboardContext(token("tenant", suspended.id.toHexString(), suspendedUser.id.toHexString()), tenants, users))

        val active = tenant()
        val disabledUser = user(active, DashboardUserStatus.DISABLED)
        coEvery { tenants.findById(active.id) } returns active
        coEvery { users.findById(disabledUser.id) } returns disabledUser
        assertNull(resolveDashboardContext(token("tenant", active.id.toHexString(), disabledUser.id.toHexString()), tenants, users))
    }

    @Test
    fun `malformed or unknown tenant ids do not resolve`() = runBlocking {
        coEvery { tenants.findById(any()) } returns null
        assertNull(resolveDashboardContext(token("tenant", "not-an-object-id"), tenants, users))
        assertNull(resolveDashboardContext(token("tenant", ObjectId().toHexString(), ObjectId().toHexString()), tenants, users))
    }

    private fun employee(company: Tenant, archivedAt: kotlinx.datetime.Instant? = null) = Employee(
        tenantId = company.id, number = "COL-001", name = "Ana Costa", phone = "+351910200001",
        createdAt = now, updatedAt = now, archivedAt = archivedAt,
    )

    private fun employeeLogin(first: Tenant, company: Tenant, record: Employee) = DashboardUser(
        tenantId = first.id, email = "ana@acme.test", passwordHash = "hash", role = DashboardUserRole.TENANT_EMPLOYEE,
        createdAt = now, employeeId = record.id, employeeTenantId = company.id,
    )

    @Test
    fun `an employee's sign-in opens only the company of their record, while it has employees and services`() {
        val first = tenant()
        val second = tenant().copy(parentTenantId = first.id)
        val login = employeeLogin(first, second, employee(second))
        assertTrue(DashboardAccessPolicy.allows(second, login, DashboardAccessPolicy.TENANT_USER))
        assertFalse(DashboardAccessPolicy.allows(first, login, DashboardAccessPolicy.TENANT_USER), "the tenant's other companies stay closed")
        assertFalse(DashboardAccessPolicy.allows(second.copy(enabledModules = listOf(DashboardModules.EMPLOYEES)), login, DashboardAccessPolicy.TENANT_USER))
        assertTrue(DashboardAccessPolicy.allows(second.copy(enabledModules = listOf(DashboardModules.EMPLOYEES, DashboardModules.CLIENTS)), login, DashboardAccessPolicy.TENANT_USER))
        assertFalse(DashboardAccessPolicy.allows(second, login.copy(employeeId = null), DashboardAccessPolicy.TENANT_USER))
    }

    @Test
    fun `an employee's token needs their record, and archiving it ends the session`() = runBlocking {
        val company = tenant()
        val record = employee(company)
        val login = employeeLogin(company, company, record)
        coEvery { tenants.findById(company.id) } returns company
        coEvery { users.findById(login.id) } returns login
        val payload = token("tenant", company.id.toHexString(), login.id.toHexString())

        val context = resolveDashboardContext(payload, tenants, users) { tenantId, id -> record.takeIf { tenantId == company.id && id == record.id } }
        assertEquals(record, context?.employee)
        assertFalse(context!!.requireModule(DashboardModules.SERVICES), "an employee has none of the company's modules")
        assertFalse(context.requireModule(DashboardModules.EMPLOYEES))

        assertNull(resolveDashboardContext(payload, tenants, users) { _, _ -> record.copy(archivedAt = now) })
        assertNull(resolveDashboardContext(payload, tenants, users) { _, _ -> null })
        assertNull(resolveDashboardContext(payload, tenants, users), "refused where no lookup is passed")
    }

    @Test
    fun `team members keep the company's modules`() {
        val t = tenant()
        assertTrue(DashboardContext(t, user(t), DashboardAccessPolicy.TENANT_USER).requireModule(DashboardModules.SERVICES))
        assertTrue(DashboardContext(t, null, DashboardAccessPolicy.OPERATOR_IMPERSONATION).requireModule(DashboardModules.SERVICES))
    }

    @Test
    fun `an employee's session only reaches its own pages`() {
        listOf("/app/api/me", "/app/api/account", "/app/api/account/password", "/app/api/portal/services", "/app/api/portal/services/abc")
            .forEach { assertTrue(EmployeePortal.allowsPath(it), it) }
        listOf(
            "/app/api/overview", "/app/api/crm/clients", "/app/api/crm/service-submissions", "/app/api/notifications",
            "/app/api/companies/x/switch", "/app/api/instagram/connect", "/app/api/whatsapp/connect", "/app/api/email/send",
            "/app/api/accounting", "/app/api/portal", "/app/api/me/x",
        ).forEach { assertFalse(EmployeePortal.allowsPath(it), it) }
    }
}
