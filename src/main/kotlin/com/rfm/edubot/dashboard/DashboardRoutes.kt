package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mongodb.client.model.Filters
import com.rfm.edubot.ai.AiClient
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.ChatMessage
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.admin.CreateClientRequest
import com.rfm.edubot.admin.CreateInvoiceRequest
import com.rfm.edubot.admin.CreateQuoteRequest
import com.rfm.edubot.admin.StandardItemRequest
import com.rfm.edubot.admin.dto
import com.rfm.edubot.admin.respondPdf
import com.rfm.edubot.admin.savePdf
import com.rfm.edubot.bookings.bookingDeps
import com.rfm.edubot.bookings.installBookingRoutes
import com.rfm.edubot.bookings.model.BookingSource
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.conversation.ConversationRepository
import com.rfm.edubot.conversation.MessageRepository
import com.rfm.edubot.conversation.UserRepository
import com.rfm.edubot.conversation.model.MessageContent
import com.rfm.edubot.conversation.model.Message
import com.rfm.edubot.conversation.model.MessageStatus
import com.rfm.edubot.conversation.model.UserRole
import com.rfm.edubot.conversation.model.UserStatus
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.crm.CrmTools
import com.rfm.edubot.crm.InvoiceRepository
import com.rfm.edubot.crm.PdfGenerator
import com.rfm.edubot.crm.QuoteRepository
import com.rfm.edubot.crm.StandardItemRepository
import com.rfm.edubot.crm.model.InvoiceStatus
import com.rfm.edubot.crm.model.QuoteStatus
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
import com.rfm.edubot.tenant.model.Platform
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.tenant.model.TenantLocales
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.LocalDate
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date

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
) {
    post("/app/auth/login") {
        val request = call.receive<DashboardLoginRequest>()
        val user = dashboardUsers.findByEmail(request.email)
        if (user == null || user.status != DashboardUserStatus.ACTIVE || !BCrypt.verifyer().verify(request.password.toCharArray(), user.passwordHash).verified) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid credentials"))
            return@post
        }
        dashboardUsers.markLogin(user.id, SystemClock.now())
        val admin = runtimeConfig.get().admin
        call.respond(DashboardLoginResponse(token = dashboardToken(admin, user, "tenant", admin.jwtExpiryHours)))
    }

    authenticate("dashboard") {
        route("/app/api") {
            get("/me") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@get
                call.respond(MeDto(ctx.tenant.dto(), ctx.user?.dto(), DashboardModules.effectiveFor(ctx.tenant), ctx.principalType))
            }
            get("/overview") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@get
                if (!ctx.requireModule(DashboardModules.OVERVIEW)) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(overview(mongo, ctx.tenant.id))
            }
            get("/contacts") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@get
                if (!ctx.requireModule(DashboardModules.CONTACTS)) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(UserRepository(mongo, ctx.tenant.id).list(call.request.queryParameters["q"]).map { it.dto() })
            }
            patch("/contacts/{id}/status") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@patch
                if (!ctx.requireModule(DashboardModules.CONTACTS)) return@patch call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<ContactStatusRequest>()
                val user = UserRepository(mongo, ctx.tenant.id).setStatus(ObjectId(call.parameters["id"]), UserStatus.valueOf(request.status))
                    ?: return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(user.dto())
            }
            get("/conversations") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@get
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@get call.respond(HttpStatusCode.Forbidden)
                val conversations = ConversationRepository(mongo, ctx.tenant.id).list(call.request.queryParameters["q"])
                val displayNames = UserRepository(mongo, ctx.tenant.id).displayNamesByIds(conversations.map { it.userId })
                call.respond(conversations.map { it.dto(displayNames[it.userId]) })
            }
            get("/conversations/{id}/messages") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@get
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@get call.respond(HttpStatusCode.Forbidden)
                val convo = ConversationRepository(mongo, ctx.tenant.id).findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(MessageRepository(mongo, ctx.tenant.id).threadByConversation(convo.id).map { it.dto() })
            }
            post("/conversations/{id}/messages") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@post
                if (!ctx.requireModule(DashboardModules.CONVERSATIONS)) return@post call.respond(HttpStatusCode.Forbidden)
                val conversationId = runCatching { ObjectId(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid conversation id"))
                val conversation = ConversationRepository(mongo, ctx.tenant.id).findById(conversationId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val request = call.receive<OutboundMessageRequest>()
                val text = request.text.trim()
                if (text.isBlank() || text.length > 1000) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "message must contain 1 to 1000 characters"))
                }
                if (conversation.channel == Platform.WEB) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "website conversations do not support operator messages"))
                }
                val binding = ctx.tenant.binding(conversation.channel)
                if (binding == null || binding.externalId != request.assetExternalId) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "selected asset does not match this conversation"))
                }

                try {
                    pipelineFactory.responderFor(ctx.tenant, conversation.channel).sendText(conversation.waId, text)
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to (e.message ?: "message delivery failed")))
                }

                val message = Message(
                    tenantId = ctx.tenant.id,
                    conversationId = conversation.id,
                    channel = conversation.channel,
                    waId = conversation.waId,
                    role = UserRole.ASSISTANT,
                    content = MessageContent.Text(text),
                    status = MessageStatus.DELIVERED,
                    createdAt = SystemClock.now(),
                )
                MessageRepository(mongo, ctx.tenant.id).insert(message)
                ConversationRepository(mongo, ctx.tenant.id).bumpActivity(conversation.id)
                call.respond(HttpStatusCode.Created, message.dto())
            }
            get("/persona") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@get
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@get call.respond(HttpStatusCode.Forbidden)
                val repo = PersonaRepository(mongo)
                call.respond(personaDto(repo.findByTenant(ctx.tenant.id), repo.listSources(ctx.tenant.id)))
            }
            put("/persona") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@put
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@put call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<PersonaUpdateRequest>()
                val repo = PersonaRepository(mongo)
                val persona = repo.upsertCompiled(ctx.tenant.id, request.compiledInstructions.trim())
                pipelineFactory.evict(ctx.tenant.id)
                call.respond(personaDto(persona, repo.listSources(ctx.tenant.id)))
            }
            post("/persona/sources") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@post
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@post call.respond(HttpStatusCode.Forbidden)
                val content = call.receive<PersonaSourceRequest>().content.trim()
                if (content.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "content is required"))
                val repo = PersonaRepository(mongo)
                repo.addSource(ctx.tenant.id, SourceKind.TEXT_NOTE, content, content.take(60))
                personaCompiler.enqueue(ctx.tenant.id, ctx.tenant.openrouterModel)
                call.respond(HttpStatusCode.Accepted, personaDto(repo.findByTenant(ctx.tenant.id), repo.listSources(ctx.tenant.id)))
            }
            post("/persona/sources/file") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@post
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
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@delete
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@delete call.respond(HttpStatusCode.Forbidden)
                val repo = PersonaRepository(mongo)
                if (!repo.deleteSource(ctx.tenant.id, ObjectId(call.parameters["id"]))) return@delete call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("deleted" to true))
            }
            post("/persona/rebuild") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@post
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@post call.respond(HttpStatusCode.Forbidden)
                personaCompiler.rebuild(ctx.tenant.id, ctx.tenant.openrouterModel)
                val repo = PersonaRepository(mongo)
                call.respond(personaDto(repo.findByTenant(ctx.tenant.id), repo.listSources(ctx.tenant.id)))
            }
            post("/persona/test") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@post
                if (!ctx.requireModule(DashboardModules.PERSONA)) return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<PersonaTestRequest>()
                val history = request.messages.filter { it.content.isNotBlank() }.takeLast(20)
                if (history.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "messages are required"))
                val reply = runPersonaTest(mongo, aiClient, ctx.tenant, history)
                call.respond(PersonaTestResponse(reply))
            }
            get("/web-widget") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@get
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@get call.respond(HttpStatusCode.Forbidden)
                call.respond(ctx.tenant.binding(Platform.WEB).toWebWidgetDto())
            }
            // Self-serve website-widget onboarding: create the WEB binding on first call (minting a
            // public key) and update its origin allow-list on later calls — the key is preserved so a
            // tenant's embedded snippet never breaks. ChannelBindingService re-indexes the registry and
            // evicts the pipeline so the new channel is live without a restart.
            post("/web-widget") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@post
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
            // Tenant-selectable UI language. Persisted on the tenant so it becomes the default for every
            // dashboard session; the browser keeps a per-session override (localStorage.uiLocale).
            post("/settings/locale") {
                val ctx = call.dashboardContext(tenantRepository, dashboardUsers) ?: return@post
                if (!ctx.requireModule(DashboardModules.SETTINGS)) return@post call.respond(HttpStatusCode.Forbidden)
                val request = call.receive<LocaleRequest>()
                if (request.locale !in TenantLocales.SUPPORTED) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported locale"))
                }
                val updated = tenantRepository.setLocale(ctx.tenant.slug, request.locale, SystemClock.now())
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                call.respond(mapOf("locale" to updated.locale))
            }
            dashboardAssistantRoutes(mongo, tenantRepository, dashboardUsers, aiClient)
            crmRoutes(mongo, tenantRepository, dashboardUsers, runtimeConfig)
            installBookingRoutes {
                val ctx = dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.BOOKINGS) }
                    ?: run {
                        respond(HttpStatusCode.Forbidden)
                        return@installBookingRoutes null
                    }
                bookingDeps(mongo, ctx.tenant, BookingSource.DASHBOARD)
            }
        }
    }
}

