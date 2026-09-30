package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.RunTrigger
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.messaging.ConversationLanes
import com.rfm.edubot.shared.jobs.PeriodicJob
import com.rfm.edubot.shared.jobs.SchedulerLease
import com.rfm.edubot.tenant.model.Tenant
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import org.bson.types.ObjectId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class AgentRuntimeConfig(
    val tick: Duration = 30.seconds,
    /** Workers for agent runs, separate from the customer chat lanes. */
    val lanes: Int = 4,
    val maxConcurrentPerCompany: Int = 2,
)

/**
 * The agents runtime: dispatcher (events), scheduler (time), executor (steps) on its own worker lanes,
 * so agent work never slows down customer chats. Routes and the assistant go through it.
 */
class AgentRuntime(
    private val module: AgentsModule,
    private val tenants: suspend (ObjectId) -> Tenant?,
    private val scope: CoroutineScope,
    private val lease: SchedulerLease? = null,
    private val config: AgentRuntimeConfig = AgentRuntimeConfig(),
) {
    private val clock get() = module.services.clock
    val contextBuilder = AgentContextBuilder(module.mongo, module.services.clock)
    val executor = AgentRunExecutor(module, tenants, contextBuilder, config.maxConcurrentPerCompany)
    private val lanes = ConversationLanes(scope, config.lanes)
    val starter = AgentRunStarter(module, contextBuilder) { run -> submit(run.id, AgentScheduler.laneKey(run.tenantId, run.subject, run.id)) }
    val dispatcher = AgentDispatcher(module, DomainEventLog(module.mongo, module.services.clock), tenants, starter)
    val scheduler = AgentScheduler(module, tenants, starter, SubjectQueries(module.mongo), executor, ::submit)

    fun start() {
        dispatcher.start(scope)
        PeriodicJob("agents-scheduler", config.tick, lease) { scheduler.tick() }.start(scope, initialDelay = 5.seconds)
    }

    private fun submit(runId: ObjectId, laneKey: String) {
        lanes.submit(laneKey) { executor.execute(runId) }
    }

    /** Keeps schedules and the dispatcher's cache in step with an agent's new state. */
    suspend fun onAgentChanged(tenant: Tenant, agent: Agent) {
        when (agent.status) {
            AgentStatus.ACTIVE -> module.agents.setSchedule(tenant.id, agent.id, scheduler.scheduleState(agent, tenant, clock()))
            AgentStatus.ARCHIVED -> module.runs.openForAgent(tenant.id, agent.id).forEach { run ->
                module.runs.cancelIfOpen(run.id, "agent_archived")
                module.approvals.cancelForRun(run.id)
            }
            else -> Unit
        }
        dispatcher.refresh(tenant.id)
    }

    suspend fun runManually(tenant: Tenant, agent: Agent, subject: SubjectRef?, userId: String?): StartResult =
        starter.start(
            tenant = tenant,
            agent = agent,
            trigger = RunTrigger(type = TriggerTypes.MANUAL, firedAt = clock(), byUserId = userId),
            subject = subject,
            dedupeKey = AgentRunStarter.manualKey(),
            manual = true,
        )

    /** A dry run on [subject]: every step is previewed, nothing is sent or changed, waits are skipped. */
    suspend fun test(tenant: Tenant, agent: Agent, subject: SubjectRef?, definition: AgentDefinition = agent.definition, userId: String? = null): AgentRun? {
        val started = starter.start(
            tenant = tenant,
            agent = agent,
            trigger = RunTrigger(type = "test", firedAt = clock(), byUserId = userId),
            subject = subject,
            dedupeKey = AgentRunStarter.testKey(),
            dryRun = true,
            definition = definition,
        )
        val run = (started as? StartResult.Started)?.run ?: return null
        return if (run.status == RunStatus.QUEUED) executor.executeInline(run.id) else run
    }

    suspend fun approve(tenant: Tenant, approvalId: ObjectId, userId: String?, userName: String?, editedInput: JsonObject?): AgentApproval? {
        val approval = module.approvals.approve(tenant.id, approvalId, userId, userName, editedInput) ?: return null
        continueRun(approval)
        return approval
    }

    suspend fun reject(tenant: Tenant, approvalId: ObjectId, userId: String?, userName: String?, reason: String?): AgentApproval? {
        val approval = module.approvals.reject(tenant.id, approvalId, userId, userName, reason) ?: return null
        module.agents.recordApproval(tenant.id, approval.agentId, cleanApproval = false)
        continueRun(approval)
        return approval
    }

    private suspend fun continueRun(approval: AgentApproval) {
        val run = module.runs.load(approval.runId) ?: return
        lanes.submit(AgentScheduler.laneKey(run.tenantId, run.subject, run.id)) { executor.continueAfterDecision(run.id) }
    }

    suspend fun cancelRun(tenant: Tenant, runId: ObjectId): AgentRun? {
        val run = module.runs.findById(tenant.id, runId) ?: return null
        val cancelled = module.runs.cancelIfOpen(run.id, "cancelled_by_user") ?: return null
        module.approvals.cancelForRun(run.id)
        return cancelled
    }

    /** Tries a failed run, or one waiting for review, again from the step that stopped it. */
    suspend fun retryRun(tenant: Tenant, runId: ObjectId): AgentRun? {
        val run = module.runs.findById(tenant.id, runId) ?: return null
        if (run.status != RunStatus.FAILED && run.status != RunStatus.NEEDS_REVIEW) return null
        val stepId = run.definition.steps.getOrNull(run.currentStep)?.id
        // A person chose to try again, so an interrupted send may go out once more.
        if (stepId != null && run.status == RunStatus.NEEDS_REVIEW) module.services.outboundLog.markFailed("${run.id.toHexString()}:$stepId", "retried_by_user")
        val steps = run.steps.map { if (it.stepId == stepId) it.copy(attempts = 0) else it }
        val reopened = module.runs.save(run.copy(status = RunStatus.WAITING, resumeAt = clock(), finishedAt = null, error = null, outcome = null, steps = steps))
        submit(reopened.id, AgentScheduler.laneKey(reopened.tenantId, reopened.subject, reopened.id))
        return reopened
    }
}
