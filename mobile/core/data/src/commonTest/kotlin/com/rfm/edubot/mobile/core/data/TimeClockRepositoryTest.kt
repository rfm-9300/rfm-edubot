package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.InMemorySnapshotStore
import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.CloseShift
import com.rfm.edubot.mobile.core.model.EnrollDevice
import com.rfm.edubot.mobile.core.model.PunchRequest
import com.rfm.edubot.mobile.core.model.PunchResult
import com.rfm.edubot.mobile.core.model.Shift
import com.rfm.edubot.mobile.core.model.TimeChallenge
import com.rfm.edubot.mobile.core.model.TimeClockStatus
import com.rfm.edubot.mobile.core.model.TimeDevice
import com.rfm.edubot.mobile.core.model.TimesheetPolicy
import com.rfm.edubot.mobile.core.network.TimeClockApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeClockRepositoryTest {
    private class FakeApi : TimeClockApi {
        var statusCalls = 0
        var shiftCalls = 0
        val punches = mutableListOf<PunchRequest>()
        var devices = emptyList<TimeDevice>()

        override suspend fun status(): TimeClockStatus {
            statusCalls += 1
            return TimeClockStatus(policy = TimesheetPolicy(), state = "OFF", serverTime = "2026-10-07T08:00:00Z", devices = devices)
        }

        override suspend fun shifts(): List<Shift> {
            shiftCalls += 1
            return listOf(Shift(id = "old", employeeId = "e1", status = "CLOSED", day = "2026-10-06", startAt = "2026-10-06T07:00:00Z", startTime = "08:00"))
        }

        override suspend fun challenge() = TimeChallenge("nonce", "2026-10-07T08:02:00Z")

        override suspend fun punch(request: PunchRequest): PunchResult {
            punches += request
            return PunchResult(
                TimeClockStatus(policy = TimesheetPolicy(), state = "WORKING", serverTime = "2026-10-07T08:00:00Z"),
                Shift(id = "new", employeeId = "e1", status = "OPEN", day = "2026-10-07", startAt = "2026-10-07T07:00:00Z", startTime = "08:00"),
            )
        }

        override suspend fun closeForgotten(shiftId: String, request: CloseShift): PunchResult = error("unused")

        override suspend fun enroll(request: EnrollDevice): TimeDevice {
            devices = listOf(TimeDevice("d1", "key-1", request.name, request.platform))
            return devices.single()
        }

        override suspend fun removeDevice(keyId: String) = Unit
    }

    @Test
    fun `a punch updates the status and the shift list without fetching them again`() = runTest {
        val api = FakeApi()
        val repository = TimeClockRepository(api, SnapshotCache(InMemorySnapshotStore()), "e1")
        repository.status.load()
        repository.shifts.load()
        val result = repository.punch(PunchRequest(type = PunchRequest.IN))
        assertEquals("WORKING", (result as Outcome.Success).value.status.state)
        assertEquals("WORKING", repository.status.state.value.value?.state)
        assertEquals(listOf("new", "old"), repository.shifts.state.value.value?.map { it.id })
        assertEquals(1, api.statusCalls)
        assertEquals(1, api.shiftCalls)
        assertEquals("APP", api.punches.single().channel)
    }

    @Test
    fun `enrolling a phone refreshes the phones the status lists`() = runTest {
        val api = FakeApi()
        val repository = TimeClockRepository(api, SnapshotCache(InMemorySnapshotStore()), "e1")
        repository.status.load()
        repository.enroll(EnrollDevice("Pixel 8", "ANDROID", "pk", "nonce", "sig"))
        assertEquals(listOf("key-1"), repository.status.state.value.value?.devices?.map { it.keyId })
    }

    @Test
    fun `a second employee on the same phone never sees the first one's snapshot`() = runTest {
        val store = InMemorySnapshotStore()
        TimeClockRepository(FakeApi(), SnapshotCache(store), "e1").status.load()

        val inFlight = CompletableDeferred<TimeClockStatus>()
        val slowApi = object : TimeClockApi by FakeApi() {
            override suspend fun status(): TimeClockStatus = inFlight.await()
        }
        val next = TimeClockRepository(slowApi, SnapshotCache(store), "e2")
        backgroundScope.launch { next.status.load() }
        runCurrent()

        assertNull(next.status.state.value.value, "a spinner while it loads, not someone else's shift")
        assertTrue(next.status.state.value.loading)
    }
}
