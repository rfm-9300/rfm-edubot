package com.rfm.edubot.agents.briefings

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentSettings
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.model.Approvers
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepResult
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.StepStatus
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.agents.registry.AgentVariables
import com.rfm.edubot.agents.registry.DefinitionProblem
import com.rfm.edubot.agents.registry.ScheduleTrigger
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SchemaValidator
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.registry.ints
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.AgentRunStarter
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.AgentScheduler
import com.rfm.edubot.agents.runtime.ConditionEvaluator
import com.rfm.edubot.agents.runtime.EmployeeAudience
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.agents.runtime.ScheduleCalculator
import com.rfm.edubot.agents.runtime.StartResult
import com.rfm.edubot.agents.runtime.TemplateRenderer
import com.rfm.edubot.agents.runtime.ValueFormatter
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.model.Employee
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.tags.RecordTagRepository
import com.rfm.edubot.tags.TagKind
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * Briefings: agents that run on a schedule once per employee (a `schedule` trigger with `forEach: employee`), like
 * the daily shift briefing. The Briefings page shows each one by day and person: what each person gets (today's
 * message built as the run would build it, nothing stored), what went and how WhatsApp delivered it, and lets the
 * team send now, send again or approve the day's messages. Everything is the agents engine's own data: the runs,
 * their approvals and the messages the runs stored, whose ticks the status webhooks update.
 */
class BriefingService(private val agents: AgentsModule, private val runtime: AgentRuntime) {
    private val mongo get() = agents.mongo
    private val clock get() = agents.services.clock

    /** The company's today, in its timezone. */
    fun today(tenant: Tenant): LocalDate = clock().toLocalDateTime(zoneOf(tenant)).date

    suspend fun list(tenant: Tenant): List<BriefingDto> {
        val zone = zoneOf(tenant)
        val today = clock().toLocalDateTime(zone).date
        val availability = agents.availability(tenant)
        return agents.agents.list(tenant.id).filter(::isBriefing).map { agent ->
            val runs = runsOn(tenant, agent, today, zone)
            val step = messageStep(agent.definition)
            val messages = messagesFor(tenant, runs.values, step)
            val pending = pendingApprovals(tenant, agent)
            val states = runs.values.map { stateOf(it.last(), step, messages, pending).state }
            summary(agent, agents.validator.validate(agent.definition, availability, agent.templateParams), counts(states))
        }
    }

    /** Every member of the team on [day] (today or earlier), their briefing's outcome first, else what it would send today. */
    suspend fun day(tenant: Tenant, agent: Agent, day: LocalDate, canManage: Boolean): BriefingDayDto {
        val zone = zoneOf(tenant)
        val now = clock()
        val today = now.toLocalDateTime(zone).date
        val employees = EmployeeRepository(mongo, tenant.id).active()
        val tags = RecordTagRepository(mongo, tenant.id).forKind(TagKind.EMPLOYEE)
        val runs = runsOn(tenant, agent, day, zone)
        val step = messageStep(agent.definition)
        val messages = messagesFor(tenant, runs.values, step)
        val pending = pendingApprovals(tenant, agent)
        val trigger = scheduleTrigger(agent)
        val scheduledAt = trigger?.let { fireOn(it, day, zone) }
        val previews = if (day == today) Previews(tenant, agent, step, trigger, now, zone) else null
        val people = employees.map { employee ->
            val id = employee.id.toHexString()
            val ran = runs[id]
            when {
                ran != null -> stateOf(ran.last(), step, messages, pending).row(employee, sends = ran.size)
                EmployeeAudience.optedOut(tags[id].orEmpty()) -> Outcome(BriefingStates.OPTED_OUT).row(employee)
                !EmployeeAudience.hasPhone(employee.phone) -> Outcome(BriefingStates.NO_PHONE).row(employee)
                previews == null -> Outcome(BriefingStates.NOT_SENT).row(employee)
                else -> {
                    val preview = previews.of(employee)
                    val waitsForSchedule = agent.status == AgentStatus.ACTIVE && scheduledAt != null && scheduledAt > now
                    when {
                        preview.state != null -> Outcome(preview.state).row(employee)
                        waitsForSchedule -> Outcome(BriefingStates.SCHEDULED, message = preview.message, warnings = preview.warnings, at = scheduledAt).row(employee)
                        else -> Outcome(BriefingStates.READY, message = preview.message, warnings = preview.warnings).row(employee)
                    }
                }
            }
        }
        val problems = agents.validator.validate(agent.definition, agents.availability(tenant), agent.templateParams)
        val counted = runs.values.map { stateOf(it.last(), step, messages, pending).state }
        return BriefingDayDto(summary(agent, problems, counts(counted)), day.toString(), today.toString(), scheduledAt?.toString(), people, canManage)
    }

