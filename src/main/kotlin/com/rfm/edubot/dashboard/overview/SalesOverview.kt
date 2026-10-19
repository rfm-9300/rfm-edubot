package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewPipelineDto
import com.rfm.edubot.dashboard.OverviewServicesDto
import com.rfm.edubot.persistence.MongoModule
import java.util.Date
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId

/** The Pipeline and Services cards: open quotes and the work not invoiced yet. */
internal class SalesOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    suspend fun pipeline(tenantId: ObjectId, window: OverviewMath.Window): OverviewPipelineDto {
        val byStatus = sumByStatus("crm.quotes", tenantId)
        val pending = byStatus["PENDENTE"] ?: StatusSum()
        val sent = byStatus["SENT"] ?: StatusSum()
        val accepted = byStatus["ACEITO"] ?: StatusSum()
        val acceptedThisMonth = coll("crm.quotes").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.eq("status", "ACEITO"),
                Filters.gte("updatedAt", Date(window.monthStart.toEpochMilliseconds())),
                Filters.lt("updatedAt", Date(window.nextMonthStart.toEpochMilliseconds())),
            ),
        ).toList()
        val expiring = coll("crm.quotes").countDocuments(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("status", listOf("PENDENTE", "SENT")),
                Filters.gte("validUntil", window.today.toString()),
                Filters.lte("validUntil", window.dueSoonEnd.toString()),
            ),
        )
        return OverviewPipelineDto(
            openCents = pending.cents + sent.cents,
            pendingCount = pending.count,
            sentCount = sent.count,
            acceptedCount = accepted.count,
            acceptedThisMonthCents = acceptedThisMonth.sumOf { it.get("totalCents").asLong() },
            acceptedThisMonthCount = acceptedThisMonth.size,
            winRatePct = OverviewMath.winRatePct(accepted.count, pending.count, sent.count),
            expiringSoonCount = expiring.toInt(),
            quoteCount = byStatus.values.sumOf { it.count },
        )
    }

    suspend fun services(tenantId: ObjectId, window: OverviewMath.Window): OverviewServicesDto {
        val tenantFilter = Filters.eq("tenantId", tenantId)
        val open = coll("crm.client_services").find(Filters.and(tenantFilter, Filters.eq("status", "OPEN"))).limit(500).toList()
        val invoiced = coll("crm.client_services").find(
            Filters.and(
                tenantFilter,
                Filters.eq("status", "INVOICED"),
                Filters.gte("updatedAt", Date(window.monthStart.toEpochMilliseconds())),
                Filters.lt("updatedAt", Date(window.nextMonthStart.toEpochMilliseconds())),
            ),
        ).limit(500).toList()
        return OverviewServicesDto(
            openCount = open.size,
            openCents = open.sumOf { it.get("totalCents").asLong() },
            invoicedThisMonthCount = invoiced.size,
            invoicedThisMonthCents = invoiced.sumOf { it.get("totalCents").asLong() },
        )
    }
}
