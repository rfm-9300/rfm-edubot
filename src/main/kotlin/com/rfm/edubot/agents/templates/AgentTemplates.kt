package com.rfm.edubot.agents.templates

import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentKind
import com.rfm.edubot.agents.model.AgentPolicy
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.model.Condition
import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.model.ConditionMatch
import com.rfm.edubot.agents.model.ExitRule
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.agents.registry.AgentDefinitionValidator
import com.rfm.edubot.agents.registry.Availability
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SchemaProblem
import com.rfm.edubot.agents.registry.SchemaValidator
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.registry.bool
import com.rfm.edubot.agents.registry.int
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.store.AgentJson
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.DomainEventTypes
import com.rfm.edubot.events.SubjectTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A new agent made from a template, ready to save as a draft. */
data class TemplatedAgent(
    val name: String,
    val description: String?,
    val icon: String,
    val kind: AgentKind,
    val definition: AgentDefinition,
    val params: JsonObject,
)

sealed class TemplateBuild {
    data class Built(val agent: TemplatedAgent) : TemplateBuild()
    data object UnknownTemplate : TemplateBuild()
    data class InvalidParams(val problems: List<SchemaProblem>) : TemplateBuild()
}

/**
 * Built-in agent templates: a few setup questions ([AgentTemplate.params]) turned into an ordinary
 * definition with messages in the company's language. The result is a draft the company can edit
 * like any other agent. Steps that write to customers or change records start on "Ask me first".
 */
object AgentTemplates {
    const val FINANCE = "finance"
    const val SALES = "sales"
    const val BOOKINGS = "bookings"
    const val INBOX = "inbox"
    const val EMAIL = "email"
    const val HOUSEKEEPING = "housekeeping"

