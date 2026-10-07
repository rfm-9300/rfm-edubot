package com.rfm.edubot.timesheets

import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.rfm.edubot.crm.toDate
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.shared.jobs.PeriodicJob
import com.rfm.edubot.shared.jobs.SchedulerLease
import kotlinx.datetime.Instant
import org.bson.Document
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Drops punch coordinates after [retention]. The working-time record (times, breaks) is kept for years, but
 * where someone was only matters while the punch can still be questioned, so after that only the accuracy and
 * the site verdict (inside site X, N m away) stay.
 */
class TimesheetLocationRetention(
    mongo: MongoModule,
    private val retention: Duration = RETENTION,
    private val clock: () -> Instant = SystemClock::now,
) {
    private val collection = mongo.database.getCollection<Document>(ShiftRepository.COLLECTION)
    private val log = LoggerFactory.getLogger(TimesheetLocationRetention::class.java)

    suspend fun purge(): Long {
        val cutoff = (clock() - retention).toDate()
        val result = collection.updateMany(
            Filters.elemMatch("punches", Filters.and(Filters.lt("at", cutoff), Filters.ne("location.latitude", null))),
            Updates.combine(Updates.unset("punches.\$[old].location.latitude"), Updates.unset("punches.\$[old].location.longitude")),
            UpdateOptions().arrayFilters(listOf(Filters.and(Filters.lt("old.at", cutoff), Filters.ne("old.location.latitude", null)))),
        )
        if (result.modifiedCount > 0) log.info("Time clock: dropped punch coordinates older than {} from {} shifts", retention, result.modifiedCount)
        return result.modifiedCount
    }

    fun job(lease: SchedulerLease?): PeriodicJob = PeriodicJob("timesheet-location-retention", INTERVAL, lease) { purge() }

    companion object {
        val RETENTION: Duration = 90.days
        val INTERVAL: Duration = 6.hours
    }
}
