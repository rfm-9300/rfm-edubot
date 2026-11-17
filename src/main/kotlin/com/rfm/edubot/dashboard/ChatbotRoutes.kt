package com.rfm.edubot.dashboard

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.bookings.BookableServiceRepository
import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.flows.ActionKind
import com.rfm.edubot.flows.AfterMisses
import com.rfm.edubot.flows.AiFlowInterpreter
import com.rfm.edubot.flows.ChatFlow
import com.rfm.edubot.flows.ChatFlowLimits
import com.rfm.edubot.flows.ChatFlowRepository
import com.rfm.edubot.flows.ChatFlowUsage
import com.rfm.edubot.flows.ChatFlows
import com.rfm.edubot.flows.Check
import com.rfm.edubot.flows.DryRunFlowActions
import com.rfm.edubot.flows.Expect
import com.rfm.edubot.flows.FlowButton
import com.rfm.edubot.flows.FlowContext
import com.rfm.edubot.flows.FlowEngine
import com.rfm.edubot.flows.FlowInput
import com.rfm.edubot.flows.FlowSession
import com.rfm.edubot.flows.FlowStep
import com.rfm.edubot.flows.FlowText
import com.rfm.edubot.flows.FlowUsage
import com.rfm.edubot.flows.HoursWindow
import com.rfm.edubot.flows.OptionsDisplay
import com.rfm.edubot.flows.StepKind
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.TenantTimeZones
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId

/**
 * `/app/api/chatbot`: the company's fixed chat flows (keywords, questions, options, conditions, actions) that answer
 * before the AI. Everyone with the Persona module reads and tries them; only admins change them, since they speak for
 * the company and can create clients and bookings. The AI switch and start words stay in Persona → Channels.
 */
internal object ChatbotRoutes : DashboardFeature(DashboardModules.PERSONA) {
    override fun Route.routes(deps: DashboardDeps) {
        chatbotRoutes(deps.mongo, deps.aiClient)
    }
}

