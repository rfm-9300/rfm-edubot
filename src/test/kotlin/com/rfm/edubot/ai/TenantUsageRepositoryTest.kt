package com.rfm.edubot.ai

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TenantUsageRepositoryTest {

    @Test
    fun `currentPeriod is the current UTC calendar month as YYYY-MM`() {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val expected = "%04d-%02d".format(now.year, now.monthNumber)

        assertEquals(expected, TenantUsageRepository.currentPeriod())
    }
}
