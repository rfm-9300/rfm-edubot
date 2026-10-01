package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.admin.CreateClientRequest
import com.rfm.edubot.admin.CreateClientServiceRequest
import com.rfm.edubot.admin.CreateInvoiceRequest
import com.rfm.edubot.admin.CreatePaymentRequest
import com.rfm.edubot.admin.CreateQuoteRequest
import com.rfm.edubot.admin.CreateEmployeeRequest
import com.rfm.edubot.admin.CreateSupplierRequest
import com.rfm.edubot.admin.InvoiceClientServicesRequest
import com.rfm.edubot.admin.UpdateClientServiceRequest
import com.rfm.edubot.admin.createStandardItem
import com.rfm.edubot.admin.dto
import com.rfm.edubot.admin.respondGeneratedPdf
import com.rfm.edubot.admin.serviceItemsError
import com.rfm.edubot.admin.updateStandardItem
import com.rfm.edubot.bookings.bookingDeps
import com.rfm.edubot.bookings.installBookingRoutes
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.conversation.model.UserStatus
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.EmployeeRepository
import com.rfm.edubot.crm.ClientServiceBilling
import com.rfm.edubot.crm.ClientServiceDelete
import com.rfm.edubot.crm.ClientServiceRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.DirectoryDelete
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PaymentRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.SupplierRepository
import com.rfm.edubot.crm.eurToCents
import com.rfm.edubot.crm.model.ClientServiceStatus
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.PaymentStatus
import com.rfm.edubot.crm.model.QuoteStatus
import com.rfm.edubot.instagram.InstagramSocialDeps
import com.rfm.edubot.instagram.InstagramSocialService
import com.rfm.edubot.instagram.installInstagramSocialRoutes
import com.rfm.edubot.oauth.InstagramOAuthScopes
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.dashboard.model.DashboardUserStatus
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaCompiler
import com.rfm.edubot.persona.PersonaFileExtractor
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.persona.PersonaSource
import com.rfm.edubot.persona.PersonaStatus
import com.rfm.edubot.persona.SourceKind
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.ChannelBindingService
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.ChannelBinding
import com.rfm.edubot.tenant.model.DocumentLayoutBlock
import com.rfm.edubot.tenant.model.DocumentDesignStyle
import com.rfm.edubot.tenant.model.DocumentLayouts
import com.rfm.edubot.tenant.model.BuiltInDesignTemplates
import com.rfm.edubot.tenant.model.DocumentTemplate
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.SavedDocumentTemplate
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantLocales
import com.rfm.edubot.whatsapp.TemplateDraft
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.firstOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId
import java.util.Date
import kotlin.time.Duration.Companion.seconds

fun Route.dashboardStaticRoutes() {
    get("/app") { call.respondRedirect("/app/") }
    get("/app/") {
        val html = this::class.java.classLoader.getResource("app/index.html")?.readText()
        call.respondText(html ?: "Dashboard UI missing", ContentType.Text.Html)
    }
    get("/app/{asset}") {
        val asset = call.parameters["asset"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val contentType = when (asset.substringAfterLast('.', "")) {
            "js" -> ContentType.Application.JavaScript
            "css" -> ContentType.Text.CSS
            else -> ContentType.Application.OctetStream
        }
        val bytes = this::class.java.classLoader.getResource("app/$asset")?.readBytes()
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, contentType)
    }
}

