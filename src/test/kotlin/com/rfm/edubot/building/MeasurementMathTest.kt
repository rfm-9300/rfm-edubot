package com.rfm.edubot.building

import com.rfm.edubot.building.model.ChangeOrder
import com.rfm.edubot.building.model.ChangeOrderKind
import com.rfm.edubot.building.model.ChangeOrderStatus
import com.rfm.edubot.building.model.ContractLine
import com.rfm.edubot.building.model.Measurement
import com.rfm.edubot.building.model.MeasurementLine
import com.rfm.edubot.building.model.Project
import com.rfm.edubot.crm.model.LineItem
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MeasurementMathTest {
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val tenantId = ObjectId()
    private val projectId = ObjectId()

    private val project = Project(
        id = projectId,
        tenantId = tenantId,
        number = "OB-001",
        name = "Moradia",
        contract = listOf(
            ContractLine("walls", "Alvenarias", 120.0, "m2", 2_500, 300_000),
            ContractLine("roof", "Cobertura", 1.0, "vg", 800_000, 800_000),
        ),
        createdAt = epoch,
        updatedAt = epoch,
    )

    private fun change(kind: ChangeOrderKind, status: ChangeOrderStatus, vararg items: LineItem) = ChangeOrder(
        tenantId = tenantId,
        projectId = projectId,
        number = "TM-001",
        title = "Extra",
        kind = kind,
        items = items.toList(),
        totalCents = items.sumOf { it.totalCents },
        status = status,
        decidedAt = epoch,
        createdBy = "ana@obras.test",
        createdAt = epoch,
        updatedAt = epoch,
    )

    private fun measurement(lines: List<MeasurementLine>, retention: Double = 5.0) = Measurement(
        tenantId = tenantId,
        projectId = projectId,
        number = 1,
        periodEnd = LocalDate(2026, 10, 31),
        lines = lines,
        retentionPct = retention,
        grossCents = 0,
        retentionCents = 0,
        netCents = 0,
        toDateCents = 0,
        createdBy = "ana@obras.test",
        createdAt = epoch,
        updatedAt = epoch,
    )

    @Test
    fun `the billable lines are the contract's and the approved additions', never omissions or open changes`() {
        val extra = change(ChangeOrderKind.ADDITION, ChangeOrderStatus.APPROVED, LineItem("Pérgula", 1.0, "un", 150_000, 150_000))
        val omitted = change(ChangeOrderKind.OMISSION, ChangeOrderStatus.APPROVED, LineItem("Muro", 1.0, "un", 50_000, 50_000))
        val sent = change(ChangeOrderKind.ADDITION, ChangeOrderStatus.SENT, LineItem("Piscina", 1.0, "un", 900_000, 900_000))
        val lines = MeasurementMath.billableLines(project, listOf(extra, omitted, sent))
        assertEquals(listOf("walls", "roof", MeasurementMath.changeKey(extra, 0)), lines.map { it.key })
        assertEquals("TM-001 · Pérgula", lines.last().description)
    }

    @Test
    fun `a new measurement starts from what the previous one measured to date`() {
        val billable = MeasurementMath.billableLines(project, emptyList())
        val previous = measurement(MeasurementMath.draftLines(billable, null).map { if (it.key == "walls") it.copy(toDateQty = 30.0) else it })
        val next = MeasurementMath.draftLines(billable, previous)
        assertEquals(30.0, next.first { it.key == "walls" }.previousQty)
        assertEquals(30.0, next.first { it.key == "walls" }.toDateQty)
        assertEquals(0L, MeasurementMath.totals(next, 5.0).grossCents, "nothing new measured yet")
    }

    @Test
    fun `this period is to date less the previous measurement, with the retention held back`() {
        val lines = MeasurementMath.draftLines(MeasurementMath.billableLines(project, emptyList()), null)
            .map { if (it.key == "walls") it.copy(previousQty = 30.0, toDateQty = 90.0) else it.copy(toDateQty = 0.5) }
        val totals = MeasurementMath.totals(lines, 5.0)
        assertEquals(1_100_000L, totals.contractCents)
        assertEquals(75_000L, totals.previousCents)
        assertEquals(225_000L + 400_000L, totals.toDateCents)
        assertEquals(150_000L + 400_000L, totals.grossCents)
        assertEquals(27_500L, totals.retentionCents)
        assertEquals(522_500L, totals.netCents)
    }

    @Test
    fun `a fully measured line is worth exactly its contracted total`() {
        val odd = MeasurementLine("tiles", "Ladrilho", "m2", 3.0, 333, 1_000, 0.0, 0.0)
        assertEquals(333L, MeasurementMath.valueCents(odd, 1.0))
        assertEquals(1_000L, MeasurementMath.valueCents(odd, 3.0), "3 × 3,33 € would be 9,99 €")
    }

    @Test
    fun `typed quantities are refused above the contract or below the previous measurement`() {
        val lines = listOf(MeasurementLine("walls", "Alvenarias", "m2", 120.0, 2_500, 300_000, 30.0, 30.0))
        assertEquals(MeasurementMath.ABOVE_CONTRACT, (MeasurementMath.apply(lines, mapOf("walls" to 121.0)) as MeasurementMath.Result.Refused).error)
        assertEquals(MeasurementMath.BELOW_PREVIOUS, (MeasurementMath.apply(lines, mapOf("walls" to 29.0)) as MeasurementMath.Result.Refused).error)
        assertEquals(MeasurementMath.UNKNOWN_LINE, (MeasurementMath.apply(lines, mapOf("doors" to 1.0)) as MeasurementMath.Result.Refused).error)
        assertEquals(MeasurementMath.INVALID_QUANTITY, (MeasurementMath.apply(lines, mapOf("walls" to Double.NaN)) as MeasurementMath.Result.Refused).error)
        val ok = assertIs<MeasurementMath.Result.Ok>(MeasurementMath.apply(lines, mapOf("walls" to 120.0)))
        assertEquals(120.0, ok.lines.single().toDateQty)
    }

    @Test
    fun `the invoice bills each line's period and takes the retention off`() {
        val m = measurement(
            listOf(
                MeasurementLine("walls", "Alvenarias", "m2", 120.0, 2_500, 300_000, 30.0, 90.0),
                MeasurementLine("roof", "Cobertura", "vg", 1.0, 800_000, 800_000, 0.0, 0.0),
            ),
        )
        val items = MeasurementMath.invoiceItems(m, "Retenção de garantia (5 %)")
        assertEquals(listOf("Alvenarias", "Retenção de garantia (5 %)"), items.map { it.description })
        assertEquals(60.0, items.first().quantity)
        assertEquals(150_000L, items.first().totalCents)
        assertEquals(-7_500L, items.last().totalCents)
        assertEquals(142_500L, items.sumOf { it.totalCents })
        assertEquals(1, MeasurementMath.invoiceItems(m.copy(retentionPct = 0.0), "x").size, "no retention line at 0%")
    }
}
