package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
import com.rfm.edubot.mobile.core.common.InMemoryTokenStore
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.DashboardIdentity
import com.rfm.edubot.mobile.core.model.Session
import com.rfm.edubot.mobile.core.model.SwitchedCompany
import com.rfm.edubot.mobile.core.model.Tenant
import com.rfm.edubot.mobile.core.network.ApiException
import com.rfm.edubot.mobile.core.network.SessionApi
import com.rfm.edubot.mobile.core.network.SessionTokens
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSessionApi(
    private val loginResult: () -> Session = { Session("new-token") },
    private val identity: () -> DashboardIdentity = {
        DashboardIdentity(tenant = Tenant("t1", "acme", "Acme", "en"))
    },
    private val switchResult: () -> SwitchedCompany = { SwitchedCompany("company-token") },
) : SessionApi {
    override suspend fun login(email: String, password: String): Session = loginResult()

    override suspend fun me(): DashboardIdentity = identity()

    override suspend fun switchCompany(companyId: String): SwitchedCompany = switchResult()
}

class SessionRepositoryTest {
    private val serializer = ListSerializer(String.serializer())

    private fun repository(
        api: SessionApi = FakeSessionApi(),
        tokenStore: InMemoryTokenStore = InMemoryTokenStore(),
        snapshots: InMemorySnapshotStore = InMemorySnapshotStore(),
    ) = Triple(
        SessionRepository(api, SessionTokens(tokenStore), SnapshotCache(snapshots)),
        tokenStore,
        snapshots,
    )

    @Test
    fun `signing in stores the token so no screen has to carry it`() = runTest {
        val (repository, tokens, _) = repository()
        val result = repository.signIn("a@b.test", "pw")
        assertTrue(result is Outcome.Success)
        assertEquals("new-token", tokens.read())
    }

    @Test
    fun `signing in drops the previous account's cached lists`() = runTest {
        val snapshots = InMemorySnapshotStore()
        SnapshotCache(snapshots).write("crm.clients", serializer, listOf("previous account"))
        val (repository, _, _) = repository(snapshots = snapshots)

        repository.signIn("a@b.test", "pw")

        assertNull(snapshots.read("crm.clients"), "one tenant's data must not greet the next")
    }

    @Test
    fun `a rejected sign-in leaves no token behind`() = runTest {
        val api = FakeSessionApi(loginResult = { throw ApiException(AppError.Unauthorized) })
        val (repository, tokens, _) = repository(api)

        val result = repository.signIn("a@b.test", "wrong")

        assertEquals(AppError.Unauthorized, (result as Outcome.Failure).error)
        assertNull(tokens.read())
    }

    @Test
    fun `a sign-in that cannot reach the backend reports offline rather than bad credentials`() = runTest {
        val api = FakeSessionApi(loginResult = { throw ApiException(AppError.Offline("no route")) })
        val (repository, _, _) = repository(api)

        val result = repository.signIn("a@b.test", "pw")

        assertTrue((result as Outcome.Failure).error is AppError.Offline)
    }

    @Test
    fun `switching company adopts the new token and clears the old company's lists`() = runTest {
        val snapshots = InMemorySnapshotStore()
        SnapshotCache(snapshots).write("crm.clients", serializer, listOf("other company"))
        val (repository, tokens, _) = repository(snapshots = snapshots)

        val result = repository.switchCompany("c2")

        assertTrue(result is Outcome.Success)
        assertEquals("company-token", tokens.read())
        assertNull(snapshots.read("crm.clients"))
    }

    @Test
    fun `a failed switch keeps the current session intact`() = runTest {
        val snapshots = InMemorySnapshotStore()
        SnapshotCache(snapshots).write("crm.clients", serializer, listOf("current company"))
        val tokenStore = InMemoryTokenStore()
        tokenStore.write("current-token")
        val api = FakeSessionApi(switchResult = { throw ApiException(AppError.Forbidden) })
        val (repository, _, _) = repository(api, tokenStore, snapshots)

        val result = repository.switchCompany("c2")

        assertEquals(AppError.Forbidden, (result as Outcome.Failure).error)
        assertEquals("current-token", tokenStore.read())
        assertEquals(listOf("current company"), SnapshotCache(snapshots).read("crm.clients", serializer))
    }

    @Test
    fun `signing out clears the token and everything cached under it`() = runTest {
        val snapshots = InMemorySnapshotStore()
        val tokenStore = InMemoryTokenStore()
        tokenStore.write("token")
        SnapshotCache(snapshots).write("overview", serializer, listOf("mine"))
        val (repository, _, _) = repository(tokenStore = tokenStore, snapshots = snapshots)

        repository.signOut()

        assertNull(tokenStore.read())
        assertNull(snapshots.read("overview"))
    }

    @Test
    fun `hasToken reflects storage so a relaunch can skip the sign-in screen`() = runTest {
        val (empty, _, _) = repository()
        assertTrue(!empty.hasToken())

        val stored = InMemoryTokenStore()
        stored.write("token")
        val (restored, _, _) = repository(tokenStore = stored)
        assertTrue(restored.hasToken())
    }
}
