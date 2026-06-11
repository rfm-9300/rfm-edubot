package com.rfm.edubot.tenant

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.channel.OutboundClient
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.messaging.DeduplicationService
import com.rfm.edubot.messaging.MessagePipeline
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.ratelimit.RateLimiter
import com.rfm.edubot.instagram.InstagramClient
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
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
    private val appConfig: AppConfig,
) {
    private val pipelines = ConcurrentHashMap<ObjectId, MessagePipeline>()

    fun getOrCreate(tenant: Tenant): MessagePipeline = pipelines.getOrPut(tenant.id) { build(tenant) }

    fun evict(tenantId: ObjectId) {
        pipelines.remove(tenantId)
    }

    fun responderFor(tenant: Tenant, platform: Platform): OutboundClient {
        val binding = tenant.binding(platform) ?: throw IllegalStateException("Tenant ${tenant.slug} has no $platform binding")
        return when (platform) {
            Platform.WHATSAPP -> WhatsAppClient(
                accessToken = binding.accessToken.ifBlank { appConfig.whatsapp.accessToken },
                phoneNumberId = binding.externalId,
                apiVersion = appConfig.whatsapp.apiVersion,
                httpClient = whatsappHttpClient,
            )
            Platform.INSTAGRAM -> InstagramClient(
                accessToken = binding.accessToken,
                instagramAccountId = binding.externalId,
                apiVersion = appConfig.instagram.graphVersion,
                httpClient = whatsappHttpClient,
            )
        }
    }

    private fun build(tenant: Tenant): MessagePipeline {
        val clients = ClientRepository(mongo, tenant.id)
        val quotes = QuoteRepository(mongo, tenant.id)
        val invoices = InvoiceRepository(mongo, tenant.id)
        val items = StandardItemRepository(mongo, tenant.id).also { runBlocking { it.seedDefaults() } }
        val compiledPersona = runBlocking { PersonaRepository(mongo).findByTenant(tenant.id)?.compiledInstructions }
        return MessagePipeline(
            users = UserRepository(mongo, tenant.id),
            conversations = ConversationRepository(mongo, tenant.id),
            messages = MessageRepository(mongo, tenant.id),
            rateLimiter = RateLimiter(tenant.rateLimitPerHour, tenant.rateLimitPerDay),
            aiClient = aiClient,
            deduplicationService = deduplicationService,
            crmTools = CrmTools(clients, quotes, invoices, items),
            clientRepository = clients,
            quoteRepository = quotes,
            invoiceRepository = invoices,
            pdfGenerator = PdfGenerator(),
            pdfStoragePath = "${appConfig.pdfStoragePath}/${tenant.slug}",
            openrouterModel = tenant.openrouterModel,
            compiledPersona = compiledPersona,
        )
    }
}
