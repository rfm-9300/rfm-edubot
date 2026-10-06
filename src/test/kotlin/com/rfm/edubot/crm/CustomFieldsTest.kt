package com.rfm.edubot.crm

import com.rfm.edubot.tenant.model.CustomField
import com.rfm.edubot.tenant.model.CustomFieldType
import com.rfm.edubot.tenant.model.DirectoryFields
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomFieldsTest {

    private val keys = generateSequence(1) { it + 1 }.map { "cf_key${it.toString().padStart(5, '0')}" }.iterator()
    private fun change(current: DirectoryFields = ClientFields.DEFAULT, required: List<String> = listOf("taxId"), vararg drafts: CustomFields.Draft) =
        CustomFields.change(current, ClientFields.REQUIRABLE.keys, required, drafts.toList()) { keys.next() }

    private fun valid(change: CustomFields.Change) = (change as CustomFields.Change.Valid).fields
    private fun error(change: CustomFields.Change) = (change as CustomFields.Change.Invalid).error

    @Test
    fun `new fields get fresh keys, kept fields their key and type, and only requirable standard fields stay required`() {
        val first = valid(
            change(
                required = listOf("taxId", "name", "notes", "nonsense", "city"),
                drafts = arrayOf(
                    CustomFields.Draft(null, " Pet's name ", "text", required = true, showInList = true),
                    CustomFields.Draft(null, "Size", "select", options = listOf(" Small ", "Large", "", "small")),
                ),
            ),
        )
        assertEquals(setOf("taxId", "city"), first.required)
        assertEquals(listOf("cf_key00001", "cf_key00002"), first.custom.map { it.key })
        assertEquals(CustomField("cf_key00001", "Pet's name", CustomFieldType.TEXT, required = true, showInList = true), first.custom[0])
        assertEquals(listOf("Small", "Large"), first.custom[1].options)

        val renamed = valid(
            change(
                current = first,
                drafts = arrayOf(
                    CustomFields.Draft("cf_key00002", "Dog size", "number", options = listOf("Small")),
                    CustomFields.Draft("cf_made_up", "Visits", "number"),
                ),
            ),
        )
        assertEquals(listOf("cf_key00002", "cf_key00003"), renamed.custom.map { it.key })
        assertEquals(CustomFieldType.SELECT, renamed.custom[0].type)
        assertEquals("Dog size", renamed.custom[0].label)
        assertEquals(CustomFieldType.NUMBER, renamed.custom[1].type)
    }

    @Test
    fun `a yes or no field is never required, and options belong to choices only`() {
        val fields = valid(
            change(
                drafts = arrayOf(
                    CustomFields.Draft(null, "Newsletter", "checkbox", required = true, options = listOf("x")),
                    CustomFields.Draft(null, "Since", "date", options = listOf("x")),
                ),
            ),
        ).custom
        assertEquals(listOf(false, false), fields.map { it.required })
        assertEquals(listOf(emptyList<String>(), emptyList()), fields.map { it.options })
    }

    @Test
    fun `a bad settings change is refused with a stable code`() {
        assertEquals("label_required", error(change(drafts = arrayOf(CustomFields.Draft(null, "  ", "text")))))
        assertEquals("label_too_long", error(change(drafts = arrayOf(CustomFields.Draft(null, "x".repeat(61), "text")))))
        assertEquals("duplicate_label", error(change(drafts = arrayOf(CustomFields.Draft(null, "Pet", "text"), CustomFields.Draft(null, " pet ", "number")))))
        assertEquals("invalid_type", error(change(drafts = arrayOf(CustomFields.Draft(null, "Pet", "color")))))
        assertEquals("options_required", error(change(drafts = arrayOf(CustomFields.Draft(null, "Size", "select", options = listOf(" "))))))
        assertEquals("too_many_options", error(change(drafts = arrayOf(CustomFields.Draft(null, "Size", "select", options = (1..31).map { "o$it" })))))
        assertEquals("option_too_long", error(change(drafts = arrayOf(CustomFields.Draft(null, "Size", "select", options = listOf("x".repeat(61)))))))
        assertEquals("too_many_fields", error(change(drafts = Array(21) { CustomFields.Draft(null, "Field $it", "text") })))
    }

    private val pet = CustomField("cf_pet00001", "Pet", CustomFieldType.TEXT, required = true)
    private val visits = CustomField("cf_vis00001", "Visits", CustomFieldType.NUMBER)
    private val since = CustomField("cf_sin00001", "Since", CustomFieldType.DATE)
    private val size = CustomField("cf_siz00001", "Size", CustomFieldType.SELECT, options = listOf("Small", "Large"))
    private val news = CustomField("cf_new00001", "Newsletter", CustomFieldType.CHECKBOX)
    private val all = listOf(pet, visits, since, size, news)

    private fun values(input: Map<String, JsonElement>?, stored: Map<String, JsonPrimitive> = emptyMap()) = CustomFields.values(all, input, stored)
    private fun changes(input: Map<String, JsonElement>?, stored: Map<String, JsonPrimitive> = emptyMap()) =
        (values(input, stored) as CustomFields.Values.Valid).changes

    @Test
    fun `typed values are cleaned and stored by type, and unknown keys are ignored`() {
        val result = changes(
            mapOf(
                pet.key to JsonPrimitive("  Rex "),
                visits.key to JsonPrimitive("12,5"),
                since.key to JsonPrimitive("2026-10-06"),
                size.key to JsonPrimitive("Large"),
                news.key to JsonPrimitive(true),
                "cf_gone0001" to JsonPrimitive("ignored"),
            ),
        )
        assertEquals(
            mapOf(
                pet.key to JsonPrimitive("Rex"),
                visits.key to JsonPrimitive(12.5),
                since.key to JsonPrimitive("2026-10-06"),
                size.key to JsonPrimitive("Large"),
                news.key to JsonPrimitive(true),
            ),
            result,
        )
        assertEquals(JsonPrimitive(3.0), changes(mapOf(pet.key to JsonPrimitive("Rex"), visits.key to JsonPrimitive(3)))[visits.key])
    }

    @Test
    fun `null, blank and an unticked box clear a value, and values left out stay as stored`() {
        val stored = mapOf(pet.key to JsonPrimitive("Rex"), visits.key to JsonPrimitive(2.0), size.key to JsonPrimitive("Small"), news.key to JsonPrimitive(true))
        assertEquals(
            mapOf(visits.key to null, size.key to null, news.key to null),
            changes(mapOf(visits.key to JsonNull, size.key to JsonPrimitive(" "), news.key to JsonPrimitive(false)), stored),
        )
        assertEquals(emptyMap(), changes(null, stored))
        assertEquals(emptyMap(), changes(mapOf(pet.key to JsonPrimitive("Rex"), visits.key to JsonPrimitive(2)), stored))
    }

    @Test
    fun `a value that no longer fits is kept while it comes back unchanged`() {
        val stored = mapOf(pet.key to JsonPrimitive("Rex"), size.key to JsonPrimitive("Medium"))
        assertEquals(emptyMap(), changes(mapOf(size.key to JsonPrimitive("Medium")), stored))
        assertEquals(CustomFields.Values.Invalid("custom_field_invalid", size.key), values(mapOf(size.key to JsonPrimitive("Huge")), stored))
    }

    @Test
    fun `wrong values and missing required ones name the field`() {
        val rex = pet.key to JsonPrimitive("Rex")
        assertEquals(CustomFields.Values.Invalid("custom_field_required", pet.key), values(null))
        assertEquals(CustomFields.Values.Invalid("custom_field_required", pet.key), values(mapOf(pet.key to JsonPrimitive(" ")), mapOf(rex)))
        assertEquals(CustomFields.Values.Invalid("custom_field_too_long", pet.key), values(mapOf(pet.key to JsonPrimitive("x".repeat(501)))))
        assertEquals(CustomFields.Values.Invalid("custom_field_invalid", visits.key), values(mapOf(rex, visits.key to JsonPrimitive("twelve"))))
        assertEquals(CustomFields.Values.Invalid("custom_field_invalid", since.key), values(mapOf(rex, since.key to JsonPrimitive("06/10/2026"))))
        assertEquals(CustomFields.Values.Invalid("custom_field_invalid", size.key), values(mapOf(rex, size.key to JsonPrimitive("small"))))
        assertEquals(CustomFields.Values.Invalid("custom_field_invalid", news.key), values(mapOf(rex, news.key to JsonPrimitive("maybe"))))
    }

    @Test
    fun `keys are only ever the generated shape`() {
        repeat(50) { assertTrue(CustomFields.isKey(CustomFields.newKey())) }
        listOf("cf_", "cf_ABCDEFGH", "cf_abc.defg", "cf_abcdefgh\$", "name", "cf_abcdefghi").forEach { assertTrue(!CustomFields.isKey(it), it) }
    }
}
