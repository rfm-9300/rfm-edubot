package com.rfm.edubot.ai

import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.testing.TestMongo
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals

class TenantUsageRepositoryTest {

    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("tenant_usage")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            mongo.shutdown()
        }
    }

    private var now = Instant.parse("2026-09-30T12:00:00Z")
    private val tenantId = ObjectId()
    private val usage = TenantUsageRepository(mongo, tenantId, clock = { now })

    @Test
    fun `currentPeriod is the current UTC calendar month as YYYY-MM`() {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val expected = "%04d-%02d".format(now.year, now.monthNumber)

        assertEquals(expected, TenantUsageRepository.currentPeriod())
    }

    @Test
    fun `periodOf uses the UTC month`() {
        assertEquals("2026-10", TenantUsageRepository.periodOf(Instant.parse("2026-10-01T00:30:00Z")))
        assertEquals("2026-09", TenantUsageRepository.periodOf(Instant.parse("2026-09-30T23:59:59Z")))
    }

    @Test
    fun `usage adds up in the total and per source, pipeline by default`(): Unit = runBlocking {
        usage.recordUsage(100)
        usage.recordUsage(40, UsageSources.ASSISTANT)
        usage.recordUsage(25, UsageSources.AGENTS)
        usage.recordUsage(5, UsageSources.AGENTS)
        usage.recordUsage(0, UsageSources.AGENTS)

        assertEquals(170, usage.tokensUsedThisMonth())
        assertEquals(
            mapOf(UsageSources.PIPELINE to 100L, UsageSources.ASSISTANT to 40L, UsageSources.AGENTS to 30L),
            usage.tokensBySourceThisMonth(),
        )
    }

    @Test
    fun `a new month starts from zero`(): Unit = runBlocking {
        val tenant = TenantUsageRepository(mongo, ObjectId(), clock = { now })
        tenant.recordUsage(60, UsageSources.AGENTS)

        now = Instant.parse("2026-10-01T00:00:01Z")

        assertEquals(0, tenant.tokensUsedThisMonth())
        assertEquals(emptyMap(), tenant.tokensBySourceThisMonth())
    }
}
