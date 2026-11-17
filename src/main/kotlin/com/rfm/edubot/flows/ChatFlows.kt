package com.rfm.edubot.flows

import com.rfm.edubot.tenant.model.Platform
import kotlinx.datetime.Instant
import java.text.Normalizer
import java.util.Locale

/**
 * A company's chat flows: fixed bots that answer customers before the AI, the way ManyChat keyword automations
 * do. A flow is steps: messages with options, questions whose answers are kept, conditions, actions and hand-overs
 * ([FlowEngine] runs them). One `chat_flows` document per company; the flows are edited in the Chatbot page, the
 * AI switch and start words in Persona → Channels (wiki/chatbot-flows.md has the shape).
 */
data class ChatFlows(
    val flows: List<ChatFlow> = emptyList(),
    /**
     * Channels where the AI never answers on its own, so a message no flow answers waits for the team. Only
     * [TEAM_ONLY_CHANNELS]: the team can't reply to a website chat, so the website always gets the AI.
     */
    val teamOnlyChannels: Set<Platform> = emptySet(),
    /** Channels where a new chat only gets answers when its first message holds a start word, by channel. */
    val startGates: Map<Platform, StartGate> = emptyMap(),
    val updatedAt: Instant? = null,
    /** The dashboard user's email, or `operator`. */
    val updatedBy: String? = null,
) {
    /**
     * The gate a new chat on [platform] has to pass, or null when the channel has none or its gate can't run
     * (off, or with no word to look for: a gate nothing opens would silence the channel, so it is skipped).
     */
    fun startGate(platform: Platform): StartGate? =
        startGates[platform]?.takeIf { it.enabled && it.usable() }

    /** The flows that answer on [platform]. */
    fun live(platform: Platform): List<ChatFlow> = flows.filter { it.enabled && platform in it.channels }

    /**
     * Where a customer's [text] on [platform] starts a flow: the step a tapped option's [payload] leads to, else the
     * first step of the first flow with a keyword the text contains. Null when nothing starts.
     */
    fun entry(platform: Platform, text: String, payload: String?): FlowEntry? {
        val live = live(platform)
        FlowPayload.parse(payload)?.let { (flowId, stepId) ->
            val flow = live.firstOrNull { it.id == flowId }
            flow?.step(stepId)?.let { return FlowEntry(flow, it, tapped = true) }
        }
        val said = FlowText.normalize(text)
        val flow = live.firstOrNull { flow -> flow.keywords.any { FlowText.containsPhrase(said, it) } } ?: return null
        return flow.steps.firstOrNull()?.let { FlowEntry(flow, it, tapped = false) }
    }

    /** The first reason these flows can't be saved: the Chatbot page's own limits, then what [ChatFlow.problems] finds. */
    fun saveProblem(): FlowProblem? {
        if (flows.size > ChatFlowLimits.MAX_FLOWS) return FlowProblem("flows", limit = ChatFlowLimits.MAX_FLOWS)
        if (flows.map { it.id }.toSet().size != flows.size) return FlowProblem("invalid")
        flows.forEachIndexed { menu, flow ->
            if (flow.name.isBlank() || flow.name.length > ChatFlowLimits.MAX_NAME_CHARS) return FlowProblem("name", menu, limit = ChatFlowLimits.MAX_NAME_CHARS)
            if (flow.keywords.size > ChatFlowLimits.MAX_KEYWORDS) return FlowProblem("keywords_limit", menu, limit = ChatFlowLimits.MAX_KEYWORDS)
            if (flow.keywords.any { it.length > ChatFlowLimits.MAX_KEYWORD_CHARS }) return FlowProblem("keyword", menu, limit = ChatFlowLimits.MAX_KEYWORD_CHARS)
            if (flow.steps.size > ChatFlowLimits.MAX_STEPS) return FlowProblem("steps_limit", menu, limit = ChatFlowLimits.MAX_STEPS)
            flow.problems().firstOrNull()?.let { return FlowProblem.of(it, flow, menu) }
        }
        return gateProblem()
    }

    /** The first reason the start gates can't be saved, whatever the flows are. */
    fun gateProblem(): FlowProblem? {
        for ((platform, gate) in startGates) {
            val channel = platform.name
            if (platform !in StartGate.CHANNELS) return FlowProblem("invalid", channel = channel)
            if (gate.words.size > ChatFlowLimits.MAX_START_WORDS) return FlowProblem("start_words_limit", channel = channel, limit = ChatFlowLimits.MAX_START_WORDS)
            if (gate.words.any { it.length > ChatFlowLimits.MAX_KEYWORD_CHARS }) return FlowProblem("start_word", channel = channel, limit = ChatFlowLimits.MAX_KEYWORD_CHARS)
            if (gate.enabled && !gate.usable()) return FlowProblem("start_words", channel = channel)
        }
        return null
    }

    companion object {
        /** Where the AI can be turned off: channels the team answers from Conversations. */
        val TEAM_ONLY_CHANNELS = setOf(Platform.INSTAGRAM, Platform.WHATSAPP)
    }
}

