package com.rfm.edubot.agents.actions

import com.rfm.edubot.agents.model.ActionPreview
import com.rfm.edubot.agents.model.ConditionGroup
import com.rfm.edubot.agents.registry.ActionCategory
import com.rfm.edubot.agents.registry.ActionResult
import com.rfm.edubot.agents.registry.AgentAction
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.agents.registry.SideEffect
import com.rfm.edubot.agents.registry.bool
import com.rfm.edubot.agents.registry.int
import com.rfm.edubot.agents.registry.obj
import com.rfm.edubot.agents.registry.string
import com.rfm.edubot.agents.runtime.ConditionEvaluator
import com.rfm.edubot.agents.runtime.RunContext
import com.rfm.edubot.agents.runtime.ScheduleCalculator
import com.rfm.edubot.agents.store.AgentJson
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Pause the run for a while, optionally landing on a local time or the next business day. */
object WaitAction : AgentAction {
    override val key = "flow.wait"
    override val category = ActionCategory.FLOW
    override val sideEffect = SideEffect.NONE
    override val aiCallable = false
    override val toolDescription = "Wait before the next step."
    override val inputSchema = Schema.obj(
        "days" to Schema.integer(min = 0, max = 365, default = 1),
        "hours" to Schema.integer(min = 0, max = 720, default = 0),
        "minutes" to Schema.integer(min = 0, max = 1440, default = 0),
        "at" to Schema.string(widget = "time", description = "land on this local time"),
        "businessDay" to Schema.boolean(default = false),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(
        kind = "generic",
        fields = mapOf("wait_until" to until(input, ctx).toString()),
    )

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult = ActionResult.Wait(until(input, ctx))

    private fun until(input: JsonObject, ctx: RunContext): kotlinx.datetime.Instant {
        var target = ctx.now + (input.int("days") ?: 0).days + (input.int("hours") ?: 0).hours + (input.int("minutes") ?: 0).minutes
        val at = ScheduleCalculator.parseTime(input.string("at"))
        var local = target.toLocalDateTime(ctx.zone)
        if (at != null) {
            val onDay = LocalDateTime(local.date, at).toInstant(ctx.zone)
            target = if (onDay >= target) onDay else LocalDateTime(local.date.plus(1, DateTimeUnit.DAY), at).toInstant(ctx.zone)
            local = target.toLocalDateTime(ctx.zone)
        }
        if (input.bool("businessDay") == true) {
            while (local.date.dayOfWeek.isoDayNumber >= 6) {
                target = LocalDateTime(local.date.plus(1, DateTimeUnit.DAY), local.time).toInstant(ctx.zone)
                local = target.toLocalDateTime(ctx.zone)
            }
        }
        return target
    }
}

/** Continue at another step, or end, depending on conditions over the run's variables. */
object BranchAction : AgentAction {
    override val key = "flow.branch"
    override val category = ActionCategory.FLOW
    override val sideEffect = SideEffect.NONE
    override val aiCallable = false
    override val toolDescription = "Choose the next step."
    private val condition = Schema.obj("field" to Schema.string(widget = "field"), "op" to Schema.string(enum = ConditionEvaluator.operators.toList()), "value" to JsonObject(emptyMap()))
    override val inputSchema = Schema.obj(
        "conditions" to Schema.obj(
            "match" to Schema.string(enum = listOf("ALL", "ANY"), default = "ALL"),
            "conditions" to Schema.array(condition, maxItems = 10),
        ),
        "thenGoTo" to Schema.string(widget = "step", default = "next", description = "step id, next or end"),
        "elseGoTo" to Schema.string(widget = "step", default = "end", description = "step id, next or end"),
        required = listOf("conditions"),
    )

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "generic", fields = mapOf("next" to target(input, ctx)))

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult = when (val next = target(input, ctx)) {
        "next" -> ActionResult.Done(buildJsonObject { put("matched", next != input.string("elseGoTo")) })
        "end" -> ActionResult.Stop("branch_end")
        else -> ActionResult.Jump(next)
    }

    private fun target(input: JsonObject, ctx: RunContext): String {
        val group = input.obj("conditions")?.let { runCatching { AgentJson.json.decodeFromJsonElement(ConditionGroup.serializer(), it) }.getOrNull() }
        val today = ctx.now.toLocalDateTime(ctx.zone).date
        val matched = ConditionEvaluator.matches(group, ctx.variables, today, ctx.zone)
        return if (matched) input.string("thenGoTo") ?: "next" else input.string("elseGoTo") ?: "end"
    }
}

/** End the run here. */
object StopAction : AgentAction {
    override val key = "flow.stop"
    override val category = ActionCategory.FLOW
    override val sideEffect = SideEffect.NONE
    override val aiCallable = false
    override val toolDescription = "Stop the agent run."
    override val inputSchema = Schema.obj("outcome" to Schema.string(maxLength = 60))

    override suspend fun preview(input: JsonObject, ctx: RunContext) = ActionPreview(kind = "generic")

    override suspend fun execute(input: JsonObject, ctx: RunContext): ActionResult = ActionResult.Stop(input.string("outcome") ?: "stopped")
}
