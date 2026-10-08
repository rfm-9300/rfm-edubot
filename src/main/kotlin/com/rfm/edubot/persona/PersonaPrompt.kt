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
     * written, so a company that never touches the settings sees no change in what the model reads.
     */
    fun personaBlock(behavior: PersonaBehavior, instructions: String, purpose: Purpose = Purpose.CHAT): String? {
        val parts = listOfNotNull(behaviorText(behavior, purpose), instructions.trim().takeIf { it.isNotEmpty() })
        if (parts.isEmpty()) return null
        return "<persona>\n${parts.joinToString("\n\n")}\n</persona>"
    }

    /**
     * Every system message a customer chat starts with, in order: the persona (or the neutral identity),
     * the platform rules, the date, the CRM rules for the company's modules, bookings and the handoff.
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
        if (handoff) handoffNote(persona?.behavior?.handoff)?.let { add(ChatMessage(role = "system", content = it)) }
    }

    /** Whether the model may hand conversations to a person. */
    fun handsOff(persona: TenantPersona?): Boolean = persona?.behavior?.handoff?.enabled == true

    /**
     * Whether a chat on [platform] may be handed over: only where the team answers from the inbox. Website
     * chats are read-only there, so a handover would leave the visitor with nobody to reply.
     */
    fun handsOff(persona: TenantPersona?, platform: Platform): Boolean = handsOff(persona) && platform != Platform.WEB

    fun handoffNote(handoff: PersonaHandoff?): String? {
        if (handoff?.enabled != true) return null
        val triggers = handoff.triggers?.trim()?.takeIf { it.isNotEmpty() }
            ?: "the customer asks to talk to a person, is upset or complaining, or needs something you can't do"
        val message = handoff.message?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Handing over to a person: call $HANDOFF_TOOL, with a short reason, when $triggers. ")
            append("It pauses your replies in this conversation and alerts the company's team. ")
            if (message != null) {
                append("The customer then gets the company's own handover message, so don't write a reply of your own after calling it. ")
            } else {
                append("After calling it, tell the customer briefly that a person from the team will continue here; don't promise a time. ")
            }
            append("Never say you handed the conversation over without calling $HANDOFF_TOOL.")
        }
    }

    fun estimateTokens(text: String?): Int = if (text.isNullOrBlank()) 0 else (text.length + 3) / 4

    private fun behaviorText(behavior: PersonaBehavior, purpose: Purpose): String? {
        val chat = purpose == Purpose.CHAT
        val settings = buildList {
            if (chat) behavior.botName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("Your name is $it. Use it when you introduce yourself or someone asks who you are.")
            }
            if (chat) languageLine(behavior)?.let { add(it) }
            if (chat) behavior.tone?.let { add("Tone: ${toneText(it)}") }
            behavior.addressForm?.let { add(addressText(it)) }
            if (chat) behavior.replyLength?.let { add("Length: ${lengthText(it)}") }
            if (chat) behavior.emoji?.let { add("Emoji: ${emojiText(it)}") }
            if (chat) behavior.greeting?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("Greeting: when the conversation has no earlier messages, open your reply with a greeting like this one, adapted naturally: \"$it\"")
            }
        }
        val rules = behavior.rules.map { it.trim() }.filter { it.isNotEmpty() }
        if (settings.isEmpty() && rules.isEmpty()) return null
        return buildString {
            if (settings.isNotEmpty()) {
                append("How the company wants you to talk (these settings win over the instructions below when they disagree):\n")
                append(settings.joinToString("\n") { "- $it" })
            }
            if (rules.isNotEmpty()) {
                if (settings.isNotEmpty()) append("\n\n")
                append("Rules you always follow:\n")
                append(rules.joinToString("\n") { "- $it" })
            }
        }
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