/**
 * What the first message of a new chat must hold before the bot answers on a channel: one of [words], as whole
 * words, ignoring case, accents and punctuation. A chat that starts without one is stored and left to the team,
 * paused for the AI and for menus until someone resumes it, so a later start word in that chat doesn't wake the bot.
 * Chats that already exist are never gated.
 */
data class StartGate(val enabled: Boolean = true, val words: List<String> = emptyList()) {
    /** Whether there is a word to look for. */
    fun usable(): Boolean = words.any { FlowText.normalize(it).isNotEmpty() }

    /** Whether [text] holds a start word. */
    fun opens(text: String): Boolean {
        val said = FlowText.normalize(text)
        return words.any { FlowText.containsPhrase(said, it) }
    }

    companion object {
        /** Not the website: its visitors would wait at a silent widget. */
        val CHANNELS = setOf(Platform.INSTAGRAM, Platform.WHATSAPP)
    }
}

/** What the Chatbot page lets a company build, on top of each channel's own limits. */
object ChatFlowLimits {
    const val MAX_FLOWS = 10
    const val MAX_STEPS = 60
    const val MAX_KEYWORDS = 50
    const val MAX_KEYWORD_CHARS = 60
    const val MAX_NAME_CHARS = 60
    const val MAX_START_WORDS = 50
    /** Extra words a customer can type for one option. */
    const val MAX_OPTION_WORDS = 10
    const val MAX_WINDOWS = 21
    const val MAX_LIST_LABEL = 20
    const val MAX_ANSWER_TEXT = 300
}

/** Why flows can't be saved. [menu] and [step] are positions in what was sent, for the editor to point at; [channel] names a start gate. */
data class FlowProblem(val code: String, val menu: Int? = null, val step: Int? = null, val limit: Int? = null, val channel: String? = null) {
    companion object {
        /** A [ChatFlow.problems] entry (`code` or `code:stepId`) of the flow at position [menu]. */
        fun of(problem: String, flow: ChatFlow, menu: Int): FlowProblem {
            val code = problem.substringBefore(':')
            val step = flow.steps.indexOfFirst { it.id == problem.substringAfter(':', "") }.takeIf { it >= 0 }
            return when (code) {
                "keywords", "steps", "channels", "duplicate_step" -> FlowProblem(code, menu)
                "text" -> FlowProblem(code, menu, step, ChatFlow.MAX_TEXT_BYTES)
                "buttons" -> FlowProblem(code, menu, step, ChatFlow.MAX_BUTTONS)
                "title" -> FlowProblem(code, menu, step, ChatFlow.MAX_TITLE)
                "words" -> FlowProblem(code, menu, step, ChatFlowLimits.MAX_OPTION_WORDS)
                "retries" -> FlowProblem(code, menu, limit = ChatFlow.MAX_RETRIES)
                "hours" -> FlowProblem(code, menu, step, ChatFlowLimits.MAX_WINDOWS)
                "list_label" -> FlowProblem(code, menu, step, ChatFlowLimits.MAX_LIST_LABEL)
                "next", "key", "branch", "answer", "action", "service", "loop", "step_id", "retry" -> FlowProblem(code, menu, step)
                else -> FlowProblem("invalid", menu)
            }
        }
    }
}

