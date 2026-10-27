package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewPipelineDto
import com.rfm.edubot.dashboard.OverviewServiceEmployeeDto
import com.rfm.edubot.dashboard.OverviewServiceMonthDto
import com.rfm.edubot.dashboard.OverviewServiceStatsDto
import com.rfm.edubot.dashboard.OverviewServiceTopDto
import com.rfm.edubot.dashboard.OverviewServicesDto
import com.rfm.edubot.persistence.MongoModule
import java.util.Date
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.Document
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

    /** Home's Services card: what was done this week, month and year, the trend, the lines done most and by whom. */
    suspend fun serviceStats(tenantId: ObjectId, window: OverviewMath.Window, employees: Boolean): OverviewServiceStatsDto {
        val zone = window.zone
        val start = OverviewMath.monthsBackStart(window.today, zone, OverviewMath.TOP_CLIENT_MONTHS)
        val startDay = start.toLocalDateTime(zone).date
        val rows = coll("crm.client_services").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.ne("status", "CANCELLED"),
                Filters.or(
                    Filters.gte("performedAt", startDay.toString()),
                    Filters.and(Filters.`in`("performedAt", null, ""), Filters.gte("createdAt", Date(start.toEpochMilliseconds()))),
                ),
            ),
        ).projection(Projections.include("status", "performedAt", "createdAt", "totalCents", "name", "items", "employeeId"))
            .limit(SERVICE_STATS_LIMIT)
            .toList()
            .map { ServiceRow.of(it, zone) }
            .filter { it.day >= startDay }
        val todo = rows.filter { it.open && it.day >= window.today }
        val done = rows.filter { !(it.open && it.day >= window.today) }
        fun between(from: Instant, to: Instant) = done.filter { it.day >= from.toLocalDateTime(zone).date && it.day < to.toLocalDateTime(zone).date }
        val week = between(window.weekStart, window.nextWeekStart)
        val month = between(window.monthStart, window.nextMonthStart)
        val lastMonth = between(window.lastMonthStart, window.monthStart)
        val year = done.filter { it.day.year == window.today.year }
        val byMonth = done.groupBy { OverviewMath.monthKey(it.day) }
        val months = OverviewMath.trendMonthKeys(window.today).map { key ->
            val list = byMonth[key].orEmpty()
            OverviewServiceMonthDto(key, list.size, list.sumOf { it.cents })
        }
        val top = done.flatMap { it.lines() }
            .groupBy { it.first.lowercase() }
            .map { (_, lines) -> OverviewServiceTopDto(lines.first().first, lines.size, lines.sumOf { it.second }) }
            .sortedWith(compareByDescending<OverviewServiceTopDto> { it.count }.thenByDescending { it.cents })
            .take(OverviewMath.TOP_SERVICES)
        val people = if (employees) {
            val byEmployee = month.filter { it.employeeId != null }.groupBy { it.employeeId!! }
            val names = employeeNames(tenantId, byEmployee.keys)
            byEmployee.map { (id, list) -> OverviewServiceEmployeeDto(id.toHexString(), names[id] ?: "", list.size, list.sumOf { it.cents }) }
                .sortedWith(compareByDescending<OverviewServiceEmployeeDto> { it.count }.thenByDescending { it.cents })
                .take(OverviewMath.TOP_SERVICES)
        } else {
            emptyList()
        }
        return OverviewServiceStatsDto(
            doneThisWeek = week.size,
            doneThisMonth = month.size,
            doneThisMonthCents = month.sumOf { it.cents },
            doneLastMonth = lastMonth.size,
            doneLastMonthCents = lastMonth.sumOf { it.cents },
            doneThisYear = year.size,
            doneThisYearCents = year.sumOf { it.cents },
            toDo = todo.size,
            toDoToday = todo.count { it.day == window.today },
            months = months,
            top = top,
            employees = people,
        )
    }

    private companion object {
        const val SERVICE_STATS_LIMIT = 20_000
    }
}

/** One Serviços row read for Home's service cards. Its [day] is `performedAt`, or the tenant-local day it was created. */
internal class ServiceRow(private val doc: Document, val day: LocalDate) {
    val open = doc.getString("status") == "OPEN"
    val cents = doc.get("totalCents").asLong()
    val employeeId = doc.getObjectIdOrNull("employeeId")
    val clientId = doc.getObjectIdOrNull("clientId")

    /** Each line as (title, cents); a catalog line reads "title - details", so only the title counts. A row without lines is one line. */
    fun lines(): List<Pair<String, Long>> {
        val items = doc.getList("items", Document::class.java).orEmpty()
            .map { (it.getString("description") ?: "").substringBefore(" - ").trim() to it.get("totalCents").asLong() }
            .filter { it.first.isNotEmpty() }
        if (items.isNotEmpty()) return items
        val name = doc.getString("name")?.trim().orEmpty()
        return if (name.isEmpty()) emptyList() else listOf(name to cents)
    }

    companion object {
        fun of(doc: Document, zone: TimeZone) =
            ServiceRow(doc, doc.localDate("performedAt") ?: Instant.fromEpochMilliseconds((doc.getDate("createdAt") ?: Date(0)).time).toLocalDateTime(zone).date)
    }
}
