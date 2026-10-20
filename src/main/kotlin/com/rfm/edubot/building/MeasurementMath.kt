package com.rfm.edubot.building

import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderKind
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.MeasurementLine
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.crm.model.LineItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Progress billing (autos de medição): what a measurement bills. Every line is measured to date, so this
 * period is the difference with the previous measurement and a figure typed wrong last time is corrected by the
 * next one, up to what was already billed.
 */
object MeasurementMath {
    const val ABOVE_CONTRACT = "above_contract"
    const val BELOW_PREVIOUS = "below_previous"
    const val UNKNOWN_LINE = "unknown_line"
    const val INVALID_QUANTITY = "invalid_quantity"
    private const val EPS = 1e-9

    data class Totals(
        val contractCents: Long,
        val previousCents: Long,
        val toDateCents: Long,
        val grossCents: Long,
        val retentionCents: Long,
        val netCents: Long,
    )

    /** The lines a measurement bills: the contract's, then each approved addition's, in the order they were approved. */
    fun billableLines(project: Project, changes: List<ChangeOrder>): List<MeasurementLine> {
        val contract = project.contract.filter { it.quantity > 0 }.map {
            MeasurementLine(it.id, it.description, it.unit, it.quantity, it.unitPriceCents, it.totalCents, 0.0, 0.0)
        }
        val added = changes
            .filter { it.status == ChangeOrderStatus.APPROVED && it.kind == ChangeOrderKind.ADDITION }
            .sortedBy { it.decidedAt ?: it.updatedAt }
            .flatMap { change ->
                change.items.mapIndexedNotNull { index, item ->
                    if (item.quantity <= 0) return@mapIndexedNotNull null
                    MeasurementLine(changeKey(change, index), "${change.number} · ${item.description}", item.unit, item.quantity, item.unitPriceCents, item.totalCents, 0.0, 0.0)
                }
            }
        return contract + added
    }

    fun changeKey(change: ChangeOrder, index: Int): String = "co:${change.id.toHexString()}:$index"

    /** A new measurement's lines: each billable line starting from what [previous] measured to date. */
    fun draftLines(billable: List<MeasurementLine>, previous: Measurement?): List<MeasurementLine> {
        val before = previous?.lines.orEmpty().associate { it.key to it.toDateQty }
        return billable.map { line ->
            val done = (before[line.key] ?: 0.0).coerceIn(0.0, line.quantity)
            line.copy(previousQty = done, toDateQty = done)
        }
    }

    /** The value of [quantity] of [line]: its whole total once fully done, else quantity × unit price to the cent. */
    fun valueCents(line: MeasurementLine, quantity: Double): Long {
        if (quantity >= line.quantity - EPS) return line.totalCents
        val value = (quantity * line.unitPriceCents).roundToLong()
        return value.coerceIn(min(0, line.totalCents), max(0, line.totalCents))
    }

    fun previousCents(line: MeasurementLine): Long = valueCents(line, line.previousQty)

    fun toDateCents(line: MeasurementLine): Long = valueCents(line, line.toDateQty)

    fun periodCents(line: MeasurementLine): Long = toDateCents(line) - previousCents(line)

    fun periodQty(line: MeasurementLine): Double = line.toDateQty - line.previousQty

    fun pctToDate(line: MeasurementLine): Double = if (line.quantity <= 0) 0.0 else (line.toDateQty / line.quantity * 100).coerceIn(0.0, 100.0)

    fun retentionCents(grossCents: Long, retentionPct: Double): Long =
        if (grossCents <= 0 || retentionPct <= 0) 0 else (grossCents * retentionPct / 100).roundToLong()

    fun totals(lines: List<MeasurementLine>, retentionPct: Double): Totals {
        val gross = lines.sumOf { periodCents(it) }
        val retention = retentionCents(gross, retentionPct)
        return Totals(
            contractCents = lines.sumOf { it.totalCents },
            previousCents = lines.sumOf { previousCents(it) },
            toDateCents = lines.sumOf { toDateCents(it) },
            grossCents = gross,
            retentionCents = retention,
            netCents = gross - retention,
        )
    }

    /**
     * [lines] with the to-date quantities in [typed] (by key; a line left out keeps its own), or the first
     * problem: a key that isn't a line, a quantity that isn't a number, above what was contracted, or below what
     * the previous measurement had already measured.
     */
    fun apply(lines: List<MeasurementLine>, typed: Map<String, Double>): Result {
        val keys = lines.map { it.key }.toSet()
        typed.keys.firstOrNull { it !in keys }?.let { return Result.Refused(UNKNOWN_LINE, it) }
        val next = lines.map { line ->
            val quantity = typed[line.key] ?: return@map line
            if (!quantity.isFinite() || quantity < 0) return Result.Refused(INVALID_QUANTITY, line.key)
            if (quantity > line.quantity + EPS) return Result.Refused(ABOVE_CONTRACT, line.key)
            if (quantity < line.previousQty - EPS) return Result.Refused(BELOW_PREVIOUS, line.key)
            line.copy(toDateQty = if (abs(quantity - line.quantity) < EPS) line.quantity else quantity)
        }
        return Result.Ok(next)
    }

    sealed interface Result {
        data class Ok(val lines: List<MeasurementLine>) : Result
        data class Refused(val error: String, val key: String) : Result
    }

    /**
     * The invoice for [measurement]: one line per line billed this period (its quantity this period), then the
     * retention held back as a negative line labelled [retentionLabel].
     */
    fun invoiceItems(measurement: Measurement, retentionLabel: String): List<LineItem> {
        val billed = measurement.lines.filter { periodCents(it) != 0L }.map { line ->
            LineItem(
                description = line.description,
                quantity = roundQty(periodQty(line)),
                unit = line.unit,
                unitPriceCents = line.unitPriceCents,
                totalCents = periodCents(line),
            )
        }
        val retention = retentionCents(billed.sumOf { it.totalCents }, measurement.retentionPct)
        return if (retention > 0) billed + LineItem(retentionLabel, 1.0, "", -retention, -retention) else billed
    }

    private fun roundQty(value: Double): Double = (value * 1000).roundToLong() / 1000.0
}