private fun Route.chatbotRoutes(mongo: MongoModule, aiClient: AiClient) {
    route("/chatbot") {
        get("/flows") {
            val ctx = call.moduleContext()
            call.respond(chatbotDto(mongo, ctx))
        }
        put("/flows") {
            val ctx = call.personaEditor() ?: return@put
            val input = call.body<FlowsInput>() ?: return@put
            val flows = input.flows.map { it.toFlow() }
            ChatFlows(flows = flows).saveProblem()?.let { return@put call.respond(HttpStatusCode.BadRequest, it.dto()) }
            ChatFlowRepository(mongo, ctx.tenant.id).saveFlows(flows, ctx.author())
            call.respond(chatbotDto(mongo, ctx))
        }
        // Tries a flow without sending anything or writing records; the page keeps the customer's place and sends it back.
        post("/test") {
            val ctx = call.moduleContext()
            val request = call.body<ChatbotTestRequest>() ?: return@post
            val repository = ChatFlowRepository(mongo, ctx.tenant.id)
            val saved = repository.load()
            val flows = if (request.flows != null) {
                val draft = ChatFlows(flows = request.flows.map { it.toFlow() })
                draft.saveProblem()?.let { return@post call.respond(HttpStatusCode.BadRequest, it.dto()) }
                draft
            } else {
                repository.find() ?: ChatFlows()
            }
            val platform = Platform.entries.firstOrNull { it.name == request.channel } ?: Platform.WHATSAPP
            val zone = TimeZone.of(TenantTimeZones.normalize(ctx.tenant.timezone))
            val now = request.at?.let { runCatching { LocalDateTime.parse(it).toInstant(zone) }.getOrNull() } ?: SystemClock.now()
            val usage = TenantUsageRepository(mongo, ctx.tenant.id)
            val aiAllowed = usage.tokensUsedThisMonth() < ctx.tenant.monthlyTokenBudget
            val engine = FlowEngine(
                actions = DryRunFlowActions(request.knownClient),
                interpreter = AiFlowInterpreter(aiClient, ctx.tenant.openrouterModel) { tokens -> usage.recordUsage(tokens, UsageSources.CHATBOT) },
            )
            val session = request.state?.let { FlowSession(it.flowId, it.stepId, it.vars, it.misses, now) }
            val input = FlowInput(platform, request.text, request.payload)
            val aiAnswers = platform !in (saved?.teamOnlyChannels.orEmpty())
            val result = try {
                if (engine.wouldAnswer(flows, session, input, now)) {
                    // A WhatsApp customer's id is their number, so the test chat has one; the other channels have no phone, as in real chats.
                    val waId = if (platform == Platform.WHATSAPP) "351900000000" else "test"
                    engine.handle(flows, session, input, FlowContext(platform, waId, null, null, now, zone, aiAnswers, aiAllowed))
                } else {
                    com.rfm.edubot.flows.FlowResult.UNHANDLED
                }
            } catch (e: CancellationException) {
                throw e
            }
            val caps = when (platform) {
                Platform.WHATSAPP -> ChannelCapabilities.WHATSAPP
                Platform.INSTAGRAM -> ChannelCapabilities.INSTAGRAM
                Platform.WEB -> ChannelCapabilities.WEB
            }
            call.respond(
                ChatbotTestDto(
                    handled = result.handled,
                    flow = result.flowName,
                    messages = result.messages.map { message ->
                        val out = message.fitTo(caps)
                        TestMessageDto(out.text, out.replies.map { QuickReplyDto(it.title, it.payload) }, out.listLabel, message.origin)
                    },
                    state = result.session?.let { TestStateDto(it.flowId, it.stepId, it.vars, it.misses) },
                    handedOver = result.handOver != null,
                    reason = result.handOver?.reason,
                    effects = result.effects.map { EffectDto(it.action.name, it.ok, it.detail) },
                    aiAnswers = aiAnswers,
                ),
            )
        }
    }
}

private suspend fun chatbotDto(mongo: MongoModule, ctx: DashboardContext): ChatbotDto {
    val flows = ChatFlowRepository(mongo, ctx.tenant.id).load()
    val modules = DashboardModules.effectiveFor(ctx.tenant).toSet()
    val bookable = if (DashboardModules.BOOKINGS in modules) BookableServiceRepository(mongo, ctx.tenant.id).list(activeOnly = true) else emptyList()
    val usage = ChatFlowUsage.of(MessageRepository(mongo, ctx.tenant.id).countByOrigin(ChatFlowUsage.ORIGIN_PREFIX, ChatFlowUsage.since(SystemClock.now())))
    return ChatbotDto(
        flows = flows?.flows.orEmpty().map { it.dto() },
        canEdit = ctx.isAdmin(),
        connected = channelsOf(ctx.tenant).filter { it.connected }.map { it.channel },
        teamOnlyChannels = flows?.teamOnlyChannels.orEmpty().map { it.name }.sorted(),
        clientsModule = DashboardModules.CLIENTS in modules,
        bookingsModule = DashboardModules.BOOKINGS in modules,
        services = bookable.map { ServiceOptionDto(it.id, it.name) },
        limits = FlowLimitsDto(
            flows = ChatFlowLimits.MAX_FLOWS,
            steps = ChatFlowLimits.MAX_STEPS,
            keywords = ChatFlowLimits.MAX_KEYWORDS,
            keywordChars = ChatFlowLimits.MAX_KEYWORD_CHARS,
            nameChars = ChatFlowLimits.MAX_NAME_CHARS,
            options = ChatFlow.MAX_BUTTONS,
            titleChars = ChatFlow.MAX_TITLE,
            textBytes = ChatFlow.MAX_TEXT_BYTES,
            optionWords = ChatFlowLimits.MAX_OPTION_WORDS,
            hours = ChatFlowLimits.MAX_WINDOWS,
            listLabelChars = ChatFlowLimits.MAX_LIST_LABEL,
        ),
        usage = FlowUsageDto(
            days = ChatFlowUsage.DAYS,
            flows = flows?.flows.orEmpty().mapNotNull { flow ->
                usage[flow.id]?.let { FlowSentDto(flow.id, it.sent, it.failed, it.customers, it.lastSentAt?.toString(), it.steps) }
            },
        ),
        updatedAt = flows?.updatedAt?.toString(),
        updatedBy = flows?.updatedBy,
    )
}