fun Route.dashboardImpersonationRoute(
    tenantRepository: TenantRepository,
    dashboardUsers: DashboardUserRepository,
    runtimeConfig: RuntimeConfig,
) {
    authenticate("admin-jwt") {
        get("/admin/api/tenants/{slug}/dashboard-users") {
            val slug = call.parameters["slug"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(dashboardUsers.listByTenant(tenant.id).map { it.dto() })
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
                tenantId = tenant.id,
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
            val user = dashboardUsers.setStatus(ObjectId(id), tenant.id, DashboardUserStatus.DISABLED)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(user.dto())
        }
        post("/admin/api/tenants/{slug}/dashboard-users/{id}/activate") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val user = dashboardUsers.setStatus(ObjectId(id), tenant.id, DashboardUserStatus.ACTIVE)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(user.dto())
        }
        post("/admin/api/tenants/{slug}/impersonate") {
            val slug = call.parameters["slug"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val tenant = tenantRepository.findBySlug(slug) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(DashboardLoginResponse(token = dashboardToken(runtimeConfig.get().admin, tenant, "operator-imp", 1)))
        }
    }
}

private fun Route.crmRoutes(mongo: MongoModule, tenantRepository: TenantRepository, dashboardUsers: DashboardUserRepository, runtimeConfig: RuntimeConfig) {
    fun tenantDeps(ctx: DashboardContext): CrmDeps = CrmDeps(
        clients = ClientRepository(mongo, ctx.tenant.id),
        quotes = QuoteRepository(mongo, ctx.tenant.id),
        invoices = InvoiceRepository(mongo, ctx.tenant.id),
        standardItems = StandardItemRepository(mongo, ctx.tenant.id),
        pdfGenerator = PdfGenerator(),
        pdfStoragePath = "${runtimeConfig.get().pdfStoragePath}/${ctx.tenant.slug}",
    )

    route("/crm") {
        get("/clients") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            call.respond(deps.clients.search(call.request.queryParameters["q"].orEmpty()).map { it.dto() })
        }
        post("/clients") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateClientRequest>()
            if (request.name.isBlank() || request.phone.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and phone are required"))
            call.respond(HttpStatusCode.Created, deps.clients.create(request.name, request.phone, request.address).dto())
        }
        get("/standard-items") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.CATALOG) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            call.respond(deps.standardItems.search(call.request.queryParameters["q"], call.request.queryParameters["type"]))
        }
        post("/standard-items") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.CATALOG) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<StandardItemRequest>()
            call.respond(HttpStatusCode.Created, deps.standardItems.create(request.toStandardItem(request.id)))
        }
        post("/standard-items/{id}") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.CATALOG) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<StandardItemRequest>()
            call.respond(deps.standardItems.update(id, request.toStandardItem(id)) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        delete("/standard-items/{id}") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.CATALOG) } ?: return@delete call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (!deps.standardItems.delete(id)) return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("deleted" to true))
        }
        get("/quotes") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.QUOTES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val rows = deps.quotes.list(call.request.queryParameters["clientId"]?.let { ObjectId(it) }, call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { QuoteStatus.valueOf(it.uppercase()) })
            call.respond(rows.map { it.dto(deps.clients.findById(it.clientId)) })
        }
        post("/quotes") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.QUOTES) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateQuoteRequest>()
            if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
            val quote = deps.quotes.create(ObjectId(request.clientId), request.items.map { it.toLineItem() }, request.notes, request.validUntil?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) })
            val client = deps.clients.findById(quote.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
            val path = savePdf(deps.pdfStoragePath, "quotes", "Orcamento ${quote.number}.pdf", deps.pdfGenerator.generateQuote(quote, client))
            deps.quotes.setPdfPath(quote.id, path.toString())
            call.respond(HttpStatusCode.Created, quote.copy(pdfPath = path.toString()).dto(client))
        }
        get("/quotes/{id}/pdf") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.QUOTES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val quote = deps.quotes.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondPdf(quote.pdfPath)
        }
        get("/invoices") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val rows = deps.invoices.list(call.request.queryParameters["clientId"]?.let { ObjectId(it) }, call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }?.let { InvoiceStatus.valueOf(it.uppercase()) })
            call.respond(rows.map { it.dto(deps.clients.findById(it.clientId)) })
        }
        post("/invoices") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@post call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val request = call.receive<CreateInvoiceRequest>()
            if (request.items.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "at least one item is required"))
            val invoice = deps.invoices.create(ObjectId(request.clientId), request.quoteId?.takeIf { it.isNotBlank() }?.let { ObjectId(it) }, request.items.map { it.toLineItem() }, LocalDate.parse(request.dueDate))
            val client = deps.clients.findById(invoice.clientId) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "client not found"))
            val path = savePdf(deps.pdfStoragePath, "invoices", "Fatura ${invoice.number}.pdf", deps.pdfGenerator.generateInvoice(invoice, client))
            deps.invoices.setPdfPath(invoice.id, path.toString())
            call.respond(HttpStatusCode.Created, invoice.copy(pdfPath = path.toString()).dto(client))
        }
        get("/invoices/{id}/pdf") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@get call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val invoice = deps.invoices.findById(ObjectId(call.parameters["id"])) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondPdf(invoice.pdfPath)
        }
        patch("/invoices/{id}/paid") {
            val ctx = call.dashboardContext(tenantRepository, dashboardUsers)?.takeIf { it.requireModule(DashboardModules.INVOICES) } ?: return@patch call.respond(HttpStatusCode.Forbidden)
            val deps = tenantDeps(ctx)
            val invoice = deps.invoices.markPaid(ObjectId(call.parameters["id"])) ?: return@patch call.respond(HttpStatusCode.NotFound)
            call.respond(invoice.dto(deps.clients.findById(invoice.clientId)))
        }
    }
}