fun Route.dashboardRoutes(
    mongo: MongoModule,
    tenantRepository: TenantRepository,
    dashboardUsers: DashboardUserRepository,
    pipelineFactory: TenantPipelineFactory,
    personaCompiler: PersonaCompiler,
    aiClient: AiClient,
    runtimeConfig: RuntimeConfig,
    channelBindingService: ChannelBindingService,
    instagramSocial: InstagramSocialService,
) {
    val inbox = InboxService(mongo, { pipelineFactory.whatsAppFor(it) }, { tenant, platform -> pipelineFactory.responderFor(tenant, platform) })
    authenticate("dashboard") {
        route("/app/api") {
            get("/me") {
                val ctx = call.dashboardContext() ?: return@get
                val companies = tenantRepository.findCompanies(ctx.tenant.primaryTenantId)
                call.respond(
                    MeDto(
                        tenant = ctx.tenant.dto(),
                        user = ctx.user?.dto(),
                        modules = DashboardModules.effectiveFor(ctx.tenant),
                        principalType = ctx.principalType,
                        companies = companies
                            .filter { DashboardAccessPolicy.allows(it, ctx.user, ctx.principalType) }
                            .map { CompanyMeDto(it.id.toHexString(), it.name, it.slug, primary = it.parentTenantId == null) },
                        companyLimit = companies.companyLimit(ctx.tenant),
                    ),
                )
            }
            get("/overview") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.OVERVIEW)) return@get call.respond(HttpStatusCode.Forbidden)
                val extended = call.request.queryParameters["extended"] == "1"
                call.respond(OverviewService(mongo).build(ctx.tenant, extended))
            }
            get("/contacts") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.CONTACTS)) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(UserRepository(mongo, ctx.tenant.id).list(call.request.queryParameters["q"]).map { it.dto() })
            }
            patch("/contacts/{id}/status") {
                val ctx = call.dashboardContext() ?: return@patch
                if (!ctx.requireModule(DashboardModules.CONTACTS)) return@patch call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<ContactStatusRequest>()
                val user = UserRepository(mongo, ctx.tenant.id).setStatus(ObjectId(call.parameters["id"]), UserStatus.valueOf(request.status))
                    ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(user.dto())
            }
            get("/conversations") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@get call.respond(HttpStatusCode.Forbidden)
                val query = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                val nameMatches = query?.let { q -> UserRepository(mongo, ctx.tenant.id).list(q).map { it.id } }.orEmpty()
                val conversations = ConversationRepository(mongo, ctx.tenant.id).list(query, userIds = nameMatches)
                call.respond(inboxDtos(mongo, ctx.tenant, inbox, conversations))
            }
            get("/conversations/{id}/updates") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@get call.respond(HttpStatusCode.Forbidden)
                val conversation = call.inboxConversation(mongo, ctx) ?: return@get
                val since = call.request.queryParameters["since"]?.let { runCatching { kotlinx.datetime.Instant.parse(it) }.getOrNull() }
                // Taken before the query and a little early: the client merges by id, so overlap is harmless and nothing slips between polls.
                val cursor = SystemClock.now() - INBOX_CURSOR_OVERLAP
                val messages = MessageRepository(mongo, ctx.tenant.id).let { repo ->
                    if (since == null) repo.threadByConversation(conversation.id) else repo.changedSince(conversation.id, since)
                }
                call.respond(ThreadUpdatesDto(cursor.toString(), inboxDtos(mongo, ctx.tenant, inbox, listOf(conversation)).single(), messages.map { it.dto() }))
            }
            post("/conversations/{id}/read") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@post call.respond(HttpStatusCode.Forbidden)
                val conversation = call.inboxConversation(mongo, ctx) ?: return@post
                val updated = ConversationRepository(mongo, ctx.tenant.id).markRead(conversation.id) ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(inboxDtos(mongo, ctx.tenant, inbox, listOf(updated)).single())
            }
            get("/conversations/{id}/messages") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@get call.respond(HttpStatusCode.Forbidden)
                val convo = ConversationRepository(mongo, ctx.tenant.id).findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(MessageRepository(mongo, ctx.tenant.id).threadByConversation(convo.id).map { it.dto() })
            }
            patch("/conversations/{id}/auto-reply") {
                val ctx = call.dashboardContext() ?: return@patch
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@patch call.respond(HttpStatusCode.Forbidden)
                val conversationId = runCatching { ObjectId(call.parameters["id"]) }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid conversation id"))
                val request = call.receive<AutoReplyRequest>()
                val conversation = ConversationRepository(mongo, ctx.tenant.id).setAutoReplyEnabled(conversationId, request.enabled, ctx.agent().name)
                    ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(inboxDtos(mongo, ctx.tenant, inbox, listOf(conversation)).single())
            }
            post("/conversations/{id}/messages") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@post call.respond(HttpStatusCode.Forbidden)
                val conversation = call.inboxConversation(mongo, ctx) ?: return@post
                val request = call.receive<OutboundMessageRequest>()
                val binding = ctx.tenant.binding(conversation.channel)
                if (request.assetExternalId != null && binding?.externalId != request.assetExternalId) {
                    return@post call.respond(HttpStatusCode.BadRequest, InboxErrorDto("asset_mismatch"))
                }
                val message = call.inboxAction { inbox.sendText(ctx.tenant, conversation, request.text, ctx.agent()) } ?: return@post
                call.respond(HttpStatusCode.Created, message.dto())
            }
            post("/conversations/{id}/messages/{messageId}/retry") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@post call.respond(HttpStatusCode.Forbidden)
                val conversation = call.inboxConversation(mongo, ctx) ?: return@post
                val messageId = runCatching { ObjectId(call.parameters["messageId"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, InboxErrorDto("not_found"))
                val message = call.inboxAction { inbox.retry(ctx.tenant, conversation, messageId) } ?: return@post
                call.respond(message.dto())
            }
            post("/conversations/{id}/template") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@post call.respond(HttpStatusCode.Forbidden)
                val conversation = call.inboxConversation(mongo, ctx) ?: return@post
                val request = call.receive<TemplateSendRequest>()
                val message = call.inboxAction {
                    inbox.sendTemplate(ctx.tenant, conversation, InboxService.TemplateRequest(request.name, request.language, request.params), ctx.agent())
                } ?: return@post
                call.respond(HttpStatusCode.Created, message.dto())
            }
            post("/conversations/start") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<StartConversationRequest>()
                val (conversation, message) = call.inboxAction {
                    inbox.startConversation(ctx.tenant, request.phone, InboxService.TemplateRequest(request.name, request.language, request.params), ctx.agent())
                } ?: return@post
                call.respond(HttpStatusCode.Created, StartedConversationDto(inboxDtos(mongo, ctx.tenant, inbox, listOf(conversation)).single(), message.dto()))
            }
            get("/whatsapp/templates") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@get call.respond(HttpStatusCode.Forbidden)
                val refresh = call.request.queryParameters["refresh"] == "1"
                val all = call.request.queryParameters["all"] == "1"
                val templates = call.inboxAction { if (all) inbox.allTemplates(ctx.tenant, refresh) else inbox.templates(ctx.tenant, refresh) } ?: return@get
                call.respond(templates.map { it.dto() })
            }
            post("/whatsapp/templates") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<TemplateDraftRequest>()
                val draft = TemplateDraft(
                    name = request.name.trim(),
                    language = request.language.trim(),
                    category = request.category.trim().uppercase(),
                    header = request.header?.trim()?.takeIf { it.isNotEmpty() },
                    body = request.body,
                    bodyExamples = request.examples.map { it.trim() },
                    footer = request.footer?.trim()?.takeIf { it.isNotEmpty() },
                    quickReplies = request.quickReplies.map { it.trim() }.filter { it.isNotEmpty() },
                )
                val created = call.inboxAction { inbox.createTemplate(ctx.tenant, draft) } ?: return@post
                call.respond(HttpStatusCode.Created, CreatedTemplateDto(created.id, draft.name, draft.language, created.status ?: "PENDING", created.category ?: draft.category))
            }
            delete("/whatsapp/templates/{name}") {
                val ctx = call.dashboardContext() ?: return@delete
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@delete call.respond(HttpStatusCode.Forbidden)
                val name = call.parameters["name"].orEmpty()
                call.inboxAction { inbox.deleteTemplate(ctx.tenant, name, call.request.queryParameters["id"]?.takeIf { it.isNotBlank() }) } ?: return@delete
                call.respond(HttpStatusCode.NoContent)
            }
            get("/conversations/{id}/media/{messageId}") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@get call.respond(HttpStatusCode.Forbidden)
                val conversation = call.inboxConversation(mongo, ctx) ?: return@get
                val messageId = runCatching { ObjectId(call.parameters["messageId"]) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, InboxErrorDto("not_found"))
                val (file, fileName) = call.inboxAction { inbox.media(ctx.tenant, conversation, messageId) } ?: return@get
                call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=3600")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                fileName?.let { name ->
                    val safe = name.replace(Regex("""[^\w.\- ]"""), "_").take(120)
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$safe\"")
                }
                val type = runCatching { ContentType.parse(file.mimeType) }.getOrDefault(ContentType.Application.OctetStream)
                call.respondBytes(file.bytes, type)
            }
            get("/persona") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@get call.respond(HttpStatusCode.Forbidden)
                val repo = PersonaRepository(mongo)
                call.respond(personaDto(repo.findByTenant(ctx.tenant.id), repo.listSources(ctx.tenant.id)))
            }
            put("/persona") {
                val ctx = call.dashboardContext() ?: return@put
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@put call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<PersonaUpdateRequest>()
                val repo = PersonaRepository(mongo)
                val persona = repo.upsertCompiled(ctx.tenant.id, request.compiledInstructions.trim())
                pipelineFactory.evict(ctx.tenant.id)
                call.respond(personaDto(persona, repo.listSources(ctx.tenant.id)))
            }
            post("/persona/sources") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@post call.respond(HttpStatusCode.Forbidden)
                val content = call.receive<PersonaSourceRequest>().content.trim()
                if (content.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "content is required"))
                val repo = PersonaRepository(mongo)
                repo.addSource(ctx.tenant.id, SourceKind.TEXT_NOTE, content, content.take(60))
                personaCompiler.enqueue(ctx.tenant.id, ctx.tenant.openrouterModel)
                call.respond(HttpStatusCode.Accepted, personaDto(repo.findByTenant(ctx.tenant.id), repo.listSources(ctx.tenant.id)))
            }
            post("/persona/sources/file") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@post call.respond(HttpStatusCode.Forbidden)
                var filename: String? = null
                var bytes: ByteArray? = null
                call.receiveMultipart().forEachPart { part ->
                    if (part is PartData.FileItem) {
                        filename = part.originalFileName
                        bytes = part.provider().readRemaining().readByteArray()
                    }
                    part.dispose()
                }
                val name = filename?.takeIf { it.isNotBlank() } ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file provided"))
                val data = bytes ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty file"))
                val text = try {
                    PersonaFileExtractor.extract(name, data)
                } catch (e: PersonaFileExtractor.UnsupportedFileException) {
                    return@post call.respond(HttpStatusCode.UnsupportedMediaType, mapOf("error" to (e.message ?: "unsupported file")))
                }
                if (text.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no extractable text in file"))
                val repo = PersonaRepository(mongo)
                repo.addSource(ctx.tenant.id, SourceKind.FILE, text, name)
                personaCompiler.enqueue(ctx.tenant.id, ctx.tenant.openrouterModel)
                call.respond(HttpStatusCode.Accepted, personaDto(repo.findByTenant(ctx.tenant.id), repo.listSources(ctx.tenant.id)))
            }
            delete("/persona/sources/{id}") {
                val ctx = call.dashboardContext() ?: return@delete
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@delete call.respond(HttpStatusCode.Forbidden)
                val repo = PersonaRepository(mongo)
                if (!repo.deleteSource(ctx.tenant.id, ObjectId(call.parameters["id"]))) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("deleted" to true))
            }
            post("/persona/rebuild") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@post call.respond(HttpStatusCode.Forbidden)
                personaCompiler.rebuild(ctx.tenant.id, ctx.tenant.openrouterModel)
                val repo = PersonaRepository(mongo)
                call.respond(personaDto(repo.findByTenant(ctx.tenant.id), repo.listSources(ctx.tenant.id)))
            }
            post("/persona/test") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<PersonaTestRequest>()
                val history = request.messages.filter { it.content.isNotBlank() }.takeLast(20)
                if (history.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "messages are required"))
                val reply = runPersonaTest(mongo, aiClient, ctx.tenant, history)
                call.respond(PersonaTestResponse(reply))
            }
            get("/web-widget") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(ctx.tenant.binding(Platform.WEB).toWebWidgetDto())
            }
            // Self-serve website-widget onboarding: create the WEB binding on first call (minting a
            // public key) and update its origin allow-list on later calls — the key is preserved so a
            // tenant's embedded snippet never breaks. ChannelBindingService re-indexes the registry and
            // evicts the pipeline so the new channel is live without a restart.
            post("/web-widget") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@post call.respond(HttpStatusCode.Forbidden)
                val request = runCatching { call.receive<WebWidgetRequest>() }.getOrDefault(WebWidgetRequest())
                val existing = ctx.tenant.binding(Platform.WEB)
                val publicKey = existing?.externalId ?: ("web_" + ObjectId().toHexString())
                val origins = request.allowedOrigins.map { it.trim() }.filter { it.isNotBlank() }
                val updated = channelBindingService.upsert(
                    ctx.tenant.slug,
                    ChannelBinding(
                        platform = Platform.WEB,
                        externalId = publicKey,
                        displayName = existing?.displayName ?: "Website",
                        source = existing?.source ?: "dashboard",
                        allowedOrigins = origins,
                    ),
                ) ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(updated.binding(Platform.WEB).toWebWidgetDto())
            }
            // Self-serve disconnect: only removes the binding for the tenant's own slug (never an
            // arbitrary one from the URL), mirroring the admin-only delete in TenantAdminRoutes but
            // scoped to the authenticated tenant. Instagram's Meta-side deauthorize webhook
            // (InstagramMetaCallbacks) converges on the same ChannelBindingService.remove call.
            delete("/channels/{platform}/{externalId}") {
                val ctx = call.dashboardContext() ?: return@delete
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@delete call.respond(HttpStatusCode.Forbidden)
                val platform = call.parameters["platform"]?.uppercase()?.let { runCatching { Platform.valueOf(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid platform"))
                val externalId = call.parameters["externalId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val updated = channelBindingService.remove(ctx.tenant.slug, platform, externalId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(updated.dto())
            }
            // Tenant-selectable UI language. Persisted on the tenant so it becomes the default for every
            // dashboard session; the browser keeps a per-session override (localStorage.uiLocale).
            post("/settings/locale") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<LocaleRequest>()
                if (request.locale !in TenantLocales.SUPPORTED) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported locale"))
                }
                val updated = tenantRepository.setLocale(ctx.tenant.slug, request.locale, SystemClock.now())
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("locale" to updated.locale))
            }
            get("/settings/overview") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(OverviewHomeLayout.dto(ctx.tenant))
            }
            put("/settings/overview") {
                val ctx = call.dashboardContext() ?: return@put
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@put call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<OverviewLayoutRequest>()
                val hidden = OverviewHomeLayout.sanitize(request.hidden)
                val updated = tenantRepository.setOverviewHiddenCards(ctx.tenant.slug, hidden, SystemClock.now())
                    ?: return@put call.respond(HttpStatusCode.NotFound)
                call.respond(OverviewHomeLayout.dto(updated))
            }
            get("/settings/document-template") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(ctx.tenant.documentTemplate.dto(ctx.tenant.name))
            }
            put("/settings/document-template") {
                val ctx = call.dashboardContext() ?: return@put
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@put call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<DocumentTemplateRequest>()
                val next = request.toTemplate(ctx.tenant.documentTemplate)
                val updated = tenantRepository.setDocumentTemplate(ctx.tenant.slug, next, SystemClock.now())
                    ?: return@put call.respond(HttpStatusCode.NotFound)
                pipelineFactory.evict(updated.id)
                call.respond(updated.documentTemplate.dto(updated.name))
            }
            post("/settings/document-template/logo") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@post call.respond(HttpStatusCode.Forbidden)
                var filename: String? = null
                var bytes: ByteArray? = null
                call.receiveMultipart().forEachPart { part ->
                    if (part is PartData.FileItem) {
                        filename = part.originalFileName
                        bytes = part.provider().readRemaining().readByteArray()
                    }
                    part.dispose()
                }
                val name = filename?.takeIf { it.isNotBlank() } ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file provided"))
                val data = bytes?.takeIf { it.isNotEmpty() } ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty file"))
                if (data.size > MAX_LOGO_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "logo too large"))
                val ext = logoExtension(name) ?: return@post call.respond(HttpStatusCode.UnsupportedMediaType, mapOf("error" to "logo must be png, jpg, or webp"))
                val brandingDir = Path.of(runtimeConfig.get().pdfStoragePath, ctx.tenant.slug, "branding")
                Files.createDirectories(brandingDir)
                val logoPath = brandingDir.resolve("logo.$ext")
                Files.write(logoPath, data)
                ctx.tenant.documentTemplate.logoPath?.takeIf { it != logoPath.toString() }?.let { old ->
                    runCatching { Files.deleteIfExists(Path.of(old)) }
                }
                val next = ctx.tenant.documentTemplate.copy(logoPath = logoPath.toString())
                val updated = tenantRepository.setDocumentTemplate(ctx.tenant.slug, next, SystemClock.now())
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                pipelineFactory.evict(updated.id)
                call.respond(updated.documentTemplate.dto(updated.name))
            }
            delete("/settings/document-template/logo") {
                val ctx = call.dashboardContext() ?: return@delete
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@delete call.respond(HttpStatusCode.Forbidden)
                ctx.tenant.documentTemplate.logoPath?.let { runCatching { Files.deleteIfExists(Path.of(it)) } }
                val next = ctx.tenant.documentTemplate.copy(logoPath = null)
                val updated = tenantRepository.setDocumentTemplate(ctx.tenant.slug, next, SystemClock.now())
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                pipelineFactory.evict(updated.id)
                call.respond(updated.documentTemplate.dto(updated.name))
            }
            get("/settings/document-template/logo") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@get call.respond(HttpStatusCode.Forbidden)
                val path = ctx.tenant.documentTemplate.logoPath?.let(Path::of)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                if (!Files.isRegularFile(path)) return@get call.respond(HttpStatusCode.NotFound)
                val contentType = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
                    "png" -> ContentType.Image.PNG
                    "webp" -> ContentType("image", "webp")
                    else -> ContentType.Image.JPEG
                }
                call.respondBytes(Files.readAllBytes(path), contentType)
            }
            get("/settings/document-template/presets") {
                val ctx = call.dashboardContext() ?: return@get
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(ctx.tenant.designPresets())
            }
            post("/settings/document-template/presets") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@post call.respond(HttpStatusCode.Forbidden)
                val name = call.receive<SaveDesignPresetRequest>().name.trim().take(60)
                if (name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name required"))
                if (ctx.tenant.savedDocumentTemplates.size >= MAX_SAVED_PRESETS) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "too many saved templates"))
                }
                val preset = SavedDocumentTemplate(
                    id = ObjectId().toString(),
                    name = name,
                    accentColor = ctx.tenant.documentTemplate.accentColor,
                    showDecor = ctx.tenant.documentTemplate.showDecor,
                    layout = ctx.tenant.documentTemplate.layout,
                    createdAt = SystemClock.now(),
                    style = DocumentDesignStyle.sanitize(ctx.tenant.documentTemplate.style),
                )
                val updated = tenantRepository.addSavedDocumentTemplate(ctx.tenant.slug, preset, SystemClock.now())
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(updated.designPresets())
            }
            delete("/settings/document-template/presets/{id}") {
                val ctx = call.dashboardContext() ?: return@delete
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@delete call.respond(HttpStatusCode.Forbidden)
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val updated = tenantRepository.removeSavedDocumentTemplate(ctx.tenant.slug, id, SystemClock.now())
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(updated.designPresets())
            }
            post("/settings/document-template/presets/{id}/apply") {
                val ctx = call.dashboardContext() ?: return@post
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@post call.respond(HttpStatusCode.Forbidden)
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val preset = BuiltInDesignTemplates.find(id) ?: ctx.tenant.savedDocumentTemplates.find { it.id == id }
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val next = ctx.tenant.documentTemplate.copy(
                    accentColor = preset.accentColor,
                    showDecor = preset.showDecor,
                    layout = preset.layout,
                    style = DocumentDesignStyle.sanitize(preset.style),
                )
                val updated = tenantRepository.setDocumentTemplate(ctx.tenant.slug, next, SystemClock.now())
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                pipelineFactory.evict(updated.id)
                call.respond(updated.documentTemplate.dto(updated.name))
            }
            dashboardAssistantRoutes(mongo, aiClient)
            crmRoutes(mongo, runtimeConfig)
            installBookingRoutes {
                val ctx = dashboardContext()?.takeIf { it.requireModule(DashboardModules.BOOKINGS) }
                    ?: run {
                        respond(HttpStatusCode.Forbidden)
                        return@installBookingRoutes null
                    }
                bookingDeps(mongo, ctx.tenant, BookingSource.DASHBOARD)
            }
            installInstagramSocialRoutes {
                val ctx = dashboardContext()?.takeIf { it.requireModule(DashboardModules.INSTAGRAM) }
                    ?: run {
                        respond(HttpStatusCode.Forbidden)
                        return@installInstagramSocialRoutes null
                    }
                InstagramSocialDeps(ctx.tenant, instagramSocial)
            }
        }
    }
}

