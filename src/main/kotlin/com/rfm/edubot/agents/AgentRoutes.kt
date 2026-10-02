package com.rfm.edubot.agents

import com.rfm.edubot.agents.actions.AiSteps
import com.rfm.edubot.agents.ai.AgentDrafter
import com.rfm.edubot.agents.model.Agent
import com.rfm.edubot.agents.model.AgentDefinition
import com.rfm.edubot.agents.model.AgentKind
import com.rfm.edubot.agents.model.AgentStatus
import com.rfm.edubot.agents.model.AgentTask
import com.rfm.edubot.agents.model.ApprovalStatus
import com.rfm.edubot.agents.model.Approvers
import com.rfm.edubot.agents.model.CompanyAgentSettings
import com.rfm.edubot.agents.model.PlatformAgentLimits
import com.rfm.edubot.agents.model.RunStatus
import com.rfm.edubot.agents.model.TaskStatus
import com.rfm.edubot.agents.registry.AgentCatalog
import com.rfm.edubot.agents.registry.TriggerTypes
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.Activation
import com.rfm.edubot.agents.runtime.AgentRuntime
import com.rfm.edubot.agents.runtime.StartResult
import com.rfm.edubot.agents.store.AgentJson
import com.rfm.edubot.agents.templates.AgentTemplates
import com.rfm.edubot.agents.templates.TemplateBuild
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.dashboard.DashboardAccessPolicy
import com.rfm.edubot.dashboard.DashboardContext
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.dashboardContext
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.requireModule
import com.rfm.edubot.events.ActorType
import com.rfm.edubot.events.DomainEventLog
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.integrations.email.EmailDirection
import com.rfm.edubot.integrations.email.EmailMessageRepository
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.days

