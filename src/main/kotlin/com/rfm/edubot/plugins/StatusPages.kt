package com.rfm.edubot.plugins

import com.rfm.edubot.http.ApiErrors
import com.rfm.edubot.http.ApiException
import com.rfm.edubot.http.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.MissingRequestParameterException
import io.ktor.server.plugins.ParameterConversionException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond

/**
 * The API's error contract: requests it can't read answer 4xx with an [ErrorBody] code, anything else 500.
 * Ktor reports a body it can't decode as the handler's type (malformed JSON, a missing or mistyped field)
 * as a [BadRequestException]; handlers report ids, dates and statuses they can't read as an [ApiException].
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.refuse(cause.status, cause.code, cause.detail) }
        exception<BadRequestException> { call, cause ->
            val code = if (cause is MissingRequestParameterException || cause is ParameterConversionException) ApiErrors.INVALID_REQUEST else ApiErrors.INVALID_BODY
            call.refuse(HttpStatusCode.BadRequest, code)
        }
        exception<UnsupportedMediaTypeException> { call, _ -> call.refuse(HttpStatusCode.UnsupportedMediaType, ApiErrors.UNSUPPORTED_MEDIA_TYPE) }
        exception<CannotTransformContentToTypeException> { call, _ -> call.refuse(HttpStatusCode.UnsupportedMediaType, ApiErrors.UNSUPPORTED_MEDIA_TYPE) }
        exception<PayloadTooLargeException> { call, _ -> call.refuse(HttpStatusCode.PayloadTooLarge, ApiErrors.PAYLOAD_TOO_LARGE) }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error on ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody(ApiErrors.INTERNAL))
        }
    }
}

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, code: String, detail: String? = null) {
    application.environment.log.info("Refused {} {}: {} {}", request.httpMethod.value, request.local.uri, status.value, code)
    respond(status, ErrorBody(code, detail))
}
