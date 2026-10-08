package com.rfm.edubot.dashboard

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.rfm.edubot.admin.configureAdminAuth
import com.rfm.edubot.ai.AiResponse
import com.rfm.edubot.ai.SystemPrompts
import com.rfm.edubot.ai.TenantUsageRepository
import com.rfm.edubot.ai.UsageSources
import com.rfm.edubot.config.AppConfig
import com.rfm.edubot.config.RuntimeConfig
import com.rfm.edubot.crm.ClientRepository
import com.rfm.edubot.dashboard.model.DashboardUser
import com.rfm.edubot.dashboard.model.DashboardUserRole
import com.rfm.edubot.persistence.MongoModule
import com.rfm.edubot.persona.PersonaCompiler
import com.rfm.edubot.persona.PersonaLimits
import com.rfm.edubot.persona.PersonaPrompt
import com.rfm.edubot.persona.PersonaRepository
import com.rfm.edubot.persona.SourceKind
import com.rfm.edubot.plugins.configureSerialization
import com.rfm.edubot.tenant.TenantPipelineFactory
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.Tenant
import com.rfm.edubot.testing.FakeModel
import com.rfm.edubot.testing.TestMongo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The persona module end to end over HTTP: the real auth plugin, routes, repository and synthesis
 * against MongoDB, with a scripted model. Each test is one thing a company does with its bot's persona.
 */
