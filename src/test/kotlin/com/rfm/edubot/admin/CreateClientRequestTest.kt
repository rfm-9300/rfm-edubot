package com.rfm.edubot.admin

import com.rfm.edubot.crm.model.Client
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateClientRequestTest {

    private fun request(
        email: String? = null,
        taxId: String? = null,
        notes: String? = null,
        address: String? = null,
        postalCode: String? = null,
        city: String? = null,
        contactPerson: String? = null,
    ) = CreateClientRequest(
        "Ana Ribeiro", "+351 911 222 333",
        address = address, email = email, taxId = taxId, notes = notes,
        postalCode = postalCode, city = city, contactPerson = contactPerson,
    )

    @Test
    fun `accepts missing, empty and well-formed details`() {
        assertNull(request().detailsError())
        assertNull(request(email = "", taxId = "", notes = "", postalCode = "", city = "", contactPerson = "").detailsError())
        assertNull(request(email = " ana.ribeiro@example.pt ", taxId = "PT245678901", notes = "Prefere tardes.").detailsError())
        assertNull(request(postalCode = "1200-001", city = "Lisboa", contactPerson = "Marta Reis").detailsError())
    }

    @Test
    fun `rejects a malformed email and oversized fields with stable codes`() {
        assertEquals("invalid_email", request(email = "ana@example").detailsError())
        assertEquals("invalid_email", request(email = "ana ribeiro@example.pt").detailsError())
        assertEquals("tax_id_too_long", request(taxId = "1".repeat(33)).detailsError())
        assertEquals("notes_too_long", request(notes = "x".repeat(4001)).detailsError())
        assertEquals("address_too_long", request(address = "x".repeat(301)).detailsError())
        assertEquals("postal_code_too_long", request(postalCode = "1".repeat(21)).detailsError())
        assertEquals("city_too_long", request(city = "x".repeat(101)).detailsError())
        assertEquals("contact_person_too_long", request(contactPerson = "x".repeat(121)).detailsError())
    }

    @Test
    fun `staff must give a NIF and an address`() {
        assertEquals("tax_id_required", request(address = "Rua das Flores 12").requiredError())
        assertEquals("tax_id_required", request(taxId = " ", address = "Rua das Flores 12").requiredError())
        assertEquals("address_required", request(taxId = "245678901").requiredError())
        assertEquals("address_required", request(taxId = "245678901", address = "  ").requiredError())
        assertNull(request(taxId = "245678901", address = "Rua das Flores 12").requiredError())
    }

    @Test
    fun `an update that omits the NIF keeps the stored one, an empty one clears it`() {
        val stored = Client(
            tenantId = ObjectId(),
            number = "CLT-001",
            name = "Ana Ribeiro",
            phone = "+351 911 222 333",
            taxId = "245678901",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
        assertNull(request(address = "Rua das Flores 12").requiredError(stored))
        assertEquals("tax_id_required", request(taxId = "", address = "Rua das Flores 12").requiredError(stored))
        assertEquals("tax_id_required", request(address = "Rua das Flores 12").requiredError(stored.copy(taxId = null)))
    }
}
