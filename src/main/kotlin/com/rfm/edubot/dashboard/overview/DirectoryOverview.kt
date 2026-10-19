package com.rfm.edubot.dashboard.overview

import com.mongodb.client.model.Filters
import com.rfm.edubot.crm.ServiceSubmissionRepository
import com.rfm.edubot.crm.model.ServiceSubmissionStatus
import com.rfm.edubot.dashboard.OverviewCustomersDto
import com.rfm.edubot.dashboard.OverviewEmployeesDto
import com.rfm.edubot.dashboard.OverviewMath
import com.rfm.edubot.dashboard.OverviewSuppliersDto
import com.rfm.edubot.persistence.MongoModule
import java.util.Date
import org.bson.types.ObjectId

/** The Customers, Suppliers and Employees cards: each directory's size and growth, and services waiting for approval. */
internal class DirectoryOverview(mongo: MongoModule) : OverviewQueries(mongo) {
    suspend fun customers(tenantId: ObjectId, window: OverviewMath.Window): OverviewCustomersDto {
        val tenantFilter = Filters.eq("tenantId", tenantId)
        return OverviewCustomersDto(
            total = coll("crm.clients").countDocuments(Filters.and(tenantFilter, Filters.eq("archivedAt", null))),
            newThisMonth = coll("crm.clients").countDocuments(
                Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.monthStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.nextMonthStart.toEpochMilliseconds()))),
            ),
            newLastMonth = coll("crm.clients").countDocuments(
                Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.lastMonthStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.monthStart.toEpochMilliseconds()))),
            ),
        )
    }

    suspend fun suppliers(tenantId: ObjectId, window: OverviewMath.Window): OverviewSuppliersDto {
        val tenantFilter = Filters.eq("tenantId", tenantId)
        return OverviewSuppliersDto(
            total = coll("crm.suppliers").countDocuments(Filters.and(tenantFilter, Filters.eq("archivedAt", null))),
            newThisMonth = coll("crm.suppliers").countDocuments(
                Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.monthStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.nextMonthStart.toEpochMilliseconds()))),
            ),
            newLastMonth = coll("crm.suppliers").countDocuments(
                Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.lastMonthStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.monthStart.toEpochMilliseconds()))),
            ),
        )
    }

    suspend fun employees(tenantId: ObjectId, window: OverviewMath.Window, submissions: Boolean): OverviewEmployeesDto {
        val tenantFilter = Filters.eq("tenantId", tenantId)
        return OverviewEmployeesDto(
            total = coll("crm.employees").countDocuments(Filters.and(tenantFilter, Filters.eq("archivedAt", null))),
            newThisMonth = coll("crm.employees").countDocuments(
                Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.monthStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.nextMonthStart.toEpochMilliseconds()))),
            ),
            newLastMonth = coll("crm.employees").countDocuments(
                Filters.and(tenantFilter, Filters.gte("createdAt", Date(window.lastMonthStart.toEpochMilliseconds())), Filters.lt("createdAt", Date(window.monthStart.toEpochMilliseconds()))),
            ),
            pendingSubmissions = if (submissions) {
                coll(ServiceSubmissionRepository.COLLECTION).countDocuments(Filters.and(tenantFilter, Filters.eq("status", ServiceSubmissionStatus.PENDING.name)))
            } else {
                0
            },
        )
    }
}
