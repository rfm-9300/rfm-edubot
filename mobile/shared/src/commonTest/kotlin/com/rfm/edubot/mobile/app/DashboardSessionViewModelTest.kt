package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
import com.rfm.edubot.mobile.core.common.InMemoryTokenStore
import com.rfm.edubot.mobile.core.common.SessionError
import com.rfm.edubot.mobile.core.data.SessionRepository
import com.rfm.edubot.mobile.core.data.SnapshotCache
import com.rfm.edubot.mobile.core.localization.AppLocale
import com.rfm.edubot.mobile.core.model.Company
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.model.Session
import com.rfm.edubot.mobile.core.model.SwitchedCompany
import com.rfm.edubot.mobile.core.model.Tenant
import com.rfm.edubot.mobile.core.network.ApiException
import com.rfm.edubot.mobile.core.network.SessionApi
import com.rfm.edubot.mobile.core.network.SessionTokens
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private open class StubSessionApi(
    private val tenant: Tenant = Tenant("t1", "acme", "Acme", "pt-PT", timezone = "Europe/Lisbon"),
    private val companies: List<Company> = emptyList(),
) : SessionApi {
    override suspend fun login(email: String, password: String): Session = Session("token")

    override suspend fun me(): DashboardIdentity = DashboardIdentity(tenant = tenant, companies = companies)

    override suspend fun switchCompany(companyId: String): SwitchedCompany = SwitchedCompany("switched")
}

