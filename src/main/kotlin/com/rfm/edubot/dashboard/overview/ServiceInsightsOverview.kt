package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewServiceEmployeeDto
import com.rfm.edubot.dashboard.OverviewServiceMonthDto
import com.rfm.edubot.dashboard.ServiceDueBackDto
import com.rfm.edubot.dashboard.ServiceInsightClientDto
import com.rfm.edubot.dashboard.ServiceInsightItemDto
import com.rfm.edubot.dashboard.ServiceInsightsDto
import com.rfm.edubot.dashboard.ServiceMatrixCellDto
import com.rfm.edubot.dashboard.ServiceMatrixDto
import com.rfm.edubot.dashboard.ServiceMatrixRowDto
import com.rfm.edubot.dashboard.ServicePeriodDto
import com.rfm.edubot.persistence.MongoModule
import java.util.Date
import kotlin.math.ceil
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.bson.types.ObjectId

/**
 * Home's Service insights card: one period's work against the period before, by service, client, weekday and
 * employee, who buys what, and the regular clients who are later than usual. Done and to do follow
 * [SalesOverview.serviceStats]. One read takes the last two years and the rest is counted in memory.
 */
internal class ServiceInsightsOverview(mongo: MongoModule) : OverviewQueries(mongo) {

    suspend fun insights(tenantId: ObjectId, window: OverviewMath.Window, months: Int, employees: Boolean): ServiceInsightsDto {
        val zone = window.zone
        val today = window.today
        val readFrom = today.minus(DatePeriod(months = HISTORY_MONTHS))
        val docs = coll("crm.client_services").find(
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.ne("status", "CANCELLED"),
                Filters.or(
                    Filters.gte("performedAt", readFrom.toString()),
                    Filters.and(Filters.`in`("performedAt", null, ""), Filters.gte("createdAt", Date(readFrom.atStartOfDayIn(zone).toEpochMilliseconds()))),
                ),
            ),
        ).projection(Projections.include("status", "performedAt", "createdAt", "totalCents", "name", "items", "employeeId", "clientId"))
            .sort(Sorts.descending("createdAt"))
            .limit(ROW_LIMIT)
            .toList()
        val rows = docs.map { ServiceRow.of(it, zone) }.filter { it.day >= readFrom }
        val upcoming = rows.filter { it.open && it.day >= today }
        val done = rows.filter { !(it.open && it.day >= today) && it.day <= today }

        val from = today.minus(DatePeriod(months = months)).plus(DatePeriod(days = 1))
        val previousFrom = from.minus(DatePeriod(months = months))
        val current = done.filter { it.day >= from }
        val previous = done.filter { it.day >= previousFrom && it.day < from }

        val services = rankServices(current, previous)
        val clients = rankClients(current, previous)
        val matrix = matrix(current, clients, services)
        val dueBack = dueBack(done, booked = upcoming.mapNotNull { it.clientId }.toSet(), today)

        val newClients = run {
            val seen = current.mapNotNull { it.clientId }.toSet()
            val before = done.filter { it.day < from }.mapNotNull { it.clientId }.toSet() + clientsServedBefore(tenantId, seen, readFrom, window)
            (seen - before).size
        }
        val names = clientNames(tenantId, (clients.map { it.id } + dueBack.map { it.id }).toSet().map(::ObjectId))
        val trend = OverviewMath.trendMonthKeys(today, maxOf(OverviewMath.TREND_MONTHS, months)).let { keys ->
            val byMonth = done.groupBy { OverviewMath.monthKey(it.day) }
            keys.map { key -> byMonth[key].orEmpty().let { OverviewServiceMonthDto(key, it.size, it.sumOf { row -> row.cents }) } }
        }
        val weekdays = IntArray(7).also { counts -> current.forEach { counts[it.day.dayOfWeek.isoDayNumber - 1]++ } }.toList()
        val people = if (employees) {
            val byEmployee = current.filter { it.employeeId != null }.groupBy { it.employeeId!! }
            val employeeNames = employeeNames(tenantId, byEmployee.keys)
            byEmployee.map { (id, list) -> OverviewServiceEmployeeDto(id.toHexString(), employeeNames[id] ?: "", list.size, list.sumOf { it.cents }) }
                .sortedWith(compareByDescending<OverviewServiceEmployeeDto> { it.count }.thenByDescending { it.cents })
                .take(LIST_LIMIT)
        } else {
            emptyList()
        }

