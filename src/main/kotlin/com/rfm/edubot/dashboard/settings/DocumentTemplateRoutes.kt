package com.rfm.edubot.dashboard.settings

import com.rfm.edubot.dashboard.DashboardDeps
import com.rfm.edubot.dashboard.DashboardFeature
import com.rfm.edubot.dashboard.DashboardModules
import com.rfm.edubot.dashboard.moduleContext
import com.rfm.edubot.http.ApiErrors
import com.rfm.edubot.http.ApiException
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.model.BuiltInDesignTemplates
import com.rfm.edubot.tenant.model.DocumentDesignStyle
import com.rfm.edubot.tenant.model.SavedDocumentTemplate
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.Path

/** `/settings/document-template`: how the company's quotes and invoices look, its logo and its saved designs. */
internal object DocumentTemplateRoutes : DashboardFeature(DashboardModules.SETTINGS) {
    override fun Route.routes(deps: DashboardDeps) {
        val tenants = deps.tenants
        get("/settings/document-template") {
            val ctx = call.moduleContext()
            call.respond(ctx.tenant.documentTemplate.dto(ctx.tenant.name))
        }
        put("/settings/document-template") {
            val ctx = call.moduleContext()
            val request = call.receive<DocumentTemplateRequest>()
            val next = request.toTemplate(ctx.tenant.documentTemplate)
            val updated = tenants.setDocumentTemplate(ctx.tenant.slug, next, SystemClock.now())
                ?: return@put call.respond(HttpStatusCode.NotFound)
            deps.pipelineFactory.evict(updated.id)
            call.respond(updated.documentTemplate.dto(updated.name))
        }
        post("/settings/document-template/logo") {
            val ctx = call.moduleContext()
            if (!call.request.contentType().match(ContentType.MultiPart.Any)) {
                throw ApiException(HttpStatusCode.UnsupportedMediaType, ApiErrors.UNSUPPORTED_MEDIA_TYPE)
            }
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
            val brandingDir = Path.of(deps.runtimeConfig.get().pdfStoragePath, ctx.tenant.slug, "branding")
            Files.createDirectories(brandingDir)
            val logoPath = brandingDir.resolve("logo.$ext")
            Files.write(logoPath, data)
            ctx.tenant.documentTemplate.logoPath?.takeIf { it != logoPath.toString() }?.let { old ->
                runCatching { Files.deleteIfExists(Path.of(old)) }
            }
            val next = ctx.tenant.documentTemplate.copy(logoPath = logoPath.toString())
            val updated = tenants.setDocumentTemplate(ctx.tenant.slug, next, SystemClock.now())
                ?: return@post call.respond(HttpStatusCode.NotFound)
            deps.pipelineFactory.evict(updated.id)
            call.respond(updated.documentTemplate.dto(updated.name))
        }
        delete("/settings/document-template/logo") {
            val ctx = call.moduleContext()
            ctx.tenant.documentTemplate.logoPath?.let { runCatching { Files.deleteIfExists(Path.of(it)) } }
            val next = ctx.tenant.documentTemplate.copy(logoPath = null)
            val updated = tenants.setDocumentTemplate(ctx.tenant.slug, next, SystemClock.now())
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            deps.pipelineFactory.evict(updated.id)
            call.respond(updated.documentTemplate.dto(updated.name))
        }
        get("/settings/document-template/logo") {
            val ctx = call.moduleContext()
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
            val ctx = call.moduleContext()
            call.respond(ctx.tenant.designPresets())
        }
        post("/settings/document-template/presets") {
            val ctx = call.moduleContext()
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
            val updated = tenants.addSavedDocumentTemplate(ctx.tenant.slug, preset, SystemClock.now())
                ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(updated.designPresets())
        }
        delete("/settings/document-template/presets/{id}") {
            val ctx = call.moduleContext()
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val updated = tenants.removeSavedDocumentTemplate(ctx.tenant.slug, id, SystemClock.now())
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            call.respond(updated.designPresets())
        }
        post("/settings/document-template/presets/{id}/apply") {
            val ctx = call.moduleContext()
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val preset = BuiltInDesignTemplates.find(id) ?: ctx.tenant.savedDocumentTemplates.find { it.id == id }
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val next = ctx.tenant.documentTemplate.copy(
                accentColor = preset.accentColor,
                showDecor = preset.showDecor,
                layout = preset.layout,
                style = DocumentDesignStyle.sanitize(preset.style),
            )
            val updated = tenants.setDocumentTemplate(ctx.tenant.slug, next, SystemClock.now())
                ?: return@post call.respond(HttpStatusCode.NotFound)
            deps.pipelineFactory.evict(updated.id)
            call.respond(updated.documentTemplate.dto(updated.name))
        }
    }
}

private const val MAX_LOGO_BYTES = 2 * 1024 * 1024
private const val MAX_SAVED_PRESETS = 20

private fun logoExtension(filename: String): String? = when (filename.substringAfterLast('.', "").lowercase()) {
    "png" -> "png"
    "jpg", "jpeg" -> "jpg"
    "webp" -> "webp"
    else -> null
}
