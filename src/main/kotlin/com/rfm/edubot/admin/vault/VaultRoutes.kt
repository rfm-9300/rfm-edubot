package com.rfm.edubot.admin.vault

import com.rfm.edubot.admin.adminEmail
import com.rfm.edubot.http.ApiErrors
import com.rfm.edubot.http.ApiException
import com.rfm.edubot.http.idParam
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** The header that carries the token the browser derives from the unlocked vault key. */
const val VAULT_AUTH_HEADER = "X-Vault-Auth"

/**
 * `/admin/api/vault`: the team's password vault. Its state (KDF and sealed vault key) answers any backoffice
 * session, which needs it to unlock; entries and the key need [VAULT_AUTH_HEADER] too. Refusals answer 403, never
 * 401, which the backoffice treats as an expired sign-in.
 */
fun Route.vaultRoutes(service: VaultService) {
    authenticate("admin-jwt") {
        route("/admin/api/vault") {
            get {
                call.noStore()
                call.respond(service.state()?.dto() ?: VaultStateDto(false, null, null, 0, null, null, null, null))
            }
            post {
                val request = call.receive<CreateVaultRequest>()
                val state = refusing { service.create(request.kdf.model(), request.wrappedKey.model(), request.authToken, call.adminEmail()) }
                call.noStore()
                call.respond(HttpStatusCode.Created, state.dto())
            }
            put("/key") {
                val request = call.receive<ChangeKeyRequest>()
                val state = refusing { service.changeKey(call.vaultToken(), request.kdf.model(), request.wrappedKey.model(), call.adminEmail()) }
                call.noStore()
                call.respond(state.dto())
            }
            get("/entries") {
                val entries = refusing { service.entries(call.vaultToken()) }
                call.noStore()
                call.respond(entries.map { it.dto() })
            }
            post("/entries") {
                val request = call.receive<SealedDto>()
                val entry = refusing { service.addEntry(call.vaultToken(), request.model(), call.adminEmail()) }
                call.noStore()
                call.respond(HttpStatusCode.Created, entry.dto())
            }
            put("/entries/{id}") {
                val id = call.idParam("id")
                val request = call.receive<UpdateEntryRequest>()
                val expected = request.expectedUpdatedAt?.let {
                    runCatching { Instant.parse(it) }.getOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, ApiErrors.INVALID_BODY, "expectedUpdatedAt")
                }
                val entry = refusing { service.updateEntry(call.vaultToken(), id, Sealed(request.iv, request.data), expected, call.adminEmail()) }
                call.noStore()
                call.respond(entry.dto())
            }
            delete("/entries/{id}") {
                val id = call.idParam("id")
                refusing { service.deleteEntry(call.vaultToken(), id, call.adminEmail()) }
                call.respond(mapOf("deleted" to true))
            }
        }
    }
}

private fun ApplicationCall.vaultToken(): String? = request.headers[VAULT_AUTH_HEADER]

private fun ApplicationCall.noStore() = response.header(HttpHeaders.CacheControl, "no-store")

/** The vault's refusals as the error contract's answers. */
private inline fun <T> refusing(block: () -> T): T = try {
    block()
} catch (e: VaultRefusal) {
    val status = when (e.kind) {
        VaultRefusal.Kind.INVALID -> HttpStatusCode.BadRequest
        VaultRefusal.Kind.NOT_FOUND -> HttpStatusCode.NotFound
        VaultRefusal.Kind.CONFLICT -> HttpStatusCode.Conflict
        VaultRefusal.Kind.FORBIDDEN -> HttpStatusCode.Forbidden
    }
    throw ApiException(status, e.error, e.detail)
}

@Serializable
private data class KdfDto(val name: String, val hash: String, val iterations: Int, val salt: String) {
    fun model() = VaultKdf(name, hash, iterations, salt)
}

@Serializable
private data class SealedDto(val iv: String, val data: String) {
    fun model() = Sealed(iv, data)
}

@Serializable
private data class CreateVaultRequest(val kdf: KdfDto, val wrappedKey: SealedDto, val authToken: String)

@Serializable
private data class ChangeKeyRequest(val kdf: KdfDto, val wrappedKey: SealedDto)

@Serializable
private data class UpdateEntryRequest(val iv: String, val data: String, val expectedUpdatedAt: String? = null)

@Serializable
private data class VaultStateDto(
    val exists: Boolean,
    val kdf: KdfDto?,
    val wrappedKey: SealedDto?,
    val entryCount: Long,
    val createdAt: String?,
    val createdBy: String?,
    val updatedAt: String?,
    val updatedBy: String?,
)

@Serializable
private data class EntryDto(
    val id: String,
    val iv: String,
    val data: String,
    val createdAt: String,
    val createdBy: String?,
    val updatedAt: String,
    val updatedBy: String?,
)

private fun VaultState.dto() = VaultStateDto(
    exists = true,
    kdf = meta.kdf.let { KdfDto(it.name, it.hash, it.iterations, it.salt) },
    wrappedKey = SealedDto(meta.wrappedKey.iv, meta.wrappedKey.data),
    entryCount = entryCount,
    createdAt = meta.createdAt.toString(),
    createdBy = meta.createdBy,
    updatedAt = meta.updatedAt.toString(),
    updatedBy = meta.updatedBy,
)

private fun VaultEntry.dto() = EntryDto(
    id = id.toHexString(),
    iv = sealed.iv,
    data = sealed.data,
    createdAt = createdAt.toString(),
    createdBy = createdBy,
    updatedAt = updatedAt.toString(),
    updatedBy = updatedBy,
)
