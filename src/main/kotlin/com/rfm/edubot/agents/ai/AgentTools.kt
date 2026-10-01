package com.rfm.edubot.agents.ai

import com.rfm.edubot.agents.AgentsModule
import com.rfm.edubot.agents.MAX_REQUEST
import com.rfm.edubot.agents.actorId
import com.rfm.edubot.agents.actorName
import com.rfm.edubot.agents.canDecide
import com.rfm.edubot.agents.canManageAgents
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentApproval
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.registry.DefinitionProblem
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.Activation
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.StartResult
import com.rfm.edubot.ai.ToolCall
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.tools.ToolCallContext
import com.rfm.edubot.ai.tools.ToolPack
import com.rfm.edubot.dashboard.AssistantExtension
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId

/**
 * The agents module as assistant tools. Listing agents and their pending approvals reads; running,
 * pausing, activating, deciding and drafting are writes, which the assistant proposes and the person
 * confirms one by one. Pausing, activating and drafting are for admins: members don't get those
 * tools, and a call that reaches them anyway is refused.
 */
internal class AgentTools(
    private val module: AgentsModule,
    private val runtime: AgentRuntime,
    private val drafter: AgentDrafter,
    private val ctx: DashboardContext,
) : ToolPack {
    private val tenant get() = ctx.tenant
    private val manager = ctx.canManageAgents()

    override val definitions: List<ToolDefinition> = DEFINITIONS.filter { manager || it.name !in MANAGE }
    override fun knows(name: String): Boolean = name in NAMES
    override fun isReadOnly(name: String): Boolean = name in READ_ONLY
    override fun moduleOf(name: String): String? = DashboardModules.AGENTS.takeIf { name in NAMES }

    override suspend fun execute(call: ToolCall, context: ToolCallContext): JsonObject {
        if (call.name in MANAGE && !manager) return errorResult("not_allowed", "Only the company's admins can do this.")
        val args = call.arguments
        return when (call.name) {
            LIST_AGENTS -> listAgents(args)
            LIST_APPROVALS -> listApprovals(args)
            RUN -> run(args)
            PAUSE -> pause(args)
            ACTIVATE -> activate(args)
            DECIDE -> decide(args)
            DRAFT -> draft(args)
            else -> errorResult("unknown_tool")
        }
    }

    override suspend fun describe(call: ToolCall): JsonObject? = when (call.name) {
        RUN -> agent(call.arguments)?.let { agent ->
            val subject = subject(agent, call.arguments)
            val label = subject?.let { runtime.contextBuilder.build(tenant, it).label }
            buildJsonObject {
                put("agent", agent.name)
                subject?.let { put("recordType", it.type) }
                label?.let { put("record", it) }
            }
        }
        PAUSE, ACTIVATE -> agent(call.arguments)?.let { agent ->
            buildJsonObject {
                put("agent", agent.name)
                put("status", agent.status.name)
            }
        }
        DECIDE -> approval(call.arguments)?.let { approval ->
            buildJsonObject {
                put("agent", approval.agentName)
                put("action", approval.action)
                approval.subjectLabel?.let { put("record", it) }
                approval.preview.channel?.let { put("channel", it) }
                if (approval.preview.recipients.isNotEmpty()) put("recipients", JsonArray(approval.preview.recipients.map(::JsonPrimitive)))
                approval.preview.subject?.let { put("subject", it) }
                approval.preview.body?.let { put("body", it.take(MAX_PREVIEW_BODY)) }
            }
        }
        else -> null
    }

    private suspend fun listAgents(args: JsonObject): JsonObject {
        val status = args.string("status")?.uppercase()?.let { name -> AgentStatus.entries.firstOrNull { it.name == name } }
        val agents = module.agents.list(tenant.id).filter { status == null || it.status == status }
        val pending = module.approvals.list(tenant.id, ApprovalStatus.PENDING, limit = 500).groupingBy { it.agentId }.eachCount()
        val availability = module.availability(tenant)
        return buildJsonObject {
            put(
                "agents",
                buildJsonArray {
                    agents.take(MAX_LISTED).forEach { agent ->
                        add(
                            buildJsonObject {
                                put("id", agent.id.toHexString())
                                put("name", agent.name)
                                put("status", agent.status.name)
                                agent.description?.let { put("description", it) }
                                put("triggers", JsonArray(agent.definition.triggers.map { trigger -> buildJsonObject { put("type", trigger.type); put("config", trigger.config) } }))
                                recordType(agent)?.let { put("record_type", it) }
                                put("runs_on_request", agent.definition.triggers.any { it.type == TriggerTypes.MANUAL })
                                put("runs", agent.stats.runs)
                                put("failed", agent.stats.failed)
                                agent.stats.lastRunAt?.let { put("last_run_at", it.toString()) }
                                pending[agent.id]?.let { put("pending_approvals", it) }
                                agent.pausedReason?.let { put("paused_reason", it) }
                                if (agent.status != AgentStatus.ACTIVE) {
                                    val problems = module.validator.validate(agent.definition, availability, agent.templateParams)
                                    if (problems.isNotEmpty()) put("problems", problems(problems))
                                }
                            },
                        )
                    }
                },
            )
            if (agents.size > MAX_LISTED) put("more", agents.size - MAX_LISTED)
        }
    }

    private suspend fun listApprovals(args: JsonObject): JsonObject {
        val agentId = args.string("agent_id")?.let { agent(args)?.id ?: return errorResult("agent_not_found") }
        val approvals = module.approvals.list(tenant.id, ApprovalStatus.PENDING, agentId, limit = MAX_LISTED)
        return buildJsonObject {
            put(
                "approvals",
                buildJsonArray {
                    approvals.forEach { approval ->
                        add(
                            buildJsonObject {
                                put("id", approval.id.toHexString())
                                put("agent", approval.agentName)
                                put("agent_id", approval.agentId.toHexString())
                                put("action", approval.action)
                                approval.subjectLabel?.let { put("record", untrusted("record", it, approval.subject?.type in OUTSIDER_RECORDS)) }
                                approval.preview.channel?.let { put("channel", it) }
                                if (approval.preview.recipients.isNotEmpty()) put("recipients", JsonArray(approval.preview.recipients.map(::JsonPrimitive)))
                                approval.preview.subject?.let { put("subject", untrusted("subject", it)) }
                                approval.preview.body?.let { put("body", untrusted("body", it.take(MAX_LISTED_BODY))) }
                                put("created_at", approval.createdAt.toString())
                                put("expires_at", approval.expiresAt.toString())
                                put("can_decide", ctx.canDecide(approval.approvers))
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun run(args: JsonObject): JsonObject {
        val agent = agent(args) ?: return errorResult("agent_not_found")
        subjectProblem(agent, args)?.let { return it }
        return when (val started = runtime.runManually(tenant, agent, subject(agent, args), ctx.actorId())) {
            is StartResult.Started -> buildJsonObject {
                put("ok", true)
                put("run_id", started.run.id.toHexString())
                put("status", started.run.status.name)
                started.run.subjectLabel?.let { put("record", it) }
            }
            StartResult.Duplicate -> errorResult("duplicate")
            is StartResult.Skipped -> errorResult(started.reason, SKIP_HINTS[started.reason])
        }
    }

    private suspend fun pause(args: JsonObject): JsonObject {
        val agent = agent(args) ?: return errorResult("agent_not_found")
        if (agent.status != AgentStatus.ACTIVE) return errorResult("not_active", "It is ${agent.status.name}, so there is nothing to pause.")
        val paused = module.agents.setStatus(tenant.id, agent.id, AgentStatus.PAUSED) ?: return errorResult("agent_not_found")
        runtime.onAgentChanged(tenant, paused)
        return buildJsonObject {
            put("ok", true)
            put("status", paused.status.name)
        }
    }

    private suspend fun activate(args: JsonObject): JsonObject {
        val agent = agent(args) ?: return errorResult("agent_not_found")
        if (agent.status == AgentStatus.ARCHIVED) return errorResult("archived")
        return when (val activation = runtime.activate(tenant, agent)) {
            is Activation.Activated -> buildJsonObject {
                put("ok", true)
                put("status", activation.agent.status.name)
            }
            is Activation.Invalid -> buildJsonObject {
                put("error", "has_problems")
                put("message", "Fix these in the agent's builder (Agents) before activating it.")
                put("problems", problems(activation.problems))
            }
            is Activation.AtLimit -> errorResult("agent_limit", "The company can have ${activation.limit} active agents; pause one first.")
            null -> errorResult("agent_not_found")
        }
    }

    private suspend fun decide(args: JsonObject): JsonObject {
        val approval = approval(args) ?: return errorResult("approval_not_found")
        if (approval.status != ApprovalStatus.PENDING) return errorResult("already_decided", "It is already ${approval.status.name}.")
        if (!ctx.canDecide(approval.approvers)) return errorResult("not_allowed", "Only admins can decide this one.")
        val decided = when (args.string("decision")?.lowercase()) {
            "approve" -> runtime.approve(tenant, approval.id, ctx.actorId(), ctx.actorName(), editedInput = null)
            "reject" -> runtime.reject(tenant, approval.id, ctx.actorId(), ctx.actorName(), args.string("reason")?.take(MAX_REASON))
            else -> return errorResult("invalid_decision", "decision is approve or reject.")
        } ?: return errorResult("already_decided")
        return buildJsonObject {
            put("ok", true)
            put("status", decided.status.name)
        }
    }

    private suspend fun draft(args: JsonObject): JsonObject {
        val request = args.string("request")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REQUEST) ?: return errorResult("request_required")
        return when (val outcome = drafter.draft(tenant, request, ctx.actorId())) {
            is AgentDrafter.Outcome.Drafted -> buildJsonObject {
                put("ok", true)
                put("agent_id", outcome.agent.id.toHexString())
                put("name", outcome.agent.name)
                put("status", outcome.agent.status.name)
                if (outcome.problems.isNotEmpty()) put("problems", problems(outcome.problems))
                outcome.note?.let { put("note", it) }
            }
            is AgentDrafter.Outcome.Failed -> errorResult(outcome.reason)
        }
    }

    /** The agent by id, or by its exact name when the model passed that instead. */
    private suspend fun agent(args: JsonObject): Agent? {
        val ref = args.string("agent_id")?.trim() ?: return null
        val id = runCatching { ObjectId(ref) }.getOrNull()
        return if (id != null) {
            module.agents.findById(tenant.id, id)
        } else {
            module.agents.list(tenant.id).singleOrNull { it.name.equals(ref, ignoreCase = true) }
        }
    }

    private suspend fun approval(args: JsonObject): AgentApproval? =
        args.string("approval_id")?.let { runCatching { ObjectId(it.trim()) }.getOrNull() }?.let { module.approvals.findById(tenant.id, it) }

    /** The record type the agent works on (from its triggers), or null when it runs on nothing. */
    private fun recordType(agent: Agent): String? =
        agent.definition.triggers.firstNotNullOfOrNull { module.registry.trigger(it.type)?.subjectType(it.config) }?.takeIf { it != SubjectTypes.NONE }

    /** Why the arguments don't pick the record [agent] works on, or null when they do (or it needs none). */
    private fun subjectProblem(agent: Agent, args: JsonObject): JsonObject? {
        val expected = recordType(agent) ?: return null
        val type = args.string("subject_type")?.trim() ?: expected
        return when {
            args.string("subject_id") == null -> errorResult("record_required", "This agent works on a $expected: ask which one and pass its id as subject_id.")
            type != expected -> errorResult("wrong_record_type", "This agent works on a $expected, not a $type.")
            else -> null
        }
    }

    private fun subject(agent: Agent, args: JsonObject): SubjectRef? {
        val type = recordType(agent) ?: return null
        return args.string("subject_id")?.trim()?.let { SubjectRef(type, it) }
    }

    companion object {
        const val LIST_AGENTS = "list_agents"
        const val LIST_APPROVALS = "list_agent_approvals"
        const val RUN = "run_agent"
        const val PAUSE = "pause_agent"
        const val ACTIVATE = "activate_agent"
        const val DECIDE = "approve_agent_item"
        const val DRAFT = "draft_agent"

        val READ_ONLY = setOf(LIST_AGENTS, LIST_APPROVALS)
        val MANAGE = setOf(PAUSE, ACTIVATE, DRAFT)
        private val NAMES = READ_ONLY + setOf(RUN, DECIDE) + MANAGE

        private const val MAX_LISTED = 25
        private const val MAX_LISTED_BODY = 600
        private const val MAX_PREVIEW_BODY = 400
        private const val MAX_PROBLEMS = 10
        private const val MAX_REASON = 500

        /** Records named or written by outsiders, whose labels reach the model as untrusted content. */
        private val OUTSIDER_RECORDS = setOf(SubjectTypes.CONVERSATION, SubjectTypes.CONTACT, SubjectTypes.EMAIL, SubjectTypes.INSTAGRAM_COMMENT)

        private val SKIP_HINTS = mapOf(
            "inactive" to "Only active agents run.",
            "paused" to "Agents are paused for the whole company.",
            "daily_limit" to "The agent reached its runs for today.",
            "company_daily_limit" to "The company reached its agent runs for today.",
            "record_missing" to "That record doesn't exist.",
            "automation_paused" to "Automations are paused for this client.",
            "conditions_not_met" to "The record doesn't meet the agent's conditions.",
        )

        private val DEFINITIONS = listOf(
            tool(
                LIST_AGENTS,
                "List the company's agents (its automations): id, name, status (DRAFT, ACTIVE or PAUSED), triggers, the record type each works on, " +
                    "results so far, approvals waiting and, for agents that aren't active, the problems that stop them from being activated.",
                buildJsonObject { put("status", property("string", "Only agents in this status", listOf("DRAFT", "ACTIVE", "PAUSED"))) },
            ),
            tool(
                LIST_APPROVALS,
                "List what agents are waiting for a person to approve before acting (a message to send, a change to make), newest first, " +
                    "with who can decide each one.",
                buildJsonObject { put("agent_id", property("string", "Only this agent's approvals")) },
            ),
            tool(
                RUN,
                "Run an active agent now. An agent with a record_type works on one record: pass subject_type and subject_id " +
                    "(find the id with the CRM or booking tools); others run without them.",
                buildJsonObject {
                    put("agent_id", property("string", "From list_agents"))
                    put("subject_type", property("string", "The agent's record_type", SubjectTypes.runnable))
                    put("subject_id", property("string", "The record's id"))
                },
                listOf("agent_id"),
            ),
            tool(
                PAUSE,
                "Pause an active agent: it starts no new runs, and runs under way wait until it's activated again.",
                buildJsonObject { put("agent_id", property("string", "From list_agents")) },
                listOf("agent_id"),
            ),
            tool(
                ACTIVATE,
                "Switch an agent on (a draft or a paused agent). It then starts by itself on its triggers. Refused while the agent has problems.",
                buildJsonObject { put("agent_id", property("string", "From list_agents")) },
                listOf("agent_id"),
            ),
            tool(
                DECIDE,
                "Approve or reject one item an agent is waiting on (from list_agent_approvals). Approving lets the agent do exactly what the item shows.",
                buildJsonObject {
                    put("approval_id", property("string", "From list_agent_approvals"))
                    put("decision", property("string", null, listOf("approve", "reject")))
                    put("reason", property("string", "Why it was rejected, for the team"))
                },
                listOf("approval_id", "decision"),
            ),
            tool(
                DRAFT,
                "Create a new agent from a description of what it should do, saved as a draft for the user to review and activate in Agents. " +
                    "Pass what the user asked for, with the details they gave, as request.",
                buildJsonObject { put("request", property("string", "The automation, in the user's words")) },
                listOf("request"),
            ),
        )

        /** What the assistant is told about these tools; [manager] says whether the user may manage agents. */
        fun note(manager: Boolean): String = buildString {
            appendLine("Agents tools are enabled. Agents are the company's automations: they start on events, schedules, dates or by hand, and do their steps (messages, reminders, CRM changes).")
            appendLine("Use list_agents for ids, states and the record type each works on, and list_agent_approvals for what agents are waiting to have checked. Never guess an id.")
            appendLine("To run an agent that works on a record, find that record's id with the CRM or booking tools first.")
            appendLine("Every agent tool other than the two lists waits for the user's confirmation in the dashboard. Only activate an agent, or approve or reject an item, when the user asks for that one.")
            if (manager) {
                append("draft_agent saves a new agent as a draft and never switches it on: afterwards, tell the user to review it in Agents and activate it from there.")
            } else {
                append("This user is a member: only admins create, activate or pause agents, so say that instead of trying.")
            }
        }

        private fun errorResult(code: String, message: String? = null): JsonObject = buildJsonObject {
            put("error", code)
            message?.let { put("message", it) }
        }

        private fun untrusted(source: String, text: String, wrap: Boolean = true): String =
            if (wrap && text.isNotBlank()) UntrustedContent.wrap("approval.$source", text) else text

        private fun problems(problems: List<DefinitionProblem>): JsonArray =
            JsonArray(problems.take(MAX_PROBLEMS).map { JsonPrimitive(listOfNotNull(it.path.ifEmpty { null }, it.code, it.detail).joinToString(": ")) })

        private fun tool(name: String, description: String, properties: JsonObject, required: List<String> = emptyList()) = ToolDefinition(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", JsonArray(required.map(::JsonPrimitive)))
            },
        )

        private fun property(type: String, description: String?, values: List<String>? = null): JsonObject = buildJsonObject {
            put("type", type)
            description?.let { put("description", it) }
            values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
        }
    }
}

/** Gives the dashboard assistant the agents tools, and what to know about them, when the company has the module. */
internal class AgentAssistant(private val module: AgentsModule, private val runtime: AgentRuntime) : AssistantExtension {
    private val drafter = AgentDrafter(module)

    override fun tools(ctx: DashboardContext): ToolPack? =
        if (ctx.requireModule(DashboardModules.AGENTS)) AgentTools(module, runtime, drafter, ctx) else null

    override fun prompts(ctx: DashboardContext, enabledModules: List<String>): List<String> =
        if (DashboardModules.AGENTS in enabledModules) listOf(AgentTools.note(ctx.canManageAgents()), UntrustedContent.RULE) else emptyList()
}
