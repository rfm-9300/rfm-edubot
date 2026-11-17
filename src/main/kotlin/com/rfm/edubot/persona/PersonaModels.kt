package com.rfm.edubot.persona

import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.dashboard.DashboardModules
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
 * Everything a company sets in fields rather than writes as instructions: the bot's profile (name, how it
 * talks), facts about the business and the questions it answers, the rules it keeps, what it may do and
 * when it hands over. Stored as `behavior` on the persona and versioned with the instructions. A null or
 * empty field adds nothing to the prompt, so a company that never opens these settings gets exactly its
 * instructions.
 */
data class PersonaBehavior(
    val botName: String? = null,
    /** When the bot says it is a virtual assistant; null leaves it to the instructions. */
    val disclosure: PersonaDisclosure? = null,
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
    /** What the bot does with questions that have nothing to do with the company. */
    val scope: PersonaScope? = null,
    /** What the bot does when it doesn't know the answer. */
    val unknown: PersonaUnknown? = null,
    /** Rules the bot always follows, one per entry. */
    val rules: List<String> = emptyList(),
    /** Facts the bot uses as written. */
    val business: PersonaBusiness = PersonaBusiness(),
    /** Questions customers ask and the company's answers. */
    val faq: List<PersonaFaq> = emptyList(),
    /** What the bot may do in a chat beyond talking; all on unless the company turns some off. */
    val capabilities: PersonaCapabilities = PersonaCapabilities(),
    val handoff: PersonaHandoff = PersonaHandoff(),
) {
    val isEmpty: Boolean get() = this == PersonaBehavior(handoff = handoff) && !handoff.enabled
}

enum class PersonaTone { FRIENDLY, PROFESSIONAL, FORMAL, CASUAL, EMPATHETIC }
enum class PersonaAddressForm { INFORMAL, FORMAL }
enum class PersonaReplyLength { SHORT, MEDIUM, DETAILED }
enum class PersonaEmoji { NONE, LIGHT, EXPRESSIVE }

/** When the bot says it is a virtual assistant (AI). */
enum class PersonaDisclosure { WHEN_ASKED, FIRST_REPLY }

/** Questions about other things: decline them and steer back, or answer briefly first. */
enum class PersonaScope { BUSINESS, ANYTHING }

/** A question the bot can't answer: offer to check with the team, give the company's contacts, or hand over. */
enum class PersonaUnknown { OFFER_CHECK, SHARE_CONTACT, HAND_OVER }

/** Facts about the business, each quoted to customers as written. */
data class PersonaBusiness(
    /** The name customers know the business by. */
    val name: String? = null,
    /** What the business does and for whom. */
    val about: String? = null,
    val hours: String? = null,
    /** Address, or the area it serves. */
    val location: String? = null,
    /** Phone, email, website or a booking link. */
    val contact: String? = null,
) {
    /** The facts that are set, in the order the prompt lists them. */
    fun facts(): List<Pair<String, String>> = listOfNotNull(
        name?.let { "name" to it }, about?.let { "about" to it }, hours?.let { "hours" to it },
        location?.let { "location" to it }, contact?.let { "contact" to it },
    ).filter { it.second.isNotBlank() }
}

/** A question customers ask and the answer the company gives. */
data class PersonaFaq(val question: String, val answer: String)

/**
 * What the bot may do in a customer chat beyond answering from what it knows. Each switch only matters
 * when the company has the module behind it; off, the bot gets neither that module's tools nor its notes.
 */
data class PersonaCapabilities(
    /** Look up prices and items in the catalog (Catalog). */
    val catalog: Boolean = true,
    /** Look up free times and book, move or cancel appointments (Bookings). */
    val bookings: Boolean = true,
    /** Find and create clients, quotes and invoices (Clients, Quotes, Invoices). */
    val records: Boolean = true,
) {
    /** [modules] without the ones whose tools the company turned off for the bot. */
    fun allowed(modules: Set<String>): Set<String> = modules - buildSet {
        if (!catalog) add(DashboardModules.CATALOG)
        if (!bookings) add(DashboardModules.BOOKINGS)
        if (!records) addAll(RECORD_MODULES)
    }

    companion object {
        val RECORD_MODULES = setOf(DashboardModules.CLIENTS, DashboardModules.QUOTES, DashboardModules.INVOICES)
    }
}

/**
 * Passing a conversation to a person: the bot calls `handoff_to_human`, its replies pause in that
 * conversation (like a person pausing them in the inbox) and the team is notified.
 */
data class PersonaHandoff(
    val enabled: Boolean = false,
    /** The situations to hand over in. Null on a persona saved before they existed: see [effectiveReasons]. */
    val reasons: List<PersonaHandoffReason>? = null,
    /** More situations, in the company's words. */
    val triggers: String? = null,
    /** Sent as written when the bot hands over; empty lets the model word it. */
    val message: String? = null,
) {
    /**
     * The situations the bot hands over in. A persona saved before [reasons] existed hands over in the
     * platform's usual ones, unless the company wrote its own [triggers], which then stood alone.
     */
    fun effectiveReasons(): List<PersonaHandoffReason> =
        reasons ?: if (triggers.isNullOrBlank()) DEFAULT_REASONS else emptyList()

    companion object {
        /** `autoReplyPausedBy` of a conversation the bot handed over. */
        const val PAUSED_BY = Conversation.BOT_HANDOFF

        /** What the bot handed over on before a company could choose. */
        val DEFAULT_REASONS = listOf(PersonaHandoffReason.ASKS_FOR_PERSON, PersonaHandoffReason.UPSET, PersonaHandoffReason.CANT_HELP)
    }
}

/** A situation the bot hands a conversation over in. */
enum class PersonaHandoffReason { ASKS_FOR_PERSON, UPSET, CANT_HELP, PAYMENTS, URGENT }

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
    const val MAX_BUSINESS_NAME_CHARS = 100
    const val MAX_BUSINESS_ABOUT_CHARS = 1_000
    /** Opening hours, location and contact. */
    const val MAX_BUSINESS_FIELD_CHARS = 500
    /** Questions and answers: up to about 7,000 tokens on every reply when all are full. */
    const val MAX_FAQ = 30
    const val MAX_FAQ_QUESTION_CHARS = 200
    const val MAX_FAQ_ANSWER_CHARS = 800
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
    /** Settings and instructions changed in one save. */
    SETTINGS_AND_INSTRUCTIONS,
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
