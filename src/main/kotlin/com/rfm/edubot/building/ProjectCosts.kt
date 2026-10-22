package com.rfm.edubot.building

import com.rfm.edubot.building.model.MeasurementStatus
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Payment
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.timesheets.Timesheets
import com.rfm.edubot.timesheets.model.WorkSite
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import kotlin.math.roundToLong

/**
 * What a project earns, costs and leaves, in cents: its revenue (the current contract, what its measurements
 * measured, what its invoices billed and what was received of them), its costs (the bills linked to it and the
 * hours clocked at its sites), its budget, and its margin. [bills] is null without payments, [labour] without the
 * time clock.
 */
data class ProjectCosts(
    val currentContractCents: Long,
    val measuredCents: Long,
    val invoicedCents: Long,
    val receivedCents: Long,
    val bills: Bills?,
    val labour: Labour?,
    val costBudgetCents: Long?,
) {
    /** Hours without a labour rate cost nothing here: the rate is what prices them. */
    val totalCostCents: Long get() = (bills?.totalCents ?: 0) + (labour?.costCents ?: 0)

    /** The current contract less the budget, or less the costs once they pass it; without a budget, less the costs so far. */
    val expectedMarginCents: Long get() = currentContractCents - maxOf(costBudgetCents ?: 0, totalCostCents)

    /** What the measurements measured less what the work cost so far. */
    val marginToDateCents: Long get() = measuredCents - totalCostCents

    data class Bills(val totalCents: Long, val paidCents: Long, val byPayee: List<PayeeCost>, val payments: List<Payment>)

    /** One supplier's or employee's bills for the project. */
    data class PayeeCost(val supplierId: ObjectId?, val employeeId: ObjectId?, val totalCents: Long, val paidCents: Long, val count: Int)

    data class Labour(val minutes: Int, val rateCents: Long?, val byEmployee: List<EmployeeHours>, val sites: List<WorkSite>) {
        val costCents: Long? get() = rateCents?.let { byEmployee.sumOf { it.costCents ?: 0 } }
    }

    data class EmployeeHours(val employeeId: ObjectId, val minutes: Int, val costCents: Long?)
}

/** A project's [ProjectCosts]; a shift still open counts up to now. */
class ProjectCostsService(private val records: BuildingRecords, private val clock: () -> Instant = SystemClock::now) {
    /** [withBills] and [withHours] say whether the company has payments and the time clock; null for no such project. */
    suspend fun costs(projectId: ObjectId, withBills: Boolean, withHours: Boolean): ProjectCosts? {
        val project = records.projects.findById(projectId) ?: return null
        val effect = approvedEffect(records.changes.list(project.id))
        val measured = records.measurements.list(project.id).filter { it.status != MeasurementStatus.DRAFT }.maxByOrNull { it.number }
        val invoices = records.invoices.forProject(project.id).filter { it.status != InvoiceStatus.CANCELLED }
        return ProjectCosts(
            currentContractCents = project.contractCents + effect.cents,
            measuredCents = measured?.toDateCents ?: 0,
            invoicedCents = invoices.sumOf { it.totalCents },
            receivedCents = invoices.sumOf { it.paidCents },
            bills = if (withBills) bills(project.id) else null,
            labour = if (withHours) labour(project) else null,
            costBudgetCents = project.costBudgetCents,
        )
    }

    private suspend fun bills(projectId: ObjectId): ProjectCosts.Bills {
        val payments = records.payments.forProject(projectId).filter { it.status != PaymentStatus.CANCELLED }
        fun paid(list: List<Payment>) = list.filter { it.status == PaymentStatus.PAID }.sumOf { it.totalCents }
        val byPayee = payments.groupBy { it.supplierId to it.employeeId }
            .map { (payee, own) -> ProjectCosts.PayeeCost(payee.first, payee.second, own.sumOf { it.totalCents }, paid(own), own.size) }
            .sortedByDescending { it.totalCents }
        return ProjectCosts.Bills(payments.sumOf { it.totalCents }, paid(payments), byPayee, payments)
    }

    private suspend fun labour(project: Project): ProjectCosts.Labour {
        val now = clock()
        val minutes = with(Timesheets) { records.shifts.atSites(project.siteIds).groupBy { it.employeeId }.mapValues { (_, own) -> own.sumOf { it.workedUntil(now) } } }
        val rate = project.laborRateCents
        val byEmployee = minutes.map { (employeeId, worked) -> ProjectCosts.EmployeeHours(employeeId, worked, rate?.let { (worked * it / 60.0).roundToLong() }) }
            .sortedByDescending { it.minutes }
        val sites = records.sites.list().filter { it.id in project.siteIds }
        return ProjectCosts.Labour(byEmployee.sumOf { it.minutes }, rate, byEmployee, sites)
    }
}