    private val templates: List<AgentTemplate> = listOf(
        // Finance
        AgentTemplate(
            key = "invoice_due_reminder", category = FINANCE, kind = AgentKind.WORKFLOW, icon = "invoice", messagesCustomers = true,
            params = Schema.obj(
                "daysBefore" to Schema.integer(min = 0, max = 30, default = 3, widget = "days"),
                "at" to Schema.string(widget = "time", default = "09:00"),
                "attachPdf" to Schema.boolean(default = true),
                fallbackParam(), autonomyParam,
            ),
        ) {
            AgentDefinition(
                triggers = listOf(onDate(SubjectTypes.INVOICE, offsetDays = -int("daysBefore"), at = text("at"), statuses = listOf("PENDING"))),
                steps = listOf(whatsApp("s1", copy("message"), attachPdf = if (flag("attachPdf")) "invoice" else "none")),
                exitRules = listOf(ExitRule(DomainEventTypes.INVOICE_PAID)),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "overdue_sequence", category = FINANCE, kind = AgentKind.WORKFLOW, icon = "alert", messagesCustomers = true,
            params = Schema.obj(
                "firstAfterDays" to Schema.integer(min = 1, max = 60, default = 1, widget = "days"),
                "secondAfterDays" to Schema.integer(min = 1, max = 60, default = 7, widget = "days"),
                "taskAfterDays" to Schema.integer(min = 1, max = 60, default = 7, widget = "days"),
                fallbackParam(), autonomyParam,
            ),
        ) {
            val stillOverdue = whenAll(cond("invoice.isOverdue", "eq", true))
            AgentDefinition(
                triggers = listOf(onDate(SubjectTypes.INVOICE, offsetDays = int("firstAfterDays"), at = "10:00", statuses = listOf("PENDING", "OVERDUE"))),
                steps = listOf(
                    whatsApp("s1", copy("first")),
                    wait("s2", days = int("secondAfterDays"), at = "10:00", businessDay = true),
                    whatsApp("s3", copy("second"), attachPdf = "invoice", guard = stillOverdue),
                    wait("s4", days = int("taskAfterDays"), at = "09:00", businessDay = true),
                    task("s5", copy("task"), guard = stillOverdue),
                ),
                exitRules = listOf(ExitRule(DomainEventTypes.INVOICE_PAID)),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "payment_thank_you", category = FINANCE, kind = AgentKind.WORKFLOW, icon = "heart", messagesCustomers = true,
            params = Schema.obj(fallbackParam(default = "none"), autonomyParam),
        ) {
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.INVOICE_PAID)),
                steps = listOf(whatsApp("s1", copy("message"))),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "payables_digest", category = FINANCE, kind = AgentKind.DIGEST, icon = "wallet",
            modules = setOf(DashboardModules.PAYMENTS),
            params = Schema.obj(weekdayParam(1), "time" to Schema.string(widget = "time", default = "08:00"), audienceParam, skipWhenEmptyParam),
        ) {
            digest("payables_week", onSchedule("weekly", text("time"), weekdays = listOf(int("weekday"))))
        },
        AgentTemplate(
            key = "month_end_billing", category = FINANCE, kind = AgentKind.WORKFLOW, icon = "receipt",
            params = Schema.obj(
                "time" to Schema.string(widget = "time", default = "17:00"),
                "dueInDays" to Schema.integer(min = 0, max = 180, default = 30, widget = "days"),
                "notify" to Schema.boolean(default = true),
                autonomyParam,
            ),
        ) {
            AgentDefinition(
                triggers = listOf(
                    onSchedule("monthly", text("time"), dayOfMonth = -1, forEach = SubjectTypes.CLIENT, where = whenAll(cond("client.openServicesCount", "gt", 0))),
                ),
                steps = listOfNotNull(
                    StepSpec("s1", "crm.invoice.from_open_services", json("dueInDays" to int("dueInDays")), autonomy = autonomy),
                    notify("s2", copy("notify"), guard = whenAll(cond("steps.s1.output.number", "exists"))).takeIf { flag("notify") },
                ),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "weekly_cash_briefing", category = FINANCE, kind = AgentKind.DIGEST, icon = "chart",
            modules = setOf(DashboardModules.INVOICES),
            params = Schema.obj(weekdayParam(5), "time" to Schema.string(widget = "time", default = "17:00"), audienceParam),
        ) {
            digest("cash_week", onSchedule("weekly", text("time"), weekdays = listOf(int("weekday"))), skipWhenEmpty = false)
        },

        // Sales
        AgentTemplate(
            key = "quote_follow_up", category = SALES, kind = AgentKind.WORKFLOW, icon = "quote", messagesCustomers = true,
            params = Schema.obj(
                "firstAfterDays" to Schema.integer(min = 1, max = 30, default = 3, widget = "days"),
                "taskAfterDays" to Schema.integer(min = 0, max = 30, default = 4, widget = "days", description = "0 = no task"),
                fallbackParam(), autonomyParam,
            ),
        ) {
            val stillSent = whenAll(cond("quote.status", "eq", "SENT"))
            val taskAfter = int("taskAfterDays")
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.QUOTE_STATUS_CHANGED, toStatus = "SENT")),
                steps = listOfNotNull(
                    wait("s1", days = int("firstAfterDays"), at = "10:00", businessDay = true),
                    whatsApp("s2", copy("message"), guard = stillSent),
                    wait("s3", days = taskAfter, at = "09:00", businessDay = true).takeIf { taskAfter > 0 },
                    task("s4", copy("task"), guard = stillSent).takeIf { taskAfter > 0 },
                ),
                // Accepted, or back to pending: either way the follow-up is over.
                exitRules = listOf(ExitRule(DomainEventTypes.QUOTE_STATUS_CHANGED)),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "quote_expiring", category = SALES, kind = AgentKind.WORKFLOW, icon = "clock", messagesCustomers = true,
            params = Schema.obj(
                "daysBefore" to Schema.integer(min = 1, max = 30, default = 3, widget = "days"),
                "attachPdf" to Schema.boolean(default = true),
                fallbackParam(), autonomyParam,
            ),
        ) {
            AgentDefinition(
                triggers = listOf(onDate(SubjectTypes.QUOTE, offsetDays = -int("daysBefore"), at = "10:00", statuses = listOf("SENT"))),
                steps = listOf(whatsApp("s1", copy("message"), attachPdf = if (flag("attachPdf")) "quote" else "none")),
                exitRules = listOf(ExitRule(DomainEventTypes.QUOTE_STATUS_CHANGED)),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "quote_accepted", category = SALES, kind = AgentKind.WORKFLOW, icon = "check",
            params = Schema.obj(
                "depositPercent" to Schema.integer(min = 1, max = 100, default = 100, widget = "percent"),
                "dueInDays" to Schema.integer(min = 0, max = 180, default = 30, widget = "days"),
                audienceParam, autonomyParam,
            ),
        ) {
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.QUOTE_STATUS_CHANGED, toStatus = "ACEITO")),
                steps = listOf(
                    StepSpec("s1", "crm.invoice.from_quote", json("dueInDays" to int("dueInDays"), "depositPercent" to int("depositPercent")), autonomy = autonomy),
                    notify("s2", copy("notify"), guard = whenAll(cond("steps.s1.output.number", "exists"))),
                ),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "new_lead_intake", category = SALES, kind = AgentKind.MONITOR, icon = "user",
            params = Schema.obj(audienceParam, "createTask" to Schema.boolean(default = false)),
        ) {
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.CONTACT_CREATED)),
                steps = listOfNotNull(notify("s1", copy("notify")), task("s2", copy("task")).takeIf { flag("createTask") }),
                policy = policy(),
            )
        },

        // Bookings
        AgentTemplate(
            key = "booking_confirmation", category = BOOKINGS, kind = AgentKind.WORKFLOW, icon = "calendar", messagesCustomers = true,
            params = Schema.obj(fallbackParam(), autonomyParam),
        ) {
            AgentDefinition(
                triggers = listOf(
                    onEvent(DomainEventTypes.BOOKING_STATUS_CHANGED, toStatus = "CONFIRMED", id = "t1"),
                    onEvent(DomainEventTypes.BOOKING_CREATED, toStatus = "CONFIRMED", id = "t2"),
                ),
                // A booking the customer made in a chat and that was confirmed at once was already confirmed there.
                conditions = ConditionGroup(
                    ConditionMatch.ANY,
                    listOf(cond("event.type", "eq", DomainEventTypes.BOOKING_STATUS_CHANGED), cond("booking.source", "in", listOf("DASHBOARD", "ADMIN", "ASSISTANT"))),
                ),
                steps = listOf(whatsApp("s1", copy("message"))),
                exitRules = listOf(cancelledBooking()),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "booking_reminder", category = BOOKINGS, kind = AgentKind.WORKFLOW, icon = "bell", messagesCustomers = true,
            params = Schema.obj("hoursBefore" to Schema.integer(min = 1, max = 72, default = 24, widget = "hours"), fallbackParam(), autonomyParam),
        ) {
            AgentDefinition(
                triggers = listOf(onDate(SubjectTypes.BOOKING, offsetHours = -int("hoursBefore"), statuses = listOf("PENDING", "CONFIRMED"))),
                steps = listOf(whatsApp("s1", copy("message"))),
                exitRules = listOf(cancelledBooking(), ExitRule(DomainEventTypes.BOOKING_RESCHEDULED)),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "unconfirmed_booking_alert", category = BOOKINGS, kind = AgentKind.MONITOR, icon = "pending",
            params = Schema.obj("afterHours" to Schema.integer(min = 1, max = 72, default = 4, widget = "hours"), audienceParam),
        ) {
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.BOOKING_CREATED, toStatus = "PENDING")),
                steps = listOf(
                    wait("s1", hours = int("afterHours")),
                    notify("s2", copy("notify"), guard = whenAll(cond("booking.status", "eq", "PENDING"))),
                ),
                exitRules = listOf(ExitRule(DomainEventTypes.BOOKING_STATUS_CHANGED)),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "post_service_follow_up", category = BOOKINGS, kind = AgentKind.WORKFLOW, icon = "star", messagesCustomers = true,
            params = Schema.obj(
                "afterDays" to Schema.integer(min = 0, max = 30, default = 1, widget = "days"),
                "at" to Schema.string(widget = "time", default = "10:00"),
                "reviewUrl" to Schema.string(widget = "url", maxLength = 500),
                fallbackParam(), autonomyParam,
            ),
        ) {
            val reviewUrl = text("reviewUrl")
            val message = if (reviewUrl != null) copy("message_review", "reviewUrl" to reviewUrl) else copy("message")
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.BOOKING_STATUS_CHANGED, toStatus = "COMPLETED")),
                steps = listOf(wait("s1", days = int("afterDays"), at = text("at")), whatsApp("s2", message)),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "no_show_recovery", category = BOOKINGS, kind = AgentKind.WORKFLOW, icon = "retry", messagesCustomers = true,
            params = Schema.obj("afterHours" to Schema.integer(min = 0, max = 12, default = 1, widget = "hours"), fallbackParam(), autonomyParam),
        ) {
            val afterHours = int("afterHours")
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.BOOKING_STATUS_CHANGED, toStatus = "NO_SHOW")),
                steps = listOfNotNull(
                    wait("s1", hours = afterHours).takeIf { afterHours > 0 },
                    whatsApp("s2", copy("message"), guard = whenAll(cond("booking.status", "eq", "NO_SHOW"))),
                ),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "daily_agenda", category = BOOKINGS, kind = AgentKind.DIGEST, icon = "list",
            modules = setOf(DashboardModules.BOOKINGS),
            params = Schema.obj(
                "time" to Schema.string(widget = "time", default = "08:00"),
                "weekdaysOnly" to Schema.boolean(default = true),
                audienceParam, skipWhenEmptyParam,
            ),
        ) {
            digest("agenda_today", onSchedule("daily", text("time"), weekdays = (1..5).toList().takeIf { flag("weekdaysOnly") }))
        },

        // Inbox and social
        AgentTemplate(
            key = "waiting_chat_alert", category = INBOX, kind = AgentKind.MONITOR, icon = "chat",
            params = Schema.obj("minutes" to Schema.integer(min = 5, max = 1440, default = 15, widget = "minutes"), audienceParam),
        ) {
            AgentDefinition(
                triggers = listOf(onInactivity(SubjectTypes.CONVERSATION, minutes = int("minutes"))),
                steps = listOf(notify("s1", copy("notify"))),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "instagram_comment_triage", category = INBOX, kind = AgentKind.MONITOR, icon = "comment",
            params = Schema.obj(audienceParam),
        ) {
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.INSTAGRAM_COMMENT_RECEIVED)),
                steps = listOf(notify("s1", copy("notify"))),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "reengagement", category = INBOX, kind = AgentKind.WORKFLOW, icon = "wave", messagesCustomers = true,
            params = Schema.obj(
                "days" to Schema.integer(min = 30, max = 730, default = 180, widget = "days"),
                "maxPerDay" to Schema.integer(min = 1, max = 100, default = 10),
                fallbackParam(), autonomyParam,
            ),
        ) {
            AgentDefinition(
                triggers = listOf(onInactivity(SubjectTypes.CLIENT, days = int("days"))),
                steps = listOf(whatsApp("s1", copy("message"))),
                // Activating it reaches every quiet client at once, so it trickles out a few a day.
                policy = policy().copy(maxRunsPerDay = int("maxPerDay"), cooldownHours = int("days") * 24),
            )
        },

        // Email
        AgentTemplate(
            key = "email_quote_when_sent", category = EMAIL, kind = AgentKind.WORKFLOW, icon = "mail", messagesCustomers = true,
            params = Schema.obj("attachPdf" to Schema.boolean(default = true), autonomyParam),
        ) {
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.QUOTE_STATUS_CHANGED, toStatus = "SENT")),
                steps = listOf(email("s1", copy("subject"), copy("message"), attachPdf = if (flag("attachPdf")) "quote" else "none")),
                policy = policy(),
            )
        },
        AgentTemplate(
            key = "email_invoice_when_created", category = EMAIL, kind = AgentKind.WORKFLOW, icon = "mail", messagesCustomers = true,
            params = Schema.obj("attachPdf" to Schema.boolean(default = true), autonomyParam),
        ) {
            AgentDefinition(
                triggers = listOf(onEvent(DomainEventTypes.INVOICE_CREATED)),
                steps = listOf(email("s1", copy("subject"), copy("message"), attachPdf = if (flag("attachPdf")) "invoice" else "none")),
                policy = policy(),
            )
        },

        // Housekeeping
        AgentTemplate(
            key = "weekly_data_check", category = HOUSEKEEPING, kind = AgentKind.DIGEST, icon = "tasks",
            modules = setOf(DashboardModules.CLIENTS),
            params = Schema.obj(weekdayParam(1), "time" to Schema.string(widget = "time", default = "09:00"), audienceParam),
        ) {
            digest("missing_client_data", onSchedule("weekly", text("time"), weekdays = listOf(int("weekday"))), skipWhenEmpty = true)
        },
    )

    private val byKey = templates.associateBy { it.key }

    val keys: List<String> get() = templates.map { it.key }

    /**
     * Gallery entries: facts, setup form, the default definition (the gallery draws its recipe from it)
     * and whether the company can use it. Labels and descriptions come from the dashboard catalogs.
     */
    fun catalog(locale: String, availability: Availability, validator: AgentDefinitionValidator): List<JsonObject> = templates.map { template ->
        val built = template.instantiate(JsonObject(emptyMap()), locale, CompanyAgentSettings())
        val missing = (
            availability.missingModules(template.modules).map { "needs_module:$it" } +
                validator.validate(built.definition, availability, built.params)
                    .filter { it.code == "needs_module" || it.code == "needs_integration" }
                    .map { "${it.code}:${it.detail}" }
            ).distinct()
        buildJsonObject {
            put("key", template.key)
            put("category", template.category)
            put("kind", template.kind.name)
            put("icon", template.icon)
            put("name", built.name)
            put("messagesCustomers", template.messagesCustomers)
            put("params", template.params)
            put("definition", AgentJson.json.encodeToJsonElement(AgentDefinition.serializer(), built.definition))
            put("available", missing.isEmpty())
            missing.firstOrNull()?.let { put("reason", it) }
            put("requires", JsonArray(missing.map { JsonPrimitive(it) }))
        }
    }

    fun build(key: String, params: JsonObject?, locale: String, company: CompanyAgentSettings): TemplateBuild {
        val template = byKey[key] ?: return TemplateBuild.UnknownTemplate
        val given = SchemaValidator.coerce(template.params, params ?: JsonObject(emptyMap()))
        val problems = SchemaValidator.validate(template.params, given, "", lenientTemplates = false) + formatProblems(template.params, given)
        if (problems.isNotEmpty()) return TemplateBuild.InvalidParams(problems)
        return TemplateBuild.Built(template.instantiate(given, locale, company))
    }

    private fun AgentTemplate.instantiate(given: JsonObject, locale: String, company: CompanyAgentSettings): TemplatedAgent {
        val answers = Schema.withDefaults(this.params, given)
        return TemplatedAgent(
            name = TemplateCopy.t(locale, "$key.name"),
            description = null,
            icon = icon,
            kind = kind,
            definition = TemplateScope(key, answers, locale, company).define(),
            params = answers,
        )
    }

    /** Times and links the schema subset can't express. */
    private fun formatProblems(schema: JsonObject, params: JsonObject): List<SchemaProblem> {
        val properties = schema["properties"] as? JsonObject ?: return emptyList()
        return params.mapNotNull { (key, value) ->
            val widget = ((properties[key] as? JsonObject)?.get("x-widget") as? JsonPrimitive)?.content
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when {
                widget == "time" && !TIME.matches(text) -> SchemaProblem(key, "invalid_time")
                widget == "url" && !URL.matches(text) -> SchemaProblem(key, "invalid_url")
                else -> null
            }
        }
    }

    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
}

