package com.rfm.edubot.mobile.core.localization

import com.rfm.edubot.mobile.core.common.AppError
import com.rfm.edubot.mobile.core.common.SessionError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalogs used to be one data class copied per language, so a locale that forgot a string
 * silently inherited another language's wording. These tests make that a build failure.
 */
class CatalogTest {
    private val english = Localization.catalog(AppLocale.English)

    @Test
    fun `every locale carries exactly the English keys`() {
        AppLocale.entries.filterNot { it == AppLocale.English }.forEach { locale ->
            val catalog = Localization.catalog(locale)
            assertEquals(
                emptySet(),
                english.keys - catalog.keys,
                "${locale.tag} is missing keys",
            )
            assertEquals(
                emptySet(),
                catalog.keys - english.keys,
                "${locale.tag} has keys English does not (a typo, or a string English forgot)",
            )
        }
    }

    @Test
    fun `no translation is blank`() {
        AppLocale.entries.forEach { locale ->
            Localization.catalog(locale).forEach { (key, value) ->
                assertTrue(value.isNotBlank(), "${locale.tag} has a blank value for $key")
            }
        }
    }

    @Test
    fun `placeholders match English`() {
        val placeholder = Regex("\\{([a-zA-Z]+)}")
        AppLocale.entries.filterNot { it == AppLocale.English }.forEach { locale ->
            Localization.catalog(locale).forEach { (key, value) ->
                val expected = placeholder.findAll(english.getValue(key)).map { it.groupValues[1] }.toSet()
                val actual = placeholder.findAll(value).map { it.groupValues[1] }.toSet()
                assertEquals(expected, actual, "${locale.tag} changed the placeholders of $key")
            }
        }
    }

    @Test
    fun `every module id the backend can send has a name and a subtitle`() {
        val modules = listOf(
            "overview", "conversations", "contacts", "instagram", "clients", "services", "quotes",
            "invoices", "suppliers", "employees", "payments", "catalog", "bookings", "persona",
            "ai-assistant", "agents", "settings", "timesheets", "my-hours", "my-services", "my-sites", "email",
            "projects", "site-diary", "site-issues", "change-orders", "measurements",
        )
        AppLocale.entries.forEach { locale ->
            val strings = Localization.of(locale)
            modules.forEach { id ->
                assertTrue(strings.module(id) != "module.$id", "${locale.tag} has no name for $id")
                assertTrue(
                    strings.moduleSubtitle(id) != "module.$id.subtitle",
                    "${locale.tag} has no subtitle for $id",
                )
            }
        }
    }

    @Test
    fun `a rejected call names the field the backend complained about`() {
        val strings = Localization.of(AppLocale.English)
        assertEquals("A NIF is required.", strings.error(AppError.Rejected("tax_id_required")))
        assertEquals("An address is required.", strings.error(AppError.Rejected("address_required")))
    }

    @Test
    fun `an unknown error code still reads as a sentence`() {
        val strings = Localization.of(AppLocale.Portuguese)
        assertEquals(strings[Txt.ERROR_REJECTED], strings.error(AppError.Rejected("something_new")))
    }

    @Test
    fun `sign-in errors are translated rather than always English`() {
        val portuguese = Localization.of(AppLocale.Portuguese)
        val english = Localization.of(AppLocale.English)
        SessionError.entries.forEach { error ->
            assertTrue(portuguese.error(error).isNotBlank())
            assertTrue(
                portuguese.error(error) != english.error(error),
                "$error reads the same in both languages",
            )
        }
    }

    @Test
    fun `locale tags from the tenant and the device both resolve`() {
        assertEquals(AppLocale.Portuguese, AppLocale.of("pt-PT"))
        assertEquals(AppLocale.Portuguese, AppLocale.of("pt_BR"))
        assertEquals(AppLocale.Spanish, AppLocale.of("es-419"))
        assertEquals(AppLocale.English, AppLocale.of("en-GB"))
        assertEquals(AppLocale.English, AppLocale.of(null))
        assertEquals(AppLocale.English, AppLocale.of("de"))
    }

    @Test
    fun `plurals pick a form and substitute the count`() {
        val strings = Localization.of(AppLocale.English)
        assertEquals("1 message", strings.plural(Txt.INBOX_MESSAGES_COUNT, 1))
        assertEquals("4 messages", strings.plural(Txt.INBOX_MESSAGES_COUNT, 4))
    }

    @Test
    fun `a status with no copy still renders readably`() {
        val strings = Localization.of(AppLocale.English)
        assertEquals("Paid", strings.status("PAGA"))
        assertEquals("Something_new", strings.status("SOMETHING_NEW"))
    }

    @Test
    fun `the feature copy never overrides a string the base catalogs already have`() {
        assertEquals(emptySet(), englishCatalog.keys intersect featureEnglish.keys)
        assertEquals(emptySet(), portugueseCatalog.keys intersect featurePortuguese.keys)
        assertEquals(emptySet(), spanishCatalog.keys intersect featureSpanish.keys)
    }

    @Test
    fun `a record's own vocabulary wins over the generic status that still covers the rest`() {
        val portuguese = Localization.of(AppLocale.Portuguese)
        assertEquals("Por verificar", portuguese.label("building.issueStatus", "RESOLVED"))
        assertEquals("Por faturar", portuguese.label("building.measurementStatus", "APPROVED"))
        assertEquals(portuguese.status("PAID"), portuguese.label("building.issueStatus", "PAID"))
    }

    @Test
    fun `every Building value the backend sends has a label in every language`() {
        val vocabulary = mapOf(
            "building.status" to listOf("PLANNING", "ACTIVE", "ON_HOLD", "COMPLETED", "CANCELLED"),
            "building.health" to listOf("PLANNING", "ON_TRACK", "AT_RISK", "DELAYED", "ON_HOLD", "DONE", "CANCELLED"),
            "building.phaseState" to listOf("NOT_STARTED", "IN_PROGRESS", "DONE", "LATE"),
            "building.weather" to listOf("CLEAR", "CLOUDY", "RAIN", "WIND", "STORM", "FOG"),
            "building.issueKind" to listOf("PUNCH", "DEFECT", "SAFETY", "OTHER"),
            "building.priority" to listOf("LOW", "MEDIUM", "HIGH"),
            "building.issueStatus" to listOf("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"),
            "building.changeKind" to listOf("ADDITION", "OMISSION"),
            "building.changeStatus" to listOf("DRAFT", "SENT", "APPROVED", "REJECTED"),
            "building.measurementStatus" to listOf("DRAFT", "APPROVED", "INVOICED"),
        )
        AppLocale.entries.forEach { locale ->
            val catalog = Localization.catalog(locale)
            vocabulary.forEach { (prefix, values) ->
                values.forEach { value -> assertTrue("$prefix.$value" in catalog, "${locale.tag} has no $prefix.$value") }
            }
        }
    }
}
