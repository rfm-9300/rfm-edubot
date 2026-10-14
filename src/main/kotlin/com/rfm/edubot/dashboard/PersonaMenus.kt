package com.rfm.edubot.dashboard

import com.rfm.edubot.channel.QuickReply
import com.rfm.edubot.flows.ChatFlow
import com.rfm.edubot.flows.ChatFlowLimits
import com.rfm.edubot.flows.ChatFlowUsage
import com.rfm.edubot.flows.ChatFlows
import com.rfm.edubot.flows.FlowButton
import com.rfm.edubot.flows.FlowProblem
import com.rfm.edubot.flows.FlowStep
import com.rfm.edubot.flows.FlowText
import com.rfm.edubot.flows.FlowUsage
import com.rfm.edubot.tenant.model.Platform
import kotlinx.serialization.Serializable

/** The Persona page's Menus tab edits a company's chat flows; the same shape goes in and comes out. */
@Serializable
internal data class MenusInput(val flows: List<MenuDto>, val teamOnlyChannels: List<String>)

@Serializable
internal data class MenuDto(val id: String, val name: String, val enabled: Boolean, val keywords: List<String>, val steps: List<MenuStepDto>)

@Serializable
internal data class MenuStepDto(val id: String, val text: String, val buttons: List<MenuButtonDto>)

@Serializable
internal data class MenuButtonDto(val title: String, val next: String)

@Serializable
internal data class MenuLimitsDto(
    val menus: Int,
    val steps: Int,
    val keywords: Int,
    val keywordChars: Int,
    val nameChars: Int,
    val buttons: Int,
    val titleChars: Int,
    val textBytes: Int,
)

/** What each saved menu sent in the last [days] days; a menu that sent nothing, nor tried to, isn't listed. */
@Serializable
internal data class MenuUsageDto(val days: Int, val menus: List<MenuSentDto>)

/** [sent] went out and [failed] were refused by Instagram; [steps] counts the sent ones per message id, for messages stored with their step. */
@Serializable
internal data class MenuSentDto(val id: String, val sent: Int, val failed: Int, val customers: Int, val lastSentAt: String?, val steps: Map<String, Int>)

@Serializable
internal data class MenusDto(
    val flows: List<MenuDto>,
    val teamOnlyChannels: List<String>,
    val canEdit: Boolean,
    /** Menus only answer Instagram messages, so the tab says when there is no Instagram account to answer. */
    val instagramConnected: Boolean,
    val updatedAt: String?,
    val updatedBy: String?,
    val limits: MenuLimitsDto,
    val usage: MenuUsageDto,
)

/** `error` is always `menu_problem`; [problem] says what, [menu] and [step] where, as positions in what was sent. */
@Serializable
internal data class MenuErrorDto(val error: String, val problem: String, val menu: Int?, val step: Int?, val limit: Int?)

@Serializable
internal data class QuickReplyDto(val title: String, val payload: String)

/** As stored: trimmed, blank and repeated keywords dropped (repeats ignore case and accents), on Instagram. */
internal fun MenusInput.toChatFlows() = ChatFlows(
    flows = flows.map { menu ->
        ChatFlow(
            id = menu.id.trim(),
            name = menu.name.trim(),
            enabled = menu.enabled,
            channels = setOf(Platform.INSTAGRAM),
            keywords = menu.keywords.map { it.trim() }.filter { FlowText.normalize(it).isNotEmpty() }.distinctBy { FlowText.normalize(it) },
            steps = menu.steps.map { step ->
                FlowStep(step.id.trim(), step.text.trim(), step.buttons.map { FlowButton(it.title.trim(), it.next.trim()) })
            },
        )
    },
    teamOnlyChannels = teamOnlyChannels.mapNotNull { name -> Platform.entries.firstOrNull { it.name == name } }.toSet(),
)

internal fun menusDto(flows: ChatFlows?, canEdit: Boolean, instagramConnected: Boolean, usage: Map<String, FlowUsage>) = MenusDto(
    flows = flows?.flows.orEmpty().map { flow ->
        MenuDto(flow.id, flow.name, flow.enabled, flow.keywords, flow.steps.map { step -> MenuStepDto(step.id, step.text, step.buttons.map { MenuButtonDto(it.title, it.next) }) })
    },
    teamOnlyChannels = flows?.teamOnlyChannels.orEmpty().map { it.name }.sorted(),
    canEdit = canEdit,
    instagramConnected = instagramConnected,
    updatedAt = flows?.updatedAt?.toString(),
    updatedBy = flows?.updatedBy,
    limits = MenuLimitsDto(
        menus = ChatFlowLimits.MAX_FLOWS,
        steps = ChatFlowLimits.MAX_STEPS,
        keywords = ChatFlowLimits.MAX_KEYWORDS,
        keywordChars = ChatFlowLimits.MAX_KEYWORD_CHARS,
        nameChars = ChatFlowLimits.MAX_NAME_CHARS,
        buttons = ChatFlow.MAX_BUTTONS,
        titleChars = ChatFlow.MAX_TITLE,
        textBytes = ChatFlow.MAX_TEXT_BYTES,
    ),
    usage = MenuUsageDto(
        days = ChatFlowUsage.DAYS,
        menus = flows?.flows.orEmpty().mapNotNull { flow ->
            usage[flow.id]?.let { MenuSentDto(flow.id, it.sent, it.failed, it.customers, it.lastSentAt?.toString(), it.steps) }
        },
    ),
)

internal fun FlowProblem.dto() = MenuErrorDto("menu_problem", code, menu, step, limit)

internal fun QuickReply.dto() = QuickReplyDto(title, payload)
