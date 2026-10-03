package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.network.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachedResourceTest {
    private val serializer = ListSerializer(String.serializer())

    private fun resource(
        store: InMemorySnapshotStore = InMemorySnapshotStore(),
        fetch: suspend () -> List<String>,
    ) = CachedResource("key", serializer, SnapshotCache(store), fetch)

    @Test
    fun `a first load reports the value and that it is not from cache`() = runTest {
        val resource = resource { listOf("a", "b") }
        resource.load()
        val state = resource.state.value
        assertEquals(listOf("a", "b"), state.value)
        assertFalse(state.fromCache)
        assertFalse(state.loading)
        assertNull(state.error)
    }

    @Test
    fun `a second load is skipped while the value is fresh`() = runTest {
        var calls = 0
        val resource = resource { calls++; listOf("a") }
        resource.load()
        resource.load()
        assertEquals(1, calls, "a screen reopening should not refetch what it already has")
    }

    @Test
    fun `forcing a load refetches`() = runTest {
        var calls = 0
        val resource = resource { calls++; listOf("a") }
        resource.load()
        resource.load(force = true)
        assertEquals(2, calls)
    }

    @Test
    fun `a cold start shows the stored copy while the network is still in flight`() = runTest {
        val store = InMemorySnapshotStore()
        resource(store) { listOf("stored") }.load()

        // A new resource over the same store is what a fresh app launch sees.
        val inFlight = CompletableDeferred<List<String>>()
        val next = resource(store) { inFlight.await() }
        backgroundScope.launch { next.load() }
        runCurrent()

        assertEquals(listOf("stored"), next.state.value.value, "content, not a spinner")
        assertTrue(next.state.value.fromCache)
        assertTrue(next.state.value.loading)

        inFlight.complete(listOf("fresh"))
        runCurrent()
        assertEquals(listOf("fresh"), next.state.value.value)
        assertFalse(next.state.value.fromCache, "once confirmed it is no longer a snapshot")
    }

    @Test
    fun `a failed refresh keeps the cached value and reports the reason beside it`() = runTest {
        val store = InMemorySnapshotStore()
        resource(store) { listOf("stored") }.load()

        val next = resource(store) { throw ApiException(AppError.Offline("no route")) }
        val outcome = next.load()

        assertTrue(outcome is Outcome.Failure)
        assertEquals(listOf("stored"), next.state.value.value, "a dead connection must not blank the screen")
        assertTrue(next.state.value.error is AppError.Offline)
        assertTrue(next.state.value.fromCache)
    }

    @Test
    fun `a failure with nothing cached leaves an empty state, not a stale one`() = runTest {
        val resource = resource { throw ApiException(AppError.Forbidden) }
        resource.load()
        val state = resource.state.value
        assertNull(state.value)
        assertEquals(AppError.Forbidden, state.error)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `mutating a list writes through so a relaunch sees the change`() = runTest {
        val store = InMemorySnapshotStore()
        val resource = resource(store) { listOf("a") }
        resource.load()
        resource.mutate { it + "b" }

        assertEquals(listOf("a", "b"), resource.state.value.value)
        assertEquals(listOf("a", "b"), SnapshotCache(store).read("key", serializer))
    }

    @Test
    fun `mutating before anything is loaded is a no-op rather than inventing a value`() = runTest {
        val resource = resource { listOf("a") }
        resource.mutate { it + "b" }
        assertNull(resource.state.value.value)
    }

    @Test
    fun `a snapshot written by an older model version is dropped, not raised`() = runTest {
        val store = InMemorySnapshotStore()
        store.write("key", """{"shape":"no longer a list"}""")

        val resource = resource(store) { listOf("fresh") }
        resource.load()

        assertEquals(listOf("fresh"), resource.state.value.value)
    }

    @Test
    fun `an unreadable snapshot is cleared so it is not re-parsed on every launch`() = runTest {
        val store = InMemorySnapshotStore()
        store.write("key", "{}")
        assertNull(SnapshotCache(store).read("key", serializer))
        assertNull(store.read("key"))
    }

    @Test
    fun `clearing the cache stops the next account seeing the previous one's data`() = runTest {
        val store = InMemorySnapshotStore()
        resource(store) { listOf("first account") }.load()
        SnapshotCache(store).clear()
        assertNull(store.read("key"))
    }
}
