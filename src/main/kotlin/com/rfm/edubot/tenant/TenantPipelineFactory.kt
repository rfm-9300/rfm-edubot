package com.rfm.edubot.tenant

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.bookings.bookingDeps
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.events.SubjectRef
import com.rfm.edubot.events.SubjectTypes
import com.rfm.edubot.messaging.MessagePipeline
import com.rfm.edubot.notifications.NotificationAudience
import com.rfm.edubot.notifications.NotificationKinds
import com.rfm.edubot.notifications.NotificationRepository
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.ratelimit.RateLimiter
import com.rfm.edubot.instagram.InstagramClient
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.web.WebChannelRegistry
import com.rfm.edubot.web.WebChatOutboundClient
import com.rfm.edubot.whatsapp.WhatsAppClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap

class TenantPipelineFactory(
    private val mongo: MongoModule,
    private val aiClient: AiClient,
    private val deduplicationService: DeduplicationService,
    private val whatsappHttpClient: HttpClient,
    private val runtimeConfig: RuntimeConfig,
    private val webChannelRegistry: WebChannelRegistry,
) {
    private val pipelines = ConcurrentHashMap<ObjectId, MessagePipeline>()

    fun getOrCreate(tenant: Tenant): MessagePipeline = pipelines.getOrPut(tenant.id) { build(tenant) }

    fun evict(tenantId: ObjectId) {
        pipelines.remove(tenantId)
    }

    fun responderFor(tenant: Tenant, platform: Platform): OutboundClient {
        val binding = tenant.binding(platform) ?: throw IllegalStateException("Tenant ${tenant.slug} has no $platform binding")
        return when (platform) {
            Platform.WHATSAPP -> whatsAppFor(tenant)!!
            Platform.INSTAGRAM -> InstagramClient(
                accessToken = binding.accessToken,
                instagramAccountId = binding.externalId,
                apiVersion = runtimeConfig.get().instagram.graphVersion,
                httpClient = whatsappHttpClient,
            )
            Platform.WEB -> WebChatOutboundClient(webChannelRegistry)
        }
    }

    /** The tenant's WhatsApp sender, or null when no WhatsApp number is connected. */
    fun whatsAppFor(tenant: Tenant): WhatsAppClient? {
        val binding = tenant.binding(Platform.WHATSAPP) ?: return null
        val cfg = runtimeConfig.get()
        return WhatsAppClient(
            accessToken = binding.accessToken.ifBlank { cfg.whatsapp.accessToken },
            phoneNumberId = binding.externalId,
            apiVersion = cfg.whatsapp.apiVersion,
            httpClient = whatsappHttpClient,
        )
    }

    private fun build(tenant: Tenant): MessagePipeline {
        val modules = DashboardModules.effectiveFor(tenant).toSet()
        val clients = ClientRepository(mongo, tenant.id)
        val quotes = QuoteRepository(mongo, tenant.id)
        val invoices = InvoiceRepository(mongo, tenant.id)
        val items = StandardItemRepository(mongo, tenant.id)
        val persona = runBlocking { PersonaRepository(mongo).findByTenant(tenant.id) }
        val bookingTools = if (DashboardModules.BOOKINGS in modules) bookingDeps(mongo, tenant, BookingSource.WHATSAPP).tools() else null
        val notifications = NotificationRepository(mongo)
        return MessagePipeline(
            users = UserRepository(mongo, tenant.id),
            conversations = ConversationRepository(mongo, tenant.id),
            messages = MessageRepository(mongo, tenant.id),
            rateLimiter = RateLimiter(tenant.rateLimitPerHour, tenant.rateLimitPerDay),
            aiClient = aiClient,
            deduplicationService = deduplicationService,
            crmTools = CrmTools(clients, quotes, invoices, items),
            bookingTools = bookingTools,
            clientRepository = clients,
            quoteRepository = quotes,
            invoiceRepository = invoices,
            pdfGenerator = PdfGenerator(),
            documentTemplate = tenant.documentTemplate.withCompanyFallback(tenant.name),
            openrouterModel = tenant.openrouterModel,
            persona = persona,
            enabledModules = modules,
            tenantUsage = TenantUsageRepository(mongo, tenant.id),
            monthlyTokenBudget = tenant.monthlyTokenBudget,
            timezoneId = tenant.timezone,
            onHandoff = { handoff ->
                notifications.notify(
                    tenantId = tenant.id,
                    kind = NotificationKinds.CONVERSATION_HANDOFF,
                    audience = NotificationAudience.ALL,
                    params = mapOf(
                        "name" to (handoff.customerName?.takeIf { it.isNotBlank() } ?: handoff.conversation.waId),
                        "reason" to handoff.reason,
                        "channel" to handoff.conversation.channel.name,
                    ),
                    link = DashboardModules.CONVERSATIONS,
                    subject = SubjectRef.of(SubjectTypes.CONVERSATION, handoff.conversation.id),
                )
            },
        )
    }
}