/** The Agents module's dashboard API under `/app/api/agents`. */
fun Route.agentRoutes(agents: AgentsModule, runtime: AgentRuntime, tenants: TenantRepository) {
    val events = DomainEventLog(agents.mongo)
    val clock = agents.services.clock
    val drafter = AgentDrafter(agents)

    authenticate("dashboard") {
        route("/app/api/agents") {
            get("/catalog") {
                val ctx = call.agentsContext() ?: return@get
                val availability = agents.availability(ctx.tenant)
                call.respond(AgentCatalog.build(agents.registry, availability, AgentTemplates.catalog(ctx.tenant.locale, availability, agents.validator)))
            }

            get("/overview") {
                val ctx = call.agentsContext() ?: return@get
                val now = clock()
                val tenantId = ctx.tenant.id
                val settings = agents.settings.get(tenantId)
                call.respond(
                    AgentsOverviewDto(
                        activeAgents = agents.agents.countActive(tenantId),
                        runsToday = agents.runs.countSince(tenantId, now - 1.days),
                        actionsThisWeek = events.recentByActorType(tenantId, ActorType.AGENT, now - 7.days, limit = 1000).size.toLong(),
                        pendingApprovals = agents.approvals.countPending(tenantId),
                        openTasks = agents.tasks.countOpen(tenantId),
                        failedThisWeek = agents.runs.countByStatus(tenantId, listOf(RunStatus.FAILED, RunStatus.NEEDS_REVIEW), now - 7.days),
                        waitingRuns = agents.runs.countByStatus(tenantId, listOf(RunStatus.WAITING, RunStatus.AWAITING_APPROVAL)),
                        paused = settings.company.paused || settings.platform.agentsPaused,
                        pausedBy = when {
                            settings.platform.agentsPaused -> "platform"
                            settings.company.paused -> "company"
                            else -> null
                        },
                        canManage = ctx.canManageAgents(),
                    ),
                )
            }

            get("/people") {
                val ctx = call.agentsContext() ?: return@get
                // Employees' sign-ins only register their own services, so they can't take tasks or approve.
                val users = agents.services.dashboardUsers.listByTenant(ctx.tenant.primaryTenantId).filterNot { it.isEmployee }
                call.respond(users.map { PersonDto(it.id.toHexString(), it.email, it.role.name) })
            }

            get {
                val ctx = call.agentsContext() ?: return@get
                val pending = agents.approvals.list(ctx.tenant.id, ApprovalStatus.PENDING, limit = 500).groupingBy { it.agentId }.eachCount()
                val availability = agents.availability(ctx.tenant)
                call.respond(
                    agents.agents.list(ctx.tenant.id, includeArchived = call.request.queryParameters["archived"] == "1").map { agent ->
                        agent.dto(agents.validator.validate(agent.definition, availability, agent.templateParams), pending[agent.id] ?: 0)
                    },
                )
            }

            post {
                val ctx = call.agentsContext() ?: return@post
                if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val request = runCatching { call.receive<AgentWriteRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val company = agents.settings.get(ctx.tenant.id).company
                val now = clock()
                val agent = if (request.templateKey != null) {
                    val templated = when (val built = AgentTemplates.build(request.templateKey, request.params, ctx.tenant.locale, company)) {
                        is TemplateBuild.Built -> built.agent
                        TemplateBuild.UnknownTemplate -> return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "template_not_found"))
                        is TemplateBuild.InvalidParams -> return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "invalid_params", "fields" to built.problems.joinToString(",") { "${it.path}:${it.code}" }),
                        )
                    }
                    Agent(
                        tenantId = ctx.tenant.id,
                        name = request.name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: templated.name,
                        description = templated.description,
                        icon = templated.icon,
                        kind = templated.kind,
                        templateKey = request.templateKey,
                        templateParams = templated.params,
                        definition = templated.definition,
                        createdBy = ctx.actorId(),
                        createdAt = now,
                        updatedAt = now,
                    )
                } else {
                    val name = request.name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name_required"))
                    val definition = request.definition?.let { parseDefinition(it) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_definition")) }
                        ?: AgentDefinition()
                    Agent(
                        tenantId = ctx.tenant.id,
                        name = name,
                        description = request.description?.take(MAX_DESCRIPTION),
                        icon = request.icon?.take(8),
                        kind = request.kind?.let { runCatching { AgentKind.valueOf(it) }.getOrNull() } ?: AgentKind.WORKFLOW,
                        templateParams = request.params,
                        definition = if (request.definition == null) definition.copy(policy = definition.policy.copy(autonomy = company.defaultAutonomy)) else definition,
                        createdBy = ctx.actorId(),
                        createdAt = now,
                        updatedAt = now,
                    )
                }
                val saved = agents.agents.insert(agent)
                call.respond(HttpStatusCode.Created, saved.dto(agents.validator.validate(saved.definition, agents.availability(ctx.tenant), saved.templateParams)))
            }

            post("/draft") {
                val ctx = call.agentsContext() ?: return@post
                if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val request = runCatching { call.receive<DraftRequest>() }.getOrNull()?.request?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REQUEST }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
                when (val outcome = drafter.draft(ctx.tenant, request, ctx.actorId())) {
                    is AgentDrafter.Outcome.Drafted -> call.respond(HttpStatusCode.Created, DraftedAgentDto(outcome.agent.dto(outcome.problems), outcome.note))
                    is AgentDrafter.Outcome.Failed -> call.respond(
                        when (outcome.reason) {
                            AiSteps.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
                            AiSteps.TOKEN_BUDGET -> HttpStatusCode.TooManyRequests
                            else -> HttpStatusCode.UnprocessableEntity
                        },
                        mapOf("error" to outcome.reason),
                    )
                }
            }

            get("/{id}") {
                val ctx = call.agentsContext() ?: return@get
                val agent = call.agentParam(agents, ctx) ?: return@get
                val pending = agents.approvals.list(ctx.tenant.id, ApprovalStatus.PENDING, agent.id).size
                call.respond(agent.dto(agents.validator.validate(agent.definition, agents.availability(ctx.tenant), agent.templateParams), pending))
            }

            put("/{id}") {
                val ctx = call.agentsContext() ?: return@put
                if (!ctx.canManageAgents()) return@put call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val agent = call.agentParam(agents, ctx) ?: return@put
                if (agent.status == AgentStatus.ARCHIVED) return@put call.respond(HttpStatusCode.Conflict, mapOf("error" to "archived"))
                val request = runCatching { call.receive<AgentWriteRequest>() }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val definition = request.definition?.let { parseDefinition(it) ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_definition")) }
                    ?: agent.definition
                val problems = agents.validator.validate(definition, agents.availability(ctx.tenant), request.params ?: agent.templateParams)
                // A running agent must stay runnable: fix the problems or pause it first.
                if (agent.status == AgentStatus.ACTIVE && problems.isNotEmpty()) {
                    return@put call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "invalid_definition", "problems" to problems.joinToString(",") { it.code }))
                }
                val updated = agents.agents.update(
                    ctx.tenant.id,
                    agent.id,
                    name = request.name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: agent.name,
                    description = request.description?.take(MAX_DESCRIPTION) ?: agent.description,
                    icon = request.icon?.take(8) ?: agent.icon,
                    kind = request.kind?.let { runCatching { AgentKind.valueOf(it) }.getOrNull() } ?: agent.kind,
                    definition = definition,
                    templateParams = request.params ?: agent.templateParams,
                ) ?: return@put call.respond(HttpStatusCode.NotFound)
                runtime.onAgentChanged(ctx.tenant, updated)
                call.respond(updated.dto(problems))
            }

            delete("/{id}") {
                val ctx = call.agentsContext() ?: return@delete
                if (!ctx.canManageAgents()) return@delete call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val agent = call.agentParam(agents, ctx) ?: return@delete
                // An agent with history is archived so its runs keep their name.
                if (agent.stats.runs > 0 || agents.runs.list(ctx.tenant.id, agent.id, limit = 1).isNotEmpty()) {
                    val archived = agents.agents.setStatus(ctx.tenant.id, agent.id, AgentStatus.ARCHIVED) ?: return@delete call.respond(HttpStatusCode.NotFound)
                    runtime.onAgentChanged(ctx.tenant, archived)
                    return@delete call.respond(mapOf("deleted" to "false", "status" to archived.status.name))
                }
                agents.agents.delete(ctx.tenant.id, agent.id)
                runtime.dispatcher.refresh(ctx.tenant.id)
                call.respond(mapOf("deleted" to "true"))
            }

            post("/{id}/activate") {
                val ctx = call.agentsContext() ?: return@post
                if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val agent = call.agentParam(agents, ctx) ?: return@post
                when (val activation = runtime.activate(ctx.tenant, agent)) {
                    is Activation.Activated -> call.respond(activation.agent.dto())
                    is Activation.Invalid -> call.respond(HttpStatusCode.UnprocessableEntity, agent.dto(activation.problems))
                    is Activation.AtLimit -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "agent_limit", "limit" to activation.limit.toString()))
                    null -> call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/{id}/pause") {
                val ctx = call.agentsContext() ?: return@post
                if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val agent = call.agentParam(agents, ctx) ?: return@post
                val paused = agents.agents.setStatus(ctx.tenant.id, agent.id, AgentStatus.PAUSED) ?: return@post call.respond(HttpStatusCode.NotFound)
                runtime.onAgentChanged(ctx.tenant, paused)
                call.respond(paused.dto())
            }

            post("/{id}/archive") {
                val ctx = call.agentsContext() ?: return@post
                if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val agent = call.agentParam(agents, ctx) ?: return@post
                val archived = agents.agents.setStatus(ctx.tenant.id, agent.id, AgentStatus.ARCHIVED) ?: return@post call.respond(HttpStatusCode.NotFound)
                runtime.onAgentChanged(ctx.tenant, archived)
                call.respond(archived.dto())
            }

            post("/{id}/duplicate") {
                val ctx = call.agentsContext() ?: return@post
                if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val agent = call.agentParam(agents, ctx) ?: return@post
                val request = runCatching { call.receive<DuplicateRequest>() }.getOrDefault(DuplicateRequest())
                val copy = agents.agents.insert(agent.asDraftCopy(ctx.tenant.id, request.name, ctx.actorId(), clock()))
                call.respond(HttpStatusCode.Created, copy.dto())
            }

            post("/{id}/copy") {
                val ctx = call.agentsContext() ?: return@post
                if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val agent = call.agentParam(agents, ctx) ?: return@post
                val request = runCatching { call.receive<CopyRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val target = request.companyId.toObjectIdOrNull()?.let { tenants.findById(it) }
                    ?.takeIf { it.primaryTenantId == ctx.tenant.primaryTenantId }
                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "company_not_found"))
                val copy = agents.agents.insert(agent.asDraftCopy(target.id, request.name, ctx.actorId(), clock()))
                call.respond(HttpStatusCode.Created, copy.dto())
            }

            post("/{id}/test") {
                val ctx = call.agentsContext() ?: return@post
                val agent = call.agentParam(agents, ctx) ?: return@post
                val request = runCatching { call.receive<RunOnRecordRequest>() }.getOrDefault(RunOnRecordRequest())
                val definition = request.definition?.let { parseDefinition(it) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_definition")) }
                    ?: agent.definition
                val subject = request.subject() ?: defaultSubject(agents, ctx.tenant, agent, definition)
                val run = runtime.test(ctx.tenant, agent, subject, definition, ctx.actorId())
                    ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "record_missing"))
                call.respond(run.dto(withSteps = true))
            }

            post("/{id}/run") {
                val ctx = call.agentsContext() ?: return@post
                val agent = call.agentParam(agents, ctx) ?: return@post
                val request = runCatching { call.receive<RunOnRecordRequest>() }.getOrDefault(RunOnRecordRequest())
                when (val started = runtime.runManually(ctx.tenant, agent, request.subject(), ctx.actorId())) {
                    is StartResult.Started -> call.respond(HttpStatusCode.Accepted, started.run.dto())
                    StartResult.Duplicate -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "duplicate"))
                    is StartResult.Skipped -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to started.reason))
                }
            }

            get("/runs") {
                val ctx = call.agentsContext() ?: return@get
                val query = call.request.queryParameters
                val statuses = query["status"]?.split(',')?.mapNotNull { name -> RunStatus.entries.firstOrNull { it.name == name.trim().uppercase() } }.orEmpty()
                val subject = query["subjectType"]?.let { type -> query["subjectId"]?.let { SubjectRef(type, it) } }
                val runs = agents.runs.list(ctx.tenant.id, query["agentId"]?.toObjectIdOrNull(), statuses, subject, query["limit"]?.toIntOrNull() ?: 50)
                call.respond(runs.filter { query["tests"] == "1" || !it.dryRun }.map { it.dto() })
            }

            get("/runs/{id}") {
                val ctx = call.agentsContext() ?: return@get
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                val run = agents.runs.findById(ctx.tenant.id, id) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(run.dto(withSteps = true))
            }

            post("/runs/{id}/cancel") {
                val ctx = call.agentsContext() ?: return@post
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                val run = runtime.cancelRun(ctx.tenant, id) ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "not_open"))
                call.respond(run.dto(withSteps = true))
            }

            post("/runs/{id}/retry") {
                val ctx = call.agentsContext() ?: return@post
                if (!ctx.canManageAgents()) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                val run = runtime.retryRun(ctx.tenant, id) ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "not_retryable"))
                call.respond(run.dto(withSteps = true))
            }

            get("/approvals") {
                val ctx = call.agentsContext() ?: return@get
                val status = call.request.queryParameters["status"]?.let { value -> ApprovalStatus.entries.firstOrNull { it.name == value.uppercase() } }
                    ?: ApprovalStatus.PENDING.takeIf { call.request.queryParameters["status"] != "all" }
                val approvals = agents.approvals.list(ctx.tenant.id, status, call.request.queryParameters["agentId"]?.toObjectIdOrNull())
                call.respond(approvals.map { it.dto(canDecide = ctx.canDecide(it.approvers)) })
            }

            get("/approvals/{id}") {
                val ctx = call.agentsContext() ?: return@get
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                val approval = agents.approvals.findById(ctx.tenant.id, id) ?: return@get call.respond(HttpStatusCode.NotFound)
                val open = approval.status == ApprovalStatus.PENDING
                call.respond(approval.dto(canDecide = open && ctx.canDecide(approval.approvers)))
            }

            post("/approvals/{id}/approve") {
                val ctx = call.agentsContext() ?: return@post
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                val approval = agents.approvals.findById(ctx.tenant.id, id) ?: return@post call.respond(HttpStatusCode.NotFound)
                if (!ctx.canDecide(approval.approvers)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val request = runCatching { call.receive<ApproveRequest>() }.getOrDefault(ApproveRequest())
                // Only the fields the preview marks editable (the message text, a subject) may change.
                val edited = request.input?.filterKeys { it in approval.preview.editable }?.takeIf { it.isNotEmpty() }
                    ?.let { changes -> JsonObject(approval.input + changes) }
                    ?.takeIf { it != approval.input }
                val decided = runtime.approve(ctx.tenant, id, ctx.actorId(), ctx.actorName(), edited)
                    ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_decided"))
                call.respond(decided.dto(canDecide = false))
            }

            post("/approvals/{id}/reject") {
                val ctx = call.agentsContext() ?: return@post
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
                val approval = agents.approvals.findById(ctx.tenant.id, id) ?: return@post call.respond(HttpStatusCode.NotFound)
                if (!ctx.canDecide(approval.approvers)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val request = runCatching { call.receive<RejectRequest>() }.getOrDefault(RejectRequest())
                val decided = runtime.reject(ctx.tenant, id, ctx.actorId(), ctx.actorName(), request.reason)
                    ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_decided"))
                call.respond(decided.dto(canDecide = false))
            }

            get("/tasks") {
                val ctx = call.agentsContext() ?: return@get
                val query = call.request.queryParameters
                val status = when (query["status"]) {
                    "all" -> null
                    null -> TaskStatus.OPEN
                    else -> TaskStatus.entries.firstOrNull { it.name == query["status"]!!.uppercase() } ?: TaskStatus.OPEN
                }
                val assignee = if (query["mine"] == "1") ctx.actorId() else null
                val subject = query["subjectType"]?.let { type -> query["subjectId"]?.let { SubjectRef(type, it) } }
                call.respond(agents.tasks.list(ctx.tenant.id, status, assignee, subject).map { it.dto() })
            }

            get("/tasks/{id}") {
                val ctx = call.agentsContext() ?: return@get
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
                val task = agents.tasks.findById(ctx.tenant.id, id) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(task.dto())
            }

            post("/tasks") {
                val ctx = call.agentsContext() ?: return@post
                val request = runCatching { call.receive<TaskWriteRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val title = request.title?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "title_required"))
                val assignee = request.assigneeUserId?.toObjectIdOrNull()?.let { agents.services.dashboardUsers.findById(it) }
                    ?.takeIf { it.tenantId == ctx.tenant.primaryTenantId && !it.isEmployee }
                val now = clock()
                val subject = SubjectRef(request.subjectType ?: "", request.subjectId ?: "").takeIf { it.type.isNotBlank() && it.id.isNotBlank() }
                val context = subject?.let { runtime.contextBuilder.build(ctx.tenant, it) }
                val task = agents.tasks.insert(
                    AgentTask(
                        tenantId = ctx.tenant.id,
                        title = title,
                        detail = request.detail?.take(4000),
                        subject = subject,
                        subjectLabel = context?.label,
                        clientId = context?.clientId,
                        assigneeUserId = assignee?.id?.toHexString(),
                        assigneeName = assignee?.email,
                        dueAt = request.dueAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                        createdBy = ctx.actorId(),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                call.respond(HttpStatusCode.Created, task.dto())
            }

            patch("/tasks/{id}") {
                val ctx = call.agentsContext() ?: return@patch
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val request = runCatching { call.receive<TaskWriteRequest>() }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val assignee = request.assigneeUserId?.toObjectIdOrNull()?.let { agents.services.dashboardUsers.findById(it) }
                    ?.takeIf { it.tenantId == ctx.tenant.primaryTenantId && !it.isEmployee }
                val updated = agents.tasks.update(
                    ctx.tenant.id,
                    id,
                    title = request.title?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
                    detail = request.detail?.take(4000),
                    status = request.status?.let { value -> TaskStatus.entries.firstOrNull { it.name == value.uppercase() } },
                    assigneeUserId = assignee?.id?.toHexString(),
                    assigneeName = assignee?.email,
                    clearAssignee = request.clearAssignee,
                    dueAt = request.dueAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    clearDue = request.clearDue,
                    by = ctx.actorId(),
                ) ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(updated.dto())
            }

            get("/subjects/{type}/{id}") {
                val ctx = call.agentsContext() ?: return@get
                val subject = SubjectRef(call.parameters["type"].orEmpty(), call.parameters["id"].orEmpty())
                val tenantId = ctx.tenant.id
                // A client's record also covers what agents do on its quotes, invoices, bookings and chats.
                val clientId = subject.id.toObjectIdOrNull()?.takeIf { subject.type == SubjectTypes.CLIENT }
                val own = if (clientId != null) agents.runs.forClient(tenantId, clientId) else agents.runs.list(tenantId, subject = subject, limit = 30).filter { !it.dryRun }
                val activity = events.timeline(tenantId, subject, limit = 50).filter { it.actor.type == ActorType.AGENT }.map {
                    ActivityDto(it.type, it.actor.name, it.occurredAt.toString(), it.payload, it.actor.runId)
                }
                // Runs on another record that changed this one, like the quote's run that issued this invoice.
                val listed = own.map { it.id }.toSet()
                val relatedIds = activity.mapNotNull { it.runId?.toObjectIdOrNull() }.filter { it !in listed }.distinct().take(10)
                val runs = (own + agents.runs.byIds(tenantId, relatedIds).filter { !it.dryRun }).sortedByDescending { it.createdAt }
                val manual = agents.agents.activeFor(tenantId).filter { agent ->
                    agent.definition.triggers.any { it.type == TriggerTypes.MANUAL && (it.config.string("subjectType") ?: SubjectTypes.NONE) == subject.type }
                }.map { AgentOptionDto(it.id.toHexString(), it.name) }
                val paused = clientId?.let { ClientRepository(agents.mongo, tenantId).findById(it)?.automationPaused }
                val tasks = if (clientId != null) agents.tasks.openForClient(tenantId, clientId) else agents.tasks.list(tenantId, TaskStatus.OPEN, subject = subject)
                call.respond(
                    SubjectAutomationsDto(
                        upcoming = runs.filter { it.status.open }.sortedBy { it.resumeAt ?: it.createdAt }.map { it.dto() },
                        recent = runs.filter { !it.status.open }.take(10).map { it.dto() },
                        activity = activity,
                        tasks = tasks.map { it.dto() },
                        manualAgents = manual,
                        automationPaused = paused,
                    ),
                )
            }

            // Any member may pause: it's the brake for a client who asked not to be contacted.
            put("/subjects/client/{id}/automation") {
                val ctx = call.agentsContext() ?: return@put
                if (!ctx.requireModule(DashboardModules.CLIENTS)) return@put call.respond(HttpStatusCode.Forbidden, mapOf("error" to "module_disabled"))
                val id = call.parameters["id"].toObjectIdOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
                val request = runCatching { call.receive<AutomationPauseRequest>() }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val client = ClientRepository(agents.mongo, ctx.tenant.id).setAutomationPaused(id, request.paused) ?: return@put call.respond(HttpStatusCode.NotFound)
                call.respond(AutomationPauseDto(client.automationPaused))
            }

            get("/settings") {
                val ctx = call.agentsContext() ?: return@get
                call.respond(settingsDto(agents, ctx))
            }

            put("/settings") {
                val ctx = call.agentsContext() ?: return@put
                if (!ctx.canManageAgents()) return@put call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
                val request = runCatching { call.receive<CompanySettingsRequest>() }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                val settings = request.settings.copy(
                    perRecipientDailyCap = request.settings.perRecipientDailyCap.coerceIn(0, 20),
                    perRecipientWeeklyCap = request.settings.perRecipientWeeklyCap.coerceIn(0, 50),
                    approvalExpiryDays = request.settings.approvalExpiryDays.coerceIn(1, 30),
                )
                agents.settings.saveCompany(ctx.tenant.id, settings)
                call.respond(settingsDto(agents, ctx))
            }
        }
    }
}