@Serializable
internal data class FlowsInput(val flows: List<FlowDto>)

@Serializable
internal data class FlowDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val channels: List<String>,
    val keywords: List<String>,
    val steps: List<FlowStepDto>,
    val aiReads: Boolean = false,
    val afterMisses: String = "HAND_OVER",
    val handoverText: String = "",
    val retries: Int = 1,
)

/** A step with every field, empty where unused, so the page binds inputs without null checks. See [FlowStep] for what each kind uses. */
@Serializable
internal data class FlowStepDto(
    val id: String,
    val kind: String,
    val text: String,
    val options: List<FlowOptionDto>,
    val show: String,
    val listLabel: String,
    val retryText: String,
    val next: String,
    val key: String,
    val expect: String,
    val check: String,
    val hours: List<HoursDto>,
    val answerKey: String,
    val answerIs: String,
    val whenTrue: String,
    val whenFalse: String,
    val action: String,
    val serviceId: String,
    val failNext: String,
)

@Serializable
internal data class FlowOptionDto(val title: String, val next: String, val words: List<String>)

@Serializable
internal data class HoursDto(val day: Int, val from: String, val to: String)

@Serializable
internal data class FlowLimitsDto(
    val flows: Int,
    val steps: Int,
    val keywords: Int,
    val keywordChars: Int,
    val nameChars: Int,
    val options: Int,
    val titleChars: Int,
    val textBytes: Int,
    val optionWords: Int,
    val hours: Int,
    val listLabelChars: Int,
)

@Serializable
internal data class ServiceOptionDto(val id: String, val name: String)

/** What each saved flow sent in the last [days] days; a flow that sent nothing, nor tried to, isn't listed. */
@Serializable
internal data class FlowUsageDto(val days: Int, val flows: List<FlowSentDto>)

/** [sent] went out and [failed] were refused by the channel; [steps] counts the sent ones per step id. */
@Serializable
internal data class FlowSentDto(val id: String, val sent: Int, val failed: Int, val customers: Int, val lastSentAt: String?, val steps: Map<String, Int>)

@Serializable
internal data class ChatbotDto(
    val flows: List<FlowDto>,
    val canEdit: Boolean,
    /** The channels with an account connected, which the flow's channel choices are marked against. */
    val connected: List<String>,
    /** Channels where the AI is off, so what no flow answers there waits for the team. */
    val teamOnlyChannels: List<String>,
    val clientsModule: Boolean,
    val bookingsModule: Boolean,
    val services: List<ServiceOptionDto>,
    val limits: FlowLimitsDto,
    val usage: FlowUsageDto,
    val updatedAt: String?,
    val updatedBy: String?,
)

@Serializable
internal data class QuickReplyDto(val title: String, val payload: String)

/**
 * One turn in the test chat. [flows] are the page's unsaved flows (the saved ones when left out); [state] is where the
 * customer was left by the last turn; [at] pretends it is that local date and time, for opening hours.
 */
@Serializable
internal data class ChatbotTestRequest(
    val flows: List<FlowDto>? = null,
    val channel: String = "WHATSAPP",
    val text: String = "",
    val payload: String? = null,
    val state: TestStateDto? = null,
    val knownClient: Boolean = false,
    val at: String? = null,
)

