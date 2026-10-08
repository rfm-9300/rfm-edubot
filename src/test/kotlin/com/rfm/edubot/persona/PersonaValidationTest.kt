package com.rfm.edubot.persona

import kotlinx.datetime.Clock
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What a company types into the persona settings, cleaned into what is stored, or refused with a reason. */
class PersonaValidationTest {
    private fun ok(input: PersonaBehaviorInput) = assertIs<Checked.Ok<PersonaBehavior>>(PersonaValidation.behavior(input)).value
    private fun refused(input: PersonaBehaviorInput) = assertIs<Checked.Invalid>(PersonaValidation.behavior(input)).problem

    @Test
    fun `text is trimmed, blanks become unset and option names are case-insensitive`() {
        val behavior = ok(
            PersonaBehaviorInput(
                botName = "  Sofia ",
                language = "pt-PT",
                tone = "friendly",
                addressForm = "FORMAL",
                replyLength = " short ",
                emoji = "",
                greeting = "   ",
                handoff = PersonaHandoffInput(enabled = true, triggers = " reembolsos ", message = ""),
            ),
        )
        assertEquals("Sofia", behavior.botName)
        assertEquals("pt-PT", behavior.language)
        assertEquals(PersonaTone.FRIENDLY, behavior.tone)
        assertEquals(PersonaAddressForm.FORMAL, behavior.addressForm)
        assertEquals(PersonaReplyLength.SHORT, behavior.replyLength)
        assertNull(behavior.emoji)
        assertNull(behavior.greeting)
        assertEquals(PersonaHandoff(enabled = true, triggers = "reembolsos", message = null), behavior.handoff)
    }

    @Test
    fun `an empty form is an empty persona`() {
        val behavior = ok(PersonaBehaviorInput())
        assertEquals(PersonaBehavior(), behavior)
        assertEquals(true, behavior.isEmpty)
    }

    @Test
    fun `the customer's language is no language at all, and strict only holds with a language`() {
        assertNull(ok(PersonaBehaviorInput(language = "auto", languageStrict = true)).language)
        assertEquals(false, ok(PersonaBehaviorInput(language = "auto", languageStrict = true)).languageStrict)
        assertEquals(true, ok(PersonaBehaviorInput(language = "en", languageStrict = true)).languageStrict)
        assertEquals(PersonaProblem("invalid_option", "language"), refused(PersonaBehaviorInput(language = "klingon")))
    }

    @Test
    fun `unknown options are refused by field`() {
        assertEquals(PersonaProblem("invalid_option", "tone"), refused(PersonaBehaviorInput(tone = "sarcastic")))
        assertEquals(PersonaProblem("invalid_option", "addressForm"), refused(PersonaBehaviorInput(addressForm = "royal")))
        assertEquals(PersonaProblem("invalid_option", "replyLength"), refused(PersonaBehaviorInput(replyLength = "endless")))
        assertEquals(PersonaProblem("invalid_option", "emoji"), refused(PersonaBehaviorInput(emoji = "all")))
    }

    @Test
    fun `rules drop blanks and duplicates, and are capped in number and length`() {
        assertEquals(listOf("Sem descontos.", "Responda em 24h."), ok(PersonaBehaviorInput(rules = listOf(" Sem descontos. ", "", "sem DESCONTOS.", "Responda em 24h."))).rules)
        assertEquals(
            PersonaProblem("too_many", "rules", PersonaLimits.MAX_RULES),
            refused(PersonaBehaviorInput(rules = (1..PersonaLimits.MAX_RULES + 1).map { "Regra $it" })),
        )
        assertEquals(
            PersonaProblem("too_long", "rules", PersonaLimits.MAX_RULE_CHARS),
            refused(PersonaBehaviorInput(rules = listOf("x".repeat(PersonaLimits.MAX_RULE_CHARS + 1)))),
        )
    }

    @Test
    fun `long texts are refused with the field and the limit`() {
        assertEquals(PersonaProblem("too_long", "botName", PersonaLimits.MAX_BOT_NAME_CHARS), refused(PersonaBehaviorInput(botName = "n".repeat(61))))
        assertEquals(PersonaProblem("too_long", "greeting", PersonaLimits.MAX_GREETING_CHARS), refused(PersonaBehaviorInput(greeting = "g".repeat(401))))
        assertEquals(
            PersonaProblem("too_long", "handoff.triggers", PersonaLimits.MAX_HANDOFF_CHARS),
            refused(PersonaBehaviorInput(handoff = PersonaHandoffInput(triggers = "t".repeat(501)))),
        )
        assertEquals(
            PersonaProblem("too_long", "handoff.message", PersonaLimits.MAX_HANDOFF_CHARS),
            refused(PersonaBehaviorInput(handoff = PersonaHandoffInput(message = "m".repeat(501)))),
        )
    }

    @Test
    fun `instructions and notes are trimmed and bounded`() {
        assertEquals(Checked.Ok("Olá"), PersonaValidation.instructions("  Olá \n"))
        assertEquals(Checked.Ok(""), PersonaValidation.instructions("   "))
        assertEquals(
            Checked.Invalid(PersonaProblem("too_long", "compiledInstructions", PersonaLimits.MAX_INSTRUCTIONS_CHARS)),
            PersonaValidation.instructions("x".repeat(PersonaLimits.MAX_INSTRUCTIONS_CHARS + 1)),
        )
        assertEquals(Checked.Invalid(PersonaProblem("content_required", "content")), PersonaValidation.note(" \n "))
        assertEquals(
            Checked.Invalid(PersonaProblem("too_long", "content", PersonaLimits.MAX_NOTE_CHARS)),
            PersonaValidation.note("x".repeat(PersonaLimits.MAX_NOTE_CHARS + 1)),
        )
    }

    @Test
    fun `a draft puts unsaved fields over the saved persona and leaves the rest`() {
        val tenantId = ObjectId()
        val saved = TenantPersona(tenantId = tenantId, compiledInstructions = "Guardado.", behavior = PersonaBehavior(botName = "Ana"), version = 4, updatedAt = Clock.System.now())

        val onlyText = assertIs<Checked.Ok<TenantPersona>>(PersonaValidation.draft(saved, tenantId, PersonaDraftInput(compiledInstructions = " Rascunho. "))).value
        assertEquals("Rascunho.", onlyText.compiledInstructions)
        assertEquals("Ana", onlyText.behavior.botName)
        assertEquals(4, onlyText.version)

        val onlySettings = assertIs<Checked.Ok<TenantPersona>>(PersonaValidation.draft(saved, tenantId, PersonaDraftInput(behavior = PersonaBehaviorInput(botName = "Rita")))).value
        assertEquals("Guardado.", onlySettings.compiledInstructions)
        assertEquals("Rita", onlySettings.behavior.botName)

        val fromNothing = assertIs<Checked.Ok<TenantPersona>>(PersonaValidation.draft(null, tenantId, PersonaDraftInput(compiledInstructions = "Novo."))).value
        assertEquals(tenantId, fromNothing.tenantId)
        assertEquals("Novo.", fromNothing.compiledInstructions)

        assertIs<Checked.Invalid>(PersonaValidation.draft(saved, tenantId, PersonaDraftInput(behavior = PersonaBehaviorInput(tone = "loud"))))
    }
}