private class AgentTemplate(
    val key: String,
    val category: String,
    val kind: AgentKind,
    val icon: String,
    /** Modules the template is pointless without, beyond the ones its definition already requires. */
    val modules: Set<String> = emptySet(),
    val messagesCustomers: Boolean = false,
    val params: JsonObject,
    val define: TemplateScope.() -> AgentDefinition,
)

/** What a template's [AgentTemplate.define] reads: the answers (defaults filled in), the language and the company's defaults. */
private class TemplateScope(private val key: String, val params: JsonObject, private val locale: String, private val company: CompanyAgentSettings) {
    fun copy(name: String, vararg values: Pair<String, String>): String = TemplateCopy.t(locale, "$key.$name", *values)
    fun int(name: String): Int = params.int(name) ?: 0
    fun text(name: String): String? = params.string(name)?.trim()?.takeIf { it.isNotEmpty() }
    fun flag(name: String): Boolean = params.bool(name) ?: false

    /** The autonomy of the steps that message customers or change records. */
    val autonomy: Autonomy get() = text("autonomy")?.let { value -> Autonomy.entries.firstOrNull { it.name == value } } ?: Autonomy.APPROVE

    fun policy(): AgentPolicy = AgentPolicy(autonomy = company.defaultAutonomy)

    fun whatsApp(id: String, message: String, attachPdf: String = "none", guard: ConditionGroup? = null) = StepSpec(
        id,
        "whatsapp.send",
        json("to" to "client", "text" to message, "attachPdf" to attachPdf, "fallback" to (text("fallback") ?: "email")),
        guard = guard,
        autonomy = autonomy,
    )

