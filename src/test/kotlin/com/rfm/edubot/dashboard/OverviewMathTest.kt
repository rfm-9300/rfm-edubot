package com.rfm.edubot.dashboard

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverviewMathTest {
    @Test
    fun `health is urgent when money is late`() {
        assertEquals(
            OverviewMath.HEALTH_URGENT,
            OverviewMath.health(overdueCount = 1, waiting = 0, pendingBookings = 0, unreplied = 0, dueSoonCount = 0, expiringQuotes = 0, pendingAssistant = 0),
        )
    }

    @Test
    fun `health is watch when people are waiting and ok when the shop is quiet`() {
        assertEquals(
            OverviewMath.HEALTH_WATCH,
            OverviewMath.health(0, waiting = 2, pendingBookings = 0, unreplied = 0, dueSoonCount = 0, expiringQuotes = 0, pendingAssistant = 0),
        )
        assertEquals(
            OverviewMath.HEALTH_OK,
            OverviewMath.health(0, 0, 0, 0, 0, 0, 0),
        )
    }

    @Test
    fun `win rate is accepted over the live pipeline`() {
        assertEquals(50, OverviewMath.winRatePct(accepted = 1, pending = 1, sent = 0))
        assertEquals(0, OverviewMath.winRatePct(0, 0, 0))
        assertEquals(100, OverviewMath.winRatePct(accepted = 2, pending = 0, sent = 0))
    }

    @Test
    fun `delta percent is null when there is no baseline`() {
        assertEquals(50, OverviewMath.deltaPct(150, 100))
        assertEquals(-50, OverviewMath.deltaPct(50, 100))
        assertEquals(0, OverviewMath.deltaPct(0, 0))
        assertNull(OverviewMath.deltaPct(80, 0))
    }

    @Test
    fun `pending invoices past due date count as overdue`() {
        val today = LocalDate(2026, 9, 21)
        assertTrue(OverviewMath.isEffectivelyOverdue("PENDING", LocalDate(2026, 9, 1), today))
        assertEquals(false, OverviewMath.isEffectivelyOverdue("PENDING", LocalDate(2026, 9, 30), today))
        assertTrue(OverviewMath.isEffectivelyOverdue("OVERDUE", LocalDate(2026, 10, 1), today))
        assertEquals(false, OverviewMath.isEffectivelyOverdue("PAID", LocalDate(2026, 9, 1), today))
    }

    @Test
    fun `aging buckets follow the due date`() {
        val today = LocalDate(2026, 9, 21)
        assertEquals(OverviewMath.AgingBucket.CURRENT, OverviewMath.agingBucket(LocalDate(2026, 9, 21), today))
        assertEquals(OverviewMath.AgingBucket.WEEK, OverviewMath.agingBucket(LocalDate(2026, 9, 18), today))
        assertEquals(OverviewMath.AgingBucket.MONTH, OverviewMath.agingBucket(LocalDate(2026, 9, 1), today))
        assertEquals(OverviewMath.AgingBucket.OLD, OverviewMath.agingBucket(LocalDate(2026, 7, 1), today))
    }

    @Test
    fun `CRM-only tenants are not nagged to connect WhatsApp`() {
        val setup = OverviewMath.setupItems(
            modules = setOf(DashboardModules.OVERVIEW, DashboardModules.CLIENTS, DashboardModules.INVOICES),
            hasWhatsApp = false,
            hasInstagram = false,
            hasWidget = false,
            personaEmpty = true,
        )
        assertEquals(emptyList(), setup)
    }

    @Test
    fun `inbox tenants are asked to finish channels and persona`() {
        val setup = OverviewMath.setupItems(
            modules = setOf(
                DashboardModules.OVERVIEW,
                DashboardModules.SETTINGS,
                DashboardModules.CONVERSATIONS,
                DashboardModules.INSTAGRAM,
                DashboardModules.PERSONA,
            ),
            hasWhatsApp = false,
            hasInstagram = false,
            hasWidget = false,
            personaEmpty = true,
        )
        assertEquals(listOf("wa", "ig", "widget", "persona"), setup.map { it.kind })
    }

    @Test
    fun `highlights prefer cash when invoices are on`() {
        val cash = OverviewCashDto(
            collectedThisMonthCents = 120_00,
            collectedLastMonthCents = 80_00,
            issuedThisMonthCents = 200_00,
            outstandingCents = 400_00,
            overdueCents = 160_00,
            overdueCount = 1,
            dueSoonCents = 0,
            dueSoonCount = 0,
            invoiceCount = 3,
            paidCountThisMonth = 1,
            agingCurrentCents = 240_00,
            agingWeekCents = 0,
            agingMonthCents = 160_00,
            agingOldCents = 0,
        )
        val highlights = OverviewMath.pickHighlights(
            modules = setOf(DashboardModules.OVERVIEW, DashboardModules.INVOICES, DashboardModules.QUOTES, DashboardModules.CONVERSATIONS),
            cash = cash,
            pipeline = OverviewPipelineDto(10_00, 1, 1, 1, 5_00, 1, 33, 0, 3),
            inbox = OverviewInboxDto(2, 10, 4, 20, 10, 8, 1, 0),
            calendar = null,
            customers = null,
            social = null,
        )
        assertEquals(
            listOf(
                OverviewMath.HIGHLIGHT_COLLECTED,
                OverviewMath.HIGHLIGHT_OVERDUE,
                OverviewMath.HIGHLIGHT_OUTSTANDING,
                OverviewMath.HIGHLIGHT_PIPELINE,
            ),
            highlights.map { it.key },
        )
        assertEquals(50, highlights.first { it.key == OverviewMath.HIGHLIGHT_COLLECTED }.deltaPct)
    }

    @Test
    fun `CRM-only highlights skip inbox stats`() {
        val highlights = OverviewMath.pickHighlights(
            modules = setOf(DashboardModules.OVERVIEW, DashboardModules.CLIENTS, DashboardModules.QUOTES),
            cash = null,
            pipeline = OverviewPipelineDto(90_00, 2, 1, 1, 40_00, 1, 25, 1, 4),
            inbox = OverviewInboxDto(9, 9, 9, 9, 9, 9, 9, 9),
            calendar = null,
            customers = OverviewCustomersDto(12, 3, 1),
            social = null,
        )
        assertEquals(setOf(OverviewMath.HIGHLIGHT_PIPELINE, OverviewMath.HIGHLIGHT_WIN_RATE, OverviewMath.HIGHLIGHT_CLIENTS), highlights.map { it.key }.toSet())
        assertTrue(highlights.none { it.module == DashboardModules.CONVERSATIONS })
    }

    @Test
    fun `window uses the tenant timezone for calendar day boundaries`() {
        val now = Instant.parse("2026-09-21T02:30:00Z")
        val lisbon = OverviewMath.window(now, "Europe/Lisbon")
        assertEquals(LocalDate(2026, 9, 21), lisbon.today)
        assertEquals(LocalDate(2026, 9, 21).atStartOfDayIn(TimeZone.of("Europe/Lisbon")), lisbon.todayStart)
        val saoPaulo = OverviewMath.window(now, "America/Sao_Paulo")
        assertEquals(LocalDate(2026, 9, 20), saoPaulo.today)
    }
}
