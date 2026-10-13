package com.rfm.edubot.flows

import com.rfm.edubot.channel.QuickReply
import com.rfm.edubot.tenant.model.Platform
import kotlinx.datetime.Instant
import java.text.Normalizer
import java.util.Locale

/**
 * A company's menu flows: fixed messages with quick replies that answer customers before the AI, the way
 * ManyChat keyword automations do. One `chat_flows` document per company, edited in the Persona page's
 * Menus tab (wiki/ai-persona-and-bot.md has its shape).
 */
data class ChatFlows(
    val flows: List<ChatFlow> = emptyList(),
    /** Channels where the AI never answers on its own, so a message no flow answers waits for the team. */
    val teamOnlyChannels: Set<Platform> = emptySet(),
    val updatedAt: Instant? = null,
    /** The dashboard user's email, or `operator`. */
    val updatedBy: String? = null,
) {
    /**
     * The step that answers a customer's [text] on [platform]: the one a tapped quick reply's [payload] leads
     * to, else the first step of the first flow with a keyword the text contains.
     */
    fun replyTo(platform: Platform, text: String, payload: String?): FlowReply? {
        val live = flows.filter { it.enabled && platform in it.channels }
        FlowPayload.parse(payload)?.let { (flowId, stepId) ->
            val flow = live.firstOrNull { it.id == flowId }
            flow?.step(stepId)?.let { return FlowReply(flow, it) }
        }
        val said = FlowText.normalize(text)
        val flow = live.firstOrNull { flow -> flow.keywords.any { FlowText.containsPhrase(said, it) } } ?: return null
        return flow.steps.firstOrNull()?.let { FlowReply(flow, it) }
    }

    /** The first reason these flows can't be saved: the Menus tab's own limits, then what [ChatFlow.problems] finds. */
    fun saveProblem(): FlowProblem? {
        if (flows.size > ChatFlowLimits.MAX_FLOWS) return FlowProblem("menus", limit = ChatFlowLimits.MAX_FLOWS)
        if (flows.map { it.id }.toSet().size != flows.size) return FlowProblem("invalid")
        flows.forEachIndexed { menu, flow ->
            if (flow.name.isBlank() || flow.name.length > ChatFlowLimits.MAX_NAME_CHARS) return FlowProblem("name", menu, limit = ChatFlowLimits.MAX_NAME_CHARS)
            if (flow.keywords.size > ChatFlowLimits.MAX_KEYWORDS) return FlowProblem("keywords_limit", menu, limit = ChatFlowLimits.MAX_KEYWORDS)
            if (flow.keywords.any { it.length > ChatFlowLimits.MAX_KEYWORD_CHARS }) return FlowProblem("keyword", menu, limit = ChatFlowLimits.MAX_KEYWORD_CHARS)
            if (flow.steps.size > ChatFlowLimits.MAX_STEPS) return FlowProblem("steps_limit", menu, limit = ChatFlowLimits.MAX_STEPS)
            flow.problems().firstOrNull()?.let { return FlowProblem.of(it, flow, menu) }
        }
        return null
    }
}

/** What the Menus tab lets a company build, on top of Instagram's limits in [ChatFlow]. */
object ChatFlowLimits {
    const val MAX_FLOWS = 10
    const val MAX_STEPS = 30
    const val MAX_KEYWORDS = 50
    const val MAX_KEYWORD_CHARS = 60
    const val MAX_NAME_CHARS = 60
}

/** Why flows can't be saved. [menu] and [step] are positions in what was sent, for the editor to point at. */
data class FlowProblem(val code: String, val menu: Int? = null, val step: Int? = null, val limit: Int? = null) {
    companion object {
        /** A [ChatFlow.problems] entry (`code` or `code:stepId`) of the flow at position [menu]. */
        fun of(problem: String, flow: ChatFlow, menu: Int): FlowProblem {
            val code = problem.substringBefore(':')
            val step = flow.steps.indexOfFirst { it.id == problem.substringAfter(':', "") }.takeIf { it >= 0 }
            return when (code) {
                "keywords", "steps" -> FlowProblem(code, menu)
                "text" -> FlowProblem(code, menu, step, ChatFlow.MAX_TEXT_BYTES)
                "buttons" -> FlowProblem(code, menu, step, ChatFlow.MAX_BUTTONS)
                "title" -> FlowProblem(code, menu, step, ChatFlow.MAX_TITLE)
                "next" -> FlowProblem(code, menu, step)
                else -> FlowProblem("invalid", menu)
            }
        }
    }
}

