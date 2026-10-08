package com.rfm.edubot.dashboard

import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.TenantLocales
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.Instant
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

enum class AssistantReplyStyle { CONCISE, BALANCED, DETAILED }

/** How a company wants its dashboard assistant to work for the whole team. Only admins change it. */
internal data class AssistantSettings(
    /** The company's own rules and defaults, read by the assistant on every turn. */
    val instructions: String = "",
    val replyStyle: AssistantReplyStyle = AssistantReplyStyle.BALANCED,
    /** Null answers in the language of each message; otherwise one of [TenantLocales.SUPPORTED], always. */
    val language: String? = null,
    /** Off: the assistant reads and answers but never proposes a change. */
    val allowChanges: Boolean = true,
    /** Modules on in the dashboard that the company keeps out of the assistant. */
    val disabledModules: Set<String> = emptySet(),
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
) {
    /** The modules the assistant may use: on for the company and not kept out of it. */
    fun usableModules(enabled: Collection<String>): List<String> = enabled.filter { it !in disabledModules }
}

/** The dashboard modules the assistant has tools for, in the order the settings list them. */
internal object AssistantAreas {
    val all: List<String> = listOf(
        DashboardModules.CLIENTS,
        DashboardModules.SERVICES,
        DashboardModules.QUOTES,
        DashboardModules.INVOICES,
        DashboardModules.PAYMENTS,
        DashboardModules.SUPPLIERS,
        DashboardModules.EMPLOYEES,
        DashboardModules.CATALOG,
        DashboardModules.BOOKINGS,
        DashboardModules.CONVERSATIONS,
        DashboardModules.AGENTS,
    )

    fun available(enabled: Collection<String>): List<String> = all.filter { it in enabled }
}

/** One document per company in `dashboard_assistant_settings`, keyed by the company's id. */
internal class AssistantSettingsRepository(mongo: MongoModule) {
    private val collection = mongo.database.getCollection<Document>("dashboard_assistant_settings")

    suspend fun find(tenantId: ObjectId): AssistantSettings =
        collection.find(Filters.eq("_id", tenantId)).firstOrNull()?.toSettings() ?: AssistantSettings()

    suspend fun save(tenantId: ObjectId, settings: AssistantSettings, updatedBy: String?): AssistantSettings {
        val saved = settings.copy(updatedAt = Instant.fromEpochMilliseconds(SystemClock.now().toEpochMilliseconds()), updatedBy = updatedBy)
        collection.replaceOne(Filters.eq("_id", tenantId), saved.toDocument(tenantId), ReplaceOptions().upsert(true))
        return saved
    }

    private fun AssistantSettings.toDocument(tenantId: ObjectId) = Document("_id", tenantId)
        .append("instructions", instructions)
        .append("replyStyle", replyStyle.name)
        .append("language", language)
        .append("allowChanges", allowChanges)
        .append("disabledModules", disabledModules.toList())
        .append("updatedAt", updatedAt?.let { Date(it.toEpochMilliseconds()) })
        .append("updatedBy", updatedBy)

    private fun Document.toSettings() = AssistantSettings(
        instructions = getString("instructions").orEmpty(),
        replyStyle = AssistantReplyStyle.entries.firstOrNull { it.name == getString("replyStyle") } ?: AssistantReplyStyle.BALANCED,
        language = getString("language")?.takeIf { it in TenantLocales.SUPPORTED },
        allowChanges = getBoolean("allowChanges") ?: true,
        disabledModules = getList("disabledModules", String::class.java).orEmpty().toSet(),
        updatedAt = getDate("updatedAt")?.let { Instant.fromEpochMilliseconds(it.time) },
        updatedBy = getString("updatedBy"),
    )
}

/** Why a settings change was refused: a stable code, the field it is about and, for lengths, the limit. */
internal data class AssistantSettingsProblem(val error: String, val field: String, val limit: Int? = null)

internal object AssistantSettingsRules {
    const val MAX_INSTRUCTIONS = 4_000

    /** The settings a request asks for, or why they can't be saved. */
    fun parse(
        instructions: String?,
        replyStyle: String?,
        language: String?,
        allowChanges: Boolean?,
        disabledModules: List<String>?,
    ): Pair<AssistantSettings?, AssistantSettingsProblem?> {
        val text = instructions?.trim().orEmpty()
        if (text.length > MAX_INSTRUCTIONS) return null to AssistantSettingsProblem("too_long", "instructions", MAX_INSTRUCTIONS)
        val style = replyStyle?.takeIf { it.isNotBlank() }?.let { name -> AssistantReplyStyle.entries.firstOrNull { it.name == name.uppercase() } }
        if (replyStyle != null && replyStyle.isNotBlank() && style == null) return null to AssistantSettingsProblem("invalid", "replyStyle")
        val locale = language?.takeIf { it.isNotBlank() }
        if (locale != null && locale !in TenantLocales.SUPPORTED) return null to AssistantSettingsProblem("invalid", "language")
        val areas = disabledModules.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (areas.any { it !in AssistantAreas.all }) return null to AssistantSettingsProblem("invalid", "disabledModules")
        return AssistantSettings(
            instructions = text,
            replyStyle = style ?: AssistantReplyStyle.BALANCED,
            language = locale,
            allowChanges = allowChanges ?: true,
            // Areas the company doesn't have are kept, so turning a module back on keeps the choice made for it.
            disabledModules = areas,
        ) to null
    }
}
