package com.rfm.edubot.dashboard

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.dashboard.model.DashboardUser
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
}