/** What a flow's step is: it waits for the customer ([MESSAGE] with options, [ASK]) or runs on to the next step. */
enum class StepKind { MESSAGE, ASK, CONDITION, ACTION, HANDOFF }

/** How a message offers its options. [AUTO] taps where the channel has them and numbered lines where it doesn't; [HIDDEN] leaves it to the message's own words. */
enum class OptionsDisplay { AUTO, NUMBERED, HIDDEN }

/** What a question expects, so an answer can be checked and kept in a usable form. */
enum class Expect { TEXT, NAME, PHONE, EMAIL, NUMBER, DATE, TIME, YES_NO }

enum class Check { HOURS, KNOWN_CLIENT, ANSWER }

enum class ActionKind { CREATE_CLIENT, CREATE_BOOKING, NOTIFY_TEAM }

/** What happens once a customer has twice not been understood: a person takes over, or the AI answers (when the channel has it). */
enum class AfterMisses { HAND_OVER, LET_AI }

/** The company is open on [day] (1 = Monday … 7 = Sunday) from [from] to [to], as `HH:mm` in its time zone. */
data class HoursWindow(val day: Int, val from: String, val to: String) {
    fun valid(): Boolean = day in 1..7 && minutes(from) != null && minutes(to) != null && minutes(from)!! < minutes(to)!!

    fun contains(day: Int, minuteOfDay: Int): Boolean =
        this.day == day && minutes(from)?.let { it <= minuteOfDay } == true && minutes(to)?.let { minuteOfDay < it } == true

    companion object {
        private val CLOCK = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

        fun minutes(clock: String): Int? = CLOCK.matchEntire(clock)?.destructured?.let { (h, m) -> h.toInt() * 60 + m.toInt() }
    }
}

