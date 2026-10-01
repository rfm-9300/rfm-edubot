package com.rfm.edubot.shared.jobs

import com.mongodb.ErrorCategory
import com.mongodb.MongoException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import kotlinx.datetime.Instant
import org.bson.Document
import java.net.InetAddress
import java.util.Date
import java.util.UUID
import kotlin.time.Duration

/**
 * A named lease in Mongo (`scheduler_leases`): only its holder runs the job, until the lease expires.
 * Production runs one app instance; the lease keeps a second replica from firing the same schedule twice.
 */
class SchedulerLease(
    mongo: MongoModule,
    val owner: String = defaultOwner(),
    private val clock: () -> Instant = SystemClock::now,
) {
    private val collection = mongo.database.getCollection<Document>(COLLECTION)

    /** Takes or renews [name] for [ttl]; false while another owner holds it. */
    suspend fun tryAcquire(name: String, ttl: Duration): Boolean {
        val now = clock()
        return try {
            val doc = collection.findOneAndUpdate(
                Filters.and(
                    Filters.eq("_id", name),
                    Filters.or(Filters.lt("expiresAt", now.toDate()), Filters.eq("owner", owner)),
                ),
                Updates.combine(
                    Updates.set("owner", owner),
                    Updates.set("expiresAt", (now + ttl).toDate()),
                    Updates.set("renewedAt", now.toDate()),
                ),
                FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
            )
            doc?.getString("owner") == owner
        } catch (e: MongoException) {
            // The upsert collided with a lease another owner still holds.
            if (ErrorCategory.fromErrorCode(e.code) == ErrorCategory.DUPLICATE_KEY) false else throw e
        }
    }

    suspend fun release(name: String) {
        collection.deleteOne(Filters.and(Filters.eq("_id", name), Filters.eq("owner", owner)))
    }

    companion object {
        const val COLLECTION = "scheduler_leases"

        fun defaultOwner(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("app")
            return "$host-${UUID.randomUUID().toString().take(8)}"
        }
    }
}

private fun Instant.toDate(): Date = Date(toEpochMilliseconds())