internal suspend fun io.ktor.server.application.ApplicationCall.dashboardContext(tenantRepository: TenantRepository, users: DashboardUserRepository): DashboardContext? {
    val principal = this.principal<JWTPrincipal>() ?: return null
    val tenantId = ObjectId(principal.payload.getClaim("tenantId").asString())
    val tenant = tenantRepository.findById(tenantId) ?: run { respond(HttpStatusCode.NotFound); return null }
    val typ = principal.payload.getClaim("typ").asString()
    val user = principal.payload.subject?.takeIf { typ == "tenant" }?.let { users.findById(ObjectId(it)) }
    return DashboardContext(tenant, user, typ)
}

internal data class DashboardContext(val tenant: Tenant, val user: DashboardUser?, val principalType: String)

internal fun DashboardContext.requireModule(id: String): Boolean = id in DashboardModules.effectiveFor(tenant)

private data class CrmDeps(
    val clients: ClientRepository,
    val quotes: QuoteRepository,
    val invoices: InvoiceRepository,
    val standardItems: StandardItemRepository,
    val pdfGenerator: PdfGenerator,
    val pdfStoragePath: String,
)

private fun dashboardToken(config: AppConfig.AdminConfig, user: DashboardUser, typ: String, expiryHours: Int): String = JWT.create()
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

