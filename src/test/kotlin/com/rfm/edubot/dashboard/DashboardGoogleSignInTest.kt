package com.rfm.edubot.dashboard

import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.dashboard.DashboardGoogleSignIn.Outcome
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Testcontainers
class DashboardGoogleSignInTest {

    companion object {
        @Container
        @JvmStatic
        val mongo = MongoDBContainer("mongo:7")

        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo.start()
            mongoModule = MongoModule(AppConfig.MongoConfig(uri = mongo.replicaSetUrl, database = "dashboard_google"))
            mongoModule.initialize()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
            mongo.stop()
        }
    }

    private val now = Clock.System.now()
    private val users get() = DashboardUserRepository(mongoModule)
    private val tenants get() = TenantRepository(mongoModule)
    private val signIn get() = DashboardGoogleSignIn(users, tenants)

    private suspend fun tenant(status: TenantStatus = TenantStatus.ACTIVE): Tenant {
        val id = ObjectId()
        return tenants.create(Tenant(id = id, slug = "t-${id.toHexString()}", name = "Acme", channels = emptyList(), status = status, createdAt = now, updatedAt = now))
    }

    private suspend fun user(
        tenant: Tenant,
        status: DashboardUserStatus = DashboardUserStatus.ACTIVE,
        googleUid: String? = null,
    ): DashboardUser {
        val id = ObjectId()
        return users.create(
            DashboardUser(
                id = id, tenantId = tenant.id, email = "user-${id.toHexString()}@acme.test", passwordHash = "hash",
                status = status, createdAt = now, googleUid = googleUid, googleEmail = googleUid?.let { "$it@gmail.com" },
            ),
        )
    }

    private fun uid() = "uid-${ObjectId().toHexString()}"

    @Test
    fun `the first Google sign-in with a user's email links that account`() = runBlocking<Unit> {
        val u = user(tenant())
        val googleUid = uid()

        val outcome = assertIs<Outcome.SignedIn>(signIn.signIn(googleUid, u.email, now))

        assertEquals(true, outcome.linkedNow)
        val stored = users.findById(u.id)!!
        assertEquals(googleUid, stored.googleUid)
        assertEquals(u.email, stored.googleEmail)
        assertNotNull(stored.lastLoginAt)
    }

    @Test
    fun `a linked Google account signs in even after its email changed, and the new email is stored`() = runBlocking {
        val googleUid = uid()
        val u = user(tenant(), googleUid = googleUid)

        val outcome = assertIs<Outcome.SignedIn>(signIn.signIn(googleUid, "renamed@gmail.com", now))

        assertEquals(false, outcome.linkedNow)
        assertEquals(u.id, outcome.user.id)
        assertEquals("renamed@gmail.com", users.findById(u.id)!!.googleEmail)
    }

    @Test
    fun `an unknown Google account is refused`() = runBlocking {
        assertEquals(Outcome.Refused(DashboardGoogleSignIn.NO_ACCOUNT), signIn.signIn(uid(), "nobody@gmail.com", now))
    }

    @Test
    fun `a user linked to one Google account cannot be taken over by another with the same email`() = runBlocking {
        val first = uid()
        val u = user(tenant(), googleUid = first)

        assertEquals(Outcome.Refused(DashboardGoogleSignIn.OTHER_GOOGLE_ACCOUNT), signIn.signIn(uid(), u.email, now))
        assertEquals(first, users.findById(u.id)!!.googleUid)
    }

    @Test
    fun `disabled users and suspended tenants are refused without linking`() = runBlocking {
        val disabled = user(tenant(), status = DashboardUserStatus.DISABLED)
        assertEquals(Outcome.Refused(DashboardGoogleSignIn.ACCOUNT_INACTIVE), signIn.signIn(uid(), disabled.email, now))
        assertNull(users.findById(disabled.id)!!.googleUid)

        val suspended = user(tenant(TenantStatus.SUSPENDED))
        assertEquals(Outcome.Refused(DashboardGoogleSignIn.ACCOUNT_INACTIVE), signIn.signIn(uid(), suspended.email, now))
        assertNull(users.findById(suspended.id)!!.googleUid)
    }

    @Test
    fun `a Google account links to one user at most, and users without one never collide`() = runBlocking {
        val t = tenant()
        val a = user(t)
        val b = user(t)
        val c = user(t)
        val googleUid = uid()

        assertEquals(DashboardUserRepository.LinkResult.LINKED, users.linkGoogle(a.id, googleUid, "A@Gmail.com"))
        assertEquals("a@gmail.com", users.findById(a.id)!!.googleEmail)
        assertEquals(DashboardUserRepository.LinkResult.TAKEN, users.linkGoogle(b.id, googleUid, "a@gmail.com"))
        assertEquals(DashboardUserRepository.LinkResult.LINKED, users.linkGoogle(a.id, googleUid, "a@gmail.com"))
        assertEquals(DashboardUserRepository.LinkResult.NOT_FOUND, users.linkGoogle(ObjectId(), uid(), "x@gmail.com"))

        val unlinked = users.unlinkGoogle(a.id)!!
        assertNull(unlinked.googleUid)
        assertNull(unlinked.googleEmail)
        assertEquals(DashboardUserRepository.LinkResult.LINKED, users.linkGoogle(c.id, googleUid, "a@gmail.com"))
    }

    @Test
    fun `the password can be switched off and set again`() = runBlocking {
        val u = user(tenant(), googleUid = uid())

        assertNull(users.setPasswordHash(u.id, null)!!.passwordHash)
        assertNull(users.findById(u.id)!!.passwordHash)
        assertEquals("new-hash", users.setPasswordHash(u.id, "new-hash")!!.passwordHash)
    }

    @Test
    fun `new passwords need 8 characters and fit in BCrypt's 72 bytes`() {
        assertEquals("password_too_short", newPasswordProblem("1234567"))
        assertNull(newPasswordProblem("12345678"))
        assertNull(newPasswordProblem("a".repeat(72)))
        assertEquals("password_too_long", newPasswordProblem("a".repeat(73)))
        assertEquals("password_too_long", newPasswordProblem("é".repeat(37)))
    }
}
