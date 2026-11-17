package com.rfm.edubot.persona

import com.rfm.edubot.shared.SystemClock
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/** The behavior settings as the dashboard sends them; every field is optional. */
@Serializable
data class PersonaBehaviorInput(
    val botName: String? = null,
    val disclosure: String? = null,
    val language: String? = null,
    val languageStrict: Boolean = false,
    val tone: String? = null,
    val addressForm: String? = null,
    val replyLength: String? = null,
    val emoji: String? = null,
    val greeting: String? = null,
    val scope: String? = null,
    val unknown: String? = null,
    val rules: List<String> = emptyList(),
    val business: PersonaBusinessInput = PersonaBusinessInput(),
    val faq: List<PersonaFaqInput> = emptyList(),
    val capabilities: PersonaCapabilitiesInput = PersonaCapabilitiesInput(),
    val handoff: PersonaHandoffInput = PersonaHandoffInput(),
)

@Serializable
data class PersonaBusinessInput(
    val name: String? = null,
    val about: String? = null,
    val hours: String? = null,
    val location: String? = null,
    val contact: String? = null,
)

@Serializable
data class PersonaFaqInput(val question: String = "", val answer: String = "")

@Serializable
data class PersonaCapabilitiesInput(val catalog: Boolean = true, val bookings: Boolean = true, val records: Boolean = true)

/** [reasons] left out (an older page) keeps what such a persona did: see [PersonaHandoff.effectiveReasons]. */
@Serializable
data class PersonaHandoffInput(
    val enabled: Boolean = false,
    val reasons: List<String>? = null,
    val triggers: String? = null,
    val message: String? = null,
)

/** Unsaved changes to preview or test: what is set replaces the saved value. */
@Serializable
data class PersonaDraftInput(val compiledInstructions: String? = null, val behavior: PersonaBehaviorInput? = null)

/**
 * A refusal the dashboard words from [error], naming the [field] and the [limit] when there is one, and
 * for a question and answer, its position in [index].
 */
data class PersonaProblem(val error: String, val field: String? = null, val limit: Int? = null, val index: Int? = null)

sealed interface Checked<out T> {
    data class Ok<T>(val value: T) : Checked<T>
    data class Invalid(val problem: PersonaProblem) : Checked<Nothing>
}

/** Cleans what a company typed into what is stored: trimmed, blanks dropped, within [PersonaLimits]. */
object PersonaValidation {
    fun instructions(text: String): Checked<String> {
        val value = text.trim()
        return if (value.length > PersonaLimits.MAX_INSTRUCTIONS_CHARS) {
            Checked.Invalid(PersonaProblem("too_long", "compiledInstructions", PersonaLimits.MAX_INSTRUCTIONS_CHARS))
        } else {
            Checked.Ok(value)
        }
    }

    fun note(text: String): Checked<String> {
        val value = text.trim()
        return when {
            value.isEmpty() -> Checked.Invalid(PersonaProblem("content_required", "content"))
            value.length > PersonaLimits.MAX_NOTE_CHARS -> Checked.Invalid(PersonaProblem("too_long", "content", PersonaLimits.MAX_NOTE_CHARS))
            else -> Checked.Ok(value)
        }
    }

