package com.rfm.edubot.messaging

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.channel.ProfileLookupClient
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.Path

class MessagePipeline(
    private val users: UserRepository,
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val rateLimiter: RateLimiter,
    private val aiClient: AiClient,
    private val deduplicationService: DeduplicationService,
    private val crmTools: CrmTools,
    private val clientRepository: ClientRepository,
    private val quoteRepository: QuoteRepository,
    private val invoiceRepository: InvoiceRepository,
    private val pdfGenerator: PdfGenerator,
    private val pdfStoragePath: String,
    private val openrouterModel: String? = null,
    private val compiledPersona: String? = null,
) {
    private val log = LoggerFactory.getLogger("MessagePipeline")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun handle(inbound: InboundMessage, responder: OutboundClient) {
        try {
            log.info("Processing message: platform={}, waId={}, id={}", inbound.platform, inbound.waId, inbound.waMessageId)

            val profileName = inbound.profileName ?: (responder as? ProfileLookupClient)?.profileName(inbound.waId)
            val user = users.findOrCreate(inbound.waId, profileName, inbound.platform)

            if (user.status == com.rfm.edubot.conversation.model.UserStatus.BLOCKED) {
                log.warn("Blocked user attempted message: waId={}", inbound.waId)
                deduplicationService.markProcessed(inbound.eventId)
                return
            }

            if (inbound.registerOnly) {
                val existing = conversations.findByWaId(inbound.waId, inbound.platform)
                if (existing == null) {
                    val conversation = conversations.findOrCreate(user.id, inbound.waId, inbound.platform)
                    messages.insert(
                        Message(
                            tenantId = inbound.tenantId,
                            conversationId = conversation.id,
                            channel = inbound.platform,
                            waId = user.waId,
                            role = UserRole.USER,
                            waMessageId = inbound.waMessageId,
                            content = MessageContent.Text(inbound.messageText),
                            status = MessageStatus.RECEIVED,
                            createdAt = SystemClock.now(),
                        )
                    )
                    conversations.bumpActivity(conversation.id)
                    log.info("Registered conversation without automatic reply: platform={}, waId={}", inbound.platform, inbound.waId)
                }
                deduplicationService.markProcessed(inbound.eventId)
                return
            }

            val rateResult = rateLimiter.tryAcquire("${inbound.platform.name}:${user.waId}")
            if (rateResult is RateDecision.Reject) {
                log.warn("Rate limited user: waId={}", inbound.waId)
                responder.sendText(user.waId, rateResult.message)
                deduplicationService.markProcessed(inbound.eventId)
                return
            }

            val conversation = conversations.findOrCreate(user.id, inbound.waId, inbound.platform)

            val userMessage = Message(
                tenantId = inbound.tenantId,
                conversationId = conversation.id,
                channel = inbound.platform,
                waId = user.waId,
                role = UserRole.USER,
                waMessageId = inbound.waMessageId,
                content = MessageContent.Text(inbound.messageText),
                status = MessageStatus.RECEIVED,
                createdAt = SystemClock.now(),
            )
            messages.insert(userMessage)

            val contextMessages = buildContext(conversation, inbound.messageText).toMutableList()
            val confirmationReply = isConfirmationReplyToCrmPrompt(inbound.messageText, contextMessages)
            val continuingCrm = isContinuingCrmFlow(contextMessages)
            if (confirmationReply) {
                contextMessages.removeAt(contextMessages.lastIndex)
                contextMessages.add(
                    ChatMessage(
                        role = "system",
                        content = "The user's last message is an explicit confirmation for the pending CRM action. Continue from the previous assistant message and use the CRM tools now. Do not say a client, quote, invoice, or payment was created unless a tool result confirms it."
                    )
                )
                contextMessages.add(ChatMessage(role = "user", content = "pode gerar. Confirmo os dados anteriores."))
            }
            // When the bot is still collecting data (phone, service, price), never inject a
            // synthetic confirmation — just let the AI see the reply in context and decide
            // what to ask for next. Tools stay available (via useTools below) but are not forced.
            val pdfReply = if (isPdfRequest(inbound.messageText)) {
                try {
                    sendLatestQuotePdf(user.waId, contextMessages, responder)
                } catch (e: Exception) {
                    log.error("Failed to send requested PDF: waId={}, error={}", user.waId, e.message, e)
                    "Encontrei o orçamento, mas não consegui enviar o PDF por este canal agora. Tente novamente em alguns segundos."
                }
            } else {
                null
            }
            if (pdfReply != null) {
                val assistantMessage = Message(
                    tenantId = inbound.tenantId,
                    conversationId = conversation.id,
                    channel = inbound.platform,
                    waId = user.waId,
                    role = UserRole.ASSISTANT,
                    content = MessageContent.Text(pdfReply),
                    status = MessageStatus.DELIVERED,
                    createdAt = SystemClock.now(),
                )
                messages.insert(assistantMessage)
                responder.sendText(user.waId, pdfReply)
                conversations.bumpActivity(conversation.id, null)
                deduplicationService.markProcessed(inbound.eventId)
                log.info("Pipeline completed with requested PDF: waId={}", user.waId)
                return
            }

            var replyText = "Sorry, I couldn't process that."
            var tokenUsage: TokenUsage? = null
            var responseId: String? = null
            val createdDocuments = mutableListOf<CreatedDocument>()

            // Only true when user gave an explicit confirmation ("sim"/"pode gerar"/etc.) after
            // seeing the full summary. Providing partial data (phone, service) does NOT count —
            // the AI must collect everything and show a summary before tools are forced.
            val isConfirmedCrmAction = confirmationReply

            var completed = false
            var iterations = 0
            // Tools must be available when confirmed, plus whenever message has CRM keywords or mid-flow.
            var useTools = shouldUseCrmTools(inbound.messageText) || continuingCrm || isConfirmedCrmAction
            var feedbackSent = false

            // Send immediate feedback before the first AI call when we know tools will run,
            // so the user doesn't wait in silence during the ~4-5s model latency.
            if (isConfirmedCrmAction) {
                responder.sendText(user.waId, "Um momento, processando...")
                feedbackSent = true
            }

            while (!completed && iterations < 5) {
                iterations += 1
                val forceTools = useTools && isConfirmedCrmAction && iterations == 1
                val aiResponse = try {
                    aiClient.complete(contextMessages, if (useTools) crmTools.definitions else emptyList(), forceToolUse = forceTools, modelOverride = openrouterModel)
                } catch (e: Exception) {
                    if (useTools && e.message?.contains("tool", ignoreCase = true) == true) {
                        log.warn("AI model rejected tool use; retrying without tools: {}", e.message)
                        useTools = false
                        aiClient.complete(contextMessages, emptyList(), modelOverride = openrouterModel)
                    } else {
                        throw e
                    }
                }
                when (aiResponse) {
                    is AiResponse.Text -> {
                        replyText = aiResponse.content
                        tokenUsage = aiResponse.usage?.let { TokenUsage(prompt = it.prompt_tokens, completion = it.completion_tokens) }
                        responseId = aiResponse.responseId
                        completed = true
                    }
                    is AiResponse.ToolUse -> {
                        if (!feedbackSent) {
                            responder.sendText(user.waId, "Um momento, processando...")
                            feedbackSent = true
                        } else if (iterations == 2) {
                            responder.sendText(user.waId, "Quase pronto, gerando o documento...")
                        }
                        tokenUsage = aiResponse.usage?.let { TokenUsage(prompt = it.prompt_tokens, completion = it.completion_tokens) }
                        responseId = aiResponse.responseId
                        contextMessages.add(aiResponse.message)
                        for (call in aiResponse.calls) {
                            log.info("Executing CRM tool: name={}, id={}", call.name, call.id)
                            val result = try {
                                if (requiresExplicitConfirmation(call.name) && !isConfirmedCrmAction && !hasExplicitConfirmation(inbound.messageText, contextMessages)) {
                                    buildJsonObject {
                                        put("error", "confirmation_required")
                                        put("message", "Before creating or updating records, summarize the proposed data and ask the user to confirm with 'pode gerar' or 'confirmo'.")
                                    }
                                } else {
                                    crmTools.execute(call)
                                }
                            } catch (e: Exception) {
                                log.error("CRM tool failed: name={}, error={}", call.name, e.message, e)
                                buildJsonObject {
                                    put("error", "tool_failed")
                                    put("tool", call.name)
                                    put("message", e.message ?: "Unknown CRM tool failure")
                                    put("instruction", "Do not claim this action succeeded. If enough data exists, retry with corrected tool arguments. Otherwise explain the concrete failure briefly.")
                                }
                            }
                            log.info("CRM tool result: name={}, result={}", call.name, json.encodeToString(result))
                            result.createdDocument()?.let { createdDocuments.add(it) }
                            contextMessages.add(ChatMessage(role = "tool", content = json.encodeToString(result), toolCallId = call.id))
                        }
                    }
                }
            }

            if (!completed) {
                log.warn("AI tool loop ended without final text: waId={}, iterations={}", inbound.waId, iterations)
                val finalResponse = aiClient.complete(
                    contextMessages + ChatMessage(
                        role = "system",
                        content = "Do not call tools now. Reply to the user in Portuguese with the current result. If a tool returned confirmation_required, ask for confirmation before any record is created."
                    ),
                    emptyList(),
                    modelOverride = openrouterModel,
                )
                if (finalResponse is AiResponse.Text) {
                    replyText = finalResponse.content
                    tokenUsage = finalResponse.usage?.let { TokenUsage(prompt = it.prompt_tokens, completion = it.completion_tokens) } ?: tokenUsage
                    responseId = finalResponse.responseId
                }
            }

            val assistantMessage = Message(
                tenantId = inbound.tenantId,
                conversationId = conversation.id,
                channel = inbound.platform,
                waId = user.waId,
                role = UserRole.ASSISTANT,
                content = MessageContent.Text(replyText),
                tokens = tokenUsage,
                model = responseId,
                costUsd = 0.0,
                status = MessageStatus.DELIVERED,
                createdAt = SystemClock.now(),
            )
            messages.insert(assistantMessage)

            responder.sendText(user.waId, replyText)
            sendCreatedDocuments(user.waId, createdDocuments, responder)

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

        contextMessages.add(ChatMessage(role = "system", content = SystemPrompts.CRM_V1))
        compiledPersona?.takeIf { it.isNotBlank() }?.let { persona ->
            contextMessages.add(ChatMessage(role = "system", content = "<persona>\n$persona\n</persona>"))
        }

        conversation.summary?.let { summary ->
            contextMessages.add(
                ChatMessage(
                    role = "system",
                    content = "<previous_context>$summary</previous_context>"
                )
            )
        }

        val recentMessages = messages.lastNByWaId(conversation.waId, 10, conversation.channel)
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

    private suspend fun sendCreatedDocuments(waId: String, createdDocuments: List<CreatedDocument>, responder: OutboundClient) {
        for (created in createdDocuments.distinctBy { it.type to it.id }) {
            when (created.type) {
                "quote" -> {
                    val quote = quoteRepository.findById(ObjectId(created.id)) ?: continue
                    val client = clientRepository.findById(quote.clientId) ?: continue
                    val bytes = pdfGenerator.generateQuote(quote, client)
                    val filename = "Orcamento ${quote.number}.pdf"
                    val path = savePdf("quotes", filename, bytes)
                    quoteRepository.setPdfPath(quote.id, path.toString())
                    if (responder.capabilities.supportsDocuments) {
                        responder.sendDocument(waId, bytes, filename, "application/pdf")
                    } else {
                        responder.sendText(waId, "O orçamento ${quote.number} foi gerado e ficou disponível no dashboard.")
                    }
                }
                "invoice" -> {
                    val invoice = invoiceRepository.findById(ObjectId(created.id)) ?: continue
                    val client = clientRepository.findById(invoice.clientId) ?: continue
                    val bytes = pdfGenerator.generateInvoice(invoice, client)
                    val filename = "Fatura ${invoice.number}.pdf"
                    val path = savePdf("invoices", filename, bytes)
                    invoiceRepository.setPdfPath(invoice.id, path.toString())
                    if (responder.capabilities.supportsDocuments) {
                        responder.sendDocument(waId, bytes, filename, "application/pdf")
                    } else {
                        responder.sendText(waId, "A fatura ${invoice.number} foi gerada e ficou disponível no dashboard.")
                    }
                }
            }
        }
    }

    private fun savePdf(folder: String, filename: String, bytes: ByteArray): Path {
        val directory = Path.of(pdfStoragePath, folder)
        Files.createDirectories(directory)
        val path = directory.resolve(filename)
        Files.write(path, bytes)
        return path
    }

    private suspend fun sendLatestQuotePdf(waId: String, contextMessages: List<ChatMessage>, responder: OutboundClient): String? {
        val client = inferClientFromContext(contextMessages) ?: return null
        val quote = quoteRepository.list(client.id).firstOrNull() ?: return null
        val bytes = pdfGenerator.generateQuote(quote, client)
        val filename = "Orcamento ${quote.number}.pdf"
        val path = savePdf("quotes", filename, bytes)
        quoteRepository.setPdfPath(quote.id, path.toString())
        return if (responder.capabilities.supportsDocuments) {
            responder.sendDocument(waId, bytes, filename, "application/pdf")
            "Enviei o PDF do orçamento ${quote.number}."
        } else {
            "O PDF do orçamento ${quote.number} foi gerado e ficou disponível no dashboard."
        }
    }

    private suspend fun inferClientFromContext(contextMessages: List<ChatMessage>): com.rfm.edubot.crm.model.Client? {
        for (message in contextMessages.asReversed()) {
            val content = message.content ?: continue
            for (match in clientNameRegex.findAll(content)) {
                val name = match.groupValues[1].trim()
                val client = clientRepository.search(name).firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (client != null) return client
            }
        }
        return null
    }

    private fun JsonObject.createdDocument(): CreatedDocument? {
        val type = get("type")?.jsonPrimitive?.contentOrNull
        val id = get("id")?.jsonPrimitive?.contentOrNull
        if (type == null || id == null) return null
        val created = get("created")?.jsonPrimitive?.contentOrNull == "true"
        val updated = get("updated")?.jsonPrimitive?.contentOrNull == "true"
        return if (created || updated) CreatedDocument(type, id) else null
    }

    private fun shouldUseCrmTools(message: String): Boolean {
        val text = message.lowercase()
        return listOf(
            "orcamento",
            "orçamento",
            "fatura",
            "factura",
            "cliente",
            "servico",
            "serviço",
            "material",
            "pago",
            "pagamento",
            "pdf",
            "pode gerar",
            "confirmo",
            "cadê",
            "cade",
            "conseguiu",
            "gerou",
            "soma",
            "total",
            "ranking",
            "resumo",
            "quanto",
            "quanto ",
            "relatorio",
            "relatório",
            "atualiza",
            "atualizar",
            "modifica",
            "modificar",
            "altera",
            "alterar",
            "muda",
            "mudar",
            "corrige",
            "corrigir",
            "remove",
            "remover",
            "adiciona",
            "adicionar",
        ).any { it in text }
    }

    private fun requiresExplicitConfirmation(toolName: String): Boolean = toolName in setOf(
        "create_client",
        "create_quote",
        "update_quote",
        "create_invoice",
        "mark_invoice_paid",
    )

    private fun hasExplicitConfirmation(message: String, contextMessages: List<ChatMessage>): Boolean {
        val text = message.lowercase()
        if (listOf(
            "pode gerar",
            "pode criar",
            "pode emitir",
            "confirmo",
            "confirmado",
            "aprovado",
            "sim, gerar",
            "sim gerar",
            "gerar agora",
            "emitir agora",
        ).any { it in text }) {
            return true
        }
        // Bot asked for specific data (name, phone) and user provided it — counts as confirmation
        if (isContinuingCrmFlow(contextMessages)) return true
        return isConfirmationReplyToCrmPrompt(message, contextMessages)
    }

    private fun isConfirmationReplyToCrmPrompt(message: String, contextMessages: List<ChatMessage>): Boolean {
        val text = message.trim().lowercase()
        if (text !in setOf("sim", "s", "ok", "pode", "isso", "certo")) return false
        // If the bot is still collecting missing data, "sim" is not a confirmation.
        if (isContinuingCrmFlow(contextMessages)) return false
        val recentAssistantText = contextMessages
            .asReversed()
            .filter { it.role == "assistant" }
            .take(3)
            .joinToString("\n") { it.content.orEmpty().lowercase() }
        return listOf(
            "confirma",
            "confirme",
            "prosseguir",
            "pode gerar",
            "gerar o orçamento",
            "gerar o orcamento",
            "criar o orçamento",
            "criar o orcamento",
            "criar o cliente",
        ).any { it in recentAssistantText }
    }

    private fun isContinuingCrmFlow(contextMessages: List<ChatMessage>): Boolean {
        val lastAssistant = contextMessages
            .asReversed()
            .firstOrNull { it.role == "assistant" }
            ?.content?.lowercase() ?: return false
        return listOf(
            // bot asking for missing data
            "nome completo",
            "informe um telefone",
            "informe o telefone",
            "telefone para o cadastro",
            "número de telefone",
            "numero de telefone",
            "preciso do telefone",
            "preciso do número",
            "telefone do cliente",
            "qual o telefone",
            "qual é o telefone",
            "forneça o telefone",
            "forneca o telefone",
            "confirme o nome",
            "informe o nome",
            "pode me informar",
            "informe um e-mail",
            // bot presenting a summary waiting for confirmation
            "confirma a criação",
            "confirma a criacao",
            "confirme os dados",
            "confirma os dados",
            "criar o cliente",
            "criação do cliente",
            "criacao do cliente",
            "posso prosseguir",
            "posso criar",
            "aguardo sua confirmação",
            "aguardo sua confirmacao",
            "se estiver tudo certo",
            // bot asking if user wants to create a not-found client
            "não encontrei",
            "nao encontrei",
            "deseja criar",
            "quer criar",
            "criar este cliente",
            "criar esse cliente",
            "você deseja criar",
            "voce deseja criar",
        ).any { it in lastAssistant }
    }

    private fun isPdfRequest(message: String): Boolean {
        val text = message.lowercase()
        return "pdf" in text && listOf("me da", "me dá", "manda", "envia", "enviar", "quero", "gerar").any { it in text }
    }

    private val clientNameRegex = Regex("cliente\\s+[\\\"“”']([^\\\"“”']+)[\\\"“”']", RegexOption.IGNORE_CASE)

    private data class CreatedDocument(val type: String, val id: String)
}