    /**
     * Sends today's briefing now to [employeeIds] (everyone in the audience when null) who haven't had it today, or
     * once more to each with [again]. Someone the briefing's conditions leave out, or whose day has nothing for it,
     * gets no run. Before the day's scheduled time a send takes the run the schedule would start, so the schedule
     * doesn't send it twice. Each run checks the conditions and the guard again when it goes.
     */
    suspend fun send(tenant: Tenant, agent: Agent, employeeIds: List<String>?, again: Boolean, userId: String): BriefingSendDto {
        val trigger = scheduleTrigger(agent) ?: return BriefingSendDto(0, emptyList())
        val zone = zoneOf(tenant)
        val now = clock()
        val today = now.toLocalDateTime(zone).date
        val employees = EmployeeRepository(mongo, tenant.id).active()
        val tags = RecordTagRepository(mongo, tenant.id).forKind(TagKind.EMPLOYEE)
        val wanted = employeeIds?.toSet()
        val sentToday = runsOn(tenant, agent, today, zone).keys
        // The run today's schedule is still going to start, if it fires later today.
        val laterToday = agent.scheduleState[trigger.id]?.takeIf { it > now && it.toLocalDateTime(zone).date == today }
        val where = ScheduleTrigger.where(trigger.config)
        // Someone whose day has nothing for it gets no run, so "sent to N" counts only messages that go.
        val ahead = Previews(tenant, agent, messageStep(agent.definition), trigger, now, zone)
        val skipped = mutableListOf<BriefingSkipDto>()
        wanted?.filter { id -> employees.none { it.id.toHexString() == id } }?.forEach { skipped += BriefingSkipDto(it, "not_found") }
        var started = 0
        for (employee in employees.filter { wanted == null || it.id.toHexString() in wanted }) {
            val id = employee.id.toHexString()
            val refused = when {
                EmployeeAudience.optedOut(tags[id].orEmpty()) -> "opted_out"
                !EmployeeAudience.hasPhone(employee.phone) -> "no_phone"
                !again && id in sentToday -> "already_sent"
                else -> when (ahead.of(employee, message = false).state) {
                    BriefingStates.NOTHING_TODAY -> "nothing_today"
                    BriefingStates.NOT_INCLUDED -> "conditions_not_met"
                    else -> null
                }
            }
            if (refused != null) {
                // Everyone at once leaves out quietly who can't or already had it; a person asked for by name hears why.
                if (wanted != null) skipped += BriefingSkipDto(id, refused)
                continue
            }
            val key = when {
                again -> AgentRunStarter.manualKey()
                laterToday != null -> AgentScheduler.scheduleKey(trigger.id, laterToday, employee.id)
                else -> "briefing:${agent.id.toHexString()}:$today:$id"
            }
            val result = runtime.starter.start(
                tenant = tenant,
                agent = agent,
                trigger = RunTrigger(type = TriggerTypes.MANUAL, triggerId = trigger.id, firedAt = now, byUserId = userId),
                subject = SubjectRef.of(SubjectTypes.EMPLOYEE, employee.id),
                dedupeKey = key,
                manual = true,
                extraConditions = where,
            )
            when (result) {
                is StartResult.Started -> started += 1
                StartResult.Duplicate -> skipped += BriefingSkipDto(id, "already_sent")
                is StartResult.Skipped -> skipped += BriefingSkipDto(id, result.reason)
            }
        }
        return BriefingSendDto(started, skipped)
    }