class PersonaRoutesTest {
    companion object {
        private lateinit var mongo: MongoModule

        @BeforeAll
        @JvmStatic
        fun setUp() {
            mongo = TestMongo.module("persona_routes")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() = mongo.shutdown()

        private const val PASSWORD = "correct horse"
        private const val SECRET = "test-secret"
        private val MATERIAL = Regex("""<material kind="[A-Z_]+" label="[^"]*">\n(.*?)\n</material>""", RegexOption.DOT_MATCHES_ALL)
    }

    private val now = Clock.System.now()
    private val users get() = DashboardUserRepository(mongo)
    private val tenants get() = TenantRepository(mongo)
    private val personas get() = PersonaRepository(mongo)
    private val runtime = RuntimeConfig(
        AppConfig(
            port = 8080,
            whatsapp = AppConfig.WhatsAppConfig(verifyToken = "v", appSecret = "s", phoneNumberId = "1", accessToken = "t"),
            instagram = AppConfig.InstagramConfig(appId = "ig", appSecret = "s", redirectUri = "https://example.com/cb"),
            openrouter = AppConfig.OpenRouterConfig(apiKey = "k", primaryModel = "a", fallbackModel = "b", maxTokens = 512),
            mongo = AppConfig.MongoConfig(uri = "unused", database = "unused"),
            rateLimit = AppConfig.RateLimitConfig(),
            admin = AppConfig.AdminConfig(jwtSecret = SECRET),
            pdfStoragePath = "/tmp/pdfs",
        ),
    )
    private val json = Json { ignoreUnknownKeys = true }

    /** What a test drives: the HTTP client, the model, and the pipeline factory the routes evict. */
    private inner class Env(val http: HttpClient, val model: FakeModel, val pipelines: TenantPipelineFactory) {
        /** Answers the test chat; synthesis requests are always "merged" by appending the new material. */
        var chat: suspend FakeModel.Request.() -> AiResponse = { FakeModel.text("Olá! Como posso ajudar?") }

        val chats: List<FakeModel.Request> get() = model.requests.filterNot { it.isSynthesis() }

        suspend fun send(method: String, path: String, token: String, body: JsonObject? = null, raw: String? = null): HttpResponse {
            val request: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                bearerAuth(token)
                if (body != null || raw != null) {
                    contentType(ContentType.Application.Json)
                    setBody(raw ?: body.toString())
                }
            }
            return when (method) {
                "GET" -> http.get(path, request)
                "PUT" -> http.put(path, request)
                "DELETE" -> http.delete(path, request)
                else -> http.post(path, request)
            }
        }

        suspend fun upload(token: String, name: String?, bytes: ByteArray): HttpResponse =
            http.submitFormWithBinaryData(
                url = "/app/api/persona/sources/file",
                formData = formData {
                    if (name != null) {
                        append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$name\"") })
                    } else {
                        append("note", "no file here")
                    }
                },
            ) { bearerAuth(token) }

        suspend fun token(email: String): String {
            val response = http.post("/app/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("email", email); put("password", PASSWORD) }.toString())
            }
            return response.obj().text("token")!!
        }

        suspend fun persona(token: String): JsonObject = send("GET", "/app/api/persona", token).obj()

        /** Waits for the background synthesis: not compiling and nothing pending, or failed. */
        suspend fun settled(token: String): JsonObject = withTimeout(10_000) {
            var p = persona(token)
            while (p.text("status") != "ERROR" && (p.text("status") == "COMPILING" || p.int("pendingSources") > 0)) {
                delay(25)
                p = persona(token)
            }
            p
        }
    }

    private fun FakeModel.Request.isSynthesis() = system.firstOrNull()?.startsWith("You maintain the instruction file") == true

    private fun personaTest(block: suspend Env.() -> Unit): Unit = testApplication {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val model = FakeModel()
        val pipelines = mockk<TenantPipelineFactory>(relaxed = true)
        val compiler = PersonaCompiler(
            repository = PersonaRepository(mongo),
            aiClient = model.client,
            scope = scope,
            onCompiled = { pipelines.evict(it) },
            usageFor = { TenantUsageRepository(mongo, it) },
            debounceMillis = 0,
        )
        application {
            configureSerialization()
            configureAdminAuth(runtime, tenants, users)
            routing {
                dashboardAccountRoutes(tenants, users, runtime)
                dashboardRoutes(
                    mongo = mongo,
                    tenantRepository = tenants,
                    dashboardUsers = users,
                    pipelineFactory = pipelines,
                    personaCompiler = compiler,
                    aiClient = model.client,
                    runtimeConfig = runtime,
                    channelBindingService = mockk(relaxed = true),
                    instagramSocial = mockk(relaxed = true),
                )
            }
        }
        val env = Env(client, model, pipelines)
        model.answer = {
            if (isSynthesis()) {
                val current = lastUser!!.substringAfter("<current_file>\n").substringBefore("\n</current_file>").takeUnless { it.startsWith("(empty") }.orEmpty()
                FakeModel.text((listOf(current) + MATERIAL.findAll(lastUser!!).map { it.groupValues[1] }).filter { it.isNotBlank() }.joinToString("\n"))
            } else {
                env.chat(this)
            }
        }
        try {
            env.block()
        } finally {
            scope.cancel()
        }
    }

    private inner class Company(modules: List<String>? = null, val budget: Long = 2_000_000L) {
        val id = ObjectId()
        val tenant = Tenant(
            id = id, slug = "t-${id.toHexString()}", name = "Clínica Sorriso", channels = emptyList(), openrouterModel = "tenant/model",
            enabledModules = modules, monthlyTokenBudget = budget, createdAt = now, updatedAt = now,
        )
        lateinit var admin: DashboardUser
        lateinit var member: DashboardUser

        suspend fun create(): Company {
            tenants.create(tenant)
            admin = newUser(DashboardUserRole.TENANT_ADMIN)
            member = newUser(DashboardUserRole.TENANT_MEMBER)
            return this
        }

        private suspend fun newUser(role: DashboardUserRole): DashboardUser {
            val userId = ObjectId()
            return users.create(
                DashboardUser(
                    id = userId, tenantId = id, email = "${role.name.lowercase()}-${userId.toHexString()}@sorriso.test",
                    passwordHash = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray()), role = role, createdAt = now,
                ),
            )
        }

        /** The token an operator gets when opening this company's dashboard from the backoffice. */
        fun operatorToken(): String = JWT.create()
            .withIssuer("wabot-platform")
            .withSubject("operator")
            .withClaim("tenantId", id.toHexString())
            .withClaim("role", "PLATFORM_ADMIN")
            .withClaim("typ", DashboardAccessPolicy.OPERATOR_IMPERSONATION)
            .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
            .sign(Algorithm.HMAC256(SECRET))
    }

    private suspend fun company(modules: List<String>? = null, budget: Long = 2_000_000L) = Company(modules, budget).create()

    private suspend fun HttpResponse.obj(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.list(): JsonArray = json.parseToJsonElement(bodyAsText()).jsonArray
    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.content?.takeUnless { this[name].toString() == "null" }
    private fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.bool(name: String): Boolean = this[name]!!.jsonPrimitive.content.toBoolean()
    private fun JsonObject.obj(name: String): JsonObject = this[name]!!.jsonObject
    private fun JsonObject.sources(): List<JsonObject> = this["sources"]!!.jsonArray.map { it.jsonObject }
    private suspend fun HttpResponse.error(): Triple<String?, String?, Int?> =
        obj().let { Triple(it.text("error"), it.text("field"), it["limit"]?.jsonPrimitive?.content?.toInt()) }

    private fun behavior(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)
    private fun note(text: String) = buildJsonObject { put("content", text) }
    private fun instructions(text: String) = buildJsonObject { put("compiledInstructions", text) }
    private fun messages(vararg pairs: Pair<String, String>, draft: JsonObject? = null) = buildJsonObject {
        putJsonArray("messages") { pairs.forEach { (role, content) -> addJsonObject { put("role", role); put("content", content) } } }
        if (draft != null) put("draft", draft)
    }

    private fun docx(vararg paragraphs: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val body = paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }
            zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun pdf(text: String): ByteArray = PDDocument().use { doc ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { s ->
            s.beginText(); s.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f); s.newLineAtOffset(72f, 700f); s.showText(text); s.endText()
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `a company without the persona module gets 403 on every persona endpoint`() = personaTest {
        val c = company(modules = listOf(DashboardModules.CONVERSATIONS))
        val token = token(c.admin.email)
        val calls = listOf(
            "GET" to "/app/api/persona", "PUT" to "/app/api/persona", "PUT" to "/app/api/persona/behavior", "POST" to "/app/api/persona/sources",
            "GET" to "/app/api/persona/sources/${ObjectId().toHexString()}", "DELETE" to "/app/api/persona/sources/${ObjectId().toHexString()}",
            "POST" to "/app/api/persona/compile", "POST" to "/app/api/persona/rebuild", "GET" to "/app/api/persona/versions",
            "GET" to "/app/api/persona/versions/1", "POST" to "/app/api/persona/versions/1/restore", "POST" to "/app/api/persona/preview",
            "POST" to "/app/api/persona/test",
        )
        for ((method, path) in calls) assertEquals(HttpStatusCode.Forbidden, send(method, path, token, buildJsonObject { }).status, "$method $path")
        assertEquals(HttpStatusCode.Forbidden, upload(token, "a.txt", "x".toByteArray()).status)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun `a new company starts empty, with the limits it works within, and only admins may edit`() = personaTest {
        val c = company()
        val admin = persona(token(c.admin.email))
        assertEquals("EMPTY", admin.text("status"))
        assertEquals(0, admin.int("version"))
        assertEquals(true, admin.bool("canEdit"))
        assertEquals(false, admin.bool("stale"))
        assertEquals(0, admin.int("pendingSources"))
        assertNull(admin.text("lastError"))
        assertEquals(null, admin.obj("behavior").text("botName"))
        val limits = admin.obj("limits")
        assertEquals(PersonaLimits.MAX_INSTRUCTIONS_CHARS, limits.int("instructionsChars"))
        assertEquals(PersonaLimits.MAX_UPLOAD_BYTES, limits.int("uploadBytes"))
        assertTrue(limits["languages"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("pt-PT", "pt-BR", "en", "es")))
        assertTrue(limits["fileTypes"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("pdf", "docx", "txt", "md", "csv")))
        assertEquals(false, persona(token(c.member.email)).bool("canEdit"))
    }

    @Test
    fun `members read the persona and chat with the test bot, but can't change anything`() = personaTest {
        val c = company()
        val adminToken = token(c.admin.email)
        send("PUT", "/app/api/persona", adminToken, instructions("Somos a Clínica Sorriso."))
        val source = personas.addSource(c.id, SourceKind.TEXT_NOTE, "Nota", "Nota")
        val memberToken = token(c.member.email)

        val refused = listOf(
            send("PUT", "/app/api/persona", memberToken, instructions("Mudado")),
            send("PUT", "/app/api/persona/behavior", memberToken, behavior { put("botName", "Hacker") }),
            send("POST", "/app/api/persona/sources", memberToken, note("Nota de membro")),
            upload(memberToken, "a.txt", "conteúdo".toByteArray()),
            send("DELETE", "/app/api/persona/sources/${source.id.toHexString()}", memberToken),
            send("POST", "/app/api/persona/compile", memberToken, buildJsonObject { }),
            send("POST", "/app/api/persona/rebuild", memberToken, buildJsonObject { }),
            send("POST", "/app/api/persona/versions/1/restore", memberToken, buildJsonObject { }),
        )
        refused.forEach {
            assertEquals(HttpStatusCode.Forbidden, it.status)
            assertEquals("not_allowed", it.error().first)
        }
        assertEquals("Somos a Clínica Sorriso.", persona(memberToken).text("compiledInstructions"))
        assertEquals(1, personas.listSources(c.id).size)

        assertEquals(HttpStatusCode.OK, send("GET", "/app/api/persona/versions", memberToken).status)
        assertEquals(HttpStatusCode.OK, send("GET", "/app/api/persona/sources/${source.id.toHexString()}", memberToken).status)
        assertEquals(HttpStatusCode.OK, send("POST", "/app/api/persona/preview", memberToken, buildJsonObject { }).status)
        assertEquals("Olá! Como posso ajudar?", send("POST", "/app/api/persona/test", memberToken, messages("user" to "Olá")).obj().text("reply"))
    }

    @Test
    fun `behavior settings are validated, saved as a version and go live at once`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        val full = behavior {
            put("botName", " Sofia ")
            put("language", "pt-PT")
            put("languageStrict", true)
            put("tone", "FRIENDLY")
            put("addressForm", "FORMAL")
            put("replyLength", "SHORT")
            put("emoji", "NONE")
            put("greeting", "Olá! Bem-vindo à Clínica Sorriso.")
            putJsonArray("rules") { add("Nunca fale de concorrentes."); add(" "); add("nunca fale de concorrentes.") }
            putJsonObject("handoff") { put("enabled", true); put("triggers", "reembolsos"); put("message", "Vou passar a conversa à equipa.") }
        }

        val saved = send("PUT", "/app/api/persona/behavior", token, full)
        assertEquals(HttpStatusCode.OK, saved.status)
        val dto = saved.obj()
        assertEquals(1, dto.int("version"))
        assertEquals("READY", dto.text("status"))
        assertTrue(dto.int("tokenEstimate") > 0)
        val b = dto.obj("behavior")
        assertEquals("Sofia", b.text("botName"))
        assertEquals("pt-PT", b.text("language"))
        assertEquals(true, b.bool("languageStrict"))
        assertEquals(listOf("FRIENDLY", "FORMAL", "SHORT", "NONE"), listOf("tone", "addressForm", "replyLength", "emoji").map { b.text(it) })
        assertEquals(listOf("Nunca fale de concorrentes."), b["rules"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Vou passar a conversa à equipa.", b.obj("handoff").text("message"))
        verify { pipelines.evict(c.id) }

        assertEquals(1, send("PUT", "/app/api/persona/behavior", token, full).obj().int("version"), "saving the same settings adds no version")
        val history = send("GET", "/app/api/persona/versions", token).list().map { it.jsonObject }
        assertEquals(listOf("SETTINGS"), history.map { it.text("change") })
        assertEquals(c.admin.email, history.single().text("author"))

        assertEquals(Triple("invalid_option", "tone", null), send("PUT", "/app/api/persona/behavior", token, behavior { put("tone", "SARCASTIC") }).error())
        assertEquals(Triple("invalid_option", "language", null), send("PUT", "/app/api/persona/behavior", token, behavior { put("language", "xx") }).error())
        assertEquals(
            Triple("too_many", "rules", PersonaLimits.MAX_RULES),
            send("PUT", "/app/api/persona/behavior", token, behavior { putJsonArray("rules") { (1..26).forEach { add("Regra $it") } } }).error(),
        )
        assertEquals(
            Triple("too_long", "botName", PersonaLimits.MAX_BOT_NAME_CHARS),
            send("PUT", "/app/api/persona/behavior", token, behavior { put("botName", "x".repeat(61)) }).error(),
        )
        assertEquals(1, persona(token).int("version"), "refused changes write nothing")
    }

    @Test
    fun `a note is synthesized into the instructions in the background`() = personaTest {
        val c = company()
        val token = token(c.admin.email)

        val accepted = send("POST", "/app/api/persona/sources", token, note("Abrimos de segunda a sexta, das 9h às 18h.\nSábados com marcação."))
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals("Abrimos de segunda a sexta, das 9h às 18h.", accepted.obj().sources().single().text("label"))

        val done = settled(token)
        assertEquals("READY", done.text("status"))
        assertEquals(1, done.int("version"))
        assertEquals("Abrimos de segunda a sexta, das 9h às 18h.\nSábados com marcação.", done.text("compiledInstructions"))
        val source = done.sources().single()
        assertEquals(true, source.bool("compiled"))
        assertEquals(c.admin.email, source.text("addedBy"))
        assertEquals("TEXT_NOTE", source.text("kind"))
        verify { pipelines.evict(c.id) }
        assertEquals("tenant/model", model.requests.single().model)

        assertEquals(Triple("content_required", "content", null), send("POST", "/app/api/persona/sources", token, note("   ")).error())
        assertEquals(
            Triple("too_long", "content", PersonaLimits.MAX_NOTE_CHARS),
            send("POST", "/app/api/persona/sources", token, note("x".repeat(PersonaLimits.MAX_NOTE_CHARS + 1))).error(),
        )
    }

    @Test
    fun `uploaded text, Markdown, Word and PDF files become sources, and the rest is refused with a reason`() = personaTest {
        val c = company()
        val token = token(c.admin.email)

        assertEquals(HttpStatusCode.Accepted, upload(token, "precos.md", "# Preços\n- Limpeza: 40 €".toByteArray()).status)
        assertEquals(HttpStatusCode.Accepted, upload(token, "guia.docx", docx("Clínica Sorriso", "Horário: 9h–18h")).status)
        assertEquals(HttpStatusCode.Accepted, upload(token, "folheto.pdf", pdf("Branqueamento 120 EUR")).status)
        assertEquals(HttpStatusCode.Accepted, upload(token, "documentos/2026/horario.txt", "Fechado ao domingo.".toByteArray()).status)

        val done = settled(token)
        val labels = done.sources().map { it.text("label") }.toSet()
        assertEquals(setOf("precos.md", "guia.docx", "folheto.pdf", "horario.txt"), labels)
        assertTrue(done.sources().all { it.text("kind") == "FILE" })
        val instructions = done.text("compiledInstructions")!!
        listOf("Limpeza: 40 €", "Horário: 9h–18h", "Branqueamento 120 EUR", "Fechado ao domingo.").forEach { assertTrue(it in instructions, "$it missing from $instructions") }
        val docxId = done.sources().first { it.text("label") == "guia.docx" }.text("id")
        assertEquals("Clínica Sorriso\nHorário: 9h–18h", send("GET", "/app/api/persona/sources/$docxId", token).obj().text("content"))

        val unsupported = upload(token, "precos.xlsx", "x".toByteArray())
        assertEquals(HttpStatusCode.UnsupportedMediaType, unsupported.status)
        assertEquals("unsupported_file", unsupported.error().first)
        val blank = upload(token, "vazio.txt", "  \n ".toByteArray())
        assertEquals(HttpStatusCode.UnprocessableEntity, blank.status)
        assertEquals("no_text", blank.error().first)
        val broken = upload(token, "partido.pdf", "%PDF-1.7 not really".toByteArray())
        assertEquals(HttpStatusCode.UnprocessableEntity, broken.status)
        assertEquals("unreadable_file", broken.error().first)
        assertEquals("no_file", upload(token, null, ByteArray(0)).error().first)
        val huge = upload(token, "enorme.txt", ByteArray(PersonaLimits.MAX_UPLOAD_BYTES + 200_000) { 'a'.code.toByte() })
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        assertEquals(Triple("file_too_large", "file", PersonaLimits.MAX_UPLOAD_BYTES), huge.error())
        assertEquals(4, personas.listSources(c.id).size, "refused files leave no source behind")
    }

    @Test
    fun `a source reads back in full, and ids that are malformed or another company's are not found`() = personaTest {
        val mine = company()
        val theirs = company()
        val token = token(mine.admin.email)
        val ownSource = personas.addSource(mine.id, SourceKind.TEXT_NOTE, "A minha nota.", "A minha nota.")
        val otherSource = personas.addSource(theirs.id, SourceKind.TEXT_NOTE, "Segredo da outra empresa.", "Segredo")

        val detail = send("GET", "/app/api/persona/sources/${ownSource.id.toHexString()}", token).obj()
        assertEquals("A minha nota.", detail.text("content"))
        assertEquals(13, detail.int("chars"))
        assertEquals(false, detail.bool("compiled"))

        for (path in listOf("not-an-id", otherSource.id.toHexString(), ObjectId().toHexString())) {
            assertEquals(HttpStatusCode.NotFound, send("GET", "/app/api/persona/sources/$path", token).status, path)
            assertEquals(HttpStatusCode.NotFound, send("DELETE", "/app/api/persona/sources/$path", token).status, path)
        }
        assertNotNull(personas.findSource(theirs.id, otherSource.id), "another company's source survives")
    }

    @Test
    fun `removing a synthesized source flags the instructions until they are rebuilt from the sources`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        send("POST", "/app/api/persona/sources", token, note("Fato A."))
        settled(token)
        send("POST", "/app/api/persona/sources", token, note("Fato B, que deixou de ser verdade."))
        val withB = settled(token)
        assertEquals("Fato A.\nFato B, que deixou de ser verdade.", withB.text("compiledInstructions"))

        val idB = withB.sources().first { it.text("label")!!.startsWith("Fato B") }.text("id")
        val afterDelete = send("DELETE", "/app/api/persona/sources/$idB", token).obj()
        assertEquals(true, afterDelete.bool("stale"))
        assertEquals("Fato A.\nFato B, que deixou de ser verdade.", afterDelete.text("compiledInstructions"), "removing a source doesn't rewrite on its own")

        val rebuilding = send("POST", "/app/api/persona/rebuild", token, buildJsonObject { })
        assertEquals(HttpStatusCode.Accepted, rebuilding.status)
        val rebuilt = settled(token)
        assertEquals("Fato A.", rebuilt.text("compiledInstructions"))
        assertEquals(false, rebuilt.bool("stale"))
        assertEquals("REBUILD", send("GET", "/app/api/persona/versions", token).list().first().jsonObject.text("change"))

        val idA = rebuilt.sources().single().text("id")
        send("DELETE", "/app/api/persona/sources/$idA", token)
        assertEquals(Triple("no_sources", null, null), send("POST", "/app/api/persona/rebuild", token, buildJsonObject { }).error())
    }

    @Test
    fun `a failed synthesis keeps the persona in use, says why, and can be retried`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        send("PUT", "/app/api/persona", token, instructions("Base escrita à mão."))
        val healthy = model.answer
        model.answer = { throw RuntimeException("OpenRouter server error: 503") }

        send("POST", "/app/api/persona/sources", token, note("Nova regra."))
        val failed = settled(token)
        assertEquals("ERROR", failed.text("status"))
        assertEquals("ai_unavailable", failed.text("lastError"))
        assertEquals("Base escrita à mão.", failed.text("compiledInstructions"))
        assertEquals(1, failed.int("pendingSources"))

        model.answer = healthy
        assertEquals(HttpStatusCode.Accepted, send("POST", "/app/api/persona/compile", token, buildJsonObject { }).status)
        val recovered = settled(token)
        assertEquals("READY", recovered.text("status"))
        assertNull(recovered.text("lastError"))
        assertEquals("Base escrita à mão.\nNova regra.", recovered.text("compiledInstructions"))
        assertEquals(HttpStatusCode.OK, send("POST", "/app/api/persona/compile", token, buildJsonObject { }).status, "nothing pending answers at once")
    }

    @Test
    fun `hand edits are bounded, recorded with their author, and skipped when nothing changed`() = personaTest {
        val c = company()
        val token = token(c.admin.email)

        val first = send("PUT", "/app/api/persona", token, instructions("  Somos a Clínica Sorriso.  ")).obj()
        assertEquals("Somos a Clínica Sorriso.", first.text("compiledInstructions"))
        assertEquals(1, first.int("version"))
        assertEquals(1, send("PUT", "/app/api/persona", token, instructions("Somos a Clínica Sorriso.")).obj().int("version"))
        verify(exactly = 1) { pipelines.evict(c.id) }

        assertEquals(
            Triple("too_long", "compiledInstructions", PersonaLimits.MAX_INSTRUCTIONS_CHARS),
            send("PUT", "/app/api/persona", token, instructions("x".repeat(PersonaLimits.MAX_INSTRUCTIONS_CHARS + 1))).error(),
        )
        val cleared = send("PUT", "/app/api/persona", token, instructions("")).obj()
        assertEquals("EMPTY", cleared.text("status"))
        val history = send("GET", "/app/api/persona/versions", token).list().map { it.jsonObject }
        assertEquals(listOf("MANUAL", "MANUAL"), history.map { it.text("change") })
        assertTrue(history.all { it.text("author") == c.admin.email })
    }

    @Test
    fun `history lists every version and any of them can be restored`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        send("PUT", "/app/api/persona", token, instructions("Versão boa."))
        send("PUT", "/app/api/persona/behavior", token, behavior { put("botName", "Sofia") })
        send("PUT", "/app/api/persona", token, instructions("Versão estragada."))

        val history = send("GET", "/app/api/persona/versions", token).list().map { it.jsonObject }
        assertEquals(listOf(3, 2, 1), history.map { it.int("version") })
        assertEquals(listOf(true, false, false), history.map { it.bool("live") })
        val v2 = send("GET", "/app/api/persona/versions/2", token).obj()
        assertEquals("Versão boa.", v2.text("compiledInstructions"))
        assertEquals("Sofia", v2.obj("behavior").text("botName"))
        assertEquals(false, v2.bool("live"))

        val restored = send("POST", "/app/api/persona/versions/2/restore", token, buildJsonObject { }).obj()
        assertEquals(4, restored.int("version"))
        assertEquals("Versão boa.", restored.text("compiledInstructions"))
        assertEquals("Sofia", restored.obj("behavior").text("botName"))
        val top = send("GET", "/app/api/persona/versions", token).list().first().jsonObject
        assertEquals("RESTORE", top.text("change"))
        assertEquals(2, top.int("restoredFrom"))
        verify(atLeast = 4) { pipelines.evict(c.id) }

        assertEquals(HttpStatusCode.NotFound, send("GET", "/app/api/persona/versions/99", token).status)
        assertEquals(HttpStatusCode.NotFound, send("GET", "/app/api/persona/versions/abc", token).status)
        assertEquals(HttpStatusCode.NotFound, send("POST", "/app/api/persona/versions/99/restore", token, buildJsonObject { }).status)
    }

    @Test
    fun `the preview shows exactly what the bot reads, saved or unsaved`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        assertNull(send("POST", "/app/api/persona/preview", token, buildJsonObject { }).obj().text("block"))
        send("PUT", "/app/api/persona", token, instructions("Somos a Clínica Sorriso."))
        send("PUT", "/app/api/persona/behavior", token, behavior { put("tone", "FORMAL") })

        val saved = send("POST", "/app/api/persona/preview", token, buildJsonObject { }).obj()
        assertEquals(PersonaPrompt.personaBlock(personas.findByTenant(c.id)), saved.text("block"))
        assertEquals(saved.text("block")!!.length, saved.int("chars"))
        assertEquals(PersonaPrompt.estimateTokens(saved.text("block")), saved.int("tokenEstimate"))

        val draft = send("POST", "/app/api/persona/preview", token, buildJsonObject { putJsonObject("draft") { putJsonObject("behavior") { put("botName", "Rita") } } }).obj()
        assertTrue("Your name is Rita." in draft.text("block")!!)
        assertFalse("Tone: formal" in draft.text("block")!!, "a draft's settings replace the saved ones")
        assertTrue("Somos a Clínica Sorriso." in draft.text("block")!!, "and the saved instructions stay")
        assertEquals(
            Triple("invalid_option", "emoji", null),
            send("POST", "/app/api/persona/preview", token, buildJsonObject { putJsonObject("draft") { putJsonObject("behavior") { put("emoji", "ALL") } } }).error(),
        )
    }

    @Test
    fun `the test chat gets the same system messages as customers do, and can try unsaved changes`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        send("PUT", "/app/api/persona", token, instructions("Somos a Clínica Sorriso."))
        send("PUT", "/app/api/persona/behavior", token, behavior { put("botName", "Sofia") })

        val reply = send("POST", "/app/api/persona/test", token, messages("user" to "Olá", "assistant" to "Olá!", "user" to "Quem és?")).obj()
        assertEquals("Olá! Como posso ajudar?", reply.text("reply"))
        assertEquals(false, reply.bool("handoff"))
        assertEquals(false, reply.bool("draft"))
        assertEquals(2, reply.int("version"))
        val seen = chats.single()
        assertEquals(PersonaPrompt.personaBlock(personas.findByTenant(c.id)), seen.system[0])
        assertEquals(SystemPrompts.CUSTOMER_GUARDRAILS, seen.system[1])
        assertTrue(seen.system[2].startsWith("Current date and time:"))
        assertEquals(listOf("user", "assistant", "user"), seen.messages.filter { it.role != "system" }.map { it.role })
        assertEquals("tenant/model", seen.model)
        assertTrue((TenantUsageRepository(mongo, c.id).tokensBySourceThisMonth()[UsageSources.PERSONA] ?: 0) > 0)

        val drafted = send(
            "POST", "/app/api/persona/test", token,
            messages("user" to "Olá", draft = buildJsonObject { put("compiledInstructions", "Rascunho por gravar.") }),
        ).obj()
        assertEquals(true, drafted.bool("draft"))
        assertTrue("Rascunho por gravar." in chats.last().system[0])
        assertTrue("Your name is Sofia." in chats.last().system[0], "the saved settings still apply to a text draft")
        assertEquals("Somos a Clínica Sorriso.", persona(token).text("compiledInstructions"), "testing a draft saves nothing")
    }

    @Test
    fun `the test chat runs read tools but refuses writes, so nothing is created`() = personaTest {
        val c = company(modules = listOf(DashboardModules.PERSONA, DashboardModules.CLIENTS, DashboardModules.QUOTES))
        val token = token(c.admin.email)
        chat = {
            when {
                afterTool -> FakeModel.text("Feito o que podia.")
                lastUser == "procura" -> FakeModel.call("search_clients", buildJsonObject { put("query", "Ana") })
                else -> FakeModel.call("create_client", buildJsonObject { put("name", "Ana"); put("phone", "+351910000000") })
            }
        }

        assertEquals("Feito o que podia.", send("POST", "/app/api/persona/test", token, messages("user" to "cria a Ana")).obj().text("reply"))
        val first = chats.first()
        assertTrue(first.offers("search_clients"))
        assertFalse(first.offers("create_client"), "write tools aren't even offered")
        assertTrue(chats[1].messages.last().content!!.contains("tool_not_available_in_test"))
        assertEquals(0, ClientRepository(mongo, c.id).search("Ana").size)

        send("POST", "/app/api/persona/test", token, messages("user" to "procura"))
        assertTrue(chats.last().messages.last().content!!.contains("\"clients\""), "the read tool ran")
    }

    @Test
    fun `a handoff in the test chat is reported with the company's own message, and pauses nothing`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        chat = { if (afterTool) FakeModel.text("Vou chamar alguém.") else if (offers("handoff_to_human")) FakeModel.handoff("pediu uma pessoa") else FakeModel.text("Sem passagem.") }

        assertEquals("Sem passagem.", send("POST", "/app/api/persona/test", token, messages("user" to "quero uma pessoa")).obj().text("reply"))
        assertFalse(chats.last().offers("handoff_to_human"), "off by default")

        send(
            "PUT", "/app/api/persona/behavior", token,
            behavior { putJsonObject("handoff") { put("enabled", true); put("message", "Um colega já lhe responde por aqui.") } },
        )
        val handedOff = send("POST", "/app/api/persona/test", token, messages("user" to "quero uma pessoa")).obj()
        assertEquals(true, handedOff.bool("handoff"))
        assertEquals("pediu uma pessoa", handedOff.text("handoffReason"))
        assertEquals("Um colega já lhe responde por aqui.", handedOff.text("reply"))
        assertTrue(chats.last().system.any { it.startsWith("Handing over to a person") })
        assertEquals(0, mongo.database.getCollection<Document>("notifications").countDocuments(Document("tenantId", c.id)))
        assertEquals(0, mongo.database.getCollection<Document>("conversations").countDocuments(Document("tenantId", c.id)))

        send("PUT", "/app/api/persona/behavior", token, behavior { putJsonObject("handoff") { put("enabled", true) } })
        assertEquals("Vou chamar alguém.", send("POST", "/app/api/persona/test", token, messages("user" to "quero uma pessoa")).obj().text("reply"), "without a message the model words it")
    }

    @Test
    fun `the test chat refuses empty chats and says when the model or the budget is the problem`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        assertEquals("messages_required", send("POST", "/app/api/persona/test", token, messages()).error().first)
        assertEquals("messages_required", send("POST", "/app/api/persona/test", token, messages("user" to "   ")).error().first)

        val long = (1..30).map { (if (it % 2 == 0) "assistant" else "user") to "mensagem $it ${"x".repeat(5_000)}" }.toTypedArray()
        send("POST", "/app/api/persona/test", token, messages(*long))
        val kept = chats.last().messages.filter { it.role != "system" }
        assertEquals(PersonaLimits.MAX_TEST_MESSAGES, kept.size)
        assertTrue(kept.all { it.content!!.length <= PersonaLimits.MAX_TEST_MESSAGE_CHARS })
        assertTrue(kept.last().content!!.startsWith("mensagem 30"))

        chat = { throw RuntimeException("timeout") }
        val unavailable = send("POST", "/app/api/persona/test", token, messages("user" to "Olá"))
        assertEquals(HttpStatusCode.BadGateway, unavailable.status)
        assertEquals("ai_unavailable", unavailable.error().first)

        chat = { FakeModel.text("  ") }
        assertEquals("no_reply", send("POST", "/app/api/persona/test", token, messages("user" to "Olá")).error().first)

        val broke = company(budget = 10)
        TenantUsageRepository(mongo, broke.id).recordUsage(10, UsageSources.PIPELINE)
        val overBudget = send("POST", "/app/api/persona/test", token(broke.admin.email), messages("user" to "Olá"))
        assertEquals(HttpStatusCode.Conflict, overBudget.status)
        assertEquals("token_budget", overBudget.error().first)
    }

    @Test
    fun `an operator opening the dashboard can change the persona, and history says so`() = personaTest {
        val c = company()
        val operator = c.operatorToken()
        assertEquals(true, persona(operator).bool("canEdit"))
        assertEquals(HttpStatusCode.OK, send("PUT", "/app/api/persona/behavior", operator, behavior { put("botName", "Sofia") }).status)
        assertEquals("operator", send("GET", "/app/api/persona/versions", operator).list().single().jsonObject.text("author"))
    }

    @Test
    fun `malformed requests are refused as invalid instead of failing the server`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        assertEquals("invalid_request", send("PUT", "/app/api/persona", token, raw = "isto não é json").error().first)
        assertEquals("invalid_request", send("POST", "/app/api/persona/sources", token, buildJsonObject { }).error().first)
        assertEquals("invalid_request", send("PUT", "/app/api/persona/behavior", token, buildJsonObject { put("rules", "não é lista") }).error().first)
        assertEquals("invalid_request", send("POST", "/app/api/persona/test", token, raw = "{\"messages\": 3}").error().first)
    }

    @Test
    fun `a company can't pile up sources past the limits`() = personaTest {
        val c = company()
        val token = token(c.admin.email)
        repeat(PersonaLimits.MAX_SOURCES) { personas.addSource(c.id, SourceKind.TEXT_NOTE, "n$it", "n$it") }
        assertEquals(Triple("too_many_sources", null, PersonaLimits.MAX_SOURCES), send("POST", "/app/api/persona/sources", token, note("Mais uma.")).error())

        val full = company()
        val fullToken = token(full.admin.email)
        personas.addSource(full.id, SourceKind.FILE, "z".repeat(PersonaLimits.MAX_TOTAL_SOURCE_CHARS - 5), "enorme.txt")
        val refused = send("POST", "/app/api/persona/sources", fullToken, note("Esta nota não cabe."))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(Triple("sources_full", null, PersonaLimits.MAX_TOTAL_SOURCE_CHARS), refused.error())
        assertEquals(HttpStatusCode.Accepted, send("POST", "/app/api/persona/sources", fullToken, note("ok")).status, "a note that fits still goes in")
        assertEquals(2, mongo.database.getCollection<Document>("persona_sources").find(Document("tenantId", full.id)).toList().size)
    }
}
