package com.rfm.edubot.persona

import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.tenant.model.Platform
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * Turns a company's persona into what the model reads. The WhatsApp/Instagram/website pipeline and the
 * dashboard's persona test both build their system messages here, so a test chat sees exactly what
 * customers get.
 */
object PersonaPrompt {
    /** Where a persona is used: a customer chat, or an agent writing one message on the company's behalf. */
    enum class Purpose { CHAT, COMPOSE }

    const val HANDOFF_TOOL = "handoff_to_human"

    val handoffTool = ToolDefinition(
        name = HANDOFF_TOOL,
        description = "Pass this conversation to a person on the company's team. Your replies pause in this conversation until the team resumes them.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "Why a person should take over, in a few words, for the team")
                }
            }
            putJsonArray("required") { add("reason") }
        },
    )

    fun personaBlock(persona: TenantPersona?, purpose: Purpose = Purpose.CHAT): String? =
        personaBlock(persona?.behavior ?: PersonaBehavior(), persona?.compiledInstructions.orEmpty(), purpose)

    /**
     * The `<persona>` block, or null when the company set nothing. Instructions alone come back exactly as
     * written, so a company that never touches the settings sees no change in what the model reads. The
     * settings, rules, business facts and questions come first, in that order, and the instructions last.
     */
    fun personaBlock(behavior: PersonaBehavior, instructions: String, purpose: Purpose = Purpose.CHAT): String? {
        val parts = listOfNotNull(
            settingsText(behavior, purpose),
            rulesText(behavior),
            businessText(behavior.business),
            faqText(behavior.faq),
            instructions.trim().takeIf { it.isNotEmpty() },
        )
        if (parts.isEmpty()) return null
        return "<persona>\n${parts.joinToString("\n\n")}\n</persona>"
    }

    /**
     * Every system message a customer chat starts with, in order: the persona (or the neutral identity),
     * the platform rules, the date, the CRM rules for the company's modules, bookings and the handoff.
     * [modules] are the ones the bot may use: [botModules] of the company's.
     */
    fun customerSystemMessages(
        persona: TenantPersona?,
        modules: Set<String>,
        timezoneId: String,
        bookingNote: String? = null,
        handoff: Boolean = handsOff(persona),
    ): List<ChatMessage> = buildList {
        add(ChatMessage(role = "system", content = personaBlock(persona) ?: SystemPrompts.DEFAULT_IDENTITY))
        add(ChatMessage(role = "system", content = SystemPrompts.CUSTOMER_GUARDRAILS))
        add(ChatMessage(role = "system", content = SystemPrompts.currentDateTimeContext(timezoneId)))
        SystemPrompts.crmPromptFor(modules)?.let { add(ChatMessage(role = "system", content = it)) }
        bookingNote?.let { add(ChatMessage(role = "system", content = it)) }
        if (handoff) handoffNote(persona?.behavior)?.let { add(ChatMessage(role = "system", content = it)) }
    }

    /** The company's [modules] whose tools the bot may use in a customer chat: the ones its persona didn't turn off. */
    fun botModules(persona: TenantPersona?, modules: Set<String>): Set<String> =
        persona?.behavior?.capabilities?.allowed(modules) ?: modules

    /** Whether the model may hand conversations to a person. */
    fun handsOff(persona: TenantPersona?): Boolean = persona?.behavior?.handoff?.enabled == true

    /**
     * Whether a chat on [platform] may be handed over: only where the team answers from the inbox. Website
     * chats are read-only there, so a handover would leave the visitor with nobody to reply.
     */
    fun handsOff(persona: TenantPersona?, platform: Platform): Boolean = handsOff(persona) && platform != Platform.WEB

    fun handoffNote(handoff: PersonaHandoff?): String? = handoffNote(PersonaBehavior(handoff = handoff ?: PersonaHandoff()))

    /**
     * When and how to hand over: the company's situations (or the usual ones when it left none) and, when it
     * asked for it, questions the bot can't answer.
     */
    fun handoffNote(behavior: PersonaBehavior?): String? {
        val handoff = behavior?.handoff ?: return null
        if (!handoff.enabled) return null
        val reasons = handoff.effectiveReasons().toMutableList()
        if (behavior.unknown == PersonaUnknown.HAND_OVER && PersonaHandoffReason.CANT_HELP !in reasons) reasons += PersonaHandoffReason.CANT_HELP
        val custom = handoff.triggers?.trim()?.takeIf { it.isNotEmpty() }
        val situations = (reasons.map(::reasonText) + listOfNotNull(custom)).ifEmpty { PersonaHandoff.DEFAULT_REASONS.map(::reasonText) }
        val message = handoff.message?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Handing over to a person: call $HANDOFF_TOOL, with a short reason, when:\n")
            append(situations.joinToString("\n") { "- $it" })
            append("\nIt pauses your replies in this conversation and alerts the company's team. ")
            if (message != null) {
                append("The customer then gets the company's own handover message, so don't write a reply of your own after calling it. ")
            } else {
                append("After calling it, tell the customer briefly that a person from the team will continue here; don't promise a time. ")
            }
            append("Never say you handed the conversation over without calling $HANDOFF_TOOL.")
        }
    }

    fun estimateTokens(text: String?): Int = if (text.isNullOrBlank()) 0 else (text.length + 3) / 4

    /** How to talk and behave. An agent writing one message keeps its own voice, so it only takes the form of address. */
    private fun settingsText(behavior: PersonaBehavior, purpose: Purpose): String? {
        val chat = purpose == Purpose.CHAT
        val settings = buildList {
            if (chat) behavior.botName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("Your name is $it. Use it when you introduce yourself or someone asks who you are.")
            }
            if (chat) behavior.disclosure?.let { add(disclosureText(it)) }
            if (chat) languageLine(behavior)?.let { add(it) }
            if (chat) behavior.tone?.let { add("Tone: ${toneText(it)}") }
            behavior.addressForm?.let { add(addressText(it)) }
            if (chat) behavior.replyLength?.let { add("Length: ${lengthText(it)}") }
            if (chat) behavior.emoji?.let { add("Emoji: ${emojiText(it)}") }
            if (chat) behavior.greeting?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("Greeting: when the conversation has no earlier messages, open your reply with a greeting like this one, adapted naturally: \"$it\"")
            }
            if (chat) behavior.scope?.let { add("Topics: ${scopeText(it)}") }
            if (chat) behavior.unknown?.let { add("When you don't know: ${unknownText(it, behavior)}") }
        }
        if (settings.isEmpty()) return null
        return "How the company wants you to talk and behave (these settings win over the instructions below when they disagree):\n" +
            settings.joinToString("\n") { "- $it" }
    }

    private fun rulesText(behavior: PersonaBehavior): String? {
        val rules = behavior.rules.map { it.trim() }.filter { it.isNotEmpty() }
        if (rules.isEmpty()) return null
        return "Rules you always follow:\n" + rules.joinToString("\n") { "- $it" }
    }

    /** The company's facts, a line each; a fact written on several lines keeps them, indented under its label. */
    private fun businessText(business: PersonaBusiness): String? {
        val facts = business.facts()
        if (facts.isEmpty()) return null
        return "About the company (facts to use as written; they win over the instructions below):\n" +
            facts.joinToString("\n") { (key, value) ->
                val lines = value.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val label = BUSINESS_LABELS.getValue(key)
                if (lines.size == 1) "- $label: ${lines[0]}" else "- $label:\n" + lines.joinToString("\n") { "  $it" }
            }
    }

    private fun faqText(faq: List<PersonaFaq>): String? {
        if (faq.isEmpty()) return null
        return "Questions customers ask, with the company's answers. When a customer asks one of these, in any words, give its answer, adapted to the conversation and its language, without changing the facts:\n" +
            faq.joinToString("\n\n") { "Q: ${it.question.trim()}\nA: ${it.answer.trim()}" }
    }

    private val BUSINESS_LABELS = mapOf(
        "name" to "Name",
        "about" to "What it does",
        "hours" to "Opening hours",
        "location" to "Location",
        "contact" to "Contact",
    )

    private fun reasonText(reason: PersonaHandoffReason): String = when (reason) {
        PersonaHandoffReason.ASKS_FOR_PERSON -> "the customer asks to talk to a person"
        PersonaHandoffReason.UPSET -> "the customer is upset or complaining"
        PersonaHandoffReason.CANT_HELP -> "the customer needs something you can't do, or asks something you can't answer from what you know"
        PersonaHandoffReason.PAYMENTS -> "the customer asks about a payment, a refund or an invoice"
        PersonaHandoffReason.URGENT -> "the customer has an urgent problem"
    }

    private fun disclosureText(disclosure: PersonaDisclosure): String = when (disclosure) {
        PersonaDisclosure.WHEN_ASKED ->
            "Being an AI: when someone asks whether they are talking to a person or a bot, say honestly that you are the company's virtual assistant. Never claim to be a person."
        PersonaDisclosure.FIRST_REPLY ->
            "Being an AI: in your first reply of a new conversation, say you are the company's virtual assistant; after that, only when asked. Never claim to be a person."
    }

    private fun scopeText(scope: PersonaScope): String = when (scope) {
        PersonaScope.BUSINESS ->
            "only help with the company, its services and the customer's dealings with it. Politely decline anything else (general knowledge, homework, other companies) in one sentence and offer help with what the company does."
        PersonaScope.ANYTHING ->
            "you may answer general questions briefly, then steer the conversation back to how the company can help."
    }

    private fun unknownText(unknown: PersonaUnknown, behavior: PersonaBehavior): String = when {
        unknown == PersonaUnknown.SHARE_CONTACT ->
            "say so plainly, don't guess, and give the customer the company's contact details so they can ask directly."
        unknown == PersonaUnknown.HAND_OVER && behavior.handoff.enabled ->
            "say so plainly, don't guess, and hand the conversation to a person as described in the handover note."
        else -> "say so plainly, don't guess, and offer to check with the team."
    }

    private fun languageLine(behavior: PersonaBehavior): String? {
        val name = behavior.language?.let { PersonaLanguages.names[it] } ?: return null
        return if (behavior.languageStrict) {
            "Language: always reply in $name, even when the customer writes in another language."
        } else {
            "Language: reply in $name; when the customer writes in another language, reply in theirs."
        }
    }

    private fun toneText(tone: PersonaTone): String = when (tone) {
        PersonaTone.FRIENDLY -> "friendly and warm, like a helpful person at the front desk."
        PersonaTone.PROFESSIONAL -> "professional and clear: polite and precise, no slang."
        PersonaTone.FORMAL -> "formal and courteous."
        PersonaTone.CASUAL -> "casual and relaxed, like chatting with a regular customer."
        PersonaTone.EMPATHETIC -> "empathetic and reassuring: acknowledge how the customer feels before you solve the problem."
    }

    private fun addressText(form: PersonaAddressForm): String = when (form) {
        PersonaAddressForm.INFORMAL ->
            "Address the customer informally (for example \"tu\" in European Portuguese, \"você\" in Brazilian Portuguese, \"tú\" in Spanish, \"tu\" in French, \"du\" in German)."
        PersonaAddressForm.FORMAL ->
            "Address the customer formally (for example \"o senhor\" / \"a senhora\" in Portuguese, \"usted\" in Spanish, \"vous\" in French, \"Sie\" in German)."
    }

    private fun lengthText(length: PersonaReplyLength): String = when (length) {
        PersonaReplyLength.SHORT -> "keep replies short, one to three sentences, unless the customer asks for more detail."
        PersonaReplyLength.MEDIUM -> "keep replies to one or two short paragraphs."
        PersonaReplyLength.DETAILED -> "give complete answers when the question needs them, in short paragraphs."
    }

    private fun emojiText(emoji: PersonaEmoji): String = when (emoji) {
        PersonaEmoji.NONE -> "don't use emoji."
        PersonaEmoji.LIGHT -> "an emoji now and then is fine, at most one per message."
        PersonaEmoji.EXPRESSIVE -> "use emoji freely to keep the conversation lively."
    }
}