data class ChatFlow(
    /** Lowercase letters, digits, `-` and `_`, because it goes into every option's payload. */
    val id: String,
    /** Shown in the inbox as the sender of the flow's messages. */
    val name: String,
    val enabled: Boolean = true,
    val channels: Set<Platform>,
    /** Words or phrases that start the flow when a message contains one, ignoring case, accents and punctuation. */
    val keywords: List<String>,
    /** A keyword starts the flow at the first step. */
    val steps: List<FlowStep>,
    /** When a reply can't be read the usual way, the AI says which option or value it means (and nothing else). */
    val aiReads: Boolean = false,
    val afterMisses: AfterMisses = AfterMisses.HAND_OVER,
    /** Sent to the customer when [afterMisses] hands the chat to a person; the author's words, in the author's language. */
    val handoverText: String? = null,
    /** How many times a step asks again after a reply it couldn't read, before [afterMisses] applies. Flows saved before it read as 0. */
    val retries: Int = 1,
) {
    fun step(id: String): FlowStep? = steps.firstOrNull { it.id == id }

    /** What a channel would refuse, or a step that leads nowhere. A flow with problems never runs. */
    fun problems(): List<String> = buildList {
        if (!ID.matches(id)) add("id")
        if (channels.isEmpty() || !SUPPORTED_CHANNELS.containsAll(channels)) add("channels")
        if (keywords.none { FlowText.normalize(it).isNotEmpty() }) add("keywords")
        if (steps.isEmpty()) add("steps")
        val stepIds = steps.map { it.id }
        if (stepIds.size != stepIds.toSet().size) add("duplicate_step")
        if (handoverText != null && handoverText.toByteArray().size > MAX_TEXT_BYTES) add("text:handover")
        if (retries !in 0..MAX_RETRIES) add("retries")
        for (step in steps) stepProblems(step, stepIds)
        if (steps.isNotEmpty() && hasLoop()) add("loop")
    }

    private fun MutableList<String>.stepProblems(step: FlowStep, stepIds: List<String>) {
        val at = step.id
        if (!ID.matches(at)) add("step_id:$at")
        fun reaches(target: String?) = target == null || target in stepIds
        if (step.text.toByteArray().size > MAX_TEXT_BYTES) add("text:$at")
        if (step.retryText != null && step.retryText.toByteArray().size > MAX_TEXT_BYTES) add("retry:$at")
        when (step.kind) {
            StepKind.MESSAGE -> {
                if (step.text.isBlank()) add("text:$at")
                if (step.buttons.size > MAX_BUTTONS) add("buttons:$at")
                if (step.buttons.any { it.title.isBlank() || it.title.length > MAX_TITLE }) add("title:$at")
                if (step.buttons.any { it.words.size > ChatFlowLimits.MAX_OPTION_WORDS || it.words.any { w -> w.length > ChatFlowLimits.MAX_KEYWORD_CHARS } }) add("words:$at")
                if (step.buttons.any { it.next !in stepIds }) add("next:$at")
                if (step.key != null && (step.buttons.isEmpty() || !VAR.matches(step.key))) add("key:$at")
                if (!reaches(step.next)) add("next:$at")
                if (step.listLabel != null && step.listLabel.length > ChatFlowLimits.MAX_LIST_LABEL) add("list_label:$at")
            }
            StepKind.ASK -> {
                if (step.text.isBlank()) add("text:$at")
                if (step.key == null || !VAR.matches(step.key)) add("key:$at")
                if (!reaches(step.next)) add("next:$at")
            }
            StepKind.CONDITION -> {
                when (step.check) {
                    Check.HOURS -> if (step.hours.isEmpty() || step.hours.size > ChatFlowLimits.MAX_WINDOWS || step.hours.any { !it.valid() }) add("hours:$at")
                    Check.ANSWER -> if (step.answerKey == null || !VAR.matches(step.answerKey) || step.answerIs.isNullOrBlank() || step.answerIs.length > ChatFlowLimits.MAX_KEYWORD_CHARS) add("answer:$at")
                    Check.KNOWN_CLIENT -> Unit
                }
                if (step.whenTrue == null || step.whenTrue !in stepIds || step.whenFalse == null || step.whenFalse !in stepIds) add("branch:$at")
            }
            StepKind.ACTION -> {
                if (step.action == ActionKind.CREATE_BOOKING && step.serviceId.isNullOrBlank() && step.key.isNullOrBlank()) add("service:$at")
                if ((step.serviceId?.length ?: 0) > MAX_ID_CHARS) add("service:$at")
                if (step.key != null && !VAR.matches(step.key)) add("key:$at")
                if (!reaches(step.next) || !reaches(step.failNext)) add("next:$at")
            }
            StepKind.HANDOFF -> Unit
        }
    }

    /** Whether steps that never wait for the customer lead back to themselves, which would send messages forever. */
    private fun hasLoop(): Boolean {
        val byId = steps.associateBy { it.id }
        fun runsOn(step: FlowStep): List<String> = when (step.kind) {
            StepKind.MESSAGE -> if (step.buttons.isEmpty()) listOfNotNull(step.next) else emptyList()
            StepKind.ASK, StepKind.HANDOFF -> emptyList()
            StepKind.CONDITION -> listOfNotNull(step.whenTrue, step.whenFalse)
            StepKind.ACTION -> listOfNotNull(step.next, step.failNext)
        }
        val done = mutableSetOf<String>()
        fun visit(id: String, path: Set<String>): Boolean {
            if (id in path) return true
            if (!done.add(id)) return false
            val step = byId[id] ?: return false
            return runsOn(step).any { visit(it, path + id) }
        }
        return steps.any { visit(it.id, emptySet()) }
    }

    companion object {
        /** The channels a flow can answer on. */
        val SUPPORTED_CHANNELS = setOf(Platform.INSTAGRAM, Platform.WHATSAPP, Platform.WEB)

        /** Instagram's limits (the strictest): 13 quick replies, titles of 20 characters, a message of 1,000 bytes. */
        const val MAX_BUTTONS = 13
        const val MAX_TITLE = 20
        const val MAX_TEXT_BYTES = 1000
        const val MAX_RETRIES = 3
        private const val MAX_ID_CHARS = 64

        private val ID = Regex("^[a-z0-9_-]{1,40}$")

        /** A name a question's answer is kept under: `name`, `phone`, `email`, `service`, `date`, `time` or the company's own. */
        val VAR = Regex("^[a-z][a-z0-9_]{0,29}$")
    }
}

