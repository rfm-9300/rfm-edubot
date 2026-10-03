package com.rfm.edubot.mobile.feature.crm

import com.rfm.edubot.mobile.core.model.CrmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The previous quote form took a hand-typed client `ObjectId` and exactly one line. These cover the
 * parts of the replacement that are logic rather than layout.
 */
class LineDraftTest {
    @Test
    fun `a comma decimal is accepted because a Portuguese keyboard offers one`() {
        assertEquals(12.5, LineDraft(unitPrice = "12,5").unitPriceValue)
        assertEquals(12.5, LineDraft(unitPrice = "12.5").unitPriceValue)
    }

    @Test
    fun `a trailing separator keeps the digits already typed`() {
        // Someone part-way through "12,50" should see 12,00 in the running total, not 0,00.
        assertEquals(12.0, LineDraft(unitPrice = "12,").unitPriceValue)
    }

    @Test
    fun `something that is not a number at all reads as zero rather than throwing`() {
        assertEquals(0.0, LineDraft(unitPrice = "").unitPriceValue)
        assertEquals(0.0, LineDraft(unitPrice = "-").unitPriceValue)
        assertEquals(0.0, LineDraft(unitPrice = "abc").unitPriceValue)
    }

    @Test
    fun `the line total is quantity times unit price`() {
        assertEquals(375.0, LineDraft(quantity = "3", unitPrice = "125").totalEur)
        assertEquals(31.25, LineDraft(quantity = "2,5", unitPrice = "12,5").totalEur)
    }

    @Test
    fun `a line needs a description and a quantity to count`() {
        assertFalse(LineDraft(description = "", quantity = "1").complete)
        assertFalse(LineDraft(description = "Work", quantity = "0").complete)
        assertFalse(LineDraft(description = "Work", quantity = "").complete)
        assertTrue(LineDraft(description = "Work", quantity = "1").complete)
    }

    @Test
    fun `a line is trimmed on the way to the backend`() {
        val item = LineDraft(description = "  Work  ", quantity = "2", unit = " h ", unitPrice = "50").toLineItem()
        assertEquals("Work", item.description)
        assertEquals("h", item.unit)
        assertEquals(2.0, item.quantity)
        assertEquals(50.0, item.unitPriceEur)
    }
}

class DocumentFormStateTest {
    private val client = CrmClient(id = "cl1", name = "Maria", phone = "351900000001")

    @Test
    fun `a document cannot be saved without a client`() {
        val state = DocumentFormState(lines = listOf(LineDraft(description = "Work")))
        assertFalse(state.canSave, "the old form happily posted a typed id that matched nothing")
    }

    @Test
    fun `a document cannot be saved with no complete line`() {
        val state = DocumentFormState(clientId = client.id, lines = listOf(LineDraft()))
        assertFalse(state.canSave)
    }

    @Test
    fun `a client and one line is enough`() {
        val state = DocumentFormState(
            clientId = client.id,
            lines = listOf(LineDraft(description = "Work", quantity = "1")),
        )
        assertTrue(state.canSave)
    }

    @Test
    fun `it cannot be saved twice while the first save is in flight`() {
        val state = DocumentFormState(
            clientId = client.id,
            lines = listOf(LineDraft(description = "Work")),
            saving = true,
        )
        assertFalse(state.canSave)
    }

    @Test
    fun `the total adds up every complete line and ignores the blank one`() {
        val state = DocumentFormState(
            clientId = client.id,
            lines = listOf(
                LineDraft(description = "Work", quantity = "2", unitPrice = "100"),
                LineDraft(description = "Parts", quantity = "1", unitPrice = "49,5"),
                LineDraft(),
            ),
        )
        assertEquals(249.5, state.totalEur)
    }
}

class CatalogItemFormStateTest {
    @Test
    fun `an item needs a title because the backend requires one`() {
        assertFalse(CatalogItemFormState().canSave)
        assertTrue(CatalogItemFormState(title = "Installation").canSave)
    }

    @Test
    fun `the backend's code rejections are attributed to the code field`() {
        val taken = CatalogItemFormState(
            title = "Installation",
            error = com.rfm.edubot.mobile.core.common.AppError.Rejected("id_taken", 409),
        )
        assertTrue(taken.codeRejected)

        val other = CatalogItemFormState(
            title = "Installation",
            error = com.rfm.edubot.mobile.core.common.AppError.Rejected("title_required"),
        )
        assertFalse(other.codeRejected)
    }

    @Test
    fun `the wire values are the ones the backend and the web agree on`() {
        assertEquals("service", CatalogItemType.Service.wireValue)
        assertEquals("material", CatalogItemType.Product.wireValue)
    }
}

class ClientFormStateTest {
    @Test
    fun `a client needs its NIF and address which is why saving used to fail`() {
        val missingTaxId = ClientFormState(name = "Maria", phone = "351900000001", address = "Rua X")
        assertFalse(missingTaxId.canSave)

        val missingAddress = ClientFormState(name = "Maria", phone = "351900000001", taxId = "123456789")
        assertFalse(missingAddress.canSave)

        val complete = ClientFormState(
            name = "Maria",
            phone = "351900000001",
            taxId = "123456789",
            address = "Rua X 1",
        )
        assertTrue(complete.canSave)
    }
}
