package com.rfm.edubot.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverviewHomeLayoutTest {
    @Test
    fun `unknown hidden cards are dropped`() {
        assertEquals(
            listOf(OverviewHomeLayout.CASH, OverviewHomeLayout.PULSE),
            OverviewHomeLayout.sanitize(listOf("cash", "nope", "pulse", "cash")),
        )
        assertEquals(emptyList(), OverviewHomeLayout.sanitize(null))
    }

    @Test
    fun `available snapshots follow enabled modules`() {
        val crmOnly = OverviewHomeLayout.available(
            setOf(DashboardModules.OVERVIEW, DashboardModules.INVOICES, DashboardModules.CLIENTS),
        )
        assertEquals(
            listOf("highlights", "pulse", "attention", "setup", "cash", "customers"),
            crmOnly.map { it.id },
        )
        assertTrue(crmOnly.filter { it.group == OverviewHomeLayout.GROUP_SNAPSHOTS }.none { it.id == OverviewHomeLayout.INBOX })
    }

    @Test
    fun `hiding money drops the cash snapshot and money highlights`() {
        val applied = OverviewHomeLayout.apply(
            overview(
                cash = cashDto(),
                pipeline = pipelineDto(),
                highlights = listOf(
                    OverviewHighlightDto(OverviewMath.HIGHLIGHT_COLLECTED, DashboardModules.INVOICES, cents = 100),
                    OverviewHighlightDto(OverviewMath.HIGHLIGHT_PIPELINE, DashboardModules.QUOTES, cents = 50),
                ),
            ),
            listOf(OverviewHomeLayout.CASH),
        )
        assertNull(applied.cash)
        assertEquals(pipelineDto().openCents, applied.pipeline?.openCents)
        assertEquals(listOf(OverviewMath.HIGHLIGHT_PIPELINE), applied.highlights.map { it.key })
        assertEquals(listOf(OverviewHomeLayout.CASH), applied.hiddenCards)
    }

    @Test
    fun `hiding highlights keeps snapshots`() {
        val applied = OverviewHomeLayout.apply(
            overview(cash = cashDto(), highlights = listOf(OverviewHighlightDto(OverviewMath.HIGHLIGHT_OUTSTANDING, DashboardModules.INVOICES, cents = 1))),
            listOf(OverviewHomeLayout.HIGHLIGHTS),
        )
        assertEquals(emptyList(), applied.highlights)
        assertEquals(cashDto().outstandingCents, applied.cash?.outstandingCents)
    }

    private fun overview(
        cash: OverviewCashDto? = null,
        pipeline: OverviewPipelineDto? = null,
        highlights: List<OverviewHighlightDto> = emptyList(),
    ) = OverviewDto(
        users = 0,
        conversations = 0,
        messages = 0,
        messagesToday = 0,
        quotes = 0,
        invoices = 0,
        highlights = highlights,
        cash = cash,
        pipeline = pipeline,
    )

    private fun cashDto() = OverviewCashDto(
        collectedThisMonthCents = 10,
        collectedLastMonthCents = 5,
        issuedThisMonthCents = 8,
        outstandingCents = 20,
        overdueCents = 0,
        overdueCount = 0,
        dueSoonCents = 0,
        dueSoonCount = 0,
        invoiceCount = 1,
        paidCountThisMonth = 1,
        agingCurrentCents = 20,
        agingWeekCents = 0,
        agingMonthCents = 0,
        agingOldCents = 0,
    )

    private fun pipelineDto() = OverviewPipelineDto(
        openCents = 40,
        pendingCount = 1,
        sentCount = 1,
        acceptedCount = 0,
        acceptedThisMonthCents = 0,
        acceptedThisMonthCount = 0,
        winRatePct = 0,
        expiringSoonCount = 0,
        quoteCount = 2,
    )
}