private const val MAX_NAME = 80
private const val MAX_DESCRIPTION = 500
internal const val MAX_REQUEST = 2_000

private suspend fun settingsDto(agents: AgentsModule, ctx: DashboardContext): AgentSettingsDto {
    val tenantId = ctx.tenant.id
    val settings = agents.settings.get(tenantId)
    val now = agents.services.clock()
    val usage = agents.services.usage(ctx.tenant)
    return AgentSettingsDto(
        company = AgentJson.json.encodeToJsonElement(CompanyAgentSettings.serializer(), settings.company).jsonObject,
        platform = AgentJson.json.encodeToJsonElement(PlatformAgentLimits.serializer(), settings.platform).jsonObject,
        usage = AgentUsageDto(
            runsToday = agents.runs.countSince(tenantId, now - 1.days),
            activeAgents = agents.agents.countActive(tenantId),
            maxActiveAgents = settings.platform.maxActiveAgents,
            runsPerDay = settings.platform.runsPerDay,
            tokensThisMonth = usage.tokensUsedThisMonth(),
            agentTokensThisMonth = usage.tokensBySourceThisMonth()[UsageSources.AGENTS] ?: 0L,
            tokenBudget = ctx.tenant.monthlyTokenBudget,
        ),
        canManage = ctx.canManageAgents(),
    )
}

