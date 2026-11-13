package com.rfm.edubot.mobile.feature.crm

import com.rfm.edubot.mobile.core.model.CustomField
import com.rfm.edubot.mobile.core.model.DirectoryFields
import com.rfm.edubot.mobile.core.model.StandardField
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The form used to require NIF and address whatever the company chose. These pin that it follows
 * the company's fields instead, and sends what the backend needs to keep or clear each one.
 */
class ClientFormTest {
    private val optionalNif = DirectoryFields(
        standard = listOf(
            StandardField(DirectoryFields.NAME, required = true, locked = true),
            StandardField(DirectoryFields.PHONE, required = true, locked = true),
            StandardField(DirectoryFields.TAX_ID),
            StandardField(DirectoryFields.EMAIL, required = true),
            StandardField(DirectoryFields.ADDRESS),
        ),
        custom = listOf(
            CustomField("cf_gate", "Gate code", CustomField.TEXT, required = true),
            CustomField("cf_vip", "VIP", CustomField.CHECKBOX),
            CustomField("cf_size", "Size", CustomField.SELECT, options = listOf("S", "M")),
        ),
    )

    private fun state(values: Map<String, String> = emptyMap(), custom: Map<String, String> = emptyMap()) =
        ClientFormState(fields = optionalNif, values = values, custom = custom)

    @Test
    fun `a company that made the NIF optional can save a client without one`() {
        val form = state(
            values = mapOf(DirectoryFields.NAME to "Ana", DirectoryFields.PHONE to "912", DirectoryFields.EMAIL to "ana@example.test"),
            custom = mapOf("cf_gate" to "1234"),
        )
        assertTrue(form.canSave, "missing: ${form.missing}")
    }

    @Test
    fun `what the company requires has to be filled in first`() {
        val form = state(values = mapOf(DirectoryFields.NAME to "Ana", DirectoryFields.PHONE to "912"))
        assertEquals(setOf(DirectoryFields.EMAIL, "cf_gate"), form.missing)
        assertFalse(form.canSave)
    }

    @Test
    fun `a yes or no field is never required because unticked is an answer`() {
        val fields = optionalNif.copy(custom = listOf(CustomField("cf_vip", "VIP", CustomField.CHECKBOX, required = true)))
        val form = ClientFormState(
            fields = fields,
            values = mapOf(DirectoryFields.NAME to "Ana", DirectoryFields.PHONE to "912", DirectoryFields.EMAIL to "a@b.test"),
        )
        assertTrue(form.canSave)
    }

    @Test
    fun `the request sends every shown field so an erased one is cleared`() {
        val request = state(
            values = mapOf(DirectoryFields.NAME to " Ana ", DirectoryFields.PHONE to "912", DirectoryFields.TAX_ID to ""),
            custom = mapOf("cf_gate" to "1234", "cf_vip" to "true"),
        ).toRequest()
        assertEquals("Ana", request.name)
        assertEquals("", request.taxId, "an empty string clears the NIF on an edit; null would keep it")
        assertEquals("", request.address, "the backend clears an address that isn't sent at all")
        assertEquals(JsonPrimitive("1234"), request.customFields?.get("cf_gate"))
        assertEquals(JsonPrimitive(true), request.customFields?.get("cf_vip"))
        assertEquals(JsonPrimitive(""), request.customFields?.get("cf_size"), "an unpicked choice is cleared")
    }

    @Test
    fun `stored custom values read back as the form edits them`() {
        assertEquals("true", JsonPrimitive(true).asText())
        assertEquals("", JsonPrimitive(false).asText())
        assertEquals("12", JsonPrimitive(12.0).asText())
        assertEquals("12.5", JsonPrimitive(12.5).asText())
        assertEquals("2026-11-10", JsonPrimitive("2026-11-10").asText())
    }

    @Test
    fun `until the company's fields arrive the form asks what the backend asked before companies chose`() {
        val form = ClientFormState(values = mapOf(DirectoryFields.NAME to "Ana", DirectoryFields.PHONE to "912"))
        assertEquals(setOf(DirectoryFields.TAX_ID, DirectoryFields.ADDRESS), form.missing)
        assertEquals(
            listOf("taxId", "email", "contactPerson", "address", "postalCode", "city"),
            form.detailFields,
        )
    }
}
