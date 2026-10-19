package com.rfm.edubot.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import org.bson.types.ObjectId

/** The path parameter [name] as an id; anything else answers 400 `invalid_id`. */
fun ApplicationCall.idParam(name: String): ObjectId = idValue(parameters[name], name)

/** [raw] as an id; anything else answers 400 `invalid_id` about [field]. */
fun idValue(raw: String?, field: String): ObjectId =
    raw?.takeIf(ObjectId::isValid)?.let(::ObjectId) ?: throw ApiException(HttpStatusCode.BadRequest, ApiErrors.INVALID_ID, field)

/** [raw] as a `yyyy-MM-dd` date; anything else answers 400 `invalid_date` about [field]. */
fun dateValue(raw: String, field: String): LocalDate =
    runCatching { LocalDate.parse(raw) }.getOrElse { throw ApiException(HttpStatusCode.BadRequest, ApiErrors.INVALID_DATE, field) }

/** [raw] as one of [E]'s names, exactly; anything else answers 400 with [code] about [field]. */
inline fun <reified E : Enum<E>> enumValue(raw: String, field: String, code: String = ApiErrors.INVALID_STATUS): E =
    enumValues<E>().firstOrNull { it.name == raw } ?: throw ApiException(HttpStatusCode.BadRequest, code, field)
