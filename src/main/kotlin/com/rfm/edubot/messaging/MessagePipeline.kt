package com.rfm.edubot.messaging

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.Conversation
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.conversation.model.TokenUsage
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.ratelimit.RateDecision
import com.rfm.edubot.ratelimit.RateLimiter
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.whatsapp.WhatsAppClient
import org.slf4j.LoggerFactory

class MessagePipeline(
    private val users: UserRepository,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val rateLimiter: RateLimiter,
    private val aiClient: AiClient,
    private val whatsappClient: WhatsAppClient,
    private val deduplicationService: DeduplicationService,
) {
    private val log = LoggerFactory.getLogger("MessagePipeline")

    suspend fun handle(inbound: InboundMessage) {
        try {
            log.info("Processing message: waId={}, id={}", inbound.waId, inbound.waMessageId)

            val user = users.findOrCreate(inbound.waId, inbound.profileName)

            if (user.status == com.rfm.edubot.conversation.model.UserStatus.BLOCKED) {
                log.warn("Blocked user attempted message: waId={}", inbound.waId)
                deduplicationService.markProcessed(inbound.eventId)
                return
            }

            val rateResult = rateLimiter.tryAcquire(user.waId)
            if (rateResult is RateDecision.Reject) {
                log.warn("Rate limited user: waId={}", inbound.waId)
                whatsappClient.sendText(user.waId, rateResult.message)
                deduplicationService.markProcessed(inbound.eventId)
                return
            }

            val conversation = conversations.findOrCreate(user.id, inbound.waId)

            val userMessage = Message(
                conversationId = conversation.id,
                waId = user.waId,
                role = UserRole.USER,
                waMessageId = inbound.waMessageId,
                content = MessageContent.Text(inbound.messageText),
                status = MessageStatus.RECEIVED,
                createdAt = SystemClock.now(),
            )
            messages.insert(userMessage)

            val contextMessages = buildContext(conversation, inbound.messageText)

            val aiResponse = aiClient.complete(contextMessages)

            val replyText = aiResponse.choices.firstOrNull()?.message?.content ?: "Sorry, I couldn't process that."

            val tokenUsage = aiResponse.usage?.let {
                TokenUsage(prompt = it.prompt_tokens, completion = it.completion_tokens)
            }

            val assistantMessage = Message(
                conversationId = conversation.id,
                waId = user.waId,
                role = UserRole.ASSISTANT,
                content = MessageContent.Text(replyText),
                tokens = tokenUsage,
                model = aiResponse.id,
                costUsd = 0.0,
                status = MessageStatus.DELIVERED,
                createdAt = SystemClock.now(),
            )
            messages.insert(assistantMessage)

            whatsappClient.sendText(user.waId, replyText)

            conversations.bumpActivity(conversation.id, tokenUsage)

            deduplicationService.markProcessed(inbound.eventId)

            log.info(
                "Pipeline completed: waId={}, tokens={}, cost={}",
                user.waId,
                tokenUsage?.let { it.prompt + it.completion },
                assistantMessage.costUsd
            )
        } catch (e: Exception) {
            log.error("Pipeline failed for waId={}: {}", inbound.waId, e.message, e)
            deduplicationService.markFailed(inbound.eventId)
        }
    }

    private suspend fun buildContext(conversation: Conversation, newUserMessage: String): List<ChatMessage> {
        val contextMessages = mutableListOf<ChatMessage>()

        contextMessages.add(ChatMessage(role = "system", content = SystemPrompts.V1))

        conversation.summary?.let { summary ->
            contextMessages.add(
                ChatMessage(
                    role = "system",
                    content = "<previous_context>$summary</previous_context>"
                )
            )
        }

        val recentMessages = messages.lastN(conversation.id, 10)
        for (msg in recentMessages) {
            when (msg.content) {
                is MessageContent.Text -> {
                    val role = when (msg.role) {
                        UserRole.USER -> "user"
                        UserRole.ASSISTANT -> "assistant"
                        else -> "system"
                    }
                    contextMessages.add(ChatMessage(role = role, content = (msg.content as MessageContent.Text).body))
                }
                else -> {}
            }
        }

        contextMessages.add(ChatMessage(role = "user", content = newUserMessage))

        return contextMessages
    }
}
