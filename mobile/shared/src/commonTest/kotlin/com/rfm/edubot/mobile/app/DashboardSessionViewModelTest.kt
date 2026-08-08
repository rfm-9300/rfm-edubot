package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.core.common.InMemoryTokenStore
import com.rfm.edubot.mobile.core.common.SessionError
import com.rfm.edubot.mobile.core.testing.FakeDashboardApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DashboardSessionViewModelTest {
    private fun viewModel(api: FakeDashboardApi = FakeDashboardApi(), store: InMemoryTokenStore) =
        DashboardSessionViewModel(api, store, scopeOverride = CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun `restores the stored session and loads overview`() = runBlocking {
        val store = InMemoryTokenStore().also { it.write("stored-token") }
        val vm = viewModel(store = store)

        vm.restore()
        vm.state.awaitFirst { (it as? DashboardSessionState.SignedIn)?.overview != null }

        val state = assertIs<DashboardSessionState.SignedIn>(vm.state.value)
        assertEquals("Acme", state.identity.tenant.name)
        assertEquals(9, state.overview?.messages)
    }

    @Test
    fun `rejects blank login credentials without calling api`() = runBlocking {
        val vm = viewModel(store = InMemoryTokenStore())

        vm.login("", "")
        vm.state.awaitFirst { it is DashboardSessionState.SignedOut }

        val state = assertIs<DashboardSessionState.SignedOut>(vm.state.value)
        assertEquals(SessionError.MISSING_CREDENTIALS, state.error)
    }

    @Test
    fun `sign out clears stored token`() = runBlocking {
        val store = InMemoryTokenStore().also { it.write("stored-token") }
        val vm = viewModel(store = store)

        vm.signOut()
        vm.state.awaitFirst { it is DashboardSessionState.SignedOut }

        assertEquals(null, store.read())
    }

    @Test
    fun `applies locale to the active session immediately`() = runBlocking {
        val vm = viewModel(store = InMemoryTokenStore().also { it.write("stored-token") })
        vm.restore()
        vm.state.awaitFirst { (it as? DashboardSessionState.SignedIn)?.overview != null }

        vm.applyLocale("pt-PT")

        val state = assertIs<DashboardSessionState.SignedIn>(vm.state.value)
        assertEquals("pt-PT", state.identity.tenant.locale)
    }

    private suspend fun <T> kotlinx.coroutines.flow.StateFlow<T>.awaitFirst(predicate: (T) -> Boolean) {
        withTimeout(10_000) { first(predicate) }
    }
}