@Serializable
internal data class TestStateDto(val flowId: String, val stepId: String, val vars: Map<String, String> = emptyMap(), val misses: Int = 0)

/** [handled] false: no flow answers, so the AI would (when [aiAnswers]) or the team. [effects] are what the actions would have done. */
@Serializable
internal data class ChatbotTestDto(
    val handled: Boolean,
    val flow: String?,
    val messages: List<TestMessageDto>,
    val state: TestStateDto?,
    val handedOver: Boolean,
    val reason: String?,
    val effects: List<EffectDto>,
    val aiAnswers: Boolean,
)

@Serializable
internal data class TestMessageDto(val text: String, val replies: List<QuickReplyDto>, val listLabel: String?, val origin: String)

@Serializable
internal data class EffectDto(val action: String, val ok: Boolean, val detail: String)

private fun String.orNull(): String? = trim().takeIf { it.isNotEmpty() }

private inline fun <reified E : Enum<E>> String.enumOr(default: E): E = enumValues<E>().firstOrNull { it.name == this } ?: default

private fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { FlowText.normalize(it).isNotEmpty() }.distinctBy { FlowText.normalize(it) }

/** As stored: trimmed, blanks dropped, repeated keywords and option words ignoring case and accents. */
internal fun FlowDto.toFlow() = ChatFlow(
    id = id.trim(),
    name = name.trim(),
    enabled = enabled,
    channels = channels.mapNotNull { name -> Platform.entries.firstOrNull { it.name == name } }.toSet(),
    keywords = keywords.cleaned(),
    steps = steps.map { it.toStep() },
    aiReads = aiReads,
    afterMisses = afterMisses.enumOr(AfterMisses.HAND_OVER),
    handoverText = handoverText.orNull(),
    retries = retries,
)

private fun FlowStepDto.toStep() = FlowStep(
    id = id.trim(),
    kind = kind.enumOr(StepKind.MESSAGE),
    text = text.trim(),
    buttons = options.map { FlowButton(it.title.trim(), it.next.trim(), it.words.cleaned()) },
    show = show.enumOr(OptionsDisplay.AUTO),
    listLabel = listLabel.orNull(),
    retryText = retryText.orNull(),
    next = next.orNull(),
    key = key.orNull(),
    expect = expect.enumOr(Expect.TEXT),
    check = check.enumOr(Check.HOURS),
    hours = hours.map { HoursWindow(it.day, it.from.trim(), it.to.trim()) },
    answerKey = answerKey.orNull(),
    answerIs = answerIs.orNull(),
    whenTrue = whenTrue.orNull(),
    whenFalse = whenFalse.orNull(),
    action = action.enumOr(ActionKind.NOTIFY_TEAM),
    serviceId = serviceId.orNull(),
    failNext = failNext.orNull(),
)

internal fun ChatFlow.dto() = FlowDto(
    id = id,
    name = name,
    enabled = enabled,
    channels = channels.map { it.name }.sorted(),
    keywords = keywords,
    steps = steps.map { it.dto() },
    aiReads = aiReads,
    afterMisses = afterMisses.name,
    handoverText = handoverText.orEmpty(),
    retries = retries,
)

private fun FlowStep.dto() = FlowStepDto(
    id = id,
    kind = kind.name,
    text = text,
    options = buttons.map { FlowOptionDto(it.title, it.next, it.words) },
    show = show.name,
    listLabel = listLabel.orEmpty(),
    retryText = retryText.orEmpty(),
    next = next.orEmpty(),
    key = key.orEmpty(),
    expect = expect.name,
    check = check.name,
    hours = hours.map { HoursDto(it.day, it.from, it.to) },
    answerKey = answerKey.orEmpty(),
    answerIs = answerIs.orEmpty(),
    whenTrue = whenTrue.orEmpty(),
    whenFalse = whenFalse.orEmpty(),
    action = action.name,
    serviceId = serviceId.orEmpty(),
    failNext = failNext.orEmpty(),
)
