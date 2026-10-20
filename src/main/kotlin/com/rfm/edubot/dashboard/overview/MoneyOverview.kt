package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.rfm.edubot.crm.paidPartExpression
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.OverviewCashDto
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewMonthFlowDto
import com.rfm.edubot.dashboard.OverviewNamedAmountDto
import com.rfm.edubot.dashboard.OverviewPaymentsDto
import com.rfm.edubot.dashboard.OverviewTopClientDto
import com.rfm.edubot.persistence.MongoModule
import java.util.Date
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.bson.Document
import org.bson.types.ObjectId

/** The Financeiro card: money invoices bring in, bills to pay, the months' cash flow and the clients billed most. */
internal class MoneyOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    suspend fun cash(tenantId: ObjectId, window: OverviewMath.Window): OverviewCashDto {
        val byStatus = sumByStatus("crm.invoices", tenantId)
        val collectedThisMonth = invoiceReceipts(tenantId, window.monthStart, window.nextMonthStart)
        val collectedLastMonth = invoiceReceipts(tenantId, window.lastMonthStart, window.monthStart)
        val issuedThisMonth = sumCreated("crm.invoices", tenantId, window.monthStart, window.nextMonthStart)
        val open = coll("crm.invoices").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("status", listOf("PENDING", "OVERDUE")),
            ),
        ).limit(300).toList()

        var overdueCents = 0L
        var overdueCount = 0
        var dueSoonCents = 0L
        var dueSoonCount = 0
        var agingCurrent = 0L
        var agingWeek = 0L
        var agingMonth = 0L
        var agingOld = 0L
        val overdueDocs = mutableListOf<Pair<Document, Long>>()
        for (doc in open) {
            val status = doc.getString("status") ?: "PENDING"
            var overdueHere = 0L
            var dueSoonHere = 0L
            // Each unpaid installment ages, falls overdue and comes due on its own date.
            for ((due, cents) in doc.openParts()) {
                when (OverviewMath.agingBucket(due ?: window.today, window.today)) {
                    OverviewMath.AgingBucket.CURRENT -> agingCurrent += cents
                    OverviewMath.AgingBucket.WEEK -> agingWeek += cents
                    OverviewMath.AgingBucket.MONTH -> agingMonth += cents
                    OverviewMath.AgingBucket.OLD -> agingOld += cents
                }
                if (OverviewMath.isEffectivelyOverdue(status, due, window.today)) {
                    overdueHere += cents
                } else if (status == "PENDING" && due != null && due >= window.today && due <= window.dueSoonEnd) {
                    dueSoonHere += cents
                }
            }
            if (overdueHere > 0) {
                overdueCents += overdueHere
                overdueCount += 1
                overdueDocs += doc to overdueHere
            } else if (dueSoonHere > 0) {
                dueSoonCents += dueSoonHere
                dueSoonCount += 1
            }
        }
        val outstanding = (byStatus["PENDING"]?.cents ?: 0L) + (byStatus["OVERDUE"]?.cents ?: 0L) - receivedOnOpenInvoices(tenantId)
        val top = overdueDocs.sortedByDescending { it.second }.take(3)
        val names = clientNames(tenantId, top.mapNotNull { it.first.getObjectIdOrNull("clientId") })
        return OverviewCashDto(
            collectedThisMonthCents = collectedThisMonth.sumOf { it.second },
            collectedLastMonthCents = collectedLastMonth.sumOf { it.second },
            issuedThisMonthCents = issuedThisMonth,
            outstandingCents = outstanding,
            overdueCents = overdueCents,
            overdueCount = overdueCount,
            dueSoonCents = dueSoonCents,
            dueSoonCount = dueSoonCount,
            invoiceCount = byStatus.values.sumOf { it.count },
            paidCountThisMonth = collectedThisMonth.size,
            agingCurrentCents = agingCurrent,
            agingWeekCents = agingWeek,
            agingMonthCents = agingMonth,
            agingOldCents = agingOld,
            topOverdue = top.map { (doc, cents) ->
                val clientId = doc.getObjectIdOrNull("clientId")
                OverviewNamedAmountDto(
                    id = doc.getObjectId("_id").toHexString(),
                    name = clientId?.let { names[it] }.orEmpty().ifBlank { doc.getString("number") ?: "" },
                    amountCents = cents,
                    number = doc.getString("number"),
                )
            },
        )
    }

    /** Money invoices brought in during [from, to): each received installment, or an invoice's total on the day it was paid in one go. */
    private suspend fun invoiceReceipts(tenantId: ObjectId, from: Instant, to: Instant): List<Pair<Instant, Long>> {
        val receivedPart = Filters.elemMatch(
            "installments",
            Filters.and(Filters.gte("paidAt", Date(from.toEpochMilliseconds())), Filters.lt("paidAt", Date(to.toEpochMilliseconds()))),
        )
        return coll("crm.invoices").find(Filters.or(paidBetween(tenantId, from, to), Filters.and(Filters.eq("tenantId", tenantId), receivedPart)))
            .projection(Projections.include("status", "totalCents", "paidAt", "updatedAt", "installments"))
            .toList()
            .flatMap { it.receipts() }
            .filter { (at, _) -> at >= from && at < to }
    }

    /** Installments already received on invoices still open, which `totalCents` sums still count as owed. */
    private suspend fun receivedOnOpenInvoices(tenantId: ObjectId): Long {
        val pipeline = listOf(
            Document(
                "\$match",
                Document("tenantId", tenantId)
                    .append("status", Document("\$in", listOf("PENDING", "OVERDUE")))
                    .append("installments.paidAt", Document("\$exists", true)),
            ),
            Document("\$unwind", "\$installments"),
            Document("\$match", Document("installments.paidAt", Document("\$exists", true))),
            Document("\$group", Document("_id", null).append("cents", Document("\$sum", "\$installments.amountCents"))),
        )
        return coll("crm.invoices").aggregate<Document>(pipeline).toList().firstOrNull()?.get("cents").asLong()
    }

    suspend fun payments(tenantId: ObjectId, window: OverviewMath.Window): OverviewPaymentsDto {
        val byStatus = sumByStatus("crm.payments", tenantId)
        val paidThisMonth = sumPaid("crm.payments", tenantId, window.monthStart, window.nextMonthStart)
        val open = coll("crm.payments").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("status", listOf("PENDING", "OVERDUE")),
            ),
        ).limit(300).toList()
        var overdueCents = 0L
        var overdueCount = 0
        var dueSoonCents = 0L
        var dueSoonCount = 0
        for (doc in open) {
            val cents = doc.get("totalCents").asLong()
            val due = doc.localDate("dueDate")
            val status = doc.getString("status") ?: "PENDING"
            if (OverviewMath.isEffectivelyOverdue(status, due, window.today)) {
                overdueCents += cents
                overdueCount += 1
            } else if (status == "PENDING" && due != null && due >= window.today && due <= window.dueSoonEnd) {
                dueSoonCents += cents
                dueSoonCount += 1
            }
        }
        val outstanding = (byStatus["PENDING"]?.cents ?: 0L) + (byStatus["OVERDUE"]?.cents ?: 0L)
        return OverviewPaymentsDto(
            paidThisMonthCents = paidThisMonth.first,
            outstandingCents = outstanding,
            overdueCents = overdueCents,
            overdueCount = overdueCount,
            dueSoonCents = dueSoonCents,
            dueSoonCount = dueSoonCount,
            paymentCount = byStatus.values.sumOf { it.count },
        )
    }

    suspend fun cashFlow(tenantId: ObjectId, window: OverviewMath.Window, modules: Set<String>): List<OverviewMonthFlowDto> = coroutineScope {
        val keys = OverviewMath.trendMonthKeys(window.today)
        val from = OverviewMath.monthsBackStart(window.today, window.zone, keys.size)
        val received = async {
            if (DashboardModules.INVOICES in modules) {
                invoiceReceipts(tenantId, from, window.nextMonthStart)
                    .groupBy({ (at, _) -> OverviewMath.monthKey(at, window.zone) }, { (_, cents) -> cents })
                    .mapValues { (_, cents) -> cents.sum() }
            } else {
                emptyMap()
            }
        }
        val spent = async {
            if (DashboardModules.PAYMENTS in modules) paidByMonth("crm.payments", tenantId, from, window.nextMonthStart, window.zone) else emptyMap()
        }
        OverviewMath.monthFlow(keys, received.await(), spent.await())
    }

    private suspend fun paidByMonth(collection: String, tenantId: ObjectId, from: Instant, to: Instant, zone: TimeZone): Map<String, Long> =
        coll(collection).find(paidBetween(tenantId, from, to))
            .projection(Projections.include("totalCents", "paidAt", "updatedAt"))
            .toList()
            .mapNotNull { doc ->
                val at = doc.getDate("paidAt") ?: doc.getDate("updatedAt") ?: return@mapNotNull null
                OverviewMath.monthKey(Instant.fromEpochMilliseconds(at.time), zone) to doc.get("totalCents").asLong()
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, cents) -> cents.sum() }

    suspend fun topClients(tenantId: ObjectId, window: OverviewMath.Window): List<OverviewTopClientDto> {
        val from = OverviewMath.monthsBackStart(window.today, window.zone, OverviewMath.TOP_CLIENT_MONTHS)
        val pipeline = listOf(
            Document(
                "\$match",
                Document("tenantId", tenantId)
                    .append("status", Document("\$ne", "CANCELLED"))
                    .append("createdAt", Document("\$gte", Date(from.toEpochMilliseconds()))),
            ),
            Document("\$addFields", Document("paidPart", paidPartExpression())),
            Document(
                "\$group",
                Document("_id", "\$clientId")
                    .append("billed", Document("\$sum", "\$totalCents"))
                    .append("paid", Document("\$sum", "\$paidPart"))
                    .append("count", Document("\$sum", 1)),
            ),
            Document("\$sort", Document("billed", -1).append("_id", 1)),
            Document("\$limit", OverviewMath.TOP_CLIENTS),
        )
        val rows = coll("crm.invoices").aggregate<Document>(pipeline).toList()
        val names = clientNames(tenantId, rows.mapNotNull { it.get("_id") as? ObjectId })
        return rows.mapNotNull { row ->
            val id = row.get("_id") as? ObjectId ?: return@mapNotNull null
            OverviewTopClientDto(
                id = id.toHexString(),
                name = names[id].orEmpty(),
                billedCents = row.get("billed").asLong(),
                paidCents = row.get("paid").asLong(),
                invoiceCount = row.get("count").asLong().toInt(),
            )
        }
    }
}