    /** Approves the briefing's messages waiting for a person, for [employeeIds] or everyone; how many went ahead. */
    suspend fun approve(tenant: Tenant, agent: Agent, employeeIds: List<String>?, canDecide: (Approvers) -> Boolean, userId: String, userName: String): Int {
        val wanted = employeeIds?.toSet()
        val waiting = agents.approvals.list(tenant.id, ApprovalStatus.PENDING, agent.id, limit = 500).filter { approval ->
            val subject = approval.subject
            subject?.type == SubjectTypes.EMPLOYEE && (wanted == null || subject.id in wanted) && canDecide(approval.approvers)
        }
        return waiting.count { runtime.approve(tenant, it.id, userId, userName, null) != null }
    }

    // ── what a person's row says ─────────────────────────────────────

    private data class Outcome(
        val state: String,
        val detail: String? = null,
        val message: String? = null,
        val warnings: List<String> = emptyList(),
        val at: Instant? = null,
        val runId: String? = null,
        val approvalId: String? = null,
    ) {
        fun row(employee: Employee, sends: Int = 0) = BriefingPersonDto(
            employeeId = employee.id.toHexString(),
            name = employee.name,
            phone = employee.phone,
            role = employee.role,
            state = state,
            detail = detail,
            message = message,
            warnings = warnings,
            at = at?.toString(),
            runId = runId,
            approvalId = approvalId,
            sends = sends,
        )
    }

    /** A run's outcome for its person: how far it got, and for a sent message, WhatsApp's ticks. */
    private fun stateOf(run: AgentRun, step: StepSpec?, messages: Map<String, Message>, pending: Map<String, AgentApproval>): Outcome {
        val result = step?.let { s -> run.steps.firstOrNull { it.stepId == s.id } }
        val runId = run.id.toHexString()
        val written = result?.input?.string("text")
        return when (run.status) {
            RunStatus.QUEUED, RunStatus.RUNNING -> Outcome(BriefingStates.SENDING, message = written, at = run.createdAt, runId = runId)
            RunStatus.WAITING -> Outcome(BriefingStates.WAITING, detail = result?.note ?: "wait", message = written, at = run.resumeAt, runId = runId)
            RunStatus.AWAITING_APPROVAL -> {
                val approval = pending[runId]
                Outcome(BriefingStates.AWAITING_APPROVAL, message = approval?.preview?.body ?: written, at = run.updatedAt, runId = runId, approvalId = approval?.id?.toHexString())
            }
            RunStatus.FAILED, RunStatus.NEEDS_REVIEW -> Outcome(
                BriefingStates.FAILED,
                detail = result?.error ?: run.error ?: run.outcome,
                message = written,
                at = run.finishedAt ?: run.updatedAt,
                runId = runId,
            )
            RunStatus.CANCELLED -> Outcome(BriefingStates.CANCELLED, detail = run.outcome, message = written, at = run.finishedAt ?: run.updatedAt, runId = runId)
            RunStatus.SKIPPED -> Outcome(BriefingStates.NOT_INCLUDED, at = run.createdAt, runId = runId)
            RunStatus.SUCCEEDED -> sentOutcome(run, result, messages, written)
        }
    }

    private fun sentOutcome(run: AgentRun, result: StepResult?, messages: Map<String, Message>, written: String?): Outcome {
        val runId = run.id.toHexString()
        val finished = run.finishedAt ?: run.updatedAt
        return when {
            result == null || (result.status == StepStatus.SKIPPED && result.note == "guard_not_met") ->
                Outcome(BriefingStates.NOTHING_TODAY, at = finished, runId = runId)
            result.status == StepStatus.SKIPPED -> Outcome(BriefingStates.SKIPPED, detail = result.note, message = written, at = finished, runId = runId)
            result.status == StepStatus.REJECTED -> Outcome(BriefingStates.CANCELLED, detail = result.note, message = written, at = finished, runId = runId)
            result.status == StepStatus.DRAFTED -> Outcome(BriefingStates.DRAFTED, message = result.output?.string("body") ?: written, at = finished, runId = runId)
            result.note?.startsWith("fallback") == true -> Outcome(BriefingStates.FALLBACK, detail = result.note, message = written, at = finished, runId = runId)
            else -> {
                val message = result.output?.string("messageId")?.let { messages[it] }
                val state = when (message?.status) {
                    MessageStatus.DELIVERED -> BriefingStates.DELIVERED
                    MessageStatus.READ -> BriefingStates.READ
                    MessageStatus.FAILED -> BriefingStates.FAILED
                    else -> BriefingStates.SENT
                }
                val body = when (val content = message?.content) {
                    is MessageContent.Template -> content.body
                    is MessageContent.Text -> content.body
                    else -> written
                }
                Outcome(state, detail = message?.errorText ?: message?.errorCode?.toString(), message = body, at = message?.statusAt ?: finished, runId = runId)
            }
        }
    }

