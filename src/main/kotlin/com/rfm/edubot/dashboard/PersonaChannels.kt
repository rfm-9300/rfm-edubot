package com.rfm.edubot.dashboard

import com.rfm.edubot.flows.ChatFlowLimits
import com.rfm.edubot.flows.ChatFlows
import com.rfm.edubot.flows.FlowProblem
import com.rfm.edubot.flows.FlowText
import com.rfm.edubot.flows.StartGate
import com.rfm.edubot.tenant.model.Platform
import kotlinx.serialization.Serializable

/**
 * Persona → Channels: where the AI answers and which start words open a new chat. The chat flows themselves are the
 * Chatbot page's. [startGates] left out keeps the saved ones; flows are never touched here.
 */
@Serializable
internal data class ChannelsInput(val teamOnlyChannels: List<String>, val startGates: List<StartGateDto>? = null)

/** A channel's start gate: new chats there only get answers when the first message holds one of [words]. */
@Serializable
internal data class StartGateDto(val channel: String, val enabled: Boolean, val words: List<String>)

@Serializable
internal data class ChannelsDto(
    val teamOnlyChannels: List<String>,
    val startGates: List<StartGateDto>,
    /** The channels that can have a start gate. */
    val startChannels: List<String>,
    /** The channels where the AI can be turned off. */
    val teamOnlyOptions: List<String>,
    val canEdit: Boolean,
    val instagramConnected: Boolean,
    val whatsappConnected: Boolean,
    /** Flows that are on, so the page can say the Chatbot answers before the AI. */
    val flowsOn: Int,
    val startWords: Int,
    val keywordChars: Int,
    val updatedAt: String?,
    val updatedBy: String?,
)

/** As stored: trimmed, blank and repeated start words dropped (repeats ignore case and accents). Without [ChannelsInput.startGates], [keepGates] stay. */
internal fun ChannelsInput.toChatFlows(keepGates: Map<Platform, StartGate> = emptyMap()) = ChatFlows(
    // The website is left out: nobody could answer a visitor there if the AI were off.
    teamOnlyChannels = teamOnlyChannels.mapNotNull { name -> ChatFlows.TEAM_ONLY_CHANNELS.firstOrNull { it.name == name } }.toSet(),
    startGates = startGates?.mapNotNull { gate ->
        val platform = Platform.entries.firstOrNull { it.name == gate.channel } ?: return@mapNotNull null
        platform to StartGate(gate.enabled, gate.words.map { it.trim() }.filter { FlowText.normalize(it).isNotEmpty() }.distinctBy { FlowText.normalize(it) })
    }?.toMap() ?: keepGates,
)

internal fun channelsDto(flows: ChatFlows?, canEdit: Boolean, instagramConnected: Boolean, whatsappConnected: Boolean) = ChannelsDto(
    teamOnlyChannels = flows?.teamOnlyChannels.orEmpty().map { it.name }.sorted(),
    startGates = flows?.startGates.orEmpty().entries.sortedBy { it.key.name }.map { (platform, gate) -> StartGateDto(platform.name, gate.enabled, gate.words) },
    startChannels = StartGate.CHANNELS.map { it.name }.sorted(),
    teamOnlyOptions = ChatFlows.TEAM_ONLY_CHANNELS.map { it.name }.sorted(),
    canEdit = canEdit,
    instagramConnected = instagramConnected,
    whatsappConnected = whatsappConnected,
    flowsOn = flows?.flows.orEmpty().count { it.enabled },
    startWords = ChatFlowLimits.MAX_START_WORDS,
    keywordChars = ChatFlowLimits.MAX_KEYWORD_CHARS,
    updatedAt = flows?.updatedAt?.toString(),
    updatedBy = flows?.updatedBy,
)

/** `error` is always `flow_problem`; [problem] says what, [flow] and [step] where, as positions in what was sent, or [channel] for a start gate. */
@Serializable
internal data class FlowErrorDto(val error: String, val problem: String, val flow: Int?, val step: Int?, val limit: Int?, val channel: String?)

internal fun FlowProblem.dto() = FlowErrorDto("flow_problem", code, menu, step, limit, channel)
