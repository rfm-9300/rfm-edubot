package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.ClientService
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.LineItem
import org.bson.types.ObjectId

object ClientServiceBilling {
    const val EMPTY = "empty"
    const val CLIENT_MISMATCH = "client_mismatch"
    const val NOT_OPEN = "not_open"

    sealed class Outcome {
        data class Ready(val items: List<LineItem>) : Outcome()
        data class Rejected(val reason: String) : Outcome()
    }

    fun prepareInvoice(clientId: ObjectId, services: List<ClientService>): Outcome {
        if (services.isEmpty()) return Outcome.Rejected(EMPTY)
        if (services.any { it.clientId != clientId }) return Outcome.Rejected(CLIENT_MISMATCH)
        if (services.any { it.status != ClientServiceStatus.OPEN }) return Outcome.Rejected(NOT_OPEN)
        return Outcome.Ready(services.flatMap { it.lines() })
    }
}

/** The row's lines: its items, or the one line its own fields describe. */
fun ClientService.lines(): List<LineItem> = items.ifEmpty {
    listOf(LineItem(description = name, quantity = quantity, unit = unit, unitPriceCents = unitPriceCents, totalCents = totalCents))
}

fun clientServiceTotals(quantity: Double, unitPriceCents: Long): Long =
    (quantity * unitPriceCents).toLong()

/** What a row shows for its lines: a single line's own quantity, unit and price, or 1 × the sum of several. */
internal data class ServiceSummary(val quantity: Double, val unit: String, val unitPriceCents: Long, val totalCents: Long)

internal fun summarize(items: List<LineItem>): ServiceSummary {
    val total = items.sumOf { it.totalCents }
    val single = items.singleOrNull() ?: return ServiceSummary(1.0, "", total, total)
    return ServiceSummary(single.quantity, single.unit, single.unitPriceCents, total)
}