/**
 * A schedule test with no record picked runs on nothing; a record-based agent gets its newest record,
 * an email agent the newest email received.
 */
private suspend fun defaultSubject(agents: AgentsModule, tenant: Tenant, agent: Agent, definition: AgentDefinition): SubjectRef? {
    val type = definition.triggers.firstNotNullOfOrNull { trigger -> agents.registry.trigger(trigger.type)?.subjectType(trigger.config) } ?: return null
    val collection = when (type) {
        SubjectTypes.CLIENT -> "crm.clients"
        SubjectTypes.QUOTE -> "crm.quotes"
        SubjectTypes.INVOICE -> "crm.invoices"
        SubjectTypes.PAYMENT -> "crm.payments"
        SubjectTypes.BOOKING -> "bookings.appointments"
        SubjectTypes.CONVERSATION -> "conversations"
        SubjectTypes.EMAIL -> EmailMessageRepository.COLLECTION
        else -> return null
    }
    val filter = org.bson.Document("tenantId", tenant.id)
    if (type == SubjectTypes.EMAIL) filter.append("direction", EmailDirection.INBOUND.name)
    val latest = agents.mongo.database.getCollection<org.bson.Document>(collection)
        .find(filter)
        .sort(org.bson.Document("createdAt", -1)).limit(1)
    var id: ObjectId? = null
    latest.collect { id = it.getObjectId("_id") }
    return id?.let { SubjectRef.of(type, it) }
}

