package com.rfm.edubot.admin

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.crm.model.Invoice
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.Quote
import com.rfm.edubot.crm.model.QuoteStatus
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertTrue

class DocumentPdfTest {

    private val now = Clock.System.now()
    private val tenantId = ObjectId()
    private val client = Client(
        id = ObjectId(),
        tenantId = tenantId,
        number = "CLT-001",
        name = "Cliente",
        phone = "+351 900 000 000",
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `quote without a stored file still offers a pdf`() {
        val quote = Quote(
            tenantId = tenantId,
            number = "ORC-1",
            clientId = client.id,
            items = emptyList(),
            status = QuoteStatus.PENDENTE,
            totalCents = 0,
            pdfPath = null,
            createdAt = now,
            updatedAt = now,
        )
        assertTrue(quote.dto(client).hasPdf)
    }

    @Test
    fun `invoice without a stored file still offers a pdf`() {
        val invoice = Invoice(
            tenantId = tenantId,
            number = "FAT-1",
            clientId = client.id,
            items = emptyList(),
            status = InvoiceStatus.PENDING,
            dueDate = LocalDate(2026, 9, 22),
            totalCents = 0,
            pdfPath = null,
            createdAt = now,
            updatedAt = now,
        )
        assertTrue(invoice.dto(client).hasPdf)
    }
}
