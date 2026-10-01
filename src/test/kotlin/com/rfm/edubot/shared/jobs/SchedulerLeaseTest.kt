package com.rfm.edubot.shared.jobs

import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SchedulerLeaseTest {

    companion object {
        private lateinit var mongoModule: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongoModule = TestMongo.module("leases")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongoModule.shutdown()
        }
    }

    @Test
    fun `one owner holds a lease until it expires or is released`() = runBlocking {
        var now: Instant = Clock.System.now()
        val first = SchedulerLease(mongoModule, owner = "first") { now }
        val second = SchedulerLease(mongoModule, owner = "second") { now }

        assertTrue(first.tryAcquire("agents-scheduler", 1.minutes))
        assertTrue(first.tryAcquire("agents-scheduler", 1.minutes), "the holder renews its own lease")
        assertFalse(second.tryAcquire("agents-scheduler", 1.minutes))

        now += 2.minutes
        assertTrue(second.tryAcquire("agents-scheduler", 30.seconds), "an expired lease can be taken over")
        assertFalse(first.tryAcquire("agents-scheduler", 1.minutes))

        second.release("agents-scheduler")
        assertTrue(first.tryAcquire("agents-scheduler", 1.minutes))
    }
}