        return ServiceInsightsDto(
            months = months,
            from = from.toString(),
            to = today.toString(),
            current = period(current).copy(newClients = newClients),
            previous = period(previous),
            trend = trend,
            services = services,
            clients = clients.map { it.copy(name = names[ObjectId(it.id)] ?: "") },
            matrix = matrix.copy(rows = matrix.rows.map { it.copy(name = names[ObjectId(it.id)] ?: "") }),
            dueBack = dueBack.map { it.copy(name = names[ObjectId(it.id)] ?: "") },
            weekdays = weekdays,
            employees = people,
            truncated = docs.size >= ROW_LIMIT,
        )
    }

    private fun period(rows: List<ServiceRow>): ServicePeriodDto {
        val byClient = rows.filter { it.clientId != null }.groupBy { it.clientId!! }
        val total = rows.sumOf { it.cents }
        val fifth = byClient.values.map { list -> list.sumOf { it.cents } }.sortedDescending()
            .take(ceil(byClient.size / 5.0).toInt()).sum()
        val lines = rows.flatMap { it.lines() }
        return ServicePeriodDto(
            count = rows.size,
            cents = total,
            lines = lines.size,
            lineCents = lines.sumOf { it.second },
            clients = byClient.size,
            repeatClients = byClient.values.count { it.size >= 2 },
            topFifthSharePct = if (total > 0) ((fifth * 100) / total).toInt() else 0,
        )
    }

    /** Lines grouped by title, ignoring case: the top by times and the top by value, ordered by times. */
    private fun rankServices(current: List<ServiceRow>, previous: List<ServiceRow>): List<ServiceInsightItemDto> {
        val before = previous.flatMap { it.lines() }.groupingBy { it.first.lowercase() }.eachCount()
        val all = current.flatMap { row -> row.lines().map { Triple(it.first, it.second, row.clientId) } }
            .groupBy { it.first.lowercase() }
            .map { (key, lines) ->
                ServiceInsightItemDto(
                    name = lines.first().first,
                    count = lines.size,
                    cents = lines.sumOf { it.second },
                    clients = lines.mapNotNull { it.third }.toSet().size,
                    previousCount = before[key] ?: 0,
                )
            }
        return topByBoth(all, { it.count.toLong() }, { it.cents })
    }

    private fun rankClients(current: List<ServiceRow>, previous: List<ServiceRow>): List<ServiceInsightClientDto> {
        val before = previous.mapNotNull { it.clientId }.groupingBy { it }.eachCount()
        val all = current.filter { it.clientId != null }.groupBy { it.clientId!! }.map { (id, list) ->
            val lines = list.flatMap { it.lines() }.groupBy { it.first.lowercase() }
            val favourite = lines.values.maxWithOrNull(compareBy<List<Pair<String, Long>>> { it.size }.thenBy { group -> group.sumOf { it.second } })
            ServiceInsightClientDto(
                id = id.toHexString(),
                name = "",
                count = list.size,
                cents = list.sumOf { it.cents },
                previousCount = before[id] ?: 0,
                lastDay = list.maxOf { it.day }.toString(),
                topService = favourite?.first()?.first.orEmpty(),
                topServiceCount = favourite?.size ?: 0,
                serviceKinds = lines.size,
            )
        }
        return topByBoth(all, { it.count.toLong() }, { it.cents })
    }

    private fun <T> topByBoth(all: List<T>, times: (T) -> Long, value: (T) -> Long): List<T> {
        val byTimes = compareByDescending(times).thenByDescending(value)
        val top = all.sortedWith(byTimes).take(LIST_LIMIT) + all.sortedWith(compareByDescending(value).thenByDescending(times)).take(LIST_LIMIT)
        return top.distinct().sortedWith(byTimes)
    }

    private fun matrix(current: List<ServiceRow>, clients: List<ServiceInsightClientDto>, services: List<ServiceInsightItemDto>): ServiceMatrixDto {
        val columns = services.take(MATRIX_SIZE)
        val keys = columns.map { it.name.lowercase() }
        val byClient = current.filter { it.clientId != null }.groupBy { it.clientId!!.toHexString() }
        val rows = clients.take(MATRIX_SIZE).map { client ->
            val lines = byClient[client.id].orEmpty().flatMap { it.lines() }.groupBy { it.first.lowercase() }
            ServiceMatrixRowDto(
                id = client.id,
                name = "",
                cells = keys.map { key -> lines[key].orEmpty().let { ServiceMatrixCellDto(it.size, it.sumOf { line -> line.second }) } },
            )
        }
        return ServiceMatrixDto(columns.map { it.name }, rows)
    }

    /**
     * Clients with [DUE_BACK_MIN_VISITS] visit days or more, nothing booked, and a last visit longer ago than their
     * usual gap plus a quarter of it (three days at least). The most late against their own rhythm come first.
     */
    private fun dueBack(done: List<ServiceRow>, booked: Set<ObjectId>, today: LocalDate): List<ServiceDueBackDto> =
        done.filter { it.clientId != null && it.clientId !in booked }.groupBy { it.clientId!! }.mapNotNull { (id, list) ->
            val days = list.map { it.day }.distinct().sorted()
            if (days.size < DUE_BACK_MIN_VISITS) return@mapNotNull null
            val usual = median(days.zipWithNext { a, b -> a.daysUntil(b) }).coerceAtLeast(1)
            val late = days.last().plus(DatePeriod(days = usual)).daysUntil(today)
            if (late <= maxOf(3, usual / 4)) return@mapNotNull null
            ServiceDueBackDto(id.toHexString(), "", days.size, usual, days.last().toString(), late, list.sumOf { it.cents })
        }.sortedByDescending { it.lateDays.toDouble() / it.usualDays }.take(LIST_LIMIT)

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Which of [clients] had a service before [readFrom], older than the rows read, so they aren't new. */
    private suspend fun clientsServedBefore(tenantId: ObjectId, clients: Set<ObjectId>, readFrom: LocalDate, window: OverviewMath.Window): Set<ObjectId> {
        if (clients.isEmpty()) return emptySet()
        return coll("crm.client_services").distinct<ObjectId>(
            "clientId",
            Filters.and(
                Filters.eq("tenantId", tenantId),
                Filters.`in`("clientId", clients.toList()),
                Filters.ne("status", "CANCELLED"),
                Filters.or(
                    Filters.and(Filters.gt("performedAt", ""), Filters.lt("performedAt", readFrom.toString())),
                    Filters.and(Filters.`in`("performedAt", null, ""), Filters.lt("createdAt", Date(readFrom.atStartOfDayIn(window.zone).toEpochMilliseconds()))),
                ),
            ),
        ).toList().toSet()
    }

    companion object {
        val PERIODS = listOf(1, 3, 6, 12)
        const val DEFAULT_MONTHS = 3
        private const val HISTORY_MONTHS = 24
        private const val ROW_LIMIT = 30_000
        private const val LIST_LIMIT = 8
        private const val MATRIX_SIZE = 6
        private const val DUE_BACK_MIN_VISITS = 3
    }
}
