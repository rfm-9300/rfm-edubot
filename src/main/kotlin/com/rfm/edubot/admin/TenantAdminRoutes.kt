package com.rfm.edubot.admin

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.rfm.edubot.bookings.bookingDeps
import com.rfm.edubot.bookings.installBookingRoutes
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.lineItem
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRegistry
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.types.ObjectId

fun Route.tenantAdminRoutes(
    mongo: MongoModule,
    tenantRepository: TenantRepository,
    tenantRegistry: TenantRegistry,
    pipelineFactory: TenantPipelineFactory,
    runtimeConfig: RuntimeConfig,
) {
    val bindingService = ChannelBindingService(tenantRepository, tenantRegistry, pipelineFactory)
    authenticate("admin-jwt") {
        route("/admin/api") {
            get("/tenants") {
                call.respond(tenantRepository.findAll().map { it.dto() })
            }

            post("/tenants") {
                val request = call.receive<TenantCreateRequest>()
                if (request.name.isBlank() || request.slug.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and slug are required"))
                    return@post
                }
                val bindings = request.channels.toBindings().ifEmpty {
                    request.phoneNumberId?.trim()?.takeIf { it.isNotBlank() }
                        ?.let { listOf(ChannelBinding(Platform.WHATSAPP, it, runtimeConfig.get().whatsapp.accessToken)) }
                        .orEmpty()
                }
                val validationError = validateBindings(bindings)
                if (validationError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                    return@post
                }
                val now = SystemClock.now()
                val tenant = Tenant(
                    slug = request.slug.trim(),
                    name = request.name.trim(),
                    channels = bindings,
                    locale = com.rfm.edubot.tenant.model.TenantLocales.normalize(request.locale),
                    timezone = com.rfm.edubot.tenant.model.TenantTimeZones.normalize(request.timezone),
                    openrouterModel = request.openrouterModel?.trim()?.takeIf { it.isNotBlank() },
                    enabledModules = DashboardModules.sanitize(request.enabledModules),
                    rateLimitPerHour = request.rateLimitPerHour,
                    rateLimitPerDay = request.rateLimitPerDay,
                    createdAt = now,
                    updatedAt = now,
                )
                tenantRepository.create(tenant)
                StandardItemRepository(mongo, tenant.id).seedDefaults()
                tenantRegistry.put(tenant)
                call.respond(HttpStatusCode.Created, tenant.dto())
            }

            get("/tenants/{slug}") {
                val tenant = call.resolveTenant(tenantRepository) ?: return@get
                call.respond(tenant.dto())
            }

            put("/tenants/{slug}") {
                val slug = call.parameters["slug"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<TenantUpdateRequest>()
                val current = tenantRepository.findBySlug(slug) ?: return@put call.respond(HttpStatusCode.NotFound)
                val now = SystemClock.now()
                val updates = mutableListOf(
                    Updates.set("name", request.name.trim()),
                    Updates.set("openrouterModel", request.openrouterModel?.trim()?.takeIf { it.isNotBlank() }),
                    Updates.set("rateLimitPerHour", request.rateLimitPerHour),
                    Updates.set("rateLimitPerDay", request.rateLimitPerDay),
                    Updates.set("updatedAt", now.toDate()),
                )
                updates.add(Updates.set("enabledModules", DashboardModules.sanitize(request.enabledModules)))
                request.locale?.let { updates.add(Updates.set("locale", com.rfm.edubot.tenant.model.TenantLocales.normalize(it))) }
                request.timezone?.let { updates.add(Updates.set("timezone", com.rfm.edubot.tenant.model.TenantTimeZones.normalize(it))) }
                request.channels?.let {
                    val bindings = it.toBindings(current.channels)
                    val validationError = validateBindings(bindings)
                    if (validationError != null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                        return@put
                    }
                    updates.add(Updates.set("channels", bindings.map { binding -> binding.toDocument() }))
                    updates.add(phoneNumberIdUpdate(bindings))
                }
                val updated = tenantRepository.update(
                    slug,
                    Updates.combine(updates),
                ) ?: return@put call.respond(HttpStatusCode.NotFound)
                tenantRegistry.remove(current)
                tenantRegistry.put(updated)
                pipelineFactory.evict(updated.id)
                call.respond(updated.dto())
            }

            post("/tenants/{slug}/suspend") {
                val updated = setStatus(tenantRepository, tenantRegistry, pipelineFactory, call.parameters["slug"], TenantStatus.SUSPENDED)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(updated.dto())
            }

            post("/tenants/{slug}/activate") {
                val updated = setStatus(tenantRepository, tenantRegistry, pipelineFactory, call.parameters["slug"], TenantStatus.ACTIVE)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(updated.dto())
            }

            delete("/tenants/{slug}") {
                val updated = setStatus(tenantRepository, tenantRegistry, pipelineFactory, call.parameters["slug"], TenantStatus.DELETED)
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                tenantRegistry.remove(updated)
                call.respond(mapOf("deleted" to true))
            }

            post("/tenants/{slug}/reload") {
                val tenant = call.resolveTenant(tenantRepository) ?: return@post
                pipelineFactory.evict(tenant.id)
                tenantRegistry.put(tenant)
                call.respond(mapOf("reloaded" to true))
            }

            post("/tenants/{slug}/channels") {
                val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<ChannelBindingRequest>()
                val binding = request.toBinding()
                val validationError = validateBindings(listOf(binding))
                if (validationError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                    return@post
                }
                val updated = bindingService.upsert(slug, binding) ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(updated.dto())
            }

            // Provision (or rotate) the public website-widget key for a tenant. Returns the key plus a
            // ready-to-paste <script> snippet. WEB bindings carry no access token (the browser connects
            // directly), so they skip the generic accessToken validation.
            post("/tenants/{slug}/channels/web") {
                val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val request = runCatching { call.receive<WebProvisionRequest>() }.getOrDefault(WebProvisionRequest())
                val existing = tenantRepository.findBySlug(slug)?.binding(Platform.WEB)
                val publicKey = existing?.externalId ?: ("web_" + ObjectId().toHexString())
                val binding = ChannelBinding(
                    platform = Platform.WEB,
                    externalId = publicKey,
                    displayName = request.displayName,
                    source = "admin",
                    allowedOrigins = request.allowedOrigins,
                )
                val updated = bindingService.upsert(slug, binding) ?: return@post call.respond(HttpStatusCode.NotFound)
                val host = call.request.headers["X-Forwarded-Host"] ?: call.request.headers["Host"] ?: "localhost:8080"
                val scheme = call.request.headers["X-Forwarded-Proto"] ?: "https"
                val snippet = "<script src=\"$scheme://$host/widget/widget.js\" data-key=\"$publicKey\" defer></script>"
                call.respond(WebProvisionResponse(publicKey = publicKey, snippet = snippet, tenant = updated.dto()))
            }

            delete("/tenants/{slug}/channels/{platform}/{externalId}") {
                val slug = call.parameters["slug"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val platform = call.parameters["platform"]?.uppercase()?.let { runCatching { Platform.valueOf(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid platform"))
                val externalId = call.parameters["externalId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val updated = bindingService.remove(slug, platform, externalId) ?: return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(updated.dto())
            }

            get("/tenants/{slug}/stats") {
                val tenant = call.resolveTenant(tenantRepository) ?: return@get
                call.respond(tenantStats(mongo, tenant.id))
            }

            route("/tenants/{slug}") {
                get("/clients") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.CLIENTS) ?: return@get
                    call.respond(deps.clients.search(call.request.queryParameters["q"].orEmpty()).map { it.dto() })
                }
                get("/standard-items") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.CATALOG) ?: return@get
                    call.respond(deps.standardItems.search(call.request.queryParameters["q"], call.request.queryParameters["type"]))
                }
                post("/standard-items") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.CATALOG) ?: return@post
                    val request = call.receive<StandardItemRequest>()
                    call.respond(HttpStatusCode.Created, deps.standardItems.create(request.toStandardItem(request.id)))
                }
                post("/standard-items/{id}") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.CATALOG) ?: return@post
                    val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val request = call.receive<StandardItemRequest>()
                    val item = deps.standardItems.update(id, request.toStandardItem(id)) ?: return@post call.respond(HttpStatusCode.NotFound)
                    call.respond(item)
                }
                delete("/standard-items/{id}") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.CATALOG) ?: return@delete
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    if (!deps.standardItems.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
                    call.respond(mapOf("deleted" to true))
                }
                get("/clients/{id}") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.CLIENTS) ?: return@get
                    val client = deps.clients.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(client.dto())
                }
                post("/clients") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.CLIENTS) ?: return@post
                    val request = call.receive<CreateClientRequest>()
                    if (request.name.isBlank() || request.phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
                    call.respond(HttpStatusCode.Created, deps.clients.create(request.name, request.phone, request.address).dto())
                }
                get("/quotes") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.QUOTES) ?: return@get
                    val result = deps.quotes.list(
                        clientId = call.request.queryParameters["clientId"]?.let { ObjectId(it) },
                        status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { QuoteStatus.valueOf(it.uppercase()) },
                    )
                    call.respond(result.map { it.dto(deps.clients.findById(it.clientId)) })
                }
                post("/quotes") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.QUOTES) ?: return@post
                    val request = call.receive<CreateQuoteRequest>()
                    if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
                    val quote = deps.quotes.create(ObjectId(request.clientId), request.items.map { it.toLineItem() }, request.notes, request.validUntil?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) })
                    val client = deps.clients.findById(quote.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                    val path = savePdf(deps.pdfStoragePath, "quotes", "Orcamento ${quote.number}.pdf", deps.pdfGenerator.generateQuote(quote, client, deps.documentTemplate))
                    deps.quotes.setPdfPath(quote.id, path.toString())
                    call.respond(HttpStatusCode.Created, quote.copy(pdfPath = path.toString()).dto(client))
                }
                get("/quotes/{id}") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.QUOTES) ?: return@get
                    val quote = deps.quotes.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(quote.dto(deps.clients.findById(quote.clientId)))
                }
                get("/quotes/{id}/pdf") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.QUOTES) ?: return@get
                    val quote = deps.quotes.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondPdf(quote.pdfPath)
                }
                get("/invoices") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.INVOICES) ?: return@get
                    val result = deps.invoices.list(
                        clientId = call.request.queryParameters["clientId"]?.let { ObjectId(it) },
                        status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { InvoiceStatus.valueOf(it.uppercase()) },
                    )
                    call.respond(result.map { it.dto(deps.clients.findById(it.clientId)) })
                }
                post("/invoices") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.INVOICES) ?: return@post
                    val request = call.receive<CreateInvoiceRequest>()
                    if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
                    val invoice = deps.invoices.create(ObjectId(request.clientId), request.quoteId?.takeIf { it.isNotBlank() }?.let { ObjectId(it) }, request.items.map { it.toLineItem() }, LocalDate.parse(request.dueDate))
                    val client = deps.clients.findById(invoice.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                    val path = savePdf(deps.pdfStoragePath, "invoices", "Fatura ${invoice.number}.pdf", deps.pdfGenerator.generateInvoice(invoice, client, deps.documentTemplate))
                    deps.invoices.setPdfPath(invoice.id, path.toString())
                    call.respond(HttpStatusCode.Created, invoice.copy(pdfPath = path.toString()).dto(client))
                }
                get("/invoices/{id}") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.INVOICES) ?: return@get
                    val invoice = deps.invoices.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(invoice.dto(deps.clients.findById(invoice.clientId)))
                }
                get("/invoices/{id}/pdf") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.INVOICES) ?: return@get
                    val invoice = deps.invoices.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondPdf(invoice.pdfPath)
                }
                patch("/invoices/{id}/paid") {
                    val deps = call.crmDeps(mongo, tenantRepository, runtimeConfig.get(), DashboardModules.INVOICES) ?: return@patch
                    val invoice = deps.invoices.markPaid(ObjectId(call.parameters["id"])) ?: return@patch call.respond(HttpStatusCode.NotFound)
                    call.respond(invoice.dto(deps.clients.findById(invoice.clientId)))
                }
                installBookingRoutes {
                    val tenant = resolveTenant(tenantRepository) ?: return@installBookingRoutes null
                    if (DashboardModules.BOOKINGS !in DashboardModules.effectiveFor(tenant)) {
                        respond(HttpStatusCode.Forbidden)
                        return@installBookingRoutes null
                    }
                    bookingDeps(mongo, tenant, BookingSource.ADMIN)
                }
            }
        }
    }
}

