package com.rfm.edubot.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** What every refused API call answers with: a stable [error] code the dashboards translate, and what it is about. */
@Serializable
data class ErrorBody(val error: String, val detail: String? = null)

/**
 * A request the API refuses, answered by StatusPages as [status] with an [ErrorBody]. Thrown where a handler
 * reads a value it can't use (an id, an enum, a date), so each handler doesn't answer those by hand.
 */
class ApiException(val status: HttpStatusCode, val code: String, val detail: String? = null) : RuntimeException("$code${detail?.let { " ($it)" }.orEmpty()}")

/** The codes the API answers for requests it can't read at all; each route's own refusals have their own codes. */
object ApiErrors {
    /** The JSON body is malformed, misses a required field or has a field of the wrong type. */
    const val INVALID_BODY = "invalid_body"

    /** A path, query or body value that names a record isn't an id. */
    const val INVALID_ID = "invalid_id"

    /** A date isn't `yyyy-MM-dd`. */
    const val INVALID_DATE = "invalid_date"

    /** A status isn't one of its kind's. */
    const val INVALID_STATUS = "invalid_status"

    /** A required query parameter is missing or can't be converted. */
    const val INVALID_REQUEST = "invalid_request"

    const val UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val INTERNAL = "Internal server error"
}
