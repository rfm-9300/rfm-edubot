package com.rfm.edubot.flows

import com.rfm.edubot.channel.ChannelCapabilities
import com.rfm.edubot.channel.QuickReply

/** An option a message offers: what it says and the payload a tap sends back. */
data class FlowOption(val title: String, val payload: String)

/** One message a flow sends, before it is fitted to a channel. [origin] is `flow:<flow id>:<step id>`, kept on the stored message. */
data class FlowMessage(
    val text: String,
    val options: List<FlowOption> = emptyList(),
    val display: OptionsDisplay = OptionsDisplay.AUTO,
    val listLabel: String? = null,
    val origin: String,
) {
    /**
     * What goes out on a channel with [caps]: the options as taps when it has them (and they fit), else as numbered
     * lines under the text, which a customer answers by typing the number. [OptionsDisplay.TAPS_AND_NUMBERS] also writes
     * the lines under the text where taps may not show, when text and lines still fit beside the taps; [OptionsDisplay.HIDDEN]
     * sends the text as written.
     */
    fun fitTo(caps: ChannelCapabilities): FlowOutgoing {
        if (options.isEmpty() || display == OptionsDisplay.HIDDEN) return FlowOutgoing(text, emptyList(), null)
        val numbered = "$text\n\n" + options.mapIndexed { i, option -> "${i + 1}. ${option.title}" }.joinToString("\n")
        val canTap = display != OptionsDisplay.NUMBERED &&
            options.size <= caps.quickReplies &&
            (options.size <= caps.buttonReplies || !listLabel.isNullOrBlank()) &&
            options.all { it.title.length <= caps.quickReplyTitleChars }
        fun fits(body: String) = body.toByteArray().size <= caps.quickReplyBodyBytes
        if (canTap) {
            val replies = options.map { QuickReply(it.title, it.payload) }
            val label = listLabel?.takeIf { options.size > caps.buttonReplies }
            if (display == OptionsDisplay.TAPS_AND_NUMBERS && caps.quickRepliesMayBeHidden && fits(numbered)) return FlowOutgoing(numbered, replies, label)
            if (fits(text)) return FlowOutgoing(text, replies, label)
        }
        return FlowOutgoing(numbered, emptyList(), null)
    }
}

/** A message as a channel sends it: [replies] are taps, empty when the options are in [text] or there are none. */
data class FlowOutgoing(val text: String, val replies: List<QuickReply>, val listLabel: String?)

private val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]*)}")

/** [text] with each `{key}` replaced by what the customer answered under that key (nothing, when they haven't). */
internal fun fillAnswers(text: String, vars: Map<String, String>): String = PLACEHOLDER.replace(text) { vars[it.groupValues[1]].orEmpty() }