private fun Agent.asDraftCopy(tenantId: ObjectId, name: String?, by: String?, now: Instant) = Agent(
    tenantId = tenantId,
    name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: this.name,
    description = description,
    icon = icon,
    kind = kind,
    templateKey = templateKey,
    templateParams = templateParams,
    status = AgentStatus.DRAFT,
    definition = definition,
    createdBy = by,
    createdAt = now,
    updatedAt = now,
)

private fun parseDefinition(json: JsonObject): AgentDefinition? =
    runCatching { AgentJson.json.decodeFromJsonElement(AgentDefinition.serializer(), json) }.getOrNull()

private fun RunOnRecordRequest.subject(): SubjectRef? =
    subjectType?.takeIf { it.isNotBlank() && it != SubjectTypes.NONE }?.let { type -> subjectId?.takeIf { it.isNotBlank() }?.let { SubjectRef(type, it) } }

private suspend fun ApplicationCall.agentParam(agents: AgentsModule, ctx: DashboardContext): Agent? {
    val id = parameters["id"].toObjectIdOrNull() ?: run {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    return agents.agents.findById(ctx.tenant.id, id) ?: run {
        respond(HttpStatusCode.NotFound)
        return null
    }
}

private fun String?.toObjectIdOrNull(): ObjectId? = this?.let { runCatching { ObjectId(it) }.getOrNull() }

/** Admins (and operators opening the dashboard) build and switch agents; members see them and do their share. */
internal fun DashboardContext.canManageAgents(): Boolean =
    principalType == DashboardAccessPolicy.OPERATOR_IMPERSONATION || user?.role == DashboardUserRole.TENANT_ADMIN

internal fun DashboardContext.canDecide(approvers: Approvers): Boolean = approvers == Approvers.ANY_MEMBER || canManageAgents()

/** Who acts in this session: the user's id, or the operator. */
internal fun DashboardContext.actorId(): String = user?.id?.toHexString() ?: "operator"

internal fun DashboardContext.actorName(): String = user?.email ?: "operator"

/** The caller's dashboard context when the company has the agents module; answers 403 otherwise. */
internal suspend fun ApplicationCall.agentsContext(): DashboardContext? {
    val ctx = dashboardContext() ?: run {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    if (!ctx.requireModule(DashboardModules.AGENTS)) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "module_disabled"))
        return null
    }
    return ctx
}