class DashboardSessionViewModelTest {
    private suspend fun viewModel(
        api: SessionApi = StubSessionApi(),
        token: String? = null,
        deviceLocale: String? = null,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<DashboardSessionViewModel, SessionTokens> {
        val store = InMemoryTokenStore()
        token?.let { store.write(it) }
        val tokens = SessionTokens(store)
        val repository = SessionRepository(api, tokens, SnapshotCache(InMemorySnapshotStore()))
        return DashboardSessionViewModel(repository, deviceLocale, scope) to tokens
    }

    @Test
    fun `with no stored token it goes straight to sign-in`() = runTest {
        val (vm, _) = viewModel(scope = backgroundScope)
        vm.restore()
        runCurrent()
        assertEquals(SessionState.SignedOut(), vm.state.value)
    }

    @Test
    fun `a stored token restores the session without asking again`() = runTest {
        val (vm, _) = viewModel(token = "stored", scope = backgroundScope)
        vm.restore()
        runCurrent()
        assertTrue(vm.state.value is SessionState.SignedIn)
    }

    @Test
    fun `a dead connection on launch keeps the token for the next try`() = runTest {
        val api = object : StubSessionApi() {
            override suspend fun me(): DashboardIdentity = throw ApiException(AppError.Offline("no route"))
        }
        val (vm, tokens) = viewModel(api, token = "stored", scope = backgroundScope)

        vm.restore()
        runCurrent()

        assertEquals(SessionState.SignedOut(SessionError.CONNECTION_FAILED), vm.state.value)
        assertEquals("stored", tokens.current(), "a tunnel is not a reason to make somebody sign in again")
    }

    @Test
    fun `a rejected token on launch is thrown away`() = runTest {
        val api = object : StubSessionApi() {
            override suspend fun me(): DashboardIdentity = throw ApiException(AppError.Unauthorized)
        }
        val (vm, tokens) = viewModel(api, token = "stale", scope = backgroundScope)

        vm.restore()
        runCurrent()

        assertEquals(SessionState.SignedOut(SessionError.SESSION_EXPIRED), vm.state.value)
        assertNull(tokens.current())
    }

    @Test
    fun `blank credentials are caught before a request goes out`() = runTest {
        var called = false
        val api = object : StubSessionApi() {
            override suspend fun login(email: String, password: String): Session {
                called = true
                return Session("token")
            }
        }
        val (vm, _) = viewModel(api, scope = backgroundScope)

        vm.signIn("", "")
        runCurrent()

        assertEquals(SessionState.SignedOut(SessionError.MISSING_CREDENTIALS), vm.state.value)
        assertTrue(!called)
    }

    @Test
    fun `a 403 at sign-in reads as an inactive account, not as a wrong password`() = runTest {
        val api = object : StubSessionApi() {
            override suspend fun login(email: String, password: String): Session =
                throw ApiException(AppError.Forbidden)
        }
        val (vm, _) = viewModel(api, scope = backgroundScope)

        vm.signIn("a@b.test", "pw")
        runCurrent()

        assertEquals(SessionState.SignedOut(SessionError.ACCOUNT_INACTIVE), vm.state.value)
    }

    @Test
    fun `any screen's 401 signs the whole app out`() = runTest {
        val (vm, tokens) = viewModel(token = "stored", scope = backgroundScope)
        vm.restore()
        runCurrent()
        assertTrue(vm.state.value is SessionState.SignedIn)

        // What the HTTP layer does when a request comes back 401.
        tokens.invalidate()
        runCurrent()

        assertEquals(SessionState.SignedOut(SessionError.SESSION_EXPIRED), vm.state.value)
    }

    @Test
    fun `sign-in copy follows the device before any tenant is known`() = runTest {
        val (portuguese, _) = viewModel(deviceLocale = "pt-PT", scope = backgroundScope)
        assertEquals(AppLocale.Portuguese, portuguese.locale)

        val (spanish, _) = viewModel(deviceLocale = "es-ES", scope = backgroundScope)
        assertEquals(AppLocale.Spanish, spanish.locale)
    }

    @Test
    fun `copy follows the tenant once signed in`() = runTest {
        val (vm, _) = viewModel(deviceLocale = "en", token = "stored", scope = backgroundScope)
        vm.restore()
        runCurrent()
        assertEquals(AppLocale.Portuguese, vm.locale, "the tenant is set to pt-PT")
    }

    @Test
    fun `changing language in settings takes effect without a reload`() = runTest {
        val (vm, _) = viewModel(token = "stored", scope = backgroundScope)
        vm.restore()
        runCurrent()

        vm.applyLocale("es")

        assertEquals(AppLocale.Spanish, vm.locale)
    }

    @Test
    fun `dates are formatted in the tenant's timezone`() = runTest {
        val (vm, _) = viewModel(token = "stored", scope = backgroundScope)
        vm.restore()
        runCurrent()
        assertEquals("Europe/Lisbon", vm.clock.zone.id)
    }

    @Test
    fun `switching company adopts the new token and the new identity`() = runTest {
        val api = StubSessionApi(companies = listOf(Company("t1", "Acme", "acme", primary = true), Company("t2", "Other", "other")))
        val (vm, tokens) = viewModel(api, token = "stored", scope = backgroundScope)
        vm.restore()
        runCurrent()

        vm.switchCompany("t2")
        runCurrent()

        assertEquals("switched", tokens.current())
        assertTrue(vm.state.value is SessionState.SignedIn)
    }

    @Test
    fun `a failed switch stays on the company it was already on`() = runTest {
        val api = object : StubSessionApi() {
            override suspend fun switchCompany(companyId: String): SwitchedCompany =
                throw ApiException(AppError.Forbidden)
        }
        val (vm, tokens) = viewModel(api, token = "stored", scope = backgroundScope)
        vm.restore()
        runCurrent()

        vm.switchCompany("t2")
        runCurrent()

        assertEquals("stored", tokens.current())
        val signedIn = vm.state.value as SessionState.SignedIn
        assertTrue(!signedIn.switchingCompany, "the spinner must stop even when the switch fails")
    }

    @Test
    fun `signing out clears the token`() = runTest {
        val (vm, tokens) = viewModel(token = "stored", scope = backgroundScope)
        vm.restore()
        runCurrent()

        vm.signOut()
        runCurrent()

        assertNull(tokens.current())
        assertEquals(SessionState.SignedOut(), vm.state.value)
    }
}