    private fun counts(states: List<String>) = BriefingCountsDto(
        people = states.size,
        sent = states.count { it in SENT_STATES },
        read = states.count { it == BriefingStates.READ },
        failed = states.count { it == BriefingStates.FAILED },
        waiting = states.count { it == BriefingStates.SENDING || it == BriefingStates.WAITING },
        approval = states.count { it == BriefingStates.AWAITING_APPROVAL },
    )

    // ── today's message, as the run would build it ───────────────────

    private class Preview(val state: String? = null, val message: String? = null, val warnings: List<String> = emptyList())

    /**
     * What each person's run would send today, built like the run builds it (their day, the briefing's conditions,
     * the step's guard, the step's own preview) without storing anything: a dry run in memory.
     */
    private inner class Previews(
        private val tenant: Tenant,
        private val agent: Agent,
        private val step: StepSpec?,
        private val trigger: TriggerSpec?,
        private val now: Instant,
        private val zone: TimeZone,
    ) {
        private val today = now.toLocalDateTime(zone).date
        private val locale = agent.definition.voice.language?.takeIf { it.isNotBlank() } ?: tenant.locale
        private val formatter = ValueFormatter(locale, zone)
        private val where = trigger?.let { ScheduleTrigger.where(it.config) }
        private var settings: AgentSettings? = null

        /** With [message] false, only whether the run would send anything (`state` null when it would). */
        suspend fun of(employee: Employee, message: Boolean = true): Preview {
            val subject = SubjectRef.of(SubjectTypes.EMPLOYEE, employee.id)
            val context = runtime.contextBuilder.build(tenant, subject, params = agent.templateParams, locale = locale)
            val variables = context.variables
            val included = context.exists &&
                ConditionEvaluator.matches(agent.definition.conditions, variables, today, zone) &&
                ConditionEvaluator.matches(where, variables, today, zone)
            if (!included) return Preview(BriefingStates.NOT_INCLUDED)
            val send = step ?: return Preview(BriefingStates.NOTHING_TODAY)
            if (send.guard != null && !ConditionEvaluator.matches(send.guard, variables, today, zone)) return Preview(BriefingStates.NOTHING_TODAY)
            val action = agents.registry.action(send.action) ?: return Preview(BriefingStates.NOTHING_TODAY)
            if (!message) return Preview()
            val input = SchemaValidator.coerce(action.inputSchema, TemplateRenderer.renderJson(Schema.withDefaults(action.inputSchema, send.input), variables, formatter))
            val run = AgentRun(
                tenantId = tenant.id,
                agentId = agent.id,
                agentName = agent.name,
                agentVersion = agent.version,
                definition = agent.definition,
                trigger = RunTrigger(type = PREVIEW, firedAt = now),
                subject = subject,
                subjectLabel = context.label,
                dedupeKey = PREVIEW,
                context = variables,
                dryRun = true,
                createdAt = now,
                updatedAt = now,
            )
            val loaded = settings ?: agents.settings.get(tenant.id).also { settings = it }
            val ctx = RunContext(
                tenant, run, send, variables, loaded, agents.services, now,
                send.autonomy ?: agent.definition.policy.autonomy, agents.registry, availability = { agents.availability(tenant) },
            )
            // A preview that can't reach WhatsApp (no channel, Meta down) still shows the message's own text.
            val preview = runCatching { action.preview(input, ctx) }.getOrNull()
            return Preview(message = preview?.body ?: input.string("text"), warnings = preview?.warnings.orEmpty())
        }
    }

    // ── lookups ──────────────────────────────────────────────────────

