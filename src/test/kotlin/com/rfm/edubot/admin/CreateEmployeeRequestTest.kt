package com.rfm.edubot.admin

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateEmployeeRequestTest {

    private val today = LocalDate(2026, 10, 1)

    private fun request(birthDate: String? = null, address: String? = null, taxId: String? = null) =
        CreateEmployeeRequest("Ana Costa", "+351 912 345 678", birthDate = birthDate, address = address, taxId = taxId)

    @Test
    fun `accepts missing, empty and plausible profile details`() {
        assertNull(request().detailsError(today))
        assertNull(request(birthDate = "", address = "", taxId = "").detailsError(today))
        assertNull(request(birthDate = " 1990-03-12 ", address = "Rua das Flores 12", taxId = "245678901").detailsError(today))
        assertNull(request(birthDate = "2026-10-01").detailsError(today))
    }

    @Test
    fun `decodes through the reflective lookup Ktor uses`() {
        val body = """{"name":"Ana Costa","phone":"+351 912 345 678","birthDate":"1990-03-12","taxId":"245678901"}"""
        val request = Json.decodeFromString(serializer(typeOf<CreateEmployeeRequest>()), body) as CreateEmployeeRequest
        assertEquals("1990-03-12", request.birthDate)
        assertEquals("245678901", request.taxId)
    }

    @Test
    fun `rejects malformed, future and implausibly old birth dates and oversized fields`() {
        assertEquals("invalid_birth_date", request(birthDate = "12/03/1990").detailsError(today))
        assertEquals("invalid_birth_date", request(birthDate = "1990-02-30").detailsError(today))
        assertEquals("invalid_birth_date", request(birthDate = "2026-10-02").detailsError(today))
        assertEquals("invalid_birth_date", request(birthDate = "1899-12-31").detailsError(today))
        assertEquals("address_too_long", request(address = "x".repeat(301)).detailsError(today))
        assertEquals("tax_id_too_long", request(taxId = "1".repeat(33)).detailsError(today))
    }
}
