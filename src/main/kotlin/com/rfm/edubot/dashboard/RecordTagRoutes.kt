package com.rfm.edubot.dashboard

import com.rfm.edubot.http.ApiErrors
import com.rfm.edubot.http.ApiException
import com.rfm.edubot.tags.RecordTagRepository
import com.rfm.edubot.tags.RecordTags
import com.rfm.edubot.tags.TagKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** The tags a record now has, as answered after a save. */
@Serializable
internal data class RecordTagsDto(val tags: List<String>)

/** The tags staff typed for one record. */
@Serializable
internal data class SetRecordTagsRequest(val tags: List<String>)

/**
 * The tag routes of one kind of record, mounted inside the module that owns the record so its gate covers them:
 * `GET {path}/tags` answers every tagged record as `id → tags`, and `PUT {path}/{id}/tags` replaces one record's
 * tags (an empty list removes them). A literal `tags` segment wins over the record's own `{id}` route.
 * 400 `too_many_tags` or `tag_too_long` for tags that break [RecordTags]' limits, 404 `record_not_found`.
 */
internal fun Route.recordTagRoutes(kind: TagKind, path: String, deps: DashboardDeps) {
    get("/$path/tags") {
        call.respond(RecordTagRepository(deps.mongo, call.moduleContext().tenant.id).forKind(kind))
    }
    put("/$path/{id}/tags") {
        val tenantId = call.moduleContext().tenant.id
        val id = call.parameters["id"].orEmpty()
        if (kind.storedId(id) == null) throw ApiException(HttpStatusCode.BadRequest, ApiErrors.INVALID_ID, "id")
        val tags = when (val cleaned = RecordTags.clean(call.receive<SetRecordTagsRequest>().tags)) {
            is RecordTags.Cleaned.Valid -> cleaned.tags
            is RecordTags.Cleaned.Invalid -> throw ApiException(HttpStatusCode.BadRequest, cleaned.error, "tags")
        }
        if (!RecordTagRepository(deps.mongo, tenantId).set(kind, id, tags)) throw ApiException(HttpStatusCode.NotFound, "record_not_found")
        call.respond(RecordTagsDto(tags))
    }
}
