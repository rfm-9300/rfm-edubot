package com.rfm.edubot.tenant

import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.bookings.AvailabilityRepository
import com.rfm.edubot.bookings.BookingRepository
import com.rfm.edubot.bookings.BookingScheduler
import com.rfm.edubot.bookings.BookingServiceRepository
import com.rfm.edubot.bookings.BookingTools
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
import com.rfm.edubot.messaging.MessagePipeline
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
            Platform.WHATSAPP -> {
                val cfg = runtimeConfig.get()
                WhatsAppClient(
                    accessToken = binding.accessToken.ifBlank { cfg.whatsapp.accessToken },
                    phoneNumberId = binding.externalId,
                    apiVersion = cfg.whatsapp.apiVersion,
                    httpClient = whatsappHttpClient,
                )
            }
            Platform.INSTAGRAM -> InstagramClient(
                accessToken = binding.accessToken,
                instagramAccountId = binding.externalId,
                apiVersion = runtimeConfig.get().instagram.graphVersion,
                httpClient = whatsappHttpClient,
            )
            Platform.WEB -> WebChatOutboundClient(webChannelRegistry)
        }
    }

    private fun build(tenant: Tenant): MessagePipeline {
        val clients = ClientRepository(mongo, tenant.id)
        val quotes = QuoteRepository(mongo, tenant.id)
        val invoices = InvoiceRepository(mongo, tenant.id)
        val items = StandardItemRepository(mongo, tenant.id).also { runBlocking { it.seedDefaults() } }
        val compiledPersona = runBlocking { PersonaRepository(mongo).findByTenant(tenant.id)?.compiledInstructions }
        val bookingTools = if (DashboardModules.BOOKINGS in DashboardModules.effectiveFor(tenant)) {
            val bookingServices = BookingServiceRepository(mongo, tenant.id)
            val availability = AvailabilityRepository(mongo, tenant.id)
            val bookings = BookingRepository(mongo, tenant.id)
            BookingTools(
                services = bookingServices,
                availability = availability,
                bookings = bookings,
                scheduler = BookingScheduler(bookingServices, availability, bookings, tenant.timezone),
                timezoneId = tenant.timezone,
                source = BookingSource.WHATSAPP,
            )
        } else {
            null
        }
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
            pdfStoragePath = "${runtimeConfig.get().pdfStoragePath}/${tenant.slug}",
            openrouterModel = tenant.openrouterModel,
            compiledPersona = compiledPersona,
        )
    }
}