private suspend fun setStatus(repo: TenantRepository, registry: TenantRegistry, factory: TenantPipelineFactory, slug: String?, status: TenantStatus): Tenant? {
    if (slug.isNullOrBlank()) return null
    val updated = repo.setStatus(slug, status, SystemClock.now()) ?: return null
    if (status == TenantStatus.ACTIVE || status == TenantStatus.SUSPENDED) registry.put(updated) else registry.remove(updated)
    factory.evict(updated.id)
    return updated
}

private suspend fun ApplicationCall.resolveTenant(repo: TenantRepository): Tenant? {
    val slug = parameters["slug"] ?: run {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    val tenant = repo.findBySlug(slug)
    if (tenant == null) respond(HttpStatusCode.NotFound)
    return tenant
}

private suspend fun ApplicationCall.crmDeps(mongo: MongoModule, repo: TenantRepository, appConfig: AppConfig, module: String): CrmDeps? {
    val tenant = resolveTenant(repo) ?: return null
    if (module !in DashboardModules.effectiveFor(tenant)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    return CrmDeps(
        clients = ClientRepository(mongo, tenant.id),
        quotes = QuoteRepository(mongo, tenant.id),
        invoices = InvoiceRepository(mongo, tenant.id),
        standardItems = StandardItemRepository(mongo, tenant.id),
        pdfGenerator = PdfGenerator(),
        pdfStoragePath = "${appConfig.pdfStoragePath}/${tenant.slug}",
        documentTemplate = tenant.documentTemplate.withCompanyFallback(tenant.name),
    )
}

private data class CrmDeps(
    val clients: ClientRepository,
    val quotes: QuoteRepository,
    val invoices: InvoiceRepository,
    val standardItems: StandardItemRepository,
    val pdfGenerator: PdfGenerator,
    val pdfStoragePath: String,
    val documentTemplate: DocumentTemplate,
)

private suspend fun tenantStats(mongo: MongoModule, tenantId: ObjectId): TenantStatsDto {
    val tenantFilter = Filters.eq("tenantId", tenantId)
    val conversations = mongo.database.getCollection<Document>("conversations")
    val lastConversation = conversations.find(tenantFilter).sort(Document("lastMessageAt", -1)).firstOrNull()
    return TenantStatsDto(
        users = mongo.database.getCollection<Document>("users").countDocuments(tenantFilter),
        conversations = conversations.countDocuments(tenantFilter),
        messages = mongo.database.getCollection<Document>("messages").countDocuments(tenantFilter),
        quotes = mongo.database.getCollection<Document>("crm.quotes").countDocuments(tenantFilter),
        invoices = mongo.database.getCollection<Document>("crm.invoices").countDocuments(tenantFilter),
        lastMessageAt = lastConversation?.getDate("lastMessageAt")?.toInstant()?.toString(),
    )
}

@Serializable
private data class TenantCreateRequest(
    val name: String,
    val slug: String,
    val phoneNumberId: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val openrouterModel: String? = null,
    val rateLimitPerHour: Int = 30,
    val rateLimitPerDay: Int = 200,
    val channels: List<ChannelBindingRequest> = emptyList(),
    val enabledModules: List<String>? = null,
)

@Serializable
private data class TenantUpdateRequest(
    val name: String,
    val locale: String? = null,
    val timezone: String? = null,
    val openrouterModel: String? = null,
    val rateLimitPerHour: Int = 30,
    val rateLimitPerDay: Int = 200,
    val channels: List<ChannelBindingRequest>? = null,
    val enabledModules: List<String>? = null,
)

@Serializable
private data class ChannelBindingRequest(
    val platform: String,
    val externalId: String,
    val accessToken: String = "",
)

@Serializable
private data class WebProvisionRequest(
    val displayName: String? = null,
    val allowedOrigins: List<String> = emptyList(),
)

@Serializable
private data class WebProvisionResponse(
    val publicKey: String,
    val snippet: String,
    val tenant: TenantDto,
)

@Serializable
private data class TenantDto(
    val id: String,
    val slug: String,
    val name: String,
    val phoneNumberId: String,
    val locale: String,
    val timezone: String,
    val openrouterModel: String? = null,
    val enabledModules: List<String>? = null,
    val effectiveModules: List<String>,
    val availableModules: List<String>,
    val rateLimitPerHour: Int,
    val rateLimitPerDay: Int,
    val status: String,
    val channels: List<ChannelBindingDto>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class ChannelBindingDto(
    val platform: String,
    val externalId: String,
    val hasAccessToken: Boolean,
    val displayName: String? = null,
    val wabaId: String? = null,
    val source: String? = null,
)

@Serializable
private data class TenantStatsDto(
    val users: Long,
    val conversations: Long,
    val messages: Long,
    val quotes: Long,
    val invoices: Long,
    val lastMessageAt: String?,
)

private fun Tenant.dto() = TenantDto(
    id = id.toHexString(),
    slug = slug,
    name = name,
    phoneNumberId = phoneNumberId,
    locale = locale,
    timezone = timezone,
    openrouterModel = openrouterModel,
    enabledModules = enabledModules,
    effectiveModules = DashboardModules.effectiveFor(this),
    availableModules = DashboardModules.availableFor(),
    rateLimitPerHour = rateLimitPerHour,
    rateLimitPerDay = rateLimitPerDay,
    status = status.name,
    channels = channels.map { ChannelBindingDto(it.platform.name, it.externalId, it.accessToken.isNotBlank(), it.displayName, it.wabaId, it.source) },
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun List<ChannelBindingRequest>?.toBindings(existing: List<ChannelBinding> = emptyList()): List<ChannelBinding> =
    this.orEmpty().map { request ->
        val platform = Platform.valueOf(request.platform.uppercase())
        val existingToken = existing.firstOrNull { it.platform == platform && it.externalId == request.externalId.trim() }?.accessToken.orEmpty()
        val existingBinding = existing.firstOrNull { it.platform == platform && it.externalId == request.externalId.trim() }
        ChannelBinding(
            platform = platform,
            externalId = request.externalId.trim(),
            accessToken = request.accessToken.ifBlank { existingToken },
            displayName = existingBinding?.displayName,
            wabaId = existingBinding?.wabaId,
            tokenObtainedAt = existingBinding?.tokenObtainedAt,
            source = existingBinding?.source,
        )
    }

private fun ChannelBindingRequest.toBinding(): ChannelBinding =
    ChannelBinding(Platform.valueOf(platform.uppercase()), externalId.trim(), accessToken.trim())

private fun validateBindings(bindings: List<ChannelBinding>): String? {
    if (bindings.isEmpty()) return "at least one channel binding is required"
    if (bindings.any { it.externalId.isBlank() }) return "channel externalId is required"
    if (bindings.distinctBy { it.platform to it.externalId }.size != bindings.size) return "duplicate channel bindings are not allowed"
    if (bindings.any { it.platform == Platform.INSTAGRAM && it.accessToken.isBlank() }) return "Instagram accessToken is required"
    return null
}

private fun ChannelBinding.toDocument(): Document = Document("platform", platform.name)
    .append("externalId", externalId)
    .append("accessToken", accessToken)
    .appendIfNotNull("displayName", displayName)
    .appendIfNotNull("wabaId", wabaId)
    .appendIfNotNull("tokenObtainedAt", tokenObtainedAt?.toDate())
    .appendIfNotNull("source", source)
    .append("allowedOrigins", allowedOrigins)

private fun phoneNumberIdUpdate(bindings: List<ChannelBinding>) =
    bindings.firstOrNull { it.platform == Platform.WHATSAPP }?.externalId?.takeIf { it.isNotBlank() }
        ?.let { Updates.set("phoneNumberId", it) }
        ?: Updates.unset("phoneNumberId")

private fun kotlinx.datetime.Instant.toDate(): java.util.Date = java.util.Date(toEpochMilliseconds())

private fun Document.appendIfNotNull(key: String, value: Any?): Document = apply {
    if (value != null) append(key, value)
}
