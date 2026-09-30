package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.OutboundStatus
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.model.StepStatus
import com.rfm.edubot.agents.model.TriggerSpec
import com.rfm.edubot.agents.registry.DateOffsetTrigger
import com.rfm.edubot.agents.registry.ScheduleTrigger
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.registry.int
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.registry.strings
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The time-driven half of the runtime, run on a lease by one instance: fires due schedules, sweeps
 * date-offset and inactivity triggers, resumes waiting runs, expires approvals and recovers runs a
 * stopped instance left mid-step.
 */
class AgentScheduler(
    private val module: AgentsModule,
    private val tenants: suspend (ObjectId) -> Tenant?,
    private val starter: AgentRunStarter,
    private val queries: SubjectQueries,
    private val executor: AgentRunExecutor,
    private val submit: (ObjectId, String) -> Unit,
) {
    private val log = LoggerFactory.getLogger("AgentScheduler")
    private val clock get() = module.services.clock
    private var lastSweep: Instant? = null
    private var lastClientSweep: Instant? = null

    suspend fun tick() {
        val now = clock()
        fireSchedules(now)
        if (lastSweep.olderThan(now, SWEEP_EVERY)) {
            sweepDateOffsets(now)
            sweepInactivity(now, includeClients = lastClientSweep.olderThan(now, CLIENT_SWEEP_EVERY))
            if (lastClientSweep.olderThan(now, CLIENT_SWEEP_EVERY)) lastClientSweep = now
            lastSweep = now
        }
        resumeWaiting(now)
        resubmitQueued(now)
        expireApprovals(now)
        recoverInterrupted(now)
    }

    /** Next fire time of each schedule trigger, from [after]; called when an agent is activated or edited. */
    fun scheduleState(agent: Agent, tenant: Tenant, after: Instant): Map<String, Instant> {
        val zone = zoneOf(tenant)
        return agent.definition.triggers.filter { it.type == TriggerTypes.SCHEDULE }
            .mapNotNull { trigger -> ScheduleCalculator.nextFire(trigger.config, after, zone)?.let { trigger.id to it } }
            .toMap()
    }

    private suspend fun fireSchedules(now: Instant) {
        for (agent in module.agents.dueSchedules(now)) {
            val tenant = tenants(agent.tenantId) ?: continue
            val due = agent.scheduleState.filterValues { it <= now }
            if (due.isEmpty()) continue
            val zone = zoneOf(tenant)
            val next = agent.scheduleState.toMutableMap()
            val triggers = agent.definition.triggers.associateBy { it.id }
            due.keys.forEach { triggerId ->
                val trigger = triggers[triggerId]
                val fire = trigger?.let { ScheduleCalculator.nextFire(it.config, now, zone) }
                if (fire == null) next.remove(triggerId) else next[triggerId] = fire
            }
            val expected = agent.scheduleState.values.min()
            if (!module.agents.advanceSchedule(agent.tenantId, agent.id, expected, next)) continue
            for ((triggerId, fireAt) in due) {
                val trigger = triggers[triggerId] ?: continue
                if (fireAt < now - SCHEDULE_CATCH_UP) {
                    log.info("Skipping a schedule fire missed by more than {}: agent={} trigger={}", SCHEDULE_CATCH_UP, agent.id, triggerId)
                    continue
                }
                fireSchedule(tenant, agent, trigger, fireAt, now)
            }
        }
    }

    private suspend fun fireSchedule(tenant: Tenant, agent: Agent, trigger: TriggerSpec, fireAt: Instant, now: Instant) {
        val runTrigger = RunTrigger(type = TriggerTypes.SCHEDULE, triggerId = trigger.id, firedAt = now)
        val entity = trigger.config.string("forEach")
        val key = "schedule:${trigger.id}:${fireAt.toEpochMilliseconds()}"
        if (entity == null) {
            starter.start(tenant, agent, runTrigger, subject = null, dedupeKey = key)
            return
        }
        val where = ScheduleTrigger.where(trigger.config)
        for (id in queries.forEachCandidates(tenant.id, entity, now)) {
            starter.start(tenant, agent, runTrigger, SubjectRef.of(entity, id), "$key:${id.toHexString()}", extraConditions = where)
        }
    }

    private suspend fun sweepDateOffsets(now: Instant) {
        for (agent in module.agents.activeWithTriggerType(TriggerTypes.DATE_OFFSET)) {
            val tenant = tenants(agent.tenantId) ?: continue
            val zone = zoneOf(tenant)
            for (trigger in agent.definition.triggers.filter { it.type == TriggerTypes.DATE_OFFSET }) {
                val entity = trigger.config.string("entity") ?: continue
                val offsetDays = trigger.config.int("offsetDays") ?: 0
                val offsetHours = trigger.config.int("offsetHours") ?: 0
                val statuses = trigger.config.strings("statuses").ifEmpty { defaultStatuses(entity, offsetDays * 24 + offsetHours) }
                val hits = if (entity == SubjectTypes.BOOKING) {
                    val offset = (offsetDays * 24 + offsetHours).hours
                    // The fire moment (start + offset) fell in the catch-up window up to now.
                    queries.bookingsStartingBetween(tenant.id, now - BOOKING_CATCH_UP - offset, now - offset, statuses)
                        .filter { offset.isPositive() || Instant.fromEpochMilliseconds(it.stamp.toLong()) > now }
                } else {
                    val at = ScheduleCalculator.parseTime(trigger.config.string("at")) ?: LocalTime(9, 0)
                    val dates = fireDates(now, zone, at).map { it.minus(offsetDays, DateTimeUnit.DAY) }
                    queries.dueOn(tenant.id, entity, dates, statuses)
                }
                hits.forEach { hit ->
                    starter.start(
                        tenant, agent,
                        RunTrigger(type = TriggerTypes.DATE_OFFSET, triggerId = trigger.id, firedAt = now),
                        SubjectRef.of(entity, hit.id),
                        "date:${trigger.id}:${hit.id.toHexString()}:${hit.stamp}",
                    )
                }
            }
        }
    }

    private suspend fun sweepInactivity(now: Instant, includeClients: Boolean) {
        for (agent in module.agents.activeWithTriggerType(TriggerTypes.INACTIVITY)) {
            val tenant = tenants(agent.tenantId) ?: continue
            for (trigger in agent.definition.triggers.filter { it.type == TriggerTypes.INACTIVITY }) {
                val entity = trigger.config.string("entity") ?: continue
                val threshold = (trigger.config.int("days") ?: 0).days + (trigger.config.int("hours") ?: 0).hours + (trigger.config.int("minutes") ?: 0).minutes
                if (!threshold.isPositive()) continue
                val before = now - threshold
                val hits = when (entity) {
                    SubjectTypes.QUOTE -> queries.quotesSentBetween(tenant.id, before - 30.days, before)
                    SubjectTypes.CONVERSATION -> queries.conversationsWaiting(tenant.id, before - 1.days, before)
                    SubjectTypes.CLIENT -> if (includeClients) queries.quietClients(tenant.id, before) else emptyList()
                    else -> emptyList()
                }
                hits.forEach { hit ->
                    starter.start(
                        tenant, agent,
                        RunTrigger(type = TriggerTypes.INACTIVITY, triggerId = trigger.id, firedAt = now),
                        SubjectRef.of(entity, hit.id),
                        "idle:${trigger.id}:${hit.id.toHexString()}:${hit.stamp}",
                    )
                }
            }
        }
    }

    private suspend fun resumeWaiting(now: Instant) {
        module.runs.dueWaiting(now).forEach { submit(it.id, laneKey(it.tenantId, it.subject, it.id)) }
    }

    private suspend fun resubmitQueued(now: Instant) {
        module.runs.queued(now - 1.minutes).filter { !it.dryRun }.forEach { submit(it.id, laneKey(it.tenantId, it.subject, it.id)) }
    }

    private suspend fun expireApprovals(now: Instant) {
        repeat(200) {
            val expired = module.approvals.expireNext(now) ?: return
            executor.continueAfterDecision(expired.runId)
        }
    }

    /**
     * Runs another instance left RUNNING. A step that only reads is repeated; one that may have sent a
     * message or changed a record is checked against the outbound log, and goes to a person when unsure.
     */
    suspend fun recoverInterrupted(now: Instant, claimedBefore: Instant = now - STALE_RUN) {
        for (run in module.runs.interrupted(claimedBefore)) {
            val running = run.steps.lastOrNull { it.status == StepStatus.RUNNING }
            val action = running?.let { module.registry.action(it.action) }
            val recovered = when {
                running == null || action == null || action.sideEffect == SideEffect.NONE ->
                    run.copy(status = RunStatus.WAITING, resumeAt = now)
                action.sideEffect == SideEffect.EXTERNAL_MESSAGE -> when (module.services.outboundLog.find("${run.id.toHexString()}:${running.stepId}")?.status) {
                    null, OutboundStatus.FAILED -> run.copy(status = RunStatus.WAITING, resumeAt = now)
                    OutboundStatus.SENT -> AgentRunExecutor.record(run, running.copy(status = StepStatus.DONE, finishedAt = now, note = "recovered_sent"))
                        .copy(status = RunStatus.WAITING, resumeAt = now, currentStep = run.currentStep + 1)
                    OutboundStatus.SENDING -> run.copy(status = RunStatus.NEEDS_REVIEW, outcome = "interrupted_send")
                }
                else -> run.copy(status = RunStatus.NEEDS_REVIEW, outcome = "interrupted_write")
            }
            module.runs.save(recovered)
            if (recovered.status == RunStatus.NEEDS_REVIEW) {
                module.notifications.notify(run.tenantId, NotificationKinds.AGENT_FAILED, params = mapOf("agent" to run.agentName, "subject" to run.subjectLabel.orEmpty(), "error" to (recovered.outcome ?: "")), link = "agents", subject = run.subject)
            }
            log.warn("Recovered interrupted agent run {} as {}", run.id, recovered.status)
        }
    }

    private fun defaultStatuses(entity: String, offsetHours: Int): List<String> = when (entity) {
        SubjectTypes.INVOICE, SubjectTypes.PAYMENT -> listOf("PENDING", "OVERDUE")
        SubjectTypes.QUOTE -> listOf("PENDENTE", "SENT")
        SubjectTypes.BOOKING -> if (offsetHours < 0) listOf("PENDING", "CONFIRMED") else listOf("CONFIRMED", "COMPLETED")
        else -> emptyList()
    }

    companion object {
        val TICK: Duration = 30.seconds
        private val SWEEP_EVERY = 2.minutes
        private val CLIENT_SWEEP_EVERY = 6.hours
        /** A daily reminder missed by a longer outage is dropped rather than sent late. */
        private val SCHEDULE_CATCH_UP = 6.hours
        private val BOOKING_CATCH_UP = 6.hours
        private val STALE_RUN = 10.minutes

        fun laneKey(tenantId: ObjectId, subject: SubjectRef?, runId: ObjectId): String =
            "${tenantId.toHexString()}:${subject?.let { "${it.type}:${it.id}" } ?: runId.toHexString()}"

        /** Local days whose fire time (at [at]) has passed within the last day, today first. */
        fun fireDates(now: Instant, zone: TimeZone, at: LocalTime): List<LocalDate> {
            val today = now.toLocalDateTime(zone).date
            return listOf(today, today.minus(1, DateTimeUnit.DAY)).filter { date ->
                val fire = LocalDateTime(date, at).toInstant(zone)
                fire <= now && fire > now - 1.days - 2.hours
            }
        }

        private fun Instant?.olderThan(now: Instant, age: Duration): Boolean = this == null || now - this >= age

        private fun zoneOf(tenant: Tenant) = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
    }
}
