package com.rfm.edubot.integrations.email

import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.shared.jobs.PeriodicJob
import com.rfm.edubot.shared.jobs.SchedulerLease
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Keeps an email's text for [retention] after its date, the period `/privacy` states (Google's Limited
 * Use: no more Gmail data than the feature needs). Disconnecting an account deletes its mail outright.
 */
class EmailRetention(
    private val messages: EmailMessageRepository,
    private val retention: Duration = RETENTION,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val log = LoggerFactory.getLogger(EmailRetention::class.java)

    suspend fun purge(): Long {
        val purged = messages.purgeBodies(clock() - retention)
        if (purged > 0) log.info("Email retention: dropped the text of {} emails older than {}", purged, retention)
        return purged
    }

    fun job(lease: SchedulerLease?): PeriodicJob = PeriodicJob("email-retention", INTERVAL, lease) { purge() }

    companion object {
        val RETENTION: Duration = 90.days
        val INTERVAL: Duration = 6.hours
    }
}