    fun behavior(input: PersonaBehaviorInput): Checked<PersonaBehavior> {
        val botName = text(input.botName, "botName", PersonaLimits.MAX_BOT_NAME_CHARS) { return it }
        val disclosure = option<PersonaDisclosure>(input.disclosure, "disclosure") { return it }
        val language = input.language?.trim()?.takeIf { it.isNotEmpty() && it != "auto" }
        if (language != null && language !in PersonaLanguages.names) return Checked.Invalid(PersonaProblem("invalid_option", "language"))
        val tone = option<PersonaTone>(input.tone, "tone") { return it }
        val addressForm = option<PersonaAddressForm>(input.addressForm, "addressForm") { return it }
        val replyLength = option<PersonaReplyLength>(input.replyLength, "replyLength") { return it }
        val emoji = option<PersonaEmoji>(input.emoji, "emoji") { return it }
        val greeting = text(input.greeting, "greeting", PersonaLimits.MAX_GREETING_CHARS) { return it }
        val scope = option<PersonaScope>(input.scope, "scope") { return it }
        val unknown = option<PersonaUnknown>(input.unknown, "unknown") { return it }

        val rules = input.rules.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (rules.size > PersonaLimits.MAX_RULES) return Checked.Invalid(PersonaProblem("too_many", "rules", PersonaLimits.MAX_RULES))
        if (rules.any { it.length > PersonaLimits.MAX_RULE_CHARS }) return Checked.Invalid(PersonaProblem("too_long", "rules", PersonaLimits.MAX_RULE_CHARS))

        val business = input.business.let { b ->
            PersonaBusiness(
                name = text(b.name, "business.name", PersonaLimits.MAX_BUSINESS_NAME_CHARS) { return it },
                about = text(b.about, "business.about", PersonaLimits.MAX_BUSINESS_ABOUT_CHARS) { return it },
                hours = text(b.hours, "business.hours", PersonaLimits.MAX_BUSINESS_FIELD_CHARS) { return it },
                location = text(b.location, "business.location", PersonaLimits.MAX_BUSINESS_FIELD_CHARS) { return it },
                contact = text(b.contact, "business.contact", PersonaLimits.MAX_BUSINESS_FIELD_CHARS) { return it },
            )
        }
        val faq = faq(input.faq) { return it }

        val reasons = input.handoff.reasons?.map { name ->
            enumValues<PersonaHandoffReason>().firstOrNull { it.name == name.trim().uppercase() }
                ?: return Checked.Invalid(PersonaProblem("invalid_option", "handoff.reasons"))
        }?.distinct()
        val triggers = text(input.handoff.triggers, "handoff.triggers", PersonaLimits.MAX_HANDOFF_CHARS) { return it }
        val message = text(input.handoff.message, "handoff.message", PersonaLimits.MAX_HANDOFF_CHARS) { return it }

        return Checked.Ok(
            PersonaBehavior(
                botName = botName,
                disclosure = disclosure,
                language = language,
                languageStrict = language != null && input.languageStrict,
                tone = tone,
                addressForm = addressForm,
                replyLength = replyLength,
                emoji = emoji,
                greeting = greeting,
                scope = scope,
                unknown = unknown,
                rules = rules,
                business = business,
                faq = faq,
                capabilities = PersonaCapabilities(input.capabilities.catalog, input.capabilities.bookings, input.capabilities.records),
                handoff = PersonaHandoff(enabled = input.handoff.enabled, reasons = reasons, triggers = triggers, message = message),
            ),
        )
    }

    /**
     * Questions and answers as they are kept: trimmed, empty pairs dropped. A pair with only one side is
     * refused with its position among what was sent, so the dashboard can point at it.
     */
    private inline fun faq(input: List<PersonaFaqInput>, invalid: (Checked.Invalid) -> Nothing): List<PersonaFaq> {
        val kept = mutableListOf<PersonaFaq>()
        input.forEachIndexed { index, pair ->
            val question = pair.question.trim()
            val answer = pair.answer.trim()
            if (question.isEmpty() && answer.isEmpty()) return@forEachIndexed
            if (question.isEmpty()) invalid(Checked.Invalid(PersonaProblem("question_required", "faq.question", index = index)))
            if (answer.isEmpty()) invalid(Checked.Invalid(PersonaProblem("answer_required", "faq.answer", index = index)))
            if (question.length > PersonaLimits.MAX_FAQ_QUESTION_CHARS) {
                invalid(Checked.Invalid(PersonaProblem("too_long", "faq.question", PersonaLimits.MAX_FAQ_QUESTION_CHARS, index)))
            }
            if (answer.length > PersonaLimits.MAX_FAQ_ANSWER_CHARS) {
                invalid(Checked.Invalid(PersonaProblem("too_long", "faq.answer", PersonaLimits.MAX_FAQ_ANSWER_CHARS, index)))
            }
            kept += PersonaFaq(question, answer)
        }
        if (kept.size > PersonaLimits.MAX_FAQ) invalid(Checked.Invalid(PersonaProblem("too_many", "faq", PersonaLimits.MAX_FAQ)))
        return kept
    }

    /** The persona a draft describes: the saved one with the draft's fields put in. */
    fun draft(saved: TenantPersona?, tenantId: ObjectId, draft: PersonaDraftInput): Checked<TenantPersona> {
        val base = saved ?: TenantPersona(tenantId = tenantId, updatedAt = SystemClock.now())
        val instructions = draft.compiledInstructions?.let { text ->
            when (val checked = instructions(text)) {
                is Checked.Invalid -> return checked
                is Checked.Ok -> checked.value
            }
        } ?: base.compiledInstructions
        val behavior = draft.behavior?.let { input ->
            when (val checked = behavior(input)) {
                is Checked.Invalid -> return checked
                is Checked.Ok -> checked.value
            }
        } ?: base.behavior
        return Checked.Ok(base.copy(compiledInstructions = instructions, behavior = behavior))
    }

    private inline fun text(value: String?, field: String, max: Int, invalid: (Checked.Invalid) -> Nothing): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > max) invalid(Checked.Invalid(PersonaProblem("too_long", field, max)))
        return trimmed
    }

    private inline fun <reified E : Enum<E>> option(value: String?, field: String, invalid: (Checked.Invalid) -> Nothing): E? {
        val name = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return enumValues<E>().firstOrNull { it.name == name.uppercase() } ?: invalid(Checked.Invalid(PersonaProblem("invalid_option", field)))
    }
}