    fun email(id: String, subject: String, text: String, attachPdf: String = "none", guard: ConditionGroup? = null) = StepSpec(
        id,
        "email.send",
        json("to" to "client", "subject" to subject, "text" to text, "attachPdf" to attachPdf),
        guard = guard,
        autonomy = autonomy,
    )

    fun notify(id: String, message: String, guard: ConditionGroup? = null) =
        StepSpec(id, "team.notify", json("message" to message, "audience" to (text("audience") ?: "admins")), guard = guard)

    fun task(id: String, title: String, guard: ConditionGroup? = null) =
        StepSpec(id, "team.task.create", json("title" to title, "dueInDays" to 0), guard = guard)

    /** A summary for the team, skipped when there is nothing to report if [skipWhenEmpty]. */
    fun digest(kind: String, trigger: TriggerSpec, skipWhenEmpty: Boolean = flag("skipWhenEmpty")) = AgentDefinition(
        triggers = listOf(trigger),
        steps = listOf(
            StepSpec("s1", "data.summary", json("kind" to kind)),
            notify("s2", "{{steps.s1.output.text}}", guard = if (skipWhenEmpty) whenAll(cond("steps.s1.output.count", "gt", 0)) else null),
        ),
        policy = policy(),
    )
}

private val autonomyParam = "autonomy" to Schema.string(enum = Autonomy.entries.map { it.name }, default = Autonomy.APPROVE.name, widget = "autonomy")
private val audienceParam = "audience" to Schema.string(enum = listOf("admins", "everyone"), default = "admins", widget = "audience")
private val skipWhenEmptyParam = "skipWhenEmpty" to Schema.boolean(default = true)