private suspend fun overview(mongo: MongoModule, tenantId: ObjectId): OverviewDto {
    val tenantFilter = Filters.eq("tenantId", tenantId)
    val todayStart = Date(System.currentTimeMillis() - 24L * 60L * 60L * 1000L)
    return OverviewDto(
        users = mongo.database.getCollection<Document>("users").countDocuments(tenantFilter),
        conversations = mongo.database.getCollection<Document>("conversations").countDocuments(tenantFilter),
        messages = mongo.database.getCollection<Document>("messages").countDocuments(tenantFilter),
        messagesToday = mongo.database.getCollection<Document>("messages").countDocuments(Filters.and(tenantFilter, Filters.gte("createdAt", todayStart))),
        quotes = mongo.database.getCollection<Document>("crm.quotes").countDocuments(tenantFilter),
        invoices = mongo.database.getCollection<Document>("crm.invoices").countDocuments(tenantFilter),
    )
}

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
    val persona = PersonaRepository(mongo).findByTenant(tenant.id)?.compiledInstructions
    val context = mutableListOf<ChatMessage>()
    context.add(ChatMessage(role = "system", content = SystemPrompts.CRM_V1))
    persona?.takeIf { it.isNotBlank() }?.let { context.add(ChatMessage(role = "system", content = "<persona>\n$it\n</persona>")) }
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
    val toolDefs = crmTools.readOnlyDefinitions
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