fun Route.dashboardImpersonationRoute(
    tenantRepository: TenantRepository,
    dashboardUsers: DashboardUserRepository,
    runtimeConfig: RuntimeConfig,
) {
    // Users belong to the tenant's first company, whichever of its companies the operator opened.
    authenticate("admin-jwt") {
        get("/admin/api/tenants/{slug}/dashboard-users") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(dashboardUsers.listByTenant(tenant.primaryTenantId).map { it.dto() })
        }
        post("/admin/api/tenants/{slug}/dashboard-users") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val request = call.receive<DashboardUserCreateRequest>()
            if (request.email.isBlank() || request.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "email and password are required"))
                return@post
            }
            val now = SystemClock.now()
            val user = DashboardUser(
                tenantId = tenant.primaryTenantId,
                email = request.email.trim().lowercase(),
                passwordHash = BCrypt.withDefaults().hashToString(12, request.password.toCharArray()),
                role = DashboardUserRole.valueOf(request.role),
                createdAt = now,
            )
            call.respond(HttpStatusCode.Created, dashboardUsers.create(user).dto())
        }
        post("/admin/api/tenants/{slug}/dashboard-users/{id}/disable") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val user = dashboardUsers.setStatus(ObjectId(id), tenant.primaryTenantId, DashboardUserStatus.DISABLED)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(user.dto())
        }
        post("/admin/api/tenants/{slug}/dashboard-users/{id}/activate") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val user = dashboardUsers.setStatus(ObjectId(id), tenant.primaryTenantId, DashboardUserStatus.ACTIVE)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(user.dto())
        }
        post("/admin/api/tenants/{slug}/impersonate") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(DashboardLoginResponse(token = dashboardToken(runtimeConfig.get().admin, tenant, DashboardAccessPolicy.OPERATOR_IMPERSONATION, 1)))
        }
    }
}

