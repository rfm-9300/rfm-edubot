package com.rfm.edubot.shared.jobs

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PeriodicJobTest {
    @Test
    fun `a tick runs only while this instance holds the lease`() = runBlocking {
        val lease = mockk<SchedulerLease>()
        coEvery { lease.tryAcquire("agents", any()) } returnsMany listOf(true, false)
        var ticks = 0
        val job = PeriodicJob("agents", 30.seconds, lease) { ticks += 1 }

        assertTrue(job.runOnce())
        assertFalse(job.runOnce())
        assertEquals(1, ticks)
    }

    @Test
    fun `a failing tick is contained so the next one still runs`() = runBlocking {
        var ticks = 0
        val job = PeriodicJob("flaky", 30.seconds) {
            ticks += 1
            if (ticks == 1) error("boom")
        }

        assertFalse(job.runOnce())
        assertTrue(job.runOnce())
        assertEquals(2, ticks)
    }
}