data class ChatFlow(
    /** Lowercase letters, digits, `-` and `_`, because it goes into every quick reply's payload. */
    val id: String,
    /** Shown in the inbox as the sender of the flow's messages. */
    val name: String,
    val enabled: Boolean = true,
    val channels: Set<Platform>,
    /** Words or phrases that start the flow when a message contains one, ignoring case, accents and punctuation. */
    val keywords: List<String>,
    /** A keyword starts the flow at the first step. */
    val steps: List<FlowStep>,
) {
    fun step(id: String): FlowStep? = steps.firstOrNull { it.id == id }

    /** What Instagram would refuse, or a button that leads nowhere. A flow with problems never runs. */
    fun problems(): List<String> = buildList {
        if (!ID.matches(id)) add("id")
        if (channels.isEmpty() || !SUPPORTED_CHANNELS.containsAll(channels)) add("channels")
        if (keywords.none { FlowText.normalize(it).isNotEmpty() }) add("keywords")
        if (steps.isEmpty()) add("steps")
        val stepIds = steps.map { it.id }
        if (stepIds.size != stepIds.toSet().size) add("duplicate_step")
        for (step in steps) {
            if (!ID.matches(step.id)) add("step_id:${step.id}")
            if (step.text.isBlank() || step.text.toByteArray().size > MAX_TEXT_BYTES) add("text:${step.id}")
            if (step.buttons.size > MAX_BUTTONS) add("buttons:${step.id}")
            if (step.buttons.any { it.title.isBlank() || it.title.length > MAX_TITLE }) add("title:${step.id}")
            if (step.buttons.any { it.next !in stepIds }) add("next:${step.id}")
        }
    }

    companion object {
        /** WhatsApp would need interactive buttons, which the WhatsApp client can't send yet. */
        val SUPPORTED_CHANNELS = setOf(Platform.INSTAGRAM)

        /** Instagram's limits: 13 quick replies, titles of 20 characters, a message of 1,000 bytes. */
        const val MAX_BUTTONS = 13
        const val MAX_TITLE = 20
        const val MAX_TEXT_BYTES = 1000

        private val ID = Regex("^[a-z0-9_-]{1,40}$")
    }
}

data class FlowStep(
    val id: String,
    val text: String,
    val buttons: List<FlowButton> = emptyList(),
)

/** A quick reply under a step's message: tapping it sends [title] and moves the customer to step [next]. */
data class FlowButton(val title: String, val next: String)

data class FlowReply(val flow: ChatFlow, val step: FlowStep) {
    val quickReplies: List<QuickReply>
        get() = step.buttons.map { QuickReply(it.title, FlowPayload.of(flow.id, it.next)) }
}

/** What a flow's quick reply sends back: `flow:<flow id>:<step id>`. */
internal object FlowPayload {
    private const val PREFIX = "flow:"

    fun of(flowId: String, stepId: String) = "$PREFIX$flowId:$stepId"

    fun parse(payload: String?): Pair<String, String>? {
        if (payload == null || !payload.startsWith(PREFIX)) return null
        val parts = payload.removePrefix(PREFIX).split(':')
        return if (parts.size == 2 && parts.none { it.isEmpty() }) parts[0] to parts[1] else null
    }
}

/** Keywords match whole words, so "Oi" is found in "Oi, tudo bem?" but not in "Depois". */
internal object FlowText {
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase words without accents or punctuation, one space apart. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "")
            .replace(separators, " ")
            .trim()

    /** Whether [normalized] text holds [phrase] as whole words. */
    fun containsPhrase(normalized: String, phrase: String): Boolean {
        val wanted = normalize(phrase)
        return wanted.isNotEmpty() && " $normalized ".contains(" $wanted ")
    }
}
