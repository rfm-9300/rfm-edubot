package com.rfm.edubot.shared.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Runs [tick] every [interval] in [scope] while this instance holds [lease] (when one is given).
 * A failing tick is logged and the loop carries on; cancelling the scope stops it.
 */
class PeriodicJob(
    private val name: String,
    private val interval: Duration,
    private val lease: SchedulerLease? = null,
    private val tick: suspend () -> Unit,
) {
    private val log = LoggerFactory.getLogger("PeriodicJob")

    fun start(scope: CoroutineScope, initialDelay: Duration = Duration.ZERO): Job = scope.launch {
        delay(initialDelay)
        while (isActive) {
            runOnce()
            delay(interval)
        }
    }

    /** One tick, if this instance may run it now. Returns whether it ran. */
    suspend fun runOnce(): Boolean {
        return try {
            if (lease != null && !lease.tryAcquire(name, interval * LEASE_INTERVALS)) return false
            tick()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Periodic job {} failed: {}", name, e.message, e)
            false
        }
    }

    private companion object {
        /** A lease outlives a few missed ticks, so a slow tick doesn't hand the job to another instance. */
        const val LEASE_INTERVALS = 4
    }
}
