package com.rfm.edubot.crm

import com.rfm.edubot.crm.model.Client
import com.rfm.edubot.tenant.model.DirectoryFields
import kotlinx.datetime.Instant
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientDraftTest {

    private fun draft(
        email: String? = null,
        taxId: String? = null,
        notes: String? = null,
        address: String? = null,
        postalCode: String? = null,
        city: String? = null,
        contactPerson: String? = null,
    ) = ClientDraft(
        "Ana Ribeiro", "+351 911 222 333",
        address = address, email = email, taxId = taxId, notes = notes,
        postalCode = postalCode, city = city, contactPerson = contactPerson,
    )

    @Test
    fun `accepts missing, empty and well-formed details`() {
        assertNull(draft().detailsError())
        assertNull(draft(email = "", taxId = "", notes = "", postalCode = "", city = "", contactPerson = "").detailsError())
        assertNull(draft(email = " ana.ribeiro@example.pt ", taxId = "PT245678901", notes = "Prefere tardes.").detailsError())
        assertNull(draft(postalCode = "1200-001", city = "Lisboa", contactPerson = "Marta Reis").detailsError())
    }

    @Test
    fun `rejects a malformed email and oversized fields with stable codes`() {
        assertEquals("invalid_email", draft(email = "ana@example").detailsError())
        assertEquals("invalid_email", draft(email = "ana ribeiro@example.pt").detailsError())
        assertEquals("tax_id_too_long", draft(taxId = "1".repeat(33)).detailsError())
        assertEquals("notes_too_long", draft(notes = "x".repeat(4001)).detailsError())
        assertEquals("address_too_long", draft(address = "x".repeat(301)).detailsError())
        assertEquals("postal_code_too_long", draft(postalCode = "1".repeat(21)).detailsError())
        assertEquals("city_too_long", draft(city = "x".repeat(101)).detailsError())
        assertEquals("contact_person_too_long", draft(contactPerson = "x".repeat(121)).detailsError())
    }

    @Test
    fun `staff must give a NIF and an address`() {
        assertEquals("tax_id_required", draft(address = "Rua das Flores 12").requiredError())
        assertEquals("tax_id_required", draft(taxId = " ", address = "Rua das Flores 12").requiredError())
        assertEquals("address_required", draft(taxId = "245678901").requiredError())
        assertEquals("address_required", draft(taxId = "245678901", address = "  ").requiredError())
        assertNull(draft(taxId = "245678901", address = "Rua das Flores 12").requiredError())
    }

    @Test
    fun `the tenant chooses which standard fields staff must fill in`() {
        val none = DirectoryFields(required = emptySet())
        assertNull(draft().requiredError(fields = none))

        val contact = DirectoryFields(required = setOf("email", "city", "postalCode", "contactPerson"))
        assertEquals("email_required", draft().requiredError(fields = contact))
        assertEquals("contact_person_required", draft(email = "ana@example.pt").requiredError(fields = contact))
        assertEquals("postal_code_required", draft(email = "ana@example.pt", contactPerson = "Ana").requiredError(fields = contact))
        assertEquals("city_required", draft(email = "ana@example.pt", contactPerson = "Ana", postalCode = "1200-001").requiredError(fields = contact))
        assertNull(draft(email = "ana@example.pt", contactPerson = "Ana", postalCode = "1200-001", city = "Lisboa").requiredError(fields = contact))
    }

    @Test
    fun `an update that omits a required detail keeps the stored one`() {
        val stored = Client(
            tenantId = ObjectId(), number = "CLT-001", name = "Ana Ribeiro", phone = "+351 911 222 333",
            email = "ana@example.pt", city = "Lisboa",
            createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0),
        )
        val fields = DirectoryFields(required = setOf("email", "city"))
        assertNull(draft().requiredError(stored, fields))
        assertEquals("city_required", draft(city = "").requiredError(stored, fields))
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
        assertNull(draft(address = "Rua das Flores 12").requiredError(stored))
        assertEquals("tax_id_required", draft(taxId = "", address = "Rua das Flores 12").requiredError(stored))
        assertEquals("tax_id_required", draft(address = "Rua das Flores 12").requiredError(stored.copy(taxId = null)))
    }
}
