package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.ClientService
import com.rfm.edubot.crm.model.ClientServiceStatus
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientServiceBillingTest {
    private val clientA = ObjectId()
    private val clientB = ObjectId()
    private val tenant = ObjectId()

    @Test
    fun `empty selection is rejected`() {
        val result = ClientServiceBilling.prepareInvoice(clientA, emptyList())
        assertEquals(ClientServiceBilling.Outcome.Rejected(ClientServiceBilling.EMPTY), result)
    }

    @Test
    fun `services from another client are rejected`() {
        val result = ClientServiceBilling.prepareInvoice(clientA, listOf(openService(clientB)))
        assertEquals(ClientServiceBilling.Outcome.Rejected(ClientServiceBilling.CLIENT_MISMATCH), result)
    }

    @Test
    fun `already invoiced rows cannot be billed again`() {
        val result = ClientServiceBilling.prepareInvoice(clientA, listOf(openService(clientA).copy(status = ClientServiceStatus.INVOICED)))
        assertEquals(ClientServiceBilling.Outcome.Rejected(ClientServiceBilling.NOT_OPEN), result)
    }

    @Test
    fun `open rows for one client become invoice lines`() {
        val rows = listOf(
            openService(clientA, name = "Website", quantity = 1.0, unitPriceCents = 150000, unit = "job"),
            openService(clientA, name = "SEO", quantity = 2.0, unitPriceCents = 40000, unit = "mo"),
        )
        val result = ClientServiceBilling.prepareInvoice(clientA, rows)
        assertTrue(result is ClientServiceBilling.Outcome.Ready)
        val items = (result as ClientServiceBilling.Outcome.Ready).items
        assertEquals(listOf("Website", "SEO"), items.map { it.description })
        assertEquals(listOf(150000L, 80000L), items.map { it.totalCents })
        assertEquals("mo", items[1].unit)
    }

    private fun openService(
        clientId: ObjectId,
        name: String = "Work",
        quantity: Double = 1.0,
        unitPriceCents: Long = 1000,
        unit: String = "",
    ) = ClientService(
        tenantId = tenant,
        clientId = clientId,
        name = name,
        quantity = quantity,
        unit = unit,
        unitPriceCents = unitPriceCents,
        totalCents = clientServiceTotals(quantity, unitPriceCents),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
