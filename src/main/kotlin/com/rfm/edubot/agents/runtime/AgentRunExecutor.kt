package com.rfm.edubot.agents.runtime

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentRun
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.model.Approvers
import com.rfm.edubot.agents.model.Autonomy
import com.rfm.edubot.agents.model.ErrorPolicy
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.StepResult
import com.rfm.edubot.agents.model.StepSpec
import com.rfm.edubot.agents.model.StepStatus
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.ProposedAction
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SchemaValidator
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.store.AgentJson
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.events.ActorContext
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Moves a run through its steps until it has to wait (a wait step, quiet hours, a retry, an approval)
 * or it ends. Progress is saved after every step, and a step that changes something is marked running
 * before it starts, so a restart knows whether it may repeat it.
 */
class AgentRunExecutor(
    private val module: AgentsModule,
    private val tenants: suspend (ObjectId) -> Tenant?,
    private val contextBuilder: AgentContextBuilder,
    private val maxConcurrentPerCompany: Int = 2,
) {
    private val log = LoggerFactory.getLogger("AgentRunExecutor")
    private val clock get() = module.services.clock
    private val semaphores = ConcurrentHashMap<ObjectId, Semaphore>()

    /** Picks up a queued or waiting run. Does nothing when another worker already has it. */
    suspend fun execute(runId: ObjectId) {
        val run = module.runs.claim(runId, setOf(RunStatus.QUEUED, RunStatus.WAITING)) ?: return
        permit(run.tenantId) { advanceSafely(run) }
    }

    /** Runs a dry run to the end in the caller's coroutine, for "Test on a record". */
    suspend fun executeInline(runId: ObjectId): AgentRun? {
        val run = module.runs.claim(runId, setOf(RunStatus.QUEUED)) ?: return module.runs.load(runId)
        advanceSafely(run)
        return module.runs.load(runId)
    }

    /** Continues a run once every approval of its current step has been decided. */
    suspend fun continueAfterDecision(runId: ObjectId) {
        val waiting = module.runs.load(runId) ?: return
        if (waiting.status != RunStatus.AWAITING_APPROVAL) return
        val step = waiting.definition.steps.getOrNull(waiting.currentStep) ?: return
        if (module.approvals.forRun(runId).any { it.stepId == step.id && it.status == ApprovalStatus.PENDING }) return
        val run = module.runs.claim(runId, setOf(RunStatus.AWAITING_APPROVAL)) ?: return
        permit(run.tenantId) { advanceSafely(run) }
    }

    private suspend fun permit(tenantId: ObjectId, block: suspend () -> Unit) {
        semaphores.computeIfAbsent(tenantId) { Semaphore(maxConcurrentPerCompany.coerceAtLeast(1)) }.withPermit { block() }
    }

    private suspend fun advanceSafely(run: AgentRun) {
        try {
            advance(run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Agent run {} crashed: {}", run.id, e.message, e)
            val latest = module.runs.load(run.id) ?: run
            finish(latest, RunStatus.FAILED, outcome = "crashed", error = e.message ?: e::class.simpleName)
        }
    }

    private suspend fun advance(claimed: AgentRun) {
        val now = clock()
        var run = claimed
        val tenant = tenants(run.tenantId) ?: return finish(run, RunStatus.CANCELLED, "company_missing")
        val settings = module.settings.get(tenant.id)
        val agent = module.agents.findById(tenant.id, run.agentId)
        if (agent == null || agent.status == AgentStatus.ARCHIVED) return finish(run, RunStatus.CANCELLED, "agent_removed")
        if (!run.dryRun) {
            val held = settings.company.paused || settings.platform.agentsPaused || agent.status == AgentStatus.PAUSED ||
                DashboardModules.AGENTS !in DashboardModules.effectiveFor(tenant)
            if (held) {
                module.runs.save(run.copy(status = RunStatus.WAITING, resumeAt = now + HOLD))
                return
            }
        }

        val locale = run.definition.voice.language?.takeIf { it.isNotBlank() } ?: tenant.locale
        val subject = contextBuilder.build(tenant, run.subject, previous = run.context, locale = locale)
        if (!subject.exists) return finish(run, RunStatus.CANCELLED, "record_removed")
        if (subject.automationPaused && !run.dryRun) return finish(run, RunStatus.CANCELLED, "automation_paused")
        var variables = subject.variables
        run = run.copy(startedAt = run.startedAt ?: now, subjectLabel = subject.label ?: run.subjectLabel, context = variables)

        val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
        val formatter = ValueFormatter(locale, zone)
        val steps = run.definition.steps
        var index = run.currentStep
        while (index < steps.size) {
            val step = steps[index]
            val today = clock().toLocalDateTime(zone).date
            if (step.guard != null && !ConditionEvaluator.matches(step.guard, variables, today, zone)) {
                run = record(run, StepResult(step.id, step.action, StepStatus.SKIPPED, finishedAt = clock(), note = "guard_not_met"))
                index += 1
                run = module.runs.save(run.copy(currentStep = index))
                continue
            }
            val action = module.registry.action(step.action)
                ?: return finish(record(run, StepResult(step.id, step.action, StepStatus.FAILED, error = "unknown_action")), RunStatus.FAILED, error = "unknown_action")
            val autonomy = step.autonomy ?: run.definition.policy.autonomy
            val input = SchemaValidator.coerce(action.inputSchema, TemplateRenderer.renderJson(Schema.withDefaults(action.inputSchema, step.input), variables, formatter))
            val ctx = RunContext(tenant, run, step, variables, settings, module.services, clock(), autonomy)
            val decisions = if (run.dryRun) emptyList() else module.approvals.forRun(run.id).filter { it.stepId == step.id }

            val result: ActionResult = when {
                decisions.isNotEmpty() -> {
                    if (decisions.any { it.status == ApprovalStatus.PENDING }) {
                        module.runs.save(run.copy(status = RunStatus.AWAITING_APPROVAL, currentStep = index))
                        return
                    }
                    val approved = decisions.filter { it.status == ApprovalStatus.APPROVED }
                    if (approved.isEmpty()) {
                        val expired = decisions.all { it.status == ApprovalStatus.EXPIRED }
                        run = record(run, StepResult(step.id, step.action, StepStatus.REJECTED, finishedAt = clock(), note = if (expired) "approval_expired" else "rejected"))
                        return finish(run, RunStatus.CANCELLED, if (expired) "approval_expired" else "rejected")
                    }
                    runApproved(approved, ctx, run)
                }
                action.sideEffect != SideEffect.NONE && (run.dryRun || autonomy == Autonomy.DRAFT) -> {
                    val preview = action.preview(input, ctx)
                    run = record(run, StepResult(step.id, step.action, StepStatus.DRAFTED, clock(), clock(), input = input, output = previewJson(preview)))
                    variables = withStepOutput(variables, step.id, previewJson(preview))
                    index += 1
                    run = module.runs.save(run.copy(currentStep = index, context = variables))
                    continue
                }
                action.sideEffect != SideEffect.NONE && autonomy == Autonomy.APPROVE -> {
                    val preview = action.preview(input, ctx)
                    requestApprovals(run.copy(currentStep = index), step, listOf(ProposedAction(action.key, input, preview)), JsonObject(emptyMap()))
                    return
                }
                else -> {
                    if (action.sideEffect == SideEffect.EXTERNAL_MESSAGE && !run.dryRun) {
                        val quiet = Guardrails.quietHours(run.definition, settings.company)
                        val later = Guardrails.nextAllowed(clock(), zone, quiet, Guardrails.businessDaysOnly(run.definition, settings.company))
                        if (later != null) {
                            run = record(run, StepResult(step.id, step.action, StepStatus.WAITING, note = "quiet_hours"))
                            module.runs.save(run.copy(status = RunStatus.WAITING, currentStep = index, resumeAt = later, context = variables))
                            return
                        }
                    }
                    run = checkpoint(run, step, input, index, variables)
                    runAction(action, input, ctx)
                }
            }

            val previous = run.steps.firstOrNull { it.stepId == step.id }
            when (result) {
                is ActionResult.Done -> {
                    val output = mergeOutputs(previous?.output, result.output)
                    run = record(run, StepResult(step.id, step.action, StepStatus.DONE, previous?.startedAt ?: clock(), clock(), (previous?.attempts ?: 0) + 1, input, output, note = result.note))
                    variables = withStepOutput(variables, step.id, output)
                    index += 1
                }
                is ActionResult.Skipped -> {
                    run = record(run, StepResult(step.id, step.action, StepStatus.SKIPPED, previous?.startedAt, clock(), previous?.attempts ?: 0, input, note = result.reason))
                    index += 1
                }
                is ActionResult.Jump -> {
                    run = record(run, StepResult(step.id, step.action, StepStatus.DONE, previous?.startedAt ?: clock(), clock(), 1, input, note = result.note ?: "jump:${result.stepId}"))
                    val target = steps.indexOfFirst { it.id == result.stepId }
                    if (target <= index) return finish(run, RunStatus.SUCCEEDED, "completed")
                    index = target
                }
                is ActionResult.Stop -> {
                    run = record(run, StepResult(step.id, step.action, StepStatus.DONE, previous?.startedAt ?: clock(), clock(), 1, input, note = result.outcome))
                    return finish(run.copy(currentStep = index + 1, context = variables), RunStatus.SUCCEEDED, result.outcome)
                }
                is ActionResult.Wait -> {
                    if (run.dryRun) {
                        run = record(run, StepResult(step.id, step.action, StepStatus.DONE, clock(), clock(), 1, input, note = "would_wait_until:${result.until}"))
                        index += 1
                    } else {
                        val status = if (result.retrySameStep) StepStatus.WAITING else StepStatus.DONE
                        run = record(run, StepResult(step.id, step.action, status, previous?.startedAt ?: clock(), if (result.retrySameStep) null else clock(), (previous?.attempts ?: 0) + 1, input, note = result.note ?: "wait_until:${result.until}"))
                        val next = if (result.retrySameStep) index else index + 1
                        module.runs.save(run.copy(status = RunStatus.WAITING, currentStep = next, resumeAt = result.until, context = variables))
                        return
                    }
                }
                is ActionResult.Propose -> {
                    if (run.dryRun) {
                        val drafted = buildJsonObject {
                            put("proposals", JsonArray(result.proposals.map { buildJsonObject { put("action", it.action); put("preview", previewJson(it.preview)) } }))
                            result.output.forEach { (key, value) -> put(key, value) }
                        }
                        run = record(run, StepResult(step.id, step.action, StepStatus.DRAFTED, clock(), clock(), 1, input, drafted))
                        variables = withStepOutput(variables, step.id, drafted)
                        index += 1
                    } else if (result.proposals.isEmpty()) {
                        run = record(run, StepResult(step.id, step.action, StepStatus.DONE, clock(), clock(), 1, input, result.output))
                        variables = withStepOutput(variables, step.id, result.output)
                        index += 1
                    } else {
                        run = record(run, StepResult(step.id, step.action, StepStatus.AWAITING_APPROVAL, clock(), null, 1, input, result.output))
                        requestApprovals(run.copy(currentStep = index, context = withStepOutput(variables, step.id, result.output)), step, result.proposals, result.output)
                        return
                    }
                }
                is ActionResult.Failed -> {
                    val attempts = (previous?.attempts ?: 0) + 1
                    val failed = StepResult(step.id, step.action, StepStatus.FAILED, previous?.startedAt ?: clock(), clock(), attempts, input, error = result.error)
                    run = record(run, failed)
                    if (result.retryable && attempts < MAX_ATTEMPTS && !run.dryRun && step.onError != ErrorPolicy.STOP) {
                        module.runs.save(run.copy(status = RunStatus.WAITING, currentStep = index, resumeAt = clock() + backoff(attempts), context = variables))
                        return
                    }
                    if (step.onError == ErrorPolicy.CONTINUE) {
                        index += 1
                    } else {
                        return finish(run.copy(currentStep = index, context = variables), RunStatus.FAILED, "step_failed", "${step.id}: ${result.error}")
                    }
                }
            }
            run = module.runs.save(run.copy(currentStep = index, status = RunStatus.RUNNING, context = variables))
        }
        finish(run.copy(context = variables), RunStatus.SUCCEEDED, "completed")
    }

    private suspend fun runAction(action: AgentAction, input: JsonObject, ctx: RunContext): ActionResult =
        withContext(ActorContext(ctx.actor, ctx.run.depth + 1)) {
            try {
                action.execute(input, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Agent action {} failed in run {}: {}", action.key, ctx.run.id, e.message)
                ActionResult.Failed(e.message ?: "action_failed", retryable = action.sideEffect != SideEffect.EXTERNAL_MESSAGE)
            }
        }

    /** Runs what people approved: one action, or each of an AI step's approved proposals. */
    private suspend fun runApproved(approved: List<AgentApproval>, ctx: RunContext, run: AgentRun): ActionResult {
        val outputs = mutableListOf<JsonObject>()
        for (approval in approved.sortedBy { it.seq }) {
            val action = module.registry.action(approval.action) ?: return ActionResult.Failed("unknown_action:${approval.action}")
            val input = SchemaValidator.coerce(action.inputSchema, Schema.withDefaults(action.inputSchema, approval.input))
            val proposalCtx = RunContext(ctx.tenant, run, ctx.step.copy(id = if (approval.seq == 0) ctx.step.id else "${ctx.step.id}.${approval.seq}"), ctx.variables, ctx.settings, ctx.services, clock(), Autonomy.AUTO)
            when (val result = runAction(action, input, proposalCtx)) {
                is ActionResult.Done -> outputs += result.output
                is ActionResult.Failed -> return result
                is ActionResult.Skipped -> outputs += buildJsonObject { put("skipped", result.reason) }
                else -> outputs += JsonObject(emptyMap())
            }
            module.agents.recordApproval(run.tenantId, run.agentId, cleanApproval = !approval.edited)
        }
        val output = if (outputs.size == 1) outputs.single() else buildJsonObject { put("results", JsonArray(outputs)) }
        return ActionResult.Done(output, note = "approved")
    }

    private suspend fun requestApprovals(run: AgentRun, step: StepSpec, proposals: List<ProposedAction>, output: JsonObject) {
        val now = clock()
        val expiry = module.settings.get(run.tenantId).company.approvalExpiryDays.coerceIn(1, 30)
        val approvals = proposals.mapIndexed { seq, proposal ->
            module.approvals.insert(
                AgentApproval(
                    tenantId = run.tenantId,
                    agentId = run.agentId,
                    agentName = run.agentName,
                    runId = run.id,
                    stepId = step.id,
                    seq = seq,
                    action = proposal.action,
                    input = proposal.input,
                    preview = proposal.preview,
                    subject = run.subject,
                    subjectLabel = run.subjectLabel,
                    approvers = run.definition.policy.approvers,
                    createdAt = now,
                    expiresAt = now + expiry.days,
                ),
            )
        }
        val recorded = if (run.steps.any { it.stepId == step.id }) run else record(run, StepResult(step.id, step.action, StepStatus.AWAITING_APPROVAL, now, input = proposals.first().input, output = output))
        module.runs.save(recorded.copy(status = RunStatus.AWAITING_APPROVAL))
        module.notifications.notify(
            run.tenantId,
            NotificationKinds.AGENT_APPROVAL,
            audience = if (run.definition.policy.approvers == Approvers.ADMINS) NotificationAudience.ADMINS else NotificationAudience.ALL,
            params = mapOf("agent" to run.agentName, "subject" to run.subjectLabel.orEmpty(), "count" to proposals.size.toString()),
            link = "agents",
            subject = run.subject,
            ref = approvals.singleOrNull()?.let { "approval:${it.id.toHexString()}" } ?: "inbox",
        )
    }

    /** Marks a step that changes something as running before it starts, for crash recovery. */
    private suspend fun checkpoint(run: AgentRun, step: StepSpec, input: JsonObject, index: Int, variables: JsonObject): AgentRun {
        val action = module.registry.action(step.action)
        if (action == null || action.sideEffect == SideEffect.NONE || run.dryRun) return run
        val previous = run.steps.firstOrNull { it.stepId == step.id }
        val running = record(run, StepResult(step.id, step.action, StepStatus.RUNNING, previous?.startedAt ?: clock(), attempts = previous?.attempts ?: 0, input = input))
        return module.runs.save(running.copy(currentStep = index, context = variables))
    }

    private suspend fun finish(run: AgentRun, status: RunStatus, outcome: String? = null, error: String? = null) {
        module.runs.save(run.copy(status = status, finishedAt = clock(), outcome = outcome, error = error?.take(500), resumeAt = null))
        if (status == RunStatus.AWAITING_APPROVAL || run.dryRun) return
        module.approvals.cancelForRun(run.id)
        val agent = module.agents.recordRun(run.tenantId, run.agentId, status) ?: return
        if (status != RunStatus.FAILED) return
        val policy = run.definition.policy
        if (policy.notifyOnFailure) {
            val params = mapOf("agent" to run.agentName, "subject" to run.subjectLabel.orEmpty(), "error" to (error ?: outcome).orEmpty().take(200))
            val ref = "run:${run.id.toHexString()}"
            if (policy.notifyUserIds.isEmpty()) {
                module.notifications.notify(run.tenantId, NotificationKinds.AGENT_FAILED, NotificationAudience.ADMINS, params = params, link = "agents", subject = run.subject, ref = ref)
            } else {
                policy.notifyUserIds.forEach { userId ->
                    module.notifications.notify(run.tenantId, NotificationKinds.AGENT_FAILED, NotificationAudience.USER, userId = userId, params = params, link = "agents", subject = run.subject, ref = ref)
                }
            }
        }
        if (agent.stats.consecutiveFailures >= CIRCUIT_BREAKER && agent.status == AgentStatus.ACTIVE) {
            module.agents.setStatus(run.tenantId, run.agentId, AgentStatus.PAUSED, pausedReason = "too_many_failures")
            module.notifications.notify(run.tenantId, NotificationKinds.AGENT_PAUSED, params = mapOf("agent" to run.agentName), link = "agents", ref = "agent:${run.agentId.toHexString()}")
            log.warn("Agent {} paused after {} failed runs in a row", run.agentId, agent.stats.consecutiveFailures)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val CIRCUIT_BREAKER = 5
        private val HOLD = 30.minutes

        fun backoff(attempt: Int) = (30.seconds * (1 shl (attempt - 1).coerceIn(0, 6))).coerceAtMost(30.minutes)

        fun record(run: AgentRun, result: StepResult): AgentRun {
            val index = run.steps.indexOfFirst { it.stepId == result.stepId }
            val steps = if (index < 0) run.steps + result else run.steps.toMutableList().also { it[index] = result }
            return run.copy(steps = steps)
        }

        fun withStepOutput(variables: JsonObject, stepId: String, output: JsonObject?): JsonObject {
            val steps = (variables["steps"] as? JsonObject).orEmpty().toMutableMap()
            steps[stepId] = buildJsonObject { put("output", output ?: JsonObject(emptyMap())) }
            return JsonObject(variables + ("steps" to JsonObject(steps)))
        }

        fun previewJson(preview: ActionPreview): JsonObject = AgentJson.json.encodeToJsonElement(ActionPreview.serializer(), preview).jsonObject

        private fun mergeOutputs(previous: JsonObject?, next: JsonObject): JsonObject =
            if (previous == null || previous.isEmpty()) next else JsonObject(previous + next)

        private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    }
}