private fun Route.crmRoutes(mongo: MongoModule, runtimeConfig: RuntimeConfig) {
    fun tenantDeps(ctx: DashboardContext): CrmDeps = CrmDeps(
        clients = ClientRepository(mongo, ctx.tenant.id),
        quotes = QuoteRepository(mongo, ctx.tenant.id),
        invoices = InvoiceRepository(mongo, ctx.tenant.id),
        suppliers = SupplierRepository(mongo, ctx.tenant.id),
        employees = EmployeeRepository(mongo, ctx.tenant.id),
        payments = PaymentRepository(mongo, ctx.tenant.id),
        standardItems = StandardItemRepository(mongo, ctx.tenant.id),
        clientServices = ClientServiceRepository(mongo, ctx.tenant.id),
        pdfGenerator = PdfGenerator(),
        documentTemplate = ctx.tenant.documentTemplate.withCompanyFallback(ctx.tenant.name),
    )

    route("/crm") {
        get("/clients") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val q = call.request.queryParameters["q"].orEmpty()
            val archived = call.request.queryParameters["archived"] == "1"
            // The Clients page, and the client pickers in the quote/invoice forms, list the whole directory.
            call.respond(deps.clients.search(q, limit = if (q.isBlank()) CLIENT_DIRECTORY_LIMIT else 50, archived = archived).map { it.dto() })
        }
        get("/clients/by-phone") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val phone = call.request.queryParameters["phone"].orEmpty()
            val client = deps.clients.findByPhone(phone) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "client_not_found"))
            call.respond(client.dto())
        }
        post("/clients") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateClientRequest>()
            if (request.name.isBlank() || request.phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
            (request.requiredError() ?: request.detailsError())?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
            val client = call.uniquePhone {
                deps.clients.create(
                    request.name, request.phone, request.address, request.email, request.taxId, request.notes,
                    request.postalCode, request.city, request.contactPerson,
                )
            } ?: return@post
            call.respond(HttpStatusCode.Created, client.value.dto())
        }
        patch("/clients/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<CreateClientRequest>()
            if (request.name.isBlank() || request.phone.isBlank()) return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
            val existing = deps.clients.findById(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            (request.requiredError(existing) ?: request.detailsError())?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
            val client = (call.uniquePhone {
                deps.clients.update(
                    id, request.name, request.phone, request.address, request.email, request.taxId, request.notes,
                    request.postalCode, request.city, request.contactPerson,
                )
            } ?: return@patch).value ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(client.dto())
        }
        get("/clients/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(deps.clients.findById(id)?.dto() ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        removableDirectory("clients", DashboardModules.CLIENTS, { tenantDeps(it) }, { clients.delete(it) }) { id, archived ->
            clients.setArchived(id, archived)?.let { ArchiveStateDto(it.archivedAt?.toString()) }
        }
        get("/standard-items") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CATALOG) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            call.respond(deps.standardItems.search(call.request.queryParameters["q"], call.request.queryParameters["type"]))
        }
        post("/standard-items") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CATALOG) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            call.createStandardItem(tenantDeps(ctx).standardItems)
        }
        post("/standard-items/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CATALOG) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.updateStandardItem(tenantDeps(ctx).standardItems, id)
        }
        delete("/standard-items/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CATALOG) } ?: return@delete call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (!deps.standardItems.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("deleted" to true))
        }
        get("/quotes") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.QUOTES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val rows = deps.quotes.list(call.request.queryParameters["clientId"]?.let { ObjectId(it) }, call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { QuoteStatus.valueOf(it.uppercase()) })
            call.respond(rows.map { it.dto(deps.clients.findById(it.clientId)) })
        }
        post("/quotes") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.QUOTES) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateQuoteRequest>()
            if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
            val quote = deps.quotes.create(ObjectId(request.clientId), request.items.map { it.toLineItem() }, request.notes, request.validUntil?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) })
            val client = deps.clients.findById(quote.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
            call.respond(HttpStatusCode.Created, quote.dto(client))
        }
        get("/quotes/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.QUOTES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val quote = deps.quotes.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(quote.dto(deps.clients.findById(quote.clientId)))
        }
        patch("/quotes/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.QUOTES) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<QuoteStatusRequest>()
            val status = runCatching { QuoteStatus.valueOf(request.status.uppercase()) }.getOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid status"))
            val quote = deps.quotes.update(ObjectId(call.parameters["id"]), null, null, null, status)
                ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(quote.dto(deps.clients.findById(quote.clientId)))
        }
        post("/quotes/{id}/invoice") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.QUOTES) && it.requireModule(DashboardModules.INVOICES) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val quote = deps.quotes.findById(ObjectId(call.parameters["id"])) ?: return@post call.respond(HttpStatusCode.NotFound)
            val request = call.receive<ConvertQuoteRequest>()
            val dueDate = runCatching { LocalDate.parse(request.dueDate) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "due date required"))
            val client = deps.clients.findById(quote.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
            if (deps.invoices.list(quote.clientId).any { it.quoteId == quote.id }) {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_invoiced"))
            }
            val invoice = deps.invoices.create(quote.clientId, quote.id, quote.items, dueDate)
            deps.quotes.update(quote.id, null, null, null, QuoteStatus.ACEITO)
            call.respond(HttpStatusCode.Created, invoice.dto(client))
        }
        get("/quotes/{id}/pdf") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.QUOTES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val quote = deps.quotes.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            val client = deps.clients.findById(quote.clientId) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondGeneratedPdf { deps.pdfGenerator.generateQuote(quote, client, deps.documentTemplate) }
        }
        get("/invoices") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val rows = deps.invoices.list(call.request.queryParameters["clientId"]?.let { ObjectId(it) }, call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { InvoiceStatus.valueOf(it.uppercase()) })
            call.respond(rows.map { it.dto(deps.clients.findById(it.clientId)) })
        }
        post("/invoices") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateInvoiceRequest>()
            if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
            val invoice = deps.invoices.create(ObjectId(request.clientId), request.quoteId?.takeIf { it.isNotBlank() }?.let { ObjectId(it) }, request.items.map { it.toLineItem() }, LocalDate.parse(request.dueDate))
            val client = deps.clients.findById(invoice.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
            call.respond(HttpStatusCode.Created, invoice.dto(client))
        }
        get("/invoices/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val invoice = deps.invoices.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            val quoteNumber = invoice.quoteId?.let { deps.quotes.findById(it)?.number }
            call.respond(invoice.dto(deps.clients.findById(invoice.clientId), quoteNumber))
        }
        get("/invoices/{id}/pdf") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val invoice = deps.invoices.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            val client = deps.clients.findById(invoice.clientId) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondGeneratedPdf { deps.pdfGenerator.generateInvoice(invoice, client, deps.documentTemplate) }
        }
        patch("/invoices/{id}/paid") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val invoice = deps.invoices.markPaid(ObjectId(call.parameters["id"])) ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(invoice.dto(deps.clients.findById(invoice.clientId)))
        }
        get("/services") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SERVICES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val clientId = call.request.queryParameters["clientId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
            val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { runCatching { ClientServiceStatus.valueOf(it.uppercase()) }.getOrNull() }
            call.respond(deps.clientServices.list(clientId, status).map { it.dto(deps.clients.findById(it.clientId)) })
        }
        get("/services/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SERVICES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val service = deps.clientServices.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(service.dto(deps.clients.findById(service.clientId)))
        }
        post("/services") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SERVICES) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateClientServiceRequest>()
            val clientId = runCatching { ObjectId(request.clientId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client required"))
            val client = deps.clients.findById(clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
            if (request.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name required"))
            serviceItemsError(request.items)?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
            val service = deps.clientServices.create(
                clientId = clientId,
                name = request.name,
                notes = request.notes,
                quantity = request.quantity,
                unit = request.unit,
                unitPriceCents = eurToCents(request.unitPriceEur),
                bookingServiceId = request.bookingServiceId?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() },
                catalogItemId = request.catalogItemId,
                performedAt = request.performedAt?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                items = request.items.map { it.toLineItem() },
            )
            call.respond(HttpStatusCode.Created, service.dto(client))
        }
        post("/services/invoice") {
            val ctx = call.dashboardContext()?.takeIf {
                it.requireModule(DashboardModules.SERVICES) && it.requireModule(DashboardModules.INVOICES)
            } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<InvoiceClientServicesRequest>()
            val clientId = runCatching { ObjectId(request.clientId) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client required"))
            val dueDate = runCatching { LocalDate.parse(request.dueDate) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "due date required"))
            val ids = request.serviceIds.mapNotNull { runCatching { ObjectId(it) }.getOrNull() }
            val services = deps.clientServices.findByIds(ids)
            when (val prepared = ClientServiceBilling.prepareInvoice(clientId, services)) {
                is ClientServiceBilling.Outcome.Rejected -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to prepared.reason))
                is ClientServiceBilling.Outcome.Ready -> {
                    val client = deps.clients.findById(clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
                    val invoice = deps.invoices.create(clientId, null, prepared.items, dueDate)
                    deps.clientServices.markInvoiced(services.map { it.id }, invoice.id)
                    call.respond(HttpStatusCode.Created, invoice.dto(client))
                }
            }
        }
        patch("/services/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SERVICES) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<UpdateClientServiceRequest>()
            serviceItemsError(request.items)?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
            val status = request.status?.takeIf { it.isNotBlank() }?.let { runCatching { ClientServiceStatus.valueOf(it.uppercase()) }.getOrNull() }
            val service = deps.clientServices.update(
                id = id,
                name = request.name,
                notes = request.notes,
                quantity = request.quantity,
                unit = request.unit,
                unitPriceCents = request.unitPriceEur?.let { eurToCents(it) },
                performedAt = request.performedAt?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                status = status,
                items = request.items?.takeIf { it.isNotEmpty() }?.map { it.toLineItem() },
            ) ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(service.dto(deps.clients.findById(service.clientId)))
        }
        delete("/services/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SERVICES) } ?: return@delete call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            when (deps.clientServices.delete(id)) {
                ClientServiceDelete.NotFound -> call.respond(HttpStatusCode.NotFound)
                ClientServiceDelete.Invoiced -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "invoiced services cannot be deleted"))
                ClientServiceDelete.Removed -> call.respond(mapOf("deleted" to true))
            }
        }
        get("/suppliers") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SUPPLIERS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val archived = call.request.queryParameters["archived"] == "1"
            call.respond(deps.suppliers.search(call.request.queryParameters["q"].orEmpty(), archived).map { it.dto() })
        }
        get("/suppliers/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SUPPLIERS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(deps.suppliers.findById(id)?.dto() ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        post("/suppliers") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SUPPLIERS) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateSupplierRequest>()
            if (request.name.isBlank() || request.phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
            request.detailsError()?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
            val supplier = call.uniquePhone { deps.suppliers.create(request.name, request.phone, request.address, request.type) } ?: return@post
            call.respond(HttpStatusCode.Created, supplier.value.dto())
        }
        patch("/suppliers/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.SUPPLIERS) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<CreateSupplierRequest>()
            if (request.name.isBlank() || request.phone.isBlank()) return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
            request.detailsError()?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
            val supplier = (call.uniquePhone { deps.suppliers.update(id, request.name, request.phone, request.address, request.type) } ?: return@patch)
                .value ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(supplier.dto())
        }
        removableDirectory("suppliers", DashboardModules.SUPPLIERS, { tenantDeps(it) }, { suppliers.delete(it) }) { id, archived ->
            suppliers.setArchived(id, archived)?.let { ArchiveStateDto(it.archivedAt?.toString()) }
        }
        get("/employees") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.EMPLOYEES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val archived = call.request.queryParameters["archived"] == "1"
            call.respond(deps.employees.search(call.request.queryParameters["q"].orEmpty(), archived).map { it.dto() })
        }
        get("/employees/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.EMPLOYEES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(deps.employees.findById(id)?.dto() ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        post("/employees") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.EMPLOYEES) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateEmployeeRequest>()
            if (request.name.isBlank() || request.phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
            request.detailsError()?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
            val employee = call.uniquePhone {
                deps.employees.create(request.name, request.phone, request.role, request.birthDate, request.address, request.taxId)
            } ?: return@post
            call.respond(HttpStatusCode.Created, employee.value.dto())
        }
        patch("/employees/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.EMPLOYEES) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<CreateEmployeeRequest>()
            if (request.name.isBlank() || request.phone.isBlank()) return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
            request.detailsError()?.let { return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to it)) }
            val employee = (call.uniquePhone {
                deps.employees.update(id, request.name, request.phone, request.role, request.birthDate, request.address, request.taxId)
            } ?: return@patch).value ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(employee.dto())
        }
        removableDirectory("employees", DashboardModules.EMPLOYEES, { tenantDeps(it) }, { employees.delete(it) }) { id, archived ->
            employees.setArchived(id, archived)?.let { ArchiveStateDto(it.archivedAt?.toString()) }
        }
        get("/payments") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.PAYMENTS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val supplierId = call.request.queryParameters["supplierId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
            val employeeId = call.request.queryParameters["employeeId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
            val status = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { runCatching { PaymentStatus.valueOf(it.uppercase()) }.getOrNull() }
            val clientId = call.request.queryParameters["clientId"]?.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId(it) }.getOrNull() }
            call.respond(deps.payments.list(supplierId, employeeId, status, clientId).map { deps.paymentDto(it) })
        }
        post("/payments") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.PAYMENTS) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreatePaymentRequest>()
            if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
            val supplierRaw = request.supplierId?.takeIf { it.isNotBlank() }
            val employeeRaw = request.employeeId?.takeIf { it.isNotBlank() }
            if ((supplierRaw == null) == (employeeRaw == null)) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "choose a supplier or an employee"))
            }
            val dueDate = runCatching { LocalDate.parse(request.dueDate) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "due date required"))
            val clientId = request.clientId?.takeIf { it.isNotBlank() }?.let { call.paymentClient(ctx, deps, it) ?: return@post }
            val items = request.items.map { it.toLineItem() }
            val payment = if (employeeRaw != null) {
                if (!ctx.requireModule(DashboardModules.EMPLOYEES)) return@post call.respond(HttpStatusCode.Forbidden)
                val employeeId = runCatching { ObjectId(employeeRaw) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "employee required"))
                if (deps.employees.findById(employeeId) == null) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "employee not found"))
                deps.payments.create(supplierId = null, employeeId = employeeId, items = items, dueDate = dueDate, notes = request.notes, clientId = clientId)
            } else {
                val supplierId = runCatching { ObjectId(supplierRaw) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "supplier required"))
                if (deps.suppliers.findById(supplierId) == null) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "supplier not found"))
                deps.payments.create(supplierId = supplierId, employeeId = null, items = items, dueDate = dueDate, notes = request.notes, clientId = clientId)
            }
            call.respond(HttpStatusCode.Created, deps.paymentDto(payment))
        }
        get("/payments/{id}") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.PAYMENTS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val payment = deps.payments.findById(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(deps.paymentDto(payment))
        }
        patch("/payments/{id}/paid") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.PAYMENTS) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val payment = deps.payments.markPaid(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(deps.paymentDto(payment))
        }
        patch("/payments/{id}/client") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.PAYMENTS) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<PaymentClientRequest>()
            val clientId = request.clientId?.takeIf { it.isNotBlank() }?.let { call.paymentClient(ctx, deps, it) ?: return@patch }
            val payment = deps.payments.setClient(id, clientId) ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(deps.paymentDto(payment))
        }
    }
}