@Serializable private data class DashboardLoginRequest(val email: String, val password: String)
@Serializable private data class DashboardLoginResponse(val token: String)
@Serializable private data class DashboardUserCreateRequest(val email: String, val password: String, val role: String = "TENANT_ADMIN")
@Serializable private data class MeDto(val tenant: TenantMeDto, val user: DashboardUserDto?, val modules: List<String>, val principalType: String)
@Serializable private data class TenantMeDto(val id: String, val slug: String, val name: String, val locale: String, val timezone: String, val channels: List<ChannelMeDto> = emptyList())
@Serializable private data class ChannelMeDto(val platform: String, val externalId: String, val displayName: String? = null)
@Serializable private data class DashboardUserDto(val id: String, val email: String, val role: String, val status: String)
@Serializable private data class OverviewDto(val users: Long, val conversations: Long, val messages: Long, val messagesToday: Long, val quotes: Long, val invoices: Long)
@Serializable private data class ContactStatusRequest(val status: String)
@Serializable private data class PersonaUpdateRequest(val compiledInstructions: String)
@Serializable private data class PersonaSourceRequest(val content: String)
@Serializable private data class PersonaTestMessage(val role: String, val content: String)
@Serializable private data class PersonaTestRequest(val messages: List<PersonaTestMessage>)
@Serializable private data class PersonaTestResponse(val reply: String)
@Serializable private data class PersonaSourceDto(val id: String, val kind: String, val label: String, val compiled: Boolean, val createdAt: String)
@Serializable private data class PersonaDto(val compiledInstructions: String, val version: Int, val tokenEstimate: Int, val status: String, val updatedAt: String?, val sources: List<PersonaSourceDto>)
@Serializable private data class ContactDto(val id: String, val waId: String, val channel: String, val displayName: String?, val status: String, val lastSeenAt: String)
@Serializable private data class ConversationDto(val id: String, val waId: String, val channel: String, val displayName: String?, val state: String, val lastMessageAt: String, val messageCount: Int)
@Serializable private data class OutboundMessageRequest(val text: String, val assetExternalId: String)
@Serializable private data class ThreadMessageDto(val id: String, val role: String, val text: String, val status: String, val createdAt: String)
@Serializable private data class WebWidgetRequest(val allowedOrigins: List<String> = emptyList())
@Serializable private data class LocaleRequest(val locale: String)
@Serializable private data class WebWidgetDto(val publicKey: String? = null, val allowedOrigins: List<String> = emptyList())

private fun ChannelBinding?.toWebWidgetDto() = WebWidgetDto(publicKey = this?.externalId, allowedOrigins = this?.allowedOrigins ?: emptyList())

private fun Tenant.dto() = TenantMeDto(id.toHexString(), slug, name, locale, timezone, channels.map { ChannelMeDto(it.platform.name, it.externalId, it.displayName) })
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
private fun com.rfm.edubot.conversation.model.Conversation.dto(displayName: String?) = ConversationDto(id.toHexString(), waId, channel.name, displayName, state.name, lastMessageAt.toString(), messageCount)
private fun com.rfm.edubot.conversation.model.Message.dto() = ThreadMessageDto(id.toHexString(), role.name, (content as? MessageContent.Text)?.body ?: "", status.name, createdAt.toString())
