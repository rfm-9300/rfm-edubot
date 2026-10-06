package com.rfm.edubot.dashboard

import com.rfm.edubot.crm.ClientFields
import com.rfm.edubot.crm.CustomFields
import com.rfm.edubot.shared.SystemClock
import com.rfm.edubot.tenant.TenantRepository
import com.rfm.edubot.tenant.model.CustomField
import com.rfm.edubot.tenant.model.DirectoryFields
import com.rfm.edubot.tenant.model.FieldDirectory
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/**
 * `…/crm/clients/fields`: what the Clients directory asks staff for. Everyone with the module reads it,
 * since the client form and list follow it; only admins change it.
 */
internal fun Route.clientFieldRoutes(tenantRepository: TenantRepository) {
    get("/clients/fields") {
        val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@get call.respond(HttpStatusCode.Forbidden)
        call.respond(ClientFields.of(ctx.tenant).dto(canEdit = ctx.isAdmin()))
    }
    put("/clients/fields") {
        val ctx = call.dashboardContext()?.takeIf { it.requireModule(DashboardModules.CLIENTS) } ?: return@put call.respond(HttpStatusCode.Forbidden)
        if (!ctx.isAdmin()) return@put call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_allowed"))
        val request = call.receive<DirectoryFieldsRequest>()
        val change = CustomFields.change(
            current = ClientFields.of(ctx.tenant),
            requirable = ClientFields.REQUIRABLE.keys,
            required = request.required,
            drafts = request.custom.map { CustomFields.Draft(it.key, it.label, it.type, it.required, it.showInList, it.options) },
        )
        when (change) {
            is CustomFields.Change.Invalid -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to change.error))
            is CustomFields.Change.Valid -> {
                val updated = tenantRepository.setDirectoryFields(ctx.tenant.slug, FieldDirectory.CLIENTS, change.fields, SystemClock.now())
                    ?: return@put call.respond(HttpStatusCode.NotFound)
                call.respond(ClientFields.of(updated).dto(canEdit = true))
            }
        }
    }
}

@Serializable
internal data class DirectoryFieldsDto(
    /** Standard fields that can be required, in form order; the always-required ones come `locked`. */
    val standard: List<StandardFieldDto>,
    val custom: List<CustomFieldDto>,
    /** Whether this user may change the fields. */
    val canEdit: Boolean,
)

@Serializable
internal data class StandardFieldDto(val key: String, val required: Boolean, val locked: Boolean)

@Serializable
internal data class CustomFieldDto(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val showInList: Boolean,
    val options: List<String>,
)

/** Replaces a directory's fields: [required] standard field keys, and every custom field in order (no key for a new one). */
@Serializable
internal data class DirectoryFieldsRequest(
    val required: List<String> = emptyList(),
    val custom: List<CustomFieldRequest> = emptyList(),
)

@Serializable
internal data class CustomFieldRequest(
    val key: String? = null,
    val label: String = "",
    val type: String = "",
    val required: Boolean = false,
    val showInList: Boolean = false,
    val options: List<String> = emptyList(),
)

private fun DirectoryFields.dto(canEdit: Boolean) = DirectoryFieldsDto(
    standard = ClientFields.ALWAYS_REQUIRED.map { StandardFieldDto(it, required = true, locked = true) } +
        ClientFields.REQUIRABLE.keys.map { StandardFieldDto(it, required = it in required, locked = false) },
    custom = custom.map { it.dto() },
    canEdit = canEdit,
)

private fun CustomField.dto() = CustomFieldDto(key, label, type.name, required, showInList, options)
