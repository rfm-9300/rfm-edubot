package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.CloseShift
import com.rfm.edubot.mobile.core.model.EnrollDevice
import com.rfm.edubot.mobile.core.model.PunchRequest
import com.rfm.edubot.mobile.core.model.PunchResult
import com.rfm.edubot.mobile.core.model.Shift
import com.rfm.edubot.mobile.core.model.TimeChallenge
import com.rfm.edubot.mobile.core.model.TimeClockStatus
import com.rfm.edubot.mobile.core.model.TimeDevice
import com.rfm.edubot.mobile.core.network.TimeClockApi
import kotlinx.serialization.builtins.ListSerializer

/**
 * An employee's own time clock. Punches go straight to the backend, whose clock is the record's, so
 * nothing here is queued while offline; the cached status only fills the screen until it refreshes.
 *
 * One per [employeeId]: a phone shared at a site signs different people in, and none may see another's
 * clock, not even for the moment a refresh takes.
 */
class TimeClockRepository(
    private val api: TimeClockApi,
    cache: SnapshotCache,
    val employeeId: String,
) {
    val status = CachedResource(
        key = "timeclock.$employeeId.status",
        serializer = TimeClockStatus.serializer(),
        cache = cache,
        fetch = { api.status() },
    )

    val shifts = CachedResource(
        key = "timeclock.$employeeId.shifts",
        serializer = ListSerializer(Shift.serializer()),
        cache = cache,
        fetch = { api.shifts() },
    )

    suspend fun challenge(): Outcome<TimeChallenge> = apiCall { api.challenge() }

    suspend fun punch(request: PunchRequest): Outcome<PunchResult> = applied { api.punch(request) }

    suspend fun closeForgotten(shiftId: String, end: String): Outcome<PunchResult> = applied { api.closeForgotten(shiftId, CloseShift(end)) }

    /** Enrolls this phone, then refreshes the status that lists the employee's phones. */
    suspend fun enroll(request: EnrollDevice): Outcome<TimeDevice> {
        val enrolled = apiCall { api.enroll(request) }
        if (enrolled is Outcome.Success) status.refresh()
        return enrolled
    }

    suspend fun removeDevice(keyId: String): Outcome<Unit> {
        val removed = apiCall { api.removeDevice(keyId) }
        if (removed is Outcome.Success) status.refresh()
        return removed
    }

    /** A punch answers with the new status and the shift it changed, so neither list is fetched again. */
    private suspend fun applied(call: suspend () -> PunchResult): Outcome<PunchResult> {
        val result = apiCall { call() }
        result.valueOrNull?.let { done ->
            status.put(done.status)
            val current = shifts.state.value.value
            if (current != null) shifts.put(listOf(done.shift) + current.filterNot { it.id == done.shift.id })
        }
        return result
    }
}