/**
 * The tenant's client [raw] names, for linking a payment to it. Answers 403 without the clients
 * module and 400 for an unknown client, returning null after responding.
 */
private suspend fun ApplicationCall.paymentClient(ctx: DashboardContext, deps: CrmDeps, raw: String): ObjectId? {
    if (!ctx.requireModule(DashboardModules.CLIENTS)) {
        respond(HttpStatusCode.Forbidden)
        return null
    }
    val id = runCatching { ObjectId(raw) }.getOrNull()?.takeIf { deps.clients.findById(it) != null }
    if (id == null) respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
    return id
}

/** A blank or missing `clientId` unlinks the payment from its client. */
@Serializable
private data class PaymentClientRequest(val clientId: String? = null)

/**
 * Removing a client, supplier or employee. DELETE only works when no document refers to the record
 * (409 `in_use` otherwise, so the dashboard can offer to archive it); archive hides it from lists and
 * pickers, restore brings it back.
 */
private fun Route.removableDirectory(
    path: String,
    module: String,
    deps: (DashboardContext) -> CrmDeps,
    remove: suspend CrmDeps.(ObjectId) -> DirectoryDelete,
    archive: suspend CrmDeps.(ObjectId, Boolean) -> ArchiveStateDto?,
) {
    delete("/$path/{id}") {
        val ctx = call.dashboardContext()?.takeIf { it.requireModule(module) } ?: return@delete call.respond(HttpStatusCode.Forbidden)
        val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
        when (deps(ctx).remove(id)) {
            DirectoryDelete.NOT_FOUND -> call.respond(HttpStatusCode.NotFound)
            DirectoryDelete.IN_USE -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "in_use"))
            DirectoryDelete.DELETED -> call.respond(mapOf("deleted" to true))
        }
    }
    for ((action, archived) in listOf("archive" to true, "restore" to false)) {
        post("/$path/{id}/$action") {
            val ctx = call.dashboardContext()?.takeIf { it.requireModule(module) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val id = runCatching { ObjectId(call.parameters["id"]) }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.respond(deps(ctx).archive(id, archived) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
    }
}

private class Written<T>(val value: T)

/**
 * Runs a client, supplier or employee write. Phone is unique per tenant in each of those collections,
 * so a duplicate-key clash answers 409 `phone_taken` and returns null instead of surfacing as a 500.
 */
private suspend fun <T> ApplicationCall.uniquePhone(write: suspend () -> T): Written<T>? =
    try {
        Written(write())
    } catch (e: com.mongodb.MongoServerException) {
        // Inserts fail with MongoWriteException, findOneAndUpdate with MongoCommandException; both carry the code.
        if (com.mongodb.ErrorCategory.fromErrorCode(e.code) != com.mongodb.ErrorCategory.DUPLICATE_KEY) throw e
        respond(HttpStatusCode.Conflict, mapOf("error" to "phone_taken"))
        null
    }

private suspend fun CrmDeps.paymentDto(payment: com.rfm.edubot.crm.model.Payment) = payment.dto(
    supplier = payment.supplierId?.let { suppliers.findById(it) },
    employee = payment.employeeId?.let { employees.findById(it) },
    client = payment.clientId?.let { clients.findById(it) },
)

private data class CrmDeps(
    val clients: ClientRepository,
    val quotes: QuoteRepository,
    val invoices: InvoiceRepository,
    val suppliers: SupplierRepository,
    val employees: EmployeeRepository,
    val payments: PaymentRepository,
    val standardItems: StandardItemRepository,
    val clientServices: ClientServiceRepository,
    val pdfGenerator: PdfGenerator,
    val documentTemplate: DocumentTemplate,
)

private const val MAX_LOGO_BYTES = 2 * 1024 * 1024
private const val MAX_SAVED_PRESETS = 20
private const val CLIENT_DIRECTORY_LIMIT = 2000

@Serializable
private data class DocumentTemplateRequest(
    val companyName: String = "",
    val tagline: String = "",
    val taxId: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val quoteTitle: String = "",
    val invoiceTitle: String = "",
    val quotePaymentTerms: String = "",
    val invoicePaymentTerms: String = "",
    val termsText: String = "",
    val footerText: String = "",
    val accentColor: String? = null,
    val showDecor: Boolean? = null,
    val layout: List<DocumentLayoutBlockDto>? = null,
    val style: String? = null,
)

@Serializable
private data class DocumentTemplateDto(
    val companyName: String,
    val tagline: String,
    val taxId: String,
    val email: String,
    val phone: String,
    val address: String,
    val quoteTitle: String,
    val invoiceTitle: String,
    val quotePaymentTerms: String,
    val invoicePaymentTerms: String,
    val termsText: String,
    val footerText: String,
    val accentColor: String,
    val showDecor: Boolean,
    val layout: List<DocumentLayoutBlockDto>,
    val style: String,
    val hasLogo: Boolean,
    val defaults: DocumentTemplateDefaultsDto,
)

@Serializable
private data class DocumentLayoutBlockDto(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val visible: Boolean = true,
)

// No default values here: the JSON config drops properties equal to their default, which used to send
// the studio an empty object, so its preview showed none of what the PDFs print.
@Serializable
private data class DocumentTemplateDefaultsDto(
    val quoteTitle: String,
    val invoiceTitle: String,
    val quotePaymentTerms: String,
    val invoicePaymentTerms: String,
    val termsText: String,
    val footerText: String,
    val accentColor: String,
    val layout: List<DocumentLayoutBlockDto>,
    val style: String,
)

private fun documentTemplateDefaults() = DocumentTemplateDefaultsDto(
    quoteTitle = "ORÇAMENTO",
    invoiceTitle = "FATURA",
    quotePaymentTerms = "",
    invoicePaymentTerms = PdfGenerator.DEFAULT_INVOICE_PAYMENT_TERMS,
    termsText = PdfGenerator.DEFAULT_QUOTE_TERMS,
    footerText = PdfGenerator.DEFAULT_FOOTER,
    accentColor = DocumentLayouts.DEFAULT_ACCENT,
    layout = DocumentLayouts.DEFAULT.map { it.dto() },
    style = DocumentDesignStyle.CLASSIC.id,
)

private fun DocumentTemplateRequest.toTemplate(existing: DocumentTemplate): DocumentTemplate = DocumentTemplate(
    companyName = companyName.trim(),
    tagline = tagline.trim(),
    taxId = taxId.trim(),
    email = email.trim(),
    phone = phone.trim(),
    address = address.trim(),
    quoteTitle = quoteTitle.trim(),
    invoiceTitle = invoiceTitle.trim(),
    quotePaymentTerms = quotePaymentTerms.trim(),
    invoicePaymentTerms = invoicePaymentTerms.trim(),
    termsText = termsText.trim(),
    footerText = footerText.trim(),
    logoPath = existing.logoPath,
    accentColor = accentColor?.let(DocumentLayouts::sanitizeAccent) ?: existing.accentColor,
    showDecor = showDecor ?: existing.showDecor,
    layout = layout?.let { DocumentLayouts.sanitize(it.map { block -> block.toModel() }) } ?: existing.layout,
    style = style?.let(DocumentDesignStyle::sanitize) ?: existing.style,
)

private fun DocumentTemplate.dto(tenantName: String) = DocumentTemplateDto(
    companyName = companyName.ifBlank { tenantName },
    tagline = tagline,
    taxId = taxId,
    email = email,
    phone = phone,
    address = address,
    quoteTitle = quoteTitle,
    invoiceTitle = invoiceTitle,
    quotePaymentTerms = quotePaymentTerms,
    invoicePaymentTerms = invoicePaymentTerms,
    termsText = termsText,
    footerText = footerText,
    accentColor = accentColor,
    showDecor = showDecor,
    layout = layout.map { it.dto() },
    style = DocumentDesignStyle.sanitize(style),
    hasLogo = !logoPath.isNullOrBlank() && Files.isRegularFile(Path.of(logoPath)),
    defaults = documentTemplateDefaults(),
)

private fun DocumentLayoutBlock.dto() = DocumentLayoutBlockDto(id, x, y, w, h, visible)

private fun DocumentLayoutBlockDto.toModel() = DocumentLayoutBlock(id, x, y, w, h, visible)

@Serializable
private data class SaveDesignPresetRequest(val name: String)

@Serializable
private data class DesignPresetDto(
    val id: String,
    /** For a saved preset, the tenant's own label. For a built-in one, a stable key the UI maps via i18n. */
    val name: String,
    val builtIn: Boolean,
    val accentColor: String,
    val showDecor: Boolean,
    val layout: List<DocumentLayoutBlockDto>,
    val style: String,
)

private fun SavedDocumentTemplate.dto(builtIn: Boolean) = DesignPresetDto(
    id = id,
    name = name,
    builtIn = builtIn,
    accentColor = accentColor,
    showDecor = showDecor,
    layout = layout.map { it.dto() },
    style = DocumentDesignStyle.sanitize(style),
)

private fun Tenant.designPresets(): List<DesignPresetDto> =
    BuiltInDesignTemplates.ALL.map { it.dto(builtIn = true) } + savedDocumentTemplates.map { it.dto(builtIn = false) }

private fun logoExtension(filename: String): String? = when (filename.substringAfterLast('.', "").lowercase()) {
    "png" -> "png"
    "jpg", "jpeg" -> "jpg"
    "webp" -> "webp"
    else -> null
}

internal fun dashboardToken(config: AppConfig.AdminConfig, user: DashboardUser, typ: String, expiryHours: Int): String = JWT.create()
    .withIssuer(config.jwtIssuer)
    .withSubject(user.id.toHexString())
    .withClaim("tenantId", user.tenantId.toHexString())
    .withClaim("role", user.role.name)
    .withClaim("typ", typ)
    .withExpiresAt(Date(System.currentTimeMillis() + expiryHours * 60L * 60L * 1000L))
    .sign(Algorithm.HMAC256(config.jwtSecret))

private fun dashboardToken(config: AppConfig.AdminConfig, tenant: Tenant, typ: String, expiryHours: Int): String = JWT.create()
    .withIssuer(config.jwtIssuer)
    .withSubject("operator")
    .withClaim("tenantId", tenant.id.toHexString())
    .withClaim("role", "PLATFORM_ADMIN")
    .withClaim("typ", typ)
    .withExpiresAt(Date(System.currentTimeMillis() + expiryHours * 60L * 60L * 1000L))
    .sign(Algorithm.HMAC256(config.jwtSecret))

private val personaTestJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Ephemeral persona playground: runs the tenant's saved persona against an in-memory chat history
 * with read-only CRM tools only. Nothing is persisted, dedup/rate-limit are bypassed, and write
 * tools (create/update/mark-paid) are never offered or executed, so test chats cannot mutate data.
 */
private suspend fun runPersonaTest(
    mongo: MongoModule,
    aiClient: AiClient,
    tenant: Tenant,
    history: List<PersonaTestMessage>,
): String {
    val modules = DashboardModules.effectiveFor(tenant).toSet()
    val persona = PersonaRepository(mongo).findByTenant(tenant.id)?.compiledInstructions
    val context = mutableListOf<ChatMessage>()
    val personaBlock = persona?.takeIf { it.isNotBlank() }
    context.add(
        ChatMessage(
            role = "system",
            content = if (personaBlock != null) "<persona>\n$personaBlock\n</persona>" else SystemPrompts.DEFAULT_IDENTITY,
        )
    )
    SystemPrompts.crmPromptFor(modules)?.let { crmPrompt -> context.add(ChatMessage(role = "system", content = crmPrompt)) }
    for (msg in history) {
        val role = if (msg.role == "assistant") "assistant" else "user"
        context.add(ChatMessage(role = role, content = msg.content))
    }

    val crmTools = CrmTools(
        ClientRepository(mongo, tenant.id),
        QuoteRepository(mongo, tenant.id),
        InvoiceRepository(mongo, tenant.id),
        StandardItemRepository(mongo, tenant.id),
    )
    val toolDefs = crmTools.readOnlyDefinitionsFor(modules)
    val allowedTools = toolDefs.map { it.name }.toSet()

    var reply = "Desculpe, não consegui processar isso."
    var iterations = 0
    var completed = false
    while (!completed && iterations < 4) {
        iterations += 1
        when (val response = aiClient.complete(context, toolDefs, modelOverride = tenant.openrouterModel)) {
            is AiResponse.Text -> {
                reply = response.content
                completed = true
            }
            is AiResponse.ToolUse -> {
                context.add(response.message)
                for (call in response.calls) {
                    val result = if (call.name in allowedTools) {
                        try {
                            crmTools.execute(call)
                        } catch (e: Exception) {
                            buildJsonObject {
                                put("error", "tool_failed")
                                put("message", e.message ?: "tool failure")
                            }
                        }
                    } else {
                        buildJsonObject {
                            put("error", "tool_not_available_in_test")
                            put("message", "This action is disabled in the persona test chat.")
                        }
                    }
                    context.add(ChatMessage(role = "tool", content = personaTestJson.encodeToString(result), toolCallId = call.id))
                }
            }
        }
    }
    if (!completed) {
        val final = aiClient.complete(
            context + ChatMessage(role = "system", content = "Responda agora ao utilizador sem chamar ferramentas."),
            emptyList(),
            modelOverride = tenant.openrouterModel,
        )
        if (final is AiResponse.Text) reply = final.content
    }
    return reply
}

@Serializable private data class DashboardLoginResponse(val token: String)
@Serializable private data class ArchiveStateDto(val archivedAt: String?)
@Serializable private data class DashboardUserCreateRequest(val email: String, val password: String, val role: String = "TENANT_ADMIN")
@Serializable private data class MeDto(
    val tenant: TenantMeDto,
    val user: DashboardUserDto?,
    val modules: List<String>,
    val principalType: String,
    /** The tenant's companies this session can switch to, the current one included. */
    val companies: List<CompanyMeDto>,
    val companyLimit: Int,
)
@Serializable private data class CompanyMeDto(val id: String, val name: String, val slug: String, val primary: Boolean)
@Serializable private data class TenantMeDto(val id: String, val slug: String, val name: String, val locale: String, val timezone: String, val channels: List<ChannelMeDto> = emptyList())
@Serializable private data class ChannelMeDto(
    val platform: String,
    val externalId: String,
    val displayName: String? = null,
    val commentsEnabled: Boolean = false,
)
@Serializable private data class DashboardUserDto(val id: String, val email: String, val role: String, val status: String)
@Serializable private data class ContactStatusRequest(val status: String)
@Serializable private data class PersonaUpdateRequest(val compiledInstructions: String)
@Serializable private data class PersonaSourceRequest(val content: String)
@Serializable private data class PersonaTestMessage(val role: String, val content: String)
@Serializable private data class PersonaTestRequest(val messages: List<PersonaTestMessage>)
@Serializable private data class PersonaTestResponse(val reply: String)
@Serializable private data class PersonaSourceDto(val id: String, val kind: String, val label: String, val compiled: Boolean, val createdAt: String)
@Serializable private data class PersonaDto(val compiledInstructions: String, val version: Int, val tokenEstimate: Int, val status: String, val updatedAt: String?, val sources: List<PersonaSourceDto>)
@Serializable private data class ContactDto(val id: String, val waId: String, val channel: String, val displayName: String?, val status: String, val lastSeenAt: String)
@Serializable private data class QuoteStatusRequest(val status: String)
@Serializable private data class ConvertQuoteRequest(val dueDate: String)
@Serializable private data class ConversationDto(
    val id: String,
    val waId: String,
    val channel: String,
    val displayName: String?,
    val state: String,
    val lastMessageAt: String,
    val messageCount: Int,
    val lastPreview: String? = null,
    val lastRole: String? = null,
    val waiting: Boolean = false,
    val autoReplyEnabled: Boolean = true,
    // No defaults below: the JSON config drops default values, and the inbox reads these every poll.
    val unreadCount: Int,
    val lastAuthor: String?,
    val lastKind: String?,
    val lastStatus: String?,
    val lastTracked: Boolean,
    val lastInboundAt: String?,
    /** WhatsApp only: free-form replies are allowed until then; templates after. */
    val windowExpiresAt: String?,
    val autoReplyPausedAt: String?,
    val autoReplyPausedBy: String?,
)
@Serializable private data class AutoReplyRequest(val enabled: Boolean)
@Serializable private data class OutboundMessageRequest(val text: String, val assetExternalId: String? = null)
@Serializable private data class ThreadMessageDto(
    val id: String,
    val role: String,
    val text: String,
    val status: String,
    val createdAt: String,
    /** "customer", "ai" or "agent". */
    val author: String,
    /** "text", "template", "image", "audio", "document" or "video". */
    val kind: String,
    /** Has a WhatsApp id, so delivery ticks apply. */
    val tracked: Boolean,
    val agentName: String?,
    val agentUserId: String?,
    val templateName: String?,
    val fileName: String?,
    val statusAt: String?,
    val errorCode: Int?,
    val errorKey: String?,
    val errorText: String?,
)
@Serializable private data class ThreadUpdatesDto(val cursor: String, val conversation: ConversationDto, val messages: List<ThreadMessageDto>)
@Serializable private data class TemplateSendRequest(val name: String, val language: String, val params: Map<String, String> = emptyMap())
@Serializable private data class StartConversationRequest(val phone: String, val name: String, val language: String, val params: Map<String, String> = emptyMap())
@Serializable private data class StartedConversationDto(val conversation: ConversationDto, val message: ThreadMessageDto)
@Serializable private data class InboxErrorDto(val error: String, val detail: String? = null)
@Serializable private data class WhatsAppTemplateDto(
    val id: String?,
    val name: String,
    val language: String,
    val category: String?,
    val status: String,
    val rejectedReason: String?,
    val header: String?,
    val body: String,
    val footer: String?,
    val buttons: List<String>,
    val params: List<String>,
    val sendable: Boolean,
)
@Serializable private data class TemplateDraftRequest(
    val name: String,
    val language: String,
    val category: String,
    val header: String? = null,
    val body: String,
    val examples: List<String> = emptyList(),
    val footer: String? = null,
    val quickReplies: List<String> = emptyList(),
)
@Serializable private data class CreatedTemplateDto(val id: String?, val name: String, val language: String, val status: String, val category: String)
@Serializable private data class WebWidgetRequest(val allowedOrigins: List<String> = emptyList())
@Serializable private data class LocaleRequest(val locale: String)
@Serializable private data class OverviewLayoutRequest(val hidden: List<String> = emptyList())
@Serializable private data class WebWidgetDto(val publicKey: String? = null, val allowedOrigins: List<String> = emptyList())

private fun ChannelBinding?.toWebWidgetDto() = WebWidgetDto(publicKey = this?.externalId, allowedOrigins = this?.allowedOrigins ?: emptyList())

private fun Tenant.dto() = TenantMeDto(
    id.toHexString(),
    slug,
    name,
    locale,
    timezone,
    channels.map {
        ChannelMeDto(
            it.platform.name,
            it.externalId,
            it.displayName,
            commentsEnabled = it.platform == Platform.INSTAGRAM && InstagramOAuthScopes.hasComments(it.grantedScopes),
        )
    },
)
private fun DashboardUser.dto() = DashboardUserDto(id.toHexString(), email, role.name, status.name)
private fun personaDto(persona: com.rfm.edubot.persona.TenantPersona?, sources: List<PersonaSource>) = PersonaDto(
    compiledInstructions = persona?.compiledInstructions.orEmpty(),
    version = persona?.version ?: 0,
    tokenEstimate = persona?.tokenEstimate ?: 0,
    status = persona?.status?.name ?: PersonaStatus.EMPTY.name,
    updatedAt = persona?.updatedAt?.toString(),
    sources = sources.map { PersonaSourceDto(it.id.toHexString(), it.kind.name, it.label, it.compiledIntoVersion != null, it.createdAt.toString()) },
)
private fun com.rfm.edubot.conversation.model.User.dto() = ContactDto(id.toHexString(), waId, channel.name, displayName, status.name, lastSeenAt.toString())
private fun com.rfm.edubot.conversation.model.Conversation.dto(
    displayName: String?,
    last: com.rfm.edubot.conversation.model.Message?,
    lastInbound: kotlinx.datetime.Instant?,
    windowExpiresAt: kotlinx.datetime.Instant?,
) = ConversationDto(
    id.toHexString(),
    waId,
    channel.name,
    displayName,
    state.name,
    lastMessageAt.toString(),
    messageCount,
    lastPreview = last?.previewText(),
    lastRole = last?.role?.name,
    waiting = last?.role == UserRole.USER,
    autoReplyEnabled = autoReplyEnabled,
    unreadCount = unreadCount,
    lastAuthor = last?.authorLabel(),
    lastKind = last?.kind(),
    lastStatus = last?.status?.name,
    lastTracked = last?.waMessageId != null,
    lastInboundAt = lastInbound?.toString(),
    windowExpiresAt = windowExpiresAt?.toString(),
    autoReplyPausedAt = autoReplyPausedAt?.toString(),
    autoReplyPausedBy = autoReplyPausedBy,
)
private fun com.rfm.edubot.conversation.model.Message.previewText(): String {
    val text = displayText().trim()
    return if (text.length <= 120) text else text.take(117) + "…"
}
private fun com.rfm.edubot.conversation.model.Message.displayText(): String = when (val c = content) {
    is MessageContent.Text -> c.body
    is MessageContent.Template -> c.body
    is MessageContent.Image -> c.caption.orEmpty()
    is MessageContent.Audio -> c.transcription.orEmpty()
    is MessageContent.Video -> c.caption.orEmpty()
    is MessageContent.Document -> c.caption.orEmpty()
}
private fun com.rfm.edubot.conversation.model.Message.kind(): String = when (content) {
    is MessageContent.Text -> "text"
    is MessageContent.Template -> "template"
    is MessageContent.Image -> "image"
    is MessageContent.Audio -> "audio"
    is MessageContent.Document -> "document"
    is MessageContent.Video -> "video"
}
private fun com.rfm.edubot.conversation.model.Message.authorLabel(): String = when {
    role == UserRole.USER -> "customer"
    author == com.rfm.edubot.conversation.model.MessageAuthor.AGENT -> "agent"
    else -> "ai"
}
private fun com.rfm.edubot.conversation.model.Message.dto() = ThreadMessageDto(
    id = id.toHexString(),
    role = role.name,
    text = displayText(),
    status = status.name,
    createdAt = createdAt.toString(),
    author = authorLabel(),
    kind = kind(),
    tracked = waMessageId != null,
    agentName = agentName,
    agentUserId = agentUserId,
    templateName = (content as? MessageContent.Template)?.name,
    fileName = (content as? MessageContent.Document)?.fileName,
    statusAt = statusAt?.toString(),
    errorCode = errorCode,
    errorKey = errorCode?.let { com.rfm.edubot.whatsapp.WhatsAppErrors.key(it) },
    errorText = errorText,
)
private fun com.rfm.edubot.whatsapp.WhatsAppTemplate.dto() =
    WhatsAppTemplateDto(id, name, language, category, status, rejectedReason, header, body, footer, buttons, params, sendable)

private val INBOX_CURSOR_OVERLAP = 5.seconds

private fun DashboardContext.agent() = InboxService.Agent(userId = user?.id?.toHexString(), name = user?.email)

/** List rows for [conversations], with each WhatsApp conversation's 24-hour reply window. */
private suspend fun inboxDtos(mongo: MongoModule, tenant: Tenant, inbox: InboxService, conversations: List<com.rfm.edubot.conversation.model.Conversation>): List<ConversationDto> {
    if (conversations.isEmpty()) return emptyList()
    val messages = MessageRepository(mongo, tenant.id)
    val displayNames = UserRepository(mongo, tenant.id).displayNamesByIds(conversations.map { it.userId })
    val lastMessages = messages.lastByConversationIds(conversations.map { it.id })
    val legacyInbound = messages.lastInboundByConversationIds(conversations.filter { it.lastInboundAt == null }.map { it.id })
    return conversations.map { convo ->
        val lastInbound = convo.lastInboundAt ?: legacyInbound[convo.id]
        convo.dto(displayNames[convo.userId], lastMessages[convo.id], lastInbound, inbox.windowExpiresAt(convo, lastInbound))
    }
}

private suspend fun ApplicationCall.inboxConversation(mongo: MongoModule, ctx: DashboardContext): com.rfm.edubot.conversation.model.Conversation? {
    val id = runCatching { ObjectId(parameters["id"]) }.getOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, InboxErrorDto("not_found"))
        return null
    }
    return ConversationRepository(mongo, ctx.tenant.id).findById(id) ?: run {
        respond(HttpStatusCode.NotFound, InboxErrorDto("not_found"))
        null
    }
}

/** Runs an inbox action, answering its [InboxError] as `{ error, detail }` so the dashboard can explain it. */
private suspend fun <T> ApplicationCall.inboxAction(block: suspend () -> T): T? = try {
    block()
} catch (e: InboxError) {
    val status = when (e.key) {
        "invalid_text", "invalid_phone", "template_params", "templates_whatsapp_only", "web_read_only", "template_unsupported", "not_retryable",
        "template_name_invalid", "template_language_invalid", "template_category_invalid", "template_header_invalid", "template_footer_invalid",
        "template_body_invalid", "template_variables_invalid", "template_examples_missing", "template_buttons_invalid" -> HttpStatusCode.BadRequest
        "not_found", "template_not_found" -> HttpStatusCode.NotFound
        "window_closed", "no_channel", "no_waba" -> HttpStatusCode.Conflict
        "media_too_large" -> HttpStatusCode.PayloadTooLarge
        else -> HttpStatusCode.BadGateway
    }
    respond(status, InboxErrorDto(e.key, e.detail))
    null
}
