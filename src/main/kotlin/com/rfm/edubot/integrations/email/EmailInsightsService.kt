package com.rfm.edubot.integrations.email

import com.rfm.edubot.agents.actions.AiSteps
import com.rfm.edubot.agents.ai.UntrustedContent
import com.rfm.edubot.agents.registry.Schema
import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.ToolDefinition
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.ai.tools.CompositeToolPack
import com.rfm.edubot.ai.tools.ToolLoop
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantTimeZones
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads a received email with the company's model for the Email page: a summary, what it asks for and
 * the details the dashboard's forms need. The email's text reaches the model as untrusted content and
 * nothing is changed here: the result only fills suggestions a person acts on. It counts against the
 * company's monthly token budget, as `email` use, and is kept on the email.
 */
class EmailInsightsService(
    private val ai: AiClient?,
    private val mongo: MongoModule,
    private val clock: () -> Instant = SystemClock::now,
) {
    sealed interface Outcome {
        data class Read(val insights: EmailInsights) : Outcome

        /** [key]: `ai_unavailable`, `token_budget`, `no_text` (the text was dropped) or `no_result`. */
        data class Failed(val key: String) : Outcome
    }

    /** What the model is told about the company and the sender, besides the email. */
    data class Context(
        val companyName: String,
        val clientName: String?,
        /** Quotes and invoices the email names, as "ORC-012 (sent quote, 450.00 €)". */
        val documents: List<String>,
        /** The company's bookable services, which [EmailInsights.serviceName] must be one of. */
        val services: List<String>,
    )

    private val log = LoggerFactory.getLogger("EmailInsights")
    private val messages = EmailMessageRepository(mongo, clock)
    private val running = ConcurrentHashMap<ObjectId, CompletableDeferred<Outcome>>()

    /** Reads [email] (with the [earlier] messages of its thread for context) and keeps the result on it. Two people opening it at once share one reading. */
    suspend fun analyze(tenant: Tenant, email: EmailMessage, earlier: List<EmailMessage>, context: Context): Outcome {
        val mine = CompletableDeferred<Outcome>()
        val current = running.putIfAbsent(email.id, mine)
        if (current != null) return current.await()
        return try {
            read(tenant, email, earlier, context).also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            running.remove(email.id, mine)
        }
    }

    private suspend fun read(tenant: Tenant, email: EmailMessage, earlier: List<EmailMessage>, context: Context): Outcome {
        val model = ai ?: return Outcome.Failed(AiSteps.UNAVAILABLE)
        val text = email.bodyText?.takeIf { it.isNotBlank() }?.let(EmailFacts::ownText) ?: return Outcome.Failed(NO_TEXT)
        val usage = TenantUsageRepository(mongo, tenant.id, clock)
        if (usage.tokensUsedThisMonth() >= tenant.monthlyTokenBudget) return Outcome.Failed(AiSteps.TOKEN_BUDGET)

        val result = try {
            ToolLoop(model).run(
                messages = listOf(
                    ChatMessage(role = "system", content = prompt(tenant, context)),
                    ChatMessage(role = "system", content = facts(tenant, context)),
                    ChatMessage(role = "user", content = emailText(tenant, email, text, earlier)),
                ),
                tools = CompositeToolPack(emptyList()),
                definitions = emptyList(),
                maxIterations = 1,
                modelOverride = tenant.openrouterModel,
                finishTool = submitTool(context.services),
                fallbackInstruction = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Email reading failed: tenant={} email={} error={}", tenant.slug, email.id, e.message)
            return Outcome.Failed(AiSteps.UNAVAILABLE)
        }
        if (result.usage.total > 0) usage.recordUsage(result.usage.total.toLong(), UsageSources.EMAIL)
        val insights = result.submitted?.let { EmailInsights.read(it, context.services, clock()) } ?: return Outcome.Failed(AiSteps.NO_RESULT)
        messages.saveInsights(tenant.id, email.id, insights)
        return Outcome.Read(insights)
    }

    private fun prompt(tenant: Tenant, context: Context): String = buildString {
        val language = AiSteps.language(tenant.locale)
        appendLine("You read one email that a small business received and turn it into what its team can do in their CRM dashboard.")
        appendLine("Call $SUBMIT once with what you found. Leave out every field the email doesn't state: never guess names, numbers, amounts or dates, and never put prices on lines.")
        appendLine("- summary: one or two sentences for the team, in $language: who wrote, what they want, anything urgent.")
        appendLine(
            "- intent: quote_request (they want a price or a quote), booking_request (an appointment, a visit or a reservation), payment_sent (a client says they paid), " +
                "supplier_bill (a supplier sends an invoice or a bill to pay), quote_reply (an answer to a quote they received; set accepted), question, complaint or other.",
        )
        appendLine("- contact: the sender's details as their text or signature gives them; company only when they write on behalf of one.")
        appendLine("- request and items: what they ask for, in $language; items are lines with a description and, when stated, a quantity and a unit.")
        appendLine("- date and time: the day (YYYY-MM-DD) and the time (HH:MM) they ask for. Resolve \"tomorrow\" or \"next Monday\" from today's date.")
        appendLine("- amount (a number, in euros) and dueDate (YYYY-MM-DD): for a bill they sent or a payment they made.")
        appendLine("- documentNumber: a quote or invoice number they mention.")
        if (context.services.isNotEmpty()) appendLine("- serviceName: when they ask for one of these services, its name exactly as listed: ${context.services.joinToString("; ")}.")
        appendLine(
            "- reply: a short draft answer in the email's own language, for a person to review before sending: greet them and acknowledge what they wrote. " +
                "Promise nothing the email and the company's details don't support (no prices, dates or availability). No signature.",
        )
        append(UntrustedContent.RULE)
    }

    private fun facts(tenant: Tenant, context: Context): String = buildString {
        appendLine(SystemPrompts.currentDateTimeContext(tenant.timezone))
        appendLine("The company: ${context.companyName}.")
        appendLine(context.clientName?.let { "The sender is the company's client \"$it\"." } ?: "The sender isn't one of the company's clients.")
        if (context.documents.isNotEmpty()) append("Documents the email names, as the company has them: ${context.documents.joinToString("; ")}.")
    }

    private fun emailText(tenant: Tenant, email: EmailMessage, text: String, earlier: List<EmailMessage>): String = buildString {
        val zone = TimeZone.of(TenantTimeZones.normalize(tenant.timezone))
        fun header(m: EmailMessage) = buildString {
            appendLine("From: ${listOfNotNull(m.fromName, "<${m.from}>").joinToString(" ")}")
            appendLine("To: ${m.to.joinToString(", ")}")
            appendLine("Date: ${m.date.toLocalDateTime(zone).let { "${it.date} %02d:%02d".format(it.hour, it.minute) }}")
            appendLine("Subject: ${m.subject}")
            if (m.attachments.isNotEmpty()) appendLine("Attachments: ${m.attachments.joinToString(", ") { it.filename }}")
        }
        appendLine("The email to read:")
        appendLine(UntrustedContent.wrap("email", "${header(email)}\n${text.take(MAX_TEXT)}"))
        val context = earlier.filter { it.id != email.id && !it.bodyText.isNullOrBlank() }.takeLast(MAX_EARLIER)
        if (context.isNotEmpty()) {
            appendLine()
            appendLine("Earlier in the same thread, for context only:")
            context.forEach { m ->
                val who = if (m.direction == EmailDirection.OUTBOUND) "Sent by the company" else "Received"
                appendLine(UntrustedContent.wrap("earlier_email", "$who\n${header(m)}\n${EmailFacts.ownText(m.bodyText!!).take(MAX_EARLIER_TEXT)}"))
            }
        }
    }

    private fun submitTool(services: List<String>): ToolDefinition {
        val contact = Schema.obj(
            "name" to Schema.string(maxLength = 200),
            "company" to Schema.string(maxLength = 200),
            "phone" to Schema.string(maxLength = 40),
            "email" to Schema.string(maxLength = 254),
            "taxId" to Schema.string(maxLength = 32, description = "a tax number such as a Portuguese NIF"),
            "address" to Schema.string(maxLength = 300, description = "street and number"),
            "postalCode" to Schema.string(maxLength = 20),
            "city" to Schema.string(maxLength = 100),
        )
        val item = Schema.obj(
            "description" to Schema.string(maxLength = 200),
            "quantity" to Schema.number(),
            "unit" to Schema.string(maxLength = 20, description = "e.g. h, m2, un"),
            required = listOf("description"),
        )
        val properties = listOfNotNull(
            "summary" to Schema.string(maxLength = EmailInsights.MAX_SUMMARY),
            "intent" to Schema.string(enum = EmailIntents.all),
            "accepted" to Schema.boolean(description = "for a quote_reply: whether they accept the quote"),
            "contact" to contact,
            "request" to Schema.string(maxLength = EmailInsights.MAX_REQUEST),
            "items" to Schema.array(item, maxItems = EmailInsights.MAX_ITEMS),
            "date" to Schema.string(description = "YYYY-MM-DD"),
            "time" to Schema.string(description = "HH:MM, 24-hour"),
            "dueDate" to Schema.string(description = "YYYY-MM-DD"),
            "amount" to Schema.number(description = "euros"),
            "documentNumber" to Schema.string(maxLength = 40),
            if (services.isNotEmpty()) "serviceName" to Schema.string(enum = services) else null,
            "reply" to Schema.string(maxLength = EmailInsights.MAX_REPLY),
        )
        return ToolDefinition(
            name = SUBMIT,
            description = "Submit what the email says. Call it once.",
            parameters = Schema.forLlm(Schema.obj(*properties.toTypedArray(), required = listOf("summary", "intent"))),
        )
    }

    companion object {
        const val SUBMIT = "submit_insights"
        const val NO_TEXT = "no_text"
        private const val MAX_TEXT = 8_000
        private const val MAX_EARLIER = 2
        private const val MAX_EARLIER_TEXT = 1_500
    }
}
