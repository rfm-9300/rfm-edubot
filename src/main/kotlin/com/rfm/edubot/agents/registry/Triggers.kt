package com.rfm.edubot.agents.registry

import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.store.AgentJson
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectTypes
import kotlinx.serialization.json.JsonObject

object TriggerTypes {
    const val EVENT = "event"
    const val SCHEDULE = "schedule"
    const val DATE_OFFSET = "date_offset"
    const val INACTIVITY = "inactivity"
    const val MANUAL = "manual"
    const val EMAIL_RECEIVED = "email.received"

    /** The module a tenant needs to have records of each type. */
    val moduleOfEntity: Map<String, String> = mapOf(
        SubjectTypes.CLIENT to DashboardModules.CLIENTS,
        SubjectTypes.QUOTE to DashboardModules.QUOTES,
        SubjectTypes.INVOICE to DashboardModules.INVOICES,
        SubjectTypes.PAYMENT to DashboardModules.PAYMENTS,
        SubjectTypes.BOOKING to DashboardModules.BOOKINGS,
        SubjectTypes.CONVERSATION to DashboardModules.CONVERSATIONS,
    )

    val all: List<AgentTriggerType> = listOf(EventTrigger, ScheduleTrigger, DateOffsetTrigger, InactivityTrigger, ManualTrigger, EmailReceivedTrigger)
}

private val timePattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

private fun timeProblem(config: JsonObject, key: String): List<SchemaProblem> =
    config.string(key)?.takeIf { !timePattern.matches(it) }?.let { listOf(SchemaProblem(key, "invalid_time")) }.orEmpty()

/** A record changed: any domain event, optionally narrowed to a new status, a channel or keywords. */
object EventTrigger : AgentTriggerType {
    override val key = TriggerTypes.EVENT
    override val configSchema = Schema.obj(
        "event" to Schema.string(widget = "event", enum = DomainEventTypes.specs.map { it.type }),
        "toStatus" to Schema.string(widget = "status"),
        "channel" to Schema.string(enum = listOf("WHATSAPP", "INSTAGRAM", "WEB")),
        "keywords" to Schema.array(Schema.string(maxLength = 60), widget = "tags", maxItems = 20),
        required = listOf("event"),
    )

    override fun subjectType(config: JsonObject): String? = config.string("event")?.let { DomainEventTypes.spec(it)?.subjectType }

    override fun requiredModules(config: JsonObject): Set<String> =
        setOfNotNull(config.string("event")?.let { DomainEventTypes.spec(it)?.module })

    override fun requiredIntegration(config: JsonObject): IntegrationKind? = when (config.string("event")?.let { DomainEventTypes.spec(it)?.integration }) {
        "gmail" -> IntegrationKind.GMAIL
        "gmail_inbox" -> IntegrationKind.GMAIL_INBOX
        else -> null
    }
}

/**
 * A time of day, week or month in the company's timezone. Without [forEach] one run fires with no
 * record (digests); with it, one run per matching record (month-end billing per client).
 */
object ScheduleTrigger : AgentTriggerType {
    val frequencies = listOf("hourly", "daily", "weekly", "monthly")
    val forEachEntities = listOf(SubjectTypes.CLIENT, SubjectTypes.INVOICE, SubjectTypes.PAYMENT, SubjectTypes.QUOTE, SubjectTypes.BOOKING)

    override val key = TriggerTypes.SCHEDULE
    override val configSchema = Schema.obj(
        "frequency" to Schema.string(enum = frequencies, default = "daily"),
        "time" to Schema.string(widget = "time", default = "09:00"),
        "weekdays" to Schema.array(Schema.integer(min = 1, max = 7), widget = "weekdays", maxItems = 7),
        "dayOfMonth" to Schema.integer(min = -1, max = 31, description = "1-31, or -1 for the last day"),
        "everyHours" to Schema.integer(min = 1, max = 24, default = 1),
        "forEach" to Schema.string(enum = forEachEntities, widget = "entity"),
        "where" to Schema.obj(),
        required = listOf("frequency"),
    )

    override fun subjectType(config: JsonObject): String? = config.string("forEach")

    override fun requiredModules(config: JsonObject): Set<String> = setOfNotNull(config.string("forEach")?.let { TriggerTypes.moduleOfEntity[it] })

    override fun validate(config: JsonObject): List<SchemaProblem> {
        val problems = SchemaValidator.validate(configSchema.withoutWhere(), config.withoutWhere(), "", lenientTemplates = false).toMutableList()
        problems += timeProblem(config, "time")
        if (config.string("frequency") == "monthly" && config.int("dayOfMonth").let { it == null || it == 0 }) problems += SchemaProblem("dayOfMonth", "required")
        config.obj("where")?.let { where ->
            if (runCatching { AgentJson.json.decodeFromJsonElement(ConditionGroup.serializer(), where) }.isFailure) problems += SchemaProblem("where", "invalid_conditions")
        }
        return problems
    }

