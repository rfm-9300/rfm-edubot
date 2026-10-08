package com.rfm.edubot.persona

import com.rfm.edubot.conversation.model.Conversation
import kotlinx.datetime.Instant
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

/**
 * How a company's bot talks to its customers: the structured [behavior] settings plus the free-text
 * [compiledInstructions] (written by [PersonaCompiler] from the sources, or by hand). Both are injected
 * into every customer chat through [PersonaPrompt]; every change is a new [version] kept in history.
 */
data class TenantPersona(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val compiledInstructions: String = "",
    val behavior: PersonaBehavior = PersonaBehavior(),
    val version: Int = 0,
    /** Estimated tokens the persona block adds to every customer reply. */
    val tokenEstimate: Int = 0,
    val status: PersonaStatus = PersonaStatus.EMPTY,
    /** Why the last synthesis failed, one of [PersonaErrors]; cleared by the next successful change. */
    val lastError: String? = null,
    /** A source already synthesized was removed, so its facts stay in the instructions until a recompile. */
    val stale: Boolean = false,
    val updatedAt: Instant,
) {
    /** Nothing customized: customers get the platform's neutral identity. */
    val isEmpty: Boolean get() = compiledInstructions.isBlank() && behavior.isEmpty
}

enum class PersonaStatus { EMPTY, COMPILING, READY, ERROR }

/** Machine-readable reasons a persona operation failed; the dashboard words them. */
object PersonaErrors {
    const val AI_UNAVAILABLE = "ai_unavailable"
    const val TOKEN_BUDGET = "token_budget"
    const val EMPTY_RESULT = "empty_result"
    /** The server stopped while a synthesis ran; the sources are synthesized again at boot. */
    const val INTERRUPTED = "interrupted"
}

/**
 * The settings a company picks rather than writes. A null field adds nothing to the prompt, so a
 * company that never opens these settings gets exactly its instructions.
 */
data class PersonaBehavior(
    val botName: String? = null,
    /** A [PersonaLanguages] code; null replies in the customer's language. */
    val language: String? = null,
    /** Only ever reply in [language], even when the customer writes in another one. */
    val languageStrict: Boolean = false,
    val tone: PersonaTone? = null,
    val addressForm: PersonaAddressForm? = null,
    val replyLength: PersonaReplyLength? = null,
    val emoji: PersonaEmoji? = null,
    /** How the bot opens a new conversation, in the company's words. */
    val greeting: String? = null,
    /** Rules the bot always follows, one per entry. */
    val rules: List<String> = emptyList(),
    val handoff: PersonaHandoff = PersonaHandoff(),
) {
    val isEmpty: Boolean get() = this == PersonaBehavior(handoff = handoff) && !handoff.enabled
}

enum class PersonaTone { FRIENDLY, PROFESSIONAL, FORMAL, CASUAL, EMPATHETIC }
enum class PersonaAddressForm { INFORMAL, FORMAL }
enum class PersonaReplyLength { SHORT, MEDIUM, DETAILED }
enum class PersonaEmoji { NONE, LIGHT, EXPRESSIVE }

/**
 * Passing a conversation to a person: the bot calls `handoff_to_human`, its replies pause in that
 * conversation (like a person pausing them in the inbox) and the team is notified.
 */
data class PersonaHandoff(
    val enabled: Boolean = false,
    /** When to hand over, in the company's words; empty uses the platform default. */
    val triggers: String? = null,
    /** Sent as written when the bot hands over; empty lets the model word it. */
    val message: String? = null,
) {
    companion object {
        /** `autoReplyPausedBy` of a conversation the bot handed over. */
        const val PAUSED_BY = Conversation.BOT_HANDOFF
    }
}

object PersonaLanguages {
    /** Codes the dashboard offers, with the name the prompt uses. */
    val names: Map<String, String> = linkedMapOf(
        "pt-PT" to "European Portuguese",
        "pt-BR" to "Brazilian Portuguese",
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
    )
}

/** Bounds on what a company can store, so one persona can't make every reply slow or expensive. */
object PersonaLimits {
    /** Instructions typed by hand: about 3,000 tokens on every reply. */
    const val MAX_INSTRUCTIONS_CHARS = 12_000
    /** What the synthesis aims for, leaving room for hand edits. */
    const val TARGET_COMPILED_CHARS = 6_000
    const val MAX_NOTE_CHARS = 20_000
    const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
    const val MAX_SOURCES = 100
    /** Text across all sources, which bounds a full rebuild to about a dozen synthesis calls. */
    const val MAX_TOTAL_SOURCE_CHARS = 600_000
    const val MAX_BOT_NAME_CHARS = 60
    const val MAX_GREETING_CHARS = 400
    const val MAX_RULES = 25
    const val MAX_RULE_CHARS = 300
    const val MAX_HANDOFF_CHARS = 500
    const val MAX_TEST_MESSAGES = 20
    const val MAX_TEST_MESSAGE_CHARS = 4_000
    /** Versions kept per company; older ones are dropped. */
    const val KEPT_VERSIONS = 50
}

/**
 * A raw incremental input the tenant supplies (a typed note or an uploaded file's extracted text).
 * Sources are the gradual "sync" feed; [PersonaCompiler] folds them into the single compiled file.
 * They are kept after compilation so we can re-compact from scratch ([PersonaCompiler.rebuild]).
 */
data class PersonaSource(
    @BsonId val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val kind: SourceKind,
    val content: String,
    val label: String,                  // human label: note preview or original filename
    val compiledIntoVersion: Int? = null,  // null until folded into the compiled file
    val createdAt: Instant,
    /** Who added it: a dashboard user's email or "operator". */
    val addedBy: String? = null,
    /** Only the start of a long file was kept ([PersonaFileExtractor.MAX_CHARS]). */
    val truncated: Boolean = false,
)

enum class SourceKind { TEXT_NOTE, FILE }

/** What made a persona version. */
enum class PersonaChange {
    /** Instructions edited by hand. */
    MANUAL,
    /** Behavior settings changed. */
    SETTINGS,
    /** New sources folded into the instructions. */
    SYNTHESIS,
    /** Instructions written again from every source. */
    REBUILD,
    /** An earlier version brought back. */
    RESTORE,
    /** The persona as it was before history was kept. */
    BASELINE,
}

/** A snapshot of the persona after one change, so any earlier state can be inspected and restored. */
data class PersonaVersion(
    val id: ObjectId = ObjectId(),
    val tenantId: ObjectId,
    val version: Int,
    val change: PersonaChange,
    val compiledInstructions: String,
    val behavior: PersonaBehavior,
    /** Who made the change; null for the synthesis running on its own. */
    val author: String? = null,
    val restoredFrom: Int? = null,
    /** Sources folded in by a synthesis or rebuild. */
    val sourceCount: Int = 0,
    /** The synthesis came out over budget and was cut at a paragraph. */
    val trimmed: Boolean = false,
    val createdAt: Instant,
)
