package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.AgentTask
import com.rfm.edubot.agents.registry.ActionCategory
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.int
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.days

/** The dashboard view that shows a record of this type. */
internal fun viewFor(subjectType: String?): String = when (subjectType) {
    SubjectTypes.CLIENT -> "clients"
    SubjectTypes.QUOTE -> "quotes"
    SubjectTypes.INVOICE -> "invoices"
    SubjectTypes.PAYMENT -> "payments"
    SubjectTypes.BOOKING -> "bookings"
    SubjectTypes.SERVICE -> "services"
    SubjectTypes.CONVERSATION, SubjectTypes.CONTACT -> "conversations"
    SubjectTypes.INSTAGRAM_COMMENT -> "instagram"
    else -> "agents"
}

/** An in-app notification for the team, with the run's record linked. */
object NotifyTeamAction : AgentAction {
    override val key = "team.notify"
    override val category = ActionCategory.TEAM
    override val sideEffect = SideEffect.NONE
    override val aiCallable = true
    override val toolDescription = "Notify people in the company inside the dashboard. Use for alerts and summaries meant for staff, never for customers."
    override val inputSchema = Schema.obj(
        "message" to Schema.string(widget = "template", maxLength = 4000, description = "the notification text"),
        "audience" to Schema.string(enum = listOf("admins", "everyone", "user"), default = "admins"),
        "userId" to Schema.string(widget = "user"),
        required = listOf("message"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "notify",
        recipients = listOfNotNull(input.string("audience") ?: "admins", input.string("userId")),
        body = input.string("message"),
        editable = listOf("message"),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val message = input.string("message") ?: return ActionResult.Skipped("empty_message")
        val audience = when (input.string("audience")) {
            "everyone" -> NotificationAudience.ALL
            "user" -> NotificationAudience.USER
            else -> NotificationAudience.ADMINS
        }
        val userId = input.string("userId")
        if (audience == NotificationAudience.USER && userId == null) return ActionResult.Failed("user_required")
        val notification = ctx.services.notifications.notify(
            ctx.tenant.id,
            NotificationKinds.AGENT_NOTICE,
            audience = audience,
            userId = userId,
            params = mapOf("agent" to ctx.run.agentName, "subject" to ctx.run.subjectLabel.orEmpty()),
            body = message,
            link = viewFor(ctx.run.subject?.type),
            subject = ctx.run.subject,
        )
        return ActionResult.Done(buildJsonObject { put("notificationId", notification.id.toHexString()) })
    }
}

/** A to-do for a person, linked to the run's record and shown in the Agents inbox. */
object CreateTaskAction : AgentAction {
    override val key = "team.task.create"
    override val category = ActionCategory.TEAM
    override val sideEffect = SideEffect.NONE
    override val aiCallable = true
    override val toolDescription = "Create a to-do for someone in the company, e.g. call a client. The task links to the record the agent is working on."
    override val inputSchema = Schema.obj(
        "title" to Schema.string(widget = "template", maxLength = 200),
        "detail" to Schema.string(widget = "template", maxLength = 4000),
        "assigneeUserId" to Schema.string(widget = "user"),
        "dueInDays" to Schema.integer(min = 0, max = 90, default = 1),
        required = listOf("title"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "task",
        subject = input.string("title"),
        body = input.string("detail"),
        recipients = listOfNotNull(input.string("assigneeUserId")),
        fields = mapOf("due_in_days" to (input.int("dueInDays") ?: 1).toString()),
        editable = listOf("title", "detail"),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult {
        val title = input.string("title") ?: return ActionResult.Skipped("empty_title")
        val assignee = input.string("assigneeUserId")
            ?.let { id -> runCatching { ObjectId(id) }.getOrNull() }
            ?.let { ctx.services.dashboardUsers.findById(it) }
            ?.takeIf { it.tenantId == ctx.tenant.primaryTenantId }
        val task = ctx.services.tasks.insert(
            AgentTask(
                tenantId = ctx.tenant.id,
                title = title,
                detail = input.string("detail"),
                subject = ctx.run.subject,
                subjectLabel = ctx.run.subjectLabel,
                clientId = ctx.run.clientId,
                assigneeUserId = assignee?.id?.toHexString(),
                assigneeName = assignee?.email,
                dueAt = ctx.now + (input.int("dueInDays") ?: 1).days,
                agentId = ctx.run.agentId,
                agentName = ctx.run.agentName,
                runId = ctx.run.id,
                createdBy = "agent:${ctx.run.agentId.toHexString()}",
                createdAt = ctx.now,
                updatedAt = ctx.now,
            ),
        )
        ctx.services.notifications.notify(
            ctx.tenant.id,
            NotificationKinds.AGENT_TASK,
            audience = if (assignee != null) NotificationAudience.USER else NotificationAudience.ADMINS,
            userId = assignee?.id?.toHexString(),
            params = mapOf("agent" to ctx.run.agentName, "title" to title, "subject" to ctx.run.subjectLabel.orEmpty()),
            link = "agents",
            subject = ctx.run.subject,
            ref = "task:${task.id.toHexString()}",
        )
        return ActionResult.Done(buildJsonObject { put("taskId", task.id.toHexString()) })
    }
}
