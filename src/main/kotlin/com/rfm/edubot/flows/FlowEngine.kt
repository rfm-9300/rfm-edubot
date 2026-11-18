package com.rfm.edubot.flows

import com.rfm.edubot.tenant.model.Platform
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId

/** What a customer sent, as far as a flow cares: the text, and the payload of the option they tapped, if any. */
data class FlowInput(val platform: Platform, val text: String, val payload: String?)

/** Who is writing, where and when, and what the AI may do for them. */
data class FlowContext(
    val platform: Platform,
    val waId: String,
    val customerName: String?,
    val conversationId: ObjectId?,
    val now: Instant,
    val zone: TimeZone,
    /** The AI answers on this channel, so a flow can leave a chat to it. */
    val aiAnswers: Boolean = true,
    /** The company still has AI tokens this month, for a flow's AI reading. */
    val aiAllowed: Boolean = true,
)

/** The chat goes to a person: the pipeline pauses it and tells the team, with [reason] (the flow and what the customer answered). */
data class FlowHandOver(val reason: String)

/** What an action step did, for the log and the test chat. */
data class FlowEffect(val action: ActionKind, val ok: Boolean, val detail: String)

/**
 * What a flow did with a message. [handled] false means no flow answers (or one gave the chat to the AI): carry on as if
 * none matched. Otherwise [messages] go out in order, [session] is where the customer waits (null: the flow ended or
 * the chat went to a person) and [handOver] is set when a person takes over. [path] is the steps of flow [flowId] the
 * message went through, in order (the step asked again, after a reply it couldn't read), for the Chatbot page's board.
 */
data class FlowResult(
    val handled: Boolean,
    val flowName: String? = null,
    val messages: List<FlowMessage> = emptyList(),
    val session: FlowSession? = null,
    val handOver: FlowHandOver? = null,
    val effects: List<FlowEffect> = emptyList(),
    val flowId: String? = null,
    val path: List<String> = emptyList(),
) {
    companion object {
        val UNHANDLED = FlowResult(handled = false)
    }
}

/** What a flow's actions do outside the chat. Each returns whether it worked, with a short detail for the log. */
interface FlowActions {
    suspend fun isKnownClient(phone: String?): Boolean
    suspend fun createClient(client: FlowClient): FlowActionResult
    suspend fun createBooking(booking: FlowBooking): FlowActionResult
    suspend fun notifyTeam(note: FlowTeamNote): FlowActionResult
}

data class FlowActionResult(val ok: Boolean, val detail: String = "")
data class FlowClient(val name: String, val phone: String, val email: String?, val notes: String?)
data class FlowBooking(val serviceId: String?, val serviceName: String?, val startAt: Instant, val name: String, val phone: String, val notes: String?, val platform: Platform)
data class FlowTeamNote(val flowName: String, val answers: Map<String, String>, val customerName: String?, val platform: Platform, val waId: String, val conversationId: ObjectId?)

/**
 * Reads a reply the usual ways could not. Only what it means comes back (an option, a value, to ask again or to hand
 * over), never words for the customer.
 */
fun interface FlowInterpreter {
    suspend fun read(reading: FlowReading): FlowDecision
}

/** [options] are the titles offered, in order; [expect] is what a question wants, null when the reply should choose an option. */
data class FlowReading(val question: String, val options: List<String>, val expect: Expect?, val reply: String, val today: LocalDate)

sealed interface FlowDecision {
    data class Option(val index: Int) : FlowDecision
    data class Value(val text: String) : FlowDecision
    data object Repeat : FlowDecision
    data object HandOver : FlowDecision
}

/**
 * Runs a company's chat flows: finds the step a customer's message leads to, reads their answer, and walks on through
 * steps that don't wait (conditions, actions, messages with a next step) until one that waits or the end. It keeps no
 * state: the caller passes the customer's [FlowSession] in and stores the one that comes back.
 */
