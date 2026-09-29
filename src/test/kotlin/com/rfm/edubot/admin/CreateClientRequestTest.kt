package com.rfm.edubot.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateClientRequestTest {

    private fun request(email: String? = null, taxId: String? = null, notes: String? = null, address: String? = null) =
        CreateClientRequest("Ana Ribeiro", "+351 911 222 333", address = address, email = email, taxId = taxId, notes = notes)

    @Test
    fun `accepts missing, empty and well-formed details`() {
        assertNull(request().detailsError())
        assertNull(request(email = "", taxId = "", notes = "").detailsError())
        assertNull(request(email = " ana.ribeiro@example.pt ", taxId = "PT245678901", notes = "Prefere tardes.").detailsError())
    }

    @Test
    fun `rejects a malformed email and oversized fields with stable codes`() {
        assertEquals("invalid_email", request(email = "ana@example").detailsError())
        assertEquals("invalid_email", request(email = "ana ribeiro@example.pt").detailsError())
        assertEquals("tax_id_too_long", request(taxId = "1".repeat(33)).detailsError())
        assertEquals("notes_too_long", request(notes = "x".repeat(4001)).detailsError())
        assertEquals("address_too_long", request(address = "x".repeat(301)).detailsError())
    }
}