    fun where(config: JsonObject): ConditionGroup? =
        config.obj("where")?.let { runCatching { AgentJson.json.decodeFromJsonElement(ConditionGroup.serializer(), it) }.getOrNull() }
}

private fun JsonObject.withoutWhere(): JsonObject = JsonObject(filterKeys { it != "where" })

/** A number of days (or hours, for bookings) before or after a record's date. */
object DateOffsetTrigger : AgentTriggerType {
    /** The date each record type is counted from. */
    val fieldOfEntity = mapOf(
        SubjectTypes.INVOICE to "dueDate",
        SubjectTypes.PAYMENT to "dueDate",
        SubjectTypes.QUOTE to "validUntil",
        SubjectTypes.BOOKING to "startAt",
    )

    override val key = TriggerTypes.DATE_OFFSET
    override val configSchema = Schema.obj(
        "entity" to Schema.string(enum = fieldOfEntity.keys.toList(), widget = "entity"),
        "offsetDays" to Schema.integer(min = -90, max = 90, default = 0, description = "negative = before the date"),
        "offsetHours" to Schema.integer(min = -72, max = 72, default = 0),
        "at" to Schema.string(widget = "time", default = "09:00"),
        "statuses" to Schema.array(Schema.string(), widget = "statuses", maxItems = 6),
        required = listOf("entity"),
    )

    override fun subjectType(config: JsonObject): String? = config.string("entity")

    override fun requiredModules(config: JsonObject): Set<String> = setOfNotNull(config.string("entity")?.let { TriggerTypes.moduleOfEntity[it] })

    override fun validate(config: JsonObject): List<SchemaProblem> = super.validate(config) + timeProblem(config, "at")
}

/**
 * Nothing happened for a while: a quote still sent after N days, a chat waiting N minutes for an
 * answer, a client with no new quote, invoice or booking in N days.
 */
object InactivityTrigger : AgentTriggerType {
    val entities = listOf(SubjectTypes.QUOTE, SubjectTypes.CONVERSATION, SubjectTypes.CLIENT)

    override val key = TriggerTypes.INACTIVITY
    override val configSchema = Schema.obj(
        "entity" to Schema.string(enum = entities, widget = "entity"),
        "days" to Schema.integer(min = 0, max = 730),
        "hours" to Schema.integer(min = 0, max = 720),
        "minutes" to Schema.integer(min = 0, max = 1440),
        required = listOf("entity"),
    )

    override fun subjectType(config: JsonObject): String? = config.string("entity")

    override fun requiredModules(config: JsonObject): Set<String> = setOfNotNull(config.string("entity")?.let { TriggerTypes.moduleOfEntity[it] })

    override fun validate(config: JsonObject): List<SchemaProblem> {
        val problems = super.validate(config).toMutableList()
        if (listOf("days", "hours", "minutes").all { (config.int(it) ?: 0) <= 0 }) problems += SchemaProblem("days", "required")
        return problems
    }
}

/** Run by a person from a record's drawer, the Agents module or the assistant. */
object ManualTrigger : AgentTriggerType {
    override val key = TriggerTypes.MANUAL
    override val configSchema = Schema.obj(
        "subjectType" to Schema.string(enum = SubjectTypes.runnable + SubjectTypes.NONE, widget = "entity", default = SubjectTypes.NONE),
    )

    override fun subjectType(config: JsonObject): String? = config.string("subjectType")?.takeIf { it != SubjectTypes.NONE }

    override fun requiredModules(config: JsonObject): Set<String> = setOfNotNull(subjectType(config)?.let { TriggerTypes.moduleOfEntity[it] })
}

/** A new email in the company's connected inbox, narrowed by sender, words or a PDF attachment. */
object EmailReceivedTrigger : AgentTriggerType {
    override val key = TriggerTypes.EMAIL_RECEIVED
    override val configSchema = Schema.obj(
        "sender" to Schema.string(enum = listOf("any", "known", "unknown"), default = "any"),
        "fromContains" to Schema.string(maxLength = 200),
        "subjectContains" to Schema.array(Schema.string(maxLength = 60), widget = "tags", maxItems = 20),
        "bodyContains" to Schema.array(Schema.string(maxLength = 60), widget = "tags", maxItems = 20),
        "hasPdf" to Schema.boolean(),
    )

    override fun subjectType(config: JsonObject): String = SubjectTypes.EMAIL

    override fun requiredModules(config: JsonObject): Set<String> = emptySet()

    override fun requiredIntegration(config: JsonObject): IntegrationKind = IntegrationKind.GMAIL_INBOX
}