/**
 * One step. What it uses depends on [kind]:
 * - [StepKind.MESSAGE]: [text] and its [buttons] (options a customer taps or types; with a [key], the chosen option's title is kept under it), or, with none, [next].
 * - [StepKind.ASK]: the question [text], the name [key] its answer is kept under, what it [expect]s, then [next].
 * - [StepKind.CONDITION]: the [check] decides between [whenTrue] and [whenFalse].
 * - [StepKind.ACTION]: does [action], sends [text] when it worked, goes to [next], or to [failNext] when it didn't.
 * - [StepKind.HANDOFF]: sends [text] and passes the chat to a person.
 * A null [next] ends the flow.
 */
data class FlowStep(
    val id: String,
    val text: String = "",
    val buttons: List<FlowButton> = emptyList(),
    val kind: StepKind = StepKind.MESSAGE,
    val show: OptionsDisplay = OptionsDisplay.AUTO,
    /** WhatsApp's list needs a label on its button; without one a long list goes out as numbered lines. */
    val listLabel: String? = null,
    /** Said again, ahead of the question or options, when a reply wasn't understood. */
    val retryText: String? = null,
    val next: String? = null,
    val key: String? = null,
    val expect: Expect = Expect.TEXT,
    val check: Check = Check.HOURS,
    val hours: List<HoursWindow> = emptyList(),
    val answerKey: String? = null,
    val answerIs: String? = null,
    val whenTrue: String? = null,
    val whenFalse: String? = null,
    val action: ActionKind = ActionKind.NOTIFY_TEAM,
    /** [ActionKind.CREATE_BOOKING]: the bookable service, or null to take it from the answer kept under [key]. */
    val serviceId: String? = null,
    val failNext: String? = null,
)

/**
 * An option under a step's message: choosing it moves the customer to step [next]. A customer chooses by tapping,
 * by typing its number or [title], or by typing any of its [words].
 */
data class FlowButton(val title: String, val next: String, val words: List<String> = emptyList())

/** Where a customer's message starts a flow. [tapped] when it was an option from an earlier message. */
data class FlowEntry(val flow: ChatFlow, val step: FlowStep, val tapped: Boolean)

/** What an option sends back, and a flow message's `origin`: `flow:<flow id>:<step id>`, with `:<n>` after an option's payload so ids in one message differ. */
internal object FlowPayload {
    const val PREFIX = "flow:"

    fun of(flowId: String, stepId: String, option: Int? = null) = "$PREFIX$flowId:$stepId" + (option?.let { ":$it" } ?: "")

    /** The position of the option a payload carries, when it has one. */
    fun option(payload: String?): Int? =
        if (parse(payload) == null) null else payload!!.removePrefix(PREFIX).split(':').getOrNull(2)?.toIntOrNull()

    fun parse(payload: String?): Pair<String, String>? {
        if (payload == null || !payload.startsWith(PREFIX)) return null
        val parts = payload.removePrefix(PREFIX).split(':')
        val shaped = parts.size == 2 || (parts.size == 3 && parts[2].toIntOrNull() != null)
        return if (shaped && parts.take(2).none { it.isEmpty() }) parts[0] to parts[1] else null
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
