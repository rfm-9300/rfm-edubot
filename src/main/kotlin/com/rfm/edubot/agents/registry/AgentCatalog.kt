package com.rfm.edubot.agents.registry

import com.rfm.edubot.bookings.model.BookingStatus
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CatalogTriggerDto(val key: String, val configSchema: JsonObject, val available: Boolean, val reason: String? = null)

@Serializable
data class CatalogEventDto(val type: String, val subjectType: String, val available: Boolean, val reason: String? = null)

@Serializable
data class CatalogActionDto(
    val key: String,
    val category: String,
    val sideEffect: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val subjectTypes: List<String>,
    val available: Boolean,
    val reason: String? = null,
    /** An `ai.task` step may offer it to the model. */
    val aiCallable: Boolean = false,
)

@Serializable
data class CatalogEntityDto(val type: String, val module: String?, val available: Boolean, val statuses: List<String>)

/**
 * Everything the builder needs to draw its forms, from the registry. Labels are not here: the
 * dashboard translates `app.agents.triggers.<key>`, `app.agents.actions.<key>`, `app.agents.events.<type>`…
 */
@Serializable
data class AgentCatalogDto(
    val triggers: List<CatalogTriggerDto>,
    val events: List<CatalogEventDto>,
    val actions: List<CatalogActionDto>,
    val entities: List<CatalogEntityDto>,
    val operators: List<String>,
    /** Variables by record type; `none` holds the ones every run has. */
    val variables: Map<String, List<VariableSpec>>,
    val integrations: List<String>,
    val templates: List<JsonObject>,
)

object AgentAvailability {
    fun of(tenant: Tenant, gmail: Boolean = false, gmailInbox: Boolean = false): Availability = Availability(
        modules = DashboardModules.effectiveFor(tenant).toSet(),
        integrations = buildSet {
            if (tenant.binding(Platform.WHATSAPP) != null) add(IntegrationKind.WHATSAPP)
            if (tenant.binding(Platform.INSTAGRAM) != null) add(IntegrationKind.INSTAGRAM)
            if (gmail) add(IntegrationKind.GMAIL)
            if (gmailInbox) add(IntegrationKind.GMAIL_INBOX)
        },
    )
}

object AgentCatalog {
    private val statuses: Map<String, List<String>> = mapOf(
        SubjectTypes.QUOTE to QuoteStatus.entries.map { it.name },
        SubjectTypes.INVOICE to InvoiceStatus.entries.map { it.name },
        SubjectTypes.PAYMENT to PaymentStatus.entries.map { it.name },
        SubjectTypes.BOOKING to BookingStatus.entries.map { it.name },
    )

    fun build(registry: AgentRegistry, availability: Availability, templates: List<JsonObject> = emptyList()): AgentCatalogDto {
        val subjectTypes = listOf(
            SubjectTypes.CLIENT, SubjectTypes.QUOTE, SubjectTypes.INVOICE, SubjectTypes.PAYMENT, SubjectTypes.BOOKING,
            SubjectTypes.SERVICE, SubjectTypes.CONVERSATION, SubjectTypes.CONTACT, SubjectTypes.INSTAGRAM_COMMENT, SubjectTypes.EMAIL,
        )
        return AgentCatalogDto(
            triggers = registry.triggers.map { trigger ->
                val integration = trigger.requiredIntegration(JsonObject(emptyMap()))
                val reason = integration?.takeIf { !availability.has(it) }?.let { "needs_integration:${it.name}" }
                CatalogTriggerDto(trigger.key, trigger.configSchema, reason == null, reason)
            },
            events = DomainEventTypes.specs.map { spec ->
                val reason = when {
                    spec.module != null && spec.module !in availability.modules -> "needs_module:${spec.module}"
                    spec.integration == "gmail" && !availability.has(IntegrationKind.GMAIL) -> "needs_integration:GMAIL"
                    spec.integration == "gmail_inbox" && !availability.has(IntegrationKind.GMAIL_INBOX) -> "needs_integration:GMAIL_INBOX"
                    else -> null
                }
                CatalogEventDto(spec.type, spec.subjectType, reason == null, reason)
            },
            actions = registry.actions.map { action ->
                val missing = availability.missingModules(action.requiredModules).firstOrNull()
                val reason = when {
                    missing != null -> "needs_module:$missing"
                    !availability.has(action.requiredIntegration) -> "needs_integration:${action.requiredIntegration?.name}"
                    else -> null
                }
                CatalogActionDto(
                    key = action.key,
                    category = action.category.name,
                    sideEffect = action.sideEffect.name,
                    inputSchema = action.inputSchema,
                    outputSchema = action.outputSchema,
                    subjectTypes = action.subjectTypes.toList(),
                    available = reason == null,
                    reason = reason,
                    aiCallable = action.aiCallable,
                )
            },
            entities = TriggerTypes.moduleOfEntity.map { (type, module) ->
                CatalogEntityDto(type, module, module in availability.modules, statuses[type].orEmpty())
            },
            operators = com.rfm.edubot.agents.runtime.ConditionEvaluator.operators.toList(),
            variables = mapOf(SubjectTypes.NONE to AgentVariables.forSubject(null)) + subjectTypes.associateWith { AgentVariables.forSubject(it) },
            integrations = availability.integrations.map { it.name },
            templates = templates,
        )
    }
}