class FlowEngine(
    private val actions: FlowActions,
    private val interpreter: FlowInterpreter? = null,
) {
    /** Whether a flow will answer this message: a customer is waiting in one, or the message starts one. Has no effects. */
    fun wouldAnswer(flows: ChatFlows, session: FlowSession?, input: FlowInput, now: Instant): Boolean =
        waiting(flows, session, input.platform, now) != null || flows.entry(input.platform, input.text, input.payload) != null

    suspend fun handle(flows: ChatFlows, session: FlowSession?, input: FlowInput, ctx: FlowContext): FlowResult {
        val tapped = flows.entry(input.platform, "", input.payload)?.takeIf { it.tapped }
        if (tapped != null) {
            val live = session?.takeIf { it.flowId == tapped.flow.id && !it.expired(ctx.now) }
            return run(tapped.flow, tapped.step.id, keepTap(tapped, live, input.payload), ctx)
        }
        waiting(flows, session, input.platform, ctx.now)?.let { (flow, step) -> return answer(flows, flow, step, session!!, input, ctx) }
        val entry = flows.entry(input.platform, input.text, null) ?: return FlowResult.UNHANDLED
        return run(entry.flow, entry.step.id, emptyMap(), ctx)
    }

    private fun waiting(flows: ChatFlows, session: FlowSession?, platform: Platform, now: Instant): Pair<ChatFlow, FlowStep>? {
        if (session == null || session.expired(now)) return null
        val flow = flows.live(platform).firstOrNull { it.id == session.flowId } ?: return null
        val step = flow.step(session.stepId)?.takeIf { it.waits() } ?: return null
        return flow to step
    }

    /** A tap on an option of the message the customer is at keeps that option's title, when the message keeps its choice. */
    private fun keepTap(tapped: FlowEntry, session: FlowSession?, payload: String?): Map<String, String> {
        val vars = session?.vars.orEmpty()
        val at = session?.let { tapped.flow.step(it.stepId) } ?: return vars
        val index = FlowPayload.option(payload) ?: return vars
        val button = at.buttons.getOrNull(index) ?: return vars
        return if (button.next == tapped.step.id) chosen(at, button, vars) else vars
    }

    /** What is known once [button] of [step] is chosen: the same, plus its title under the message's key, if it has one. */
    private fun chosen(step: FlowStep, button: FlowButton, vars: Map<String, String>): Map<String, String> =
        step.key?.let { vars + (it to button.title) } ?: vars

    private fun FlowStep.waits() = kind == StepKind.ASK || (kind == StepKind.MESSAGE && buttons.isNotEmpty())

    /** The customer replied to the question or options they were left at. */
    private suspend fun answer(flows: ChatFlows, flow: ChatFlow, step: FlowStep, session: FlowSession, input: FlowInput, ctx: FlowContext): FlowResult {
        val today = ctx.now.toLocalDateTime(ctx.zone).date
        val said = input.text
        if (step.kind == StepKind.MESSAGE) {
            FlowOptions.match(step.buttons, said)?.let { return run(flow, step.buttons[it].next, chosen(step, step.buttons[it], session.vars), ctx) }
            // A keyword of any flow starts it over, so "menu" works wherever the customer is.
            flows.entry(input.platform, said, null)?.let { return run(it.flow, it.step.id, emptyMap(), ctx) }
        } else {
            FlowAnswers.read(step.expect, said, today)?.let { return accept(flow, step, session.vars, it, ctx) }
        }
        val reader = interpreter
        if (flow.aiReads && ctx.aiAllowed && reader != null) {
            val reading = FlowReading(
                question = fillAnswers(step.text, session.vars),
                options = if (step.kind == StepKind.MESSAGE) step.buttons.map { it.title } else emptyList(),
                expect = step.expect.takeIf { step.kind == StepKind.ASK },
                reply = said.take(MAX_REPLY_CHARS),
                today = today,
            )
            when (val decision = runCatching { reader.read(reading) }.getOrDefault(FlowDecision.Repeat)) {
                is FlowDecision.Option -> if (step.kind == StepKind.MESSAGE && decision.index in step.buttons.indices) {
                    return run(flow, step.buttons[decision.index].next, chosen(step, step.buttons[decision.index], session.vars), ctx)
                }
                is FlowDecision.Value -> if (step.kind == StepKind.ASK) {
                    FlowAnswers.read(step.expect, decision.text, today)?.let { return accept(flow, step, session.vars, it, ctx) }
                }
                FlowDecision.HandOver -> return handedOver(flow, emptyList(), emptyList(), session.vars)
                FlowDecision.Repeat -> Unit
            }
        }
        return miss(flow, step, session, ctx)
    }

    /** A question's answer is kept under its key, and the flow goes on. */
    private suspend fun accept(flow: ChatFlow, step: FlowStep, vars: Map<String, String>, value: String, ctx: FlowContext): FlowResult {
        val kept = vars + (step.key!! to value)
        val next = step.next ?: return finished(flow, emptyList(), emptyList())
        return run(flow, next, kept, ctx)
    }

    /** A reply nothing could read: ask again, until the flow gives up and passes the chat on. */
    private fun miss(flow: ChatFlow, step: FlowStep, session: FlowSession, ctx: FlowContext): FlowResult {
        val misses = session.misses + 1
        if (misses > flow.retries) {
            // Left to the AI, or to the team where the AI is off: the pipeline treats it as a message no flow answers.
            if (flow.afterMisses == AfterMisses.LET_AI) return FlowResult.UNHANDLED
            val sorry = flow.handoverText?.takeIf { it.isNotBlank() }?.let { listOf(FlowMessage(fillAnswers(it, session.vars), origin = FlowPayload.of(flow.id, HANDOVER_STEP))) }.orEmpty()
            return handedOver(flow, sorry, emptyList(), session.vars)
        }
        return FlowResult(
            handled = true,
            flowName = flow.name,
            messages = listOf(message(flow, step, session.vars, retry = true)),
            session = session.copy(misses = misses, updatedAt = ctx.now),
            flowId = flow.id,
            path = listOf(step.id),
        )
    }

    /** Walks from step [from], sending what each step sends, to the first that waits for the customer or the end. */
    private suspend fun run(flow: ChatFlow, from: String, start: Map<String, String>, ctx: FlowContext): FlowResult {
        val out = mutableListOf<FlowMessage>()
        val effects = mutableListOf<FlowEffect>()
        val path = mutableListOf<String>()
        val vars = start
        var id: String? = from
        repeat(MAX_HOPS) {
            val step = id?.let(flow::step) ?: return finished(flow, out, effects, path)
            path += step.id
            when (step.kind) {
                StepKind.MESSAGE -> {
                    out += message(flow, step, vars)
                    if (step.buttons.isNotEmpty()) return waitingAt(flow, step, vars, out, effects, path, ctx)
                    id = step.next
                }
                StepKind.ASK -> {
                    out += message(flow, step, vars)
                    return waitingAt(flow, step, vars, out, effects, path, ctx)
                }
                StepKind.CONDITION -> id = if (holds(step, vars, ctx)) step.whenTrue else step.whenFalse
                StepKind.ACTION -> {
                    val result = perform(flow, step, vars, ctx)
                    effects += FlowEffect(step.action, result.ok, result.detail)
                    if (result.ok && step.text.isNotBlank()) out += message(flow, step, vars)
                    // With nowhere to go when it fails, a person has to finish what the customer asked for.
                    if (!result.ok && step.failNext == null) return handedOver(flow, out, effects, vars, path)
                    id = if (result.ok) step.next else step.failNext
                }
                StepKind.HANDOFF -> {
                    if (step.text.isNotBlank()) out += message(flow, step, vars)
                    return handedOver(flow, out, effects, vars, path)
                }
            }
        }
        return finished(flow, out, effects, path)
    }

    private fun waitingAt(flow: ChatFlow, step: FlowStep, vars: Map<String, String>, out: List<FlowMessage>, effects: List<FlowEffect>, path: List<String>, ctx: FlowContext) =
        FlowResult(true, flow.name, out.toList(), FlowSession(flow.id, step.id, vars, 0, ctx.now), effects = effects.toList(), flowId = flow.id, path = path.toList())

    private fun finished(flow: ChatFlow, out: List<FlowMessage>, effects: List<FlowEffect>, path: List<String> = emptyList()) =
        FlowResult(true, flow.name, out.toList(), session = null, effects = effects.toList(), flowId = flow.id, path = path.toList())

    private fun handedOver(flow: ChatFlow, out: List<FlowMessage>, effects: List<FlowEffect>, vars: Map<String, String>, path: List<String> = emptyList()) =
        FlowResult(true, flow.name, out.toList(), session = null, handOver = FlowHandOver(reasonFor(flow, vars)), effects = effects.toList(), flowId = flow.id, path = path.toList())

    private fun message(flow: ChatFlow, step: FlowStep, vars: Map<String, String>, retry: Boolean = false): FlowMessage {
        val body = fillAnswers(step.text, vars)
        val text = if (retry && !step.retryText.isNullOrBlank()) fillAnswers(step.retryText, vars) + "\n\n" + body else body
        return FlowMessage(
            text = text,
            options = step.buttons.mapIndexed { i, button -> FlowOption(button.title, FlowPayload.of(flow.id, button.next, i)) },
            display = step.show,
            listLabel = step.listLabel?.takeIf { it.isNotBlank() },
            origin = FlowPayload.of(flow.id, step.id),
        )
    }

    private suspend fun holds(step: FlowStep, vars: Map<String, String>, ctx: FlowContext): Boolean = when (step.check) {
        Check.HOURS -> {
            val local = ctx.now.toLocalDateTime(ctx.zone)
            val minute = local.hour * 60 + local.minute
            step.hours.any { it.contains(local.dayOfWeek.isoDayNumber, minute) }
        }
        Check.KNOWN_CLIENT -> actions.isKnownClient(vars["phone"] ?: phoneOf(ctx))
        Check.ANSWER -> vars[step.answerKey.orEmpty()]?.let { FlowText.normalize(it) == FlowText.normalize(step.answerIs.orEmpty()) } == true
    }

    private suspend fun perform(flow: ChatFlow, step: FlowStep, vars: Map<String, String>, ctx: FlowContext): FlowActionResult {
        val name = vars["name"] ?: ctx.customerName?.takeIf { it.isNotBlank() }
        val phone = vars["phone"] ?: phoneOf(ctx)
        return when (step.action) {
            ActionKind.CREATE_CLIENT -> {
                if (name == null || phone == null) return FlowActionResult(false, "missing name or phone")
                actions.createClient(FlowClient(name, phone, vars["email"], notesOf(vars - setOf("name", "phone", "email"))))
            }
            ActionKind.CREATE_BOOKING -> {
                if (name == null || phone == null) return FlowActionResult(false, "missing name or phone")
                val startAt = runCatching {
                    LocalDateTime(LocalDate.parse(vars["date"].orEmpty()), LocalTime.parse(vars["time"].orEmpty())).toInstant(ctx.zone)
                }.getOrNull() ?: return FlowActionResult(false, "missing date or time")
                val serviceName = step.key?.let { vars[it] }
                if (step.serviceId.isNullOrBlank() && serviceName == null) return FlowActionResult(false, "missing service")
                actions.createBooking(FlowBooking(step.serviceId?.takeIf { it.isNotBlank() }, serviceName, startAt, name, phone, notesOf(vars - setOf("name", "phone", "email", "date", "time", step.key.orEmpty())), ctx.platform))
            }
            ActionKind.NOTIFY_TEAM -> actions.notifyTeam(FlowTeamNote(flow.name, vars, ctx.customerName, ctx.platform, ctx.waId, ctx.conversationId))
        }
    }

    /** WhatsApp's id is the customer's number; other channels have no phone unless the flow asked for one. */
    private fun phoneOf(ctx: FlowContext): String? = ctx.waId.takeIf { ctx.platform == Platform.WHATSAPP && it.any(Char::isDigit) }

    private fun notesOf(vars: Map<String, String>): String? =
        vars.entries.joinToString("\n") { "${it.key}: ${it.value}" }.takeIf { it.isNotBlank() }

    private fun reasonFor(flow: ChatFlow, vars: Map<String, String>): String {
        val answers = vars.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
        return ("“${flow.name}”" + if (answers.isNotBlank()) " · $answers" else "").take(MAX_REASON_CHARS)
    }

    companion object {
        private const val MAX_HOPS = 12
        private const val MAX_REPLY_CHARS = 500
        private const val MAX_REASON_CHARS = 300

        /** The step id on the message a flow sends when it hands over after not understanding. */
        const val HANDOVER_STEP = "handover"
    }
}
