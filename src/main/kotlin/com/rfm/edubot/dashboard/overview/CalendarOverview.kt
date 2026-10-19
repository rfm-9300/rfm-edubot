package com.rfm.edubot.dashboard.overview

import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.dashboard.OverviewAgendaItemDto
import com.rfm.edubot.dashboard.OverviewCalendarDto
import com.rfm.edubot.dashboard.OverviewCalendarNextDto
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.persistence.MongoModule
import org.bson.types.ObjectId

/** The Calendar card: today's and this week's bookings, the next one, and today's agenda. */
internal class CalendarOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    suspend fun calendar(tenantId: ObjectId, window: OverviewMath.Window): OverviewCalendarDto {
        val repo = BookingRepository(mongo, tenantId)
        val week = repo.list(from = window.weekStart, to = window.nextWeekStart)
            .filter { it.status != BookingStatus.CANCELLED }
        val today = week.filter { it.startAt >= window.todayStart && it.startAt < window.tomorrowStart }
        val pending = repo.list(status = BookingStatus.PENDING).filter { it.startAt >= window.now }
        val upcoming = week.filter { it.startAt >= window.now && it.status != BookingStatus.CANCELLED }
            .minByOrNull { it.startAt }
        return OverviewCalendarDto(
            today = today.size,
            thisWeek = week.size,
            pending = pending.size,
            next = upcoming?.let { OverviewCalendarNextDto(it.id.toHexString(), it.contactName, it.startAt.toString()) },
        )
    }

    suspend fun agenda(tenantId: ObjectId, window: OverviewMath.Window): List<OverviewAgendaItemDto> {
        val today = BookingRepository(mongo, tenantId).list(from = window.todayStart, to = window.tomorrowStart)
            .filter { it.status != BookingStatus.CANCELLED }
            .sortedBy { it.startAt }
            .take(OverviewMath.AGENDA_LIMIT)
        return today.map {
            OverviewAgendaItemDto(
                id = it.id.toHexString(),
                startAt = it.startAt.toString(),
                endAt = it.endAt.toString(),
                contactName = it.contactName,
                service = it.serviceName,
                status = it.status.name,
            )
        }
    }
}