    /** The day's runs by employee id, oldest first. */
    private suspend fun runsOn(tenant: Tenant, agent: Agent, day: LocalDate, zone: TimeZone): Map<String, List<AgentRun>> {
        val from = day.atStartOfDayIn(zone)
        val to = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
        return agents.runs.forAgentBetween(tenant.id, agent.id, from, to)
            .filter { it.subject?.type == SubjectTypes.EMPLOYEE }
            .groupBy { it.subject!!.id }
    }

    /** The messages the day's sends stored, by WhatsApp id, for their ticks. */
    private suspend fun messagesFor(tenant: Tenant, runs: Collection<List<AgentRun>>, step: StepSpec?): Map<String, Message> {
        if (step == null) return emptyMap()
        val ids = runs.mapNotNull { it.last().steps.firstOrNull { result -> result.stepId == step.id }?.output?.string("messageId") }
        return MessageRepository(mongo, tenant.id).byWaMessageIds(ids)
    }

    private suspend fun pendingApprovals(tenant: Tenant, agent: Agent): Map<String, AgentApproval> =
        agents.approvals.list(tenant.id, ApprovalStatus.PENDING, agent.id, limit = 500).associateBy { it.runId.toHexString() }

    /** The step that writes to the person: the briefing's first step that sends a message. */
    private fun messageStep(definition: AgentDefinition): StepSpec? =
        definition.steps.firstOrNull { agents.registry.action(it.action)?.sideEffect == SideEffect.EXTERNAL_MESSAGE }

    private fun summary(agent: Agent, problems: List<DefinitionProblem>, today: BriefingCountsDto): BriefingDto {
        val trigger = scheduleTrigger(agent)
        val step = messageStep(agent.definition)
        return BriefingDto(
            id = agent.id.toHexString(),
            name = agent.name,
            status = agent.status.name,
            icon = agent.icon,
            templateKey = agent.templateKey,
            schedule = trigger?.config?.let { config ->
                BriefingScheduleDto(config.string("frequency") ?: "daily", config.string("time"), config.ints("weekdays").filter { it in 1..7 }.sorted())
            },
            nextRunAt = trigger?.takeIf { agent.status == AgentStatus.ACTIVE }?.let { agent.scheduleState[it.id] }?.toString(),
            template = step?.input?.string("template"),
            templateLanguage = step?.input?.string("templateLanguage"),
            groups = groupsRead(agent.definition),
            asksFirst = step != null && (step.autonomy ?: agent.definition.policy.autonomy) == Autonomy.APPROVE,
            problems = problems,
            today = today,
        )
    }

    /** Which groups of a person's day the briefing reads, in [AgentVariables.moduleOfGroup]'s order. */
    private fun groupsRead(definition: AgentDefinition): List<String> {
        val paths = definition.steps.flatMap { step ->
            TemplateRenderer.referencesIn(step.input) + step.guard?.conditions.orEmpty().map { it.field }
        } + definition.conditions?.conditions.orEmpty().map { it.field }
        val groups = paths.map { it.substringBefore('.') }.toSet()
        return AgentVariables.moduleOfGroup.keys.filter { it in groups }
    }

    companion object {
        private const val PREVIEW = "preview"
        private val SENT_STATES = setOf(BriefingStates.SENT, BriefingStates.DELIVERED, BriefingStates.READ)

        /** A briefing: an agent with a schedule that runs once per employee. */
        fun isBriefing(agent: Agent): Boolean = scheduleTrigger(agent) != null

        fun scheduleTrigger(agent: Agent): TriggerSpec? =
            agent.definition.triggers.firstOrNull { it.type == TriggerTypes.SCHEDULE && it.config.string("forEach") == SubjectTypes.EMPLOYEE }

        /** When [trigger] fires on [day], if it does (the day's first fire, for a schedule that runs every few hours). */
        fun fireOn(trigger: TriggerSpec, day: LocalDate, zone: TimeZone): Instant? {
            val start = day.atStartOfDayIn(zone)
            return ScheduleCalculator.nextFire(trigger.config, start - 1.milliseconds, zone)
                ?.takeIf { it.toLocalDateTime(zone).date == day }
        }

        private fun zoneOf(tenant: Tenant) = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
    }
}
