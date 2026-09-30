package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.events.DomainEvent
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

sealed class StartResult {
    data class Started(val run: AgentRun) : StartResult()
    /** The trigger already fired for this occurrence. */
    data object Duplicate : StartResult()
    /** [reason]: paused, daily_limit, company_daily_limit, cooldown, record_missing, automation_paused, conditions_not_met, inactive. */
    data class Skipped(val reason: String) : StartResult()
}

/**
 * Turns a trigger firing into a run: checks pauses, daily caps, the per-record cooldown, the client's
 * opt-out and the agent's "only if" conditions, then stores the run (deduplicated) and hands it to
 * the executor. Dry runs skip the caps and always produce a run to show.
 */
class AgentRunStarter(
    private val module: AgentsModule,
    private val contextBuilder: AgentContextBuilder,
    private val submit: (AgentRun) -> Unit,
) {
    private val log = LoggerFactory.getLogger("AgentRunStarter")
    private val clock get() = module.services.clock

    suspend fun start(
        tenant: Tenant,
        agent: Agent,
        trigger: RunTrigger,
        subject: SubjectRef?,
        dedupeKey: String,
        event: DomainEvent? = null,
        depth: Int = event?.depth ?: 0,
        dryRun: Boolean = false,
        definition: AgentDefinition = agent.definition,
        manual: Boolean = false,
        /** A per-record schedule's own `where`, checked together with the agent's conditions. */
        extraConditions: ConditionGroup? = null,
    ): StartResult {
        val now = clock()
        if (!dryRun) {
            if (agent.status != AgentStatus.ACTIVE) return StartResult.Skipped("inactive")
            val settings = module.settings.get(tenant.id)
            if (settings.company.paused || settings.platform.agentsPaused) return StartResult.Skipped("paused")
            if (module.runs.countSince(tenant.id, now - 1.days, agent.id) >= definition.policy.maxRunsPerDay) return StartResult.Skipped("daily_limit")
            if (module.runs.countSince(tenant.id, now - 1.days) >= settings.platform.runsPerDay) return StartResult.Skipped("company_daily_limit")
            val cooldown = definition.policy.cooldownHours
            if (cooldown > 0 && subject != null && !manual) {
                val last = module.runs.lastForSubject(tenant.id, agent.id, subject)
                if (last != null && last.createdAt > now - cooldown.hours) return StartResult.Skipped("cooldown")
            }
        }
        val locale = definition.voice.language?.takeIf { it.isNotBlank() } ?: tenant.locale
        val context = contextBuilder.build(tenant, subject, event = event, params = agent.templateParams, locale = locale)
        if (!context.exists) return StartResult.Skipped("record_missing")
        if (context.automationPaused && !dryRun) return StartResult.Skipped("automation_paused")

        val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
        val today = now.toLocalDateTime(zone).date
        val matches = ConditionEvaluator.matches(definition.conditions, context.variables, today, zone) &&
            ConditionEvaluator.matches(extraConditions, context.variables, today, zone)
        if (!matches && !dryRun) return StartResult.Skipped("conditions_not_met")

        val run = AgentRun(
            tenantId = tenant.id,
            agentId = agent.id,
            agentName = agent.name,
            agentVersion = agent.version,
            definition = definition,
            trigger = trigger,
            subject = subject,
            subjectLabel = context.label,
            dedupeKey = dedupeKey,
            status = if (matches) RunStatus.QUEUED else RunStatus.SKIPPED,
            context = context.variables,
            depth = depth,
            dryRun = dryRun,
            createdAt = now,
            updatedAt = now,
            finishedAt = if (matches) null else now,
            outcome = if (matches) null else "conditions_not_met",
        )
        val stored = module.runs.insertIfAbsent(run) ?: return StartResult.Duplicate
        if (stored.status == RunStatus.QUEUED && !dryRun) submit(stored)
        log.info("Agent run started: tenant={} agent={} run={} trigger={} subject={}", tenant.slug, agent.id, stored.id, trigger.type, subject?.id)
        return StartResult.Started(stored)
    }

    companion object {
        fun manualKey(): String = "manual:${ObjectId().toHexString()}"
        fun testKey(): String = "test:${ObjectId().toHexString()}"
    }
}