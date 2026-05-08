package com.rfm.edubot.ratelimit

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days
import java.util.concurrent.ConcurrentHashMap

sealed class RateDecision {
    object Accept : RateDecision()
    data class Reject(val message: String) : RateDecision()
}

data class UserBuckets(
    var hourCount: Int = 0,
    var dayCount: Int = 0,
    var hourWindowStart: Instant = Clock.System.now(),
    var dayWindowStart: Instant = Clock.System.now(),
)

class RateLimiter(
    private val perHour: Int = 30,
    private val perDay: Int = 200,
) {
    private val buckets = ConcurrentHashMap<String, UserBuckets>()

    fun tryAcquire(waId: String): RateDecision {
        val now = Clock.System.now()
        val userBuckets = buckets.computeIfAbsent(waId) { UserBuckets() }

        if (now > userBuckets.hourWindowStart + 1.hours) {
            userBuckets.hourCount = 0
            userBuckets.hourWindowStart = now
        }

        if (now > userBuckets.dayWindowStart + 1.days) {
            userBuckets.dayCount = 0
            userBuckets.dayWindowStart = now
        }

        userBuckets.hourCount++
        userBuckets.dayCount++

        return if (userBuckets.hourCount > perHour) {
            RateDecision.Reject("Hourly rate limit exceeded. Try again later.")
        } else if (userBuckets.dayCount > perDay) {
            RateDecision.Reject("Daily rate limit exceeded. Try again tomorrow.")
        } else {
            RateDecision.Accept
        }
    }

    fun reset(waId: String) {
        buckets.remove(waId)
    }
}