private fun fallbackParam(default: String = "email") = "fallback" to Schema.string(enum = listOf("email", "task", "none"), default = default, widget = "fallback")

private fun weekdayParam(default: Int) = "weekday" to Schema.integer(min = 1, max = 7, default = default, widget = "weekday")

private fun onEvent(event: String, toStatus: String? = null, id: String = "t1") =
    TriggerSpec(id, TriggerTypes.EVENT, json("event" to event, "toStatus" to toStatus))

private fun onSchedule(frequency: String, time: String?, weekdays: List<Int>? = null, dayOfMonth: Int? = null, forEach: String? = null, where: ConditionGroup? = null) =
    TriggerSpec(
        "t1",
        TriggerTypes.SCHEDULE,
        json(
            "frequency" to frequency,
            "time" to time,
            "weekdays" to weekdays,
            "dayOfMonth" to dayOfMonth,
            "forEach" to forEach,
            "where" to where?.let { AgentJson.json.encodeToJsonElement(ConditionGroup.serializer(), it) },
        ),
    )

private fun onDate(entity: String, offsetDays: Int = 0, offsetHours: Int = 0, at: String? = null, statuses: List<String>) =
    TriggerSpec("t1", TriggerTypes.DATE_OFFSET, json("entity" to entity, "offsetDays" to offsetDays, "offsetHours" to offsetHours, "at" to at, "statuses" to statuses))

private fun onInactivity(entity: String, days: Int = 0, minutes: Int = 0) =
    TriggerSpec("t1", TriggerTypes.INACTIVITY, json("entity" to entity, "days" to days.takeIf { it > 0 }, "minutes" to minutes.takeIf { it > 0 }))

/** Every wait field is set: the action's defaults would otherwise add a day. */
private fun wait(id: String, days: Int = 0, hours: Int = 0, at: String? = null, businessDay: Boolean = false) =
    StepSpec(id, "flow.wait", json("days" to days, "hours" to hours, "minutes" to 0, "at" to at, "businessDay" to businessDay))

private fun cancelledBooking() = ExitRule(DomainEventTypes.BOOKING_STATUS_CHANGED, whenAll(cond("event.to", "eq", "CANCELLED")))

private fun whenAll(vararg conditions: Condition) = ConditionGroup(ConditionMatch.ALL, conditions.toList())

private fun cond(field: String, op: String, value: Any? = null) = Condition(field, op, value?.let { element(it) })

private fun json(vararg entries: Pair<String, Any?>): JsonObject =
    JsonObject(entries.filter { it.second != null }.associate { (key, value) -> key to element(value) })

private fun element(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map { element(it) })
    else -> JsonPrimitive(value.toString())
}
