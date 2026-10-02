package com.rfm.edubot.mobile.core.network

import com.rfm.edubot.mobile.core.common.AppError
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thrown inside [DashboardHttpClient]; repositories turn it into an `Outcome.Failure`. */
class ApiException(val error: AppError) : Exception(error.toString())

/**
 * Every call to the tenant dashboard goes through here. It owns three things the old per-method
 * `token: String` parameter could not:
 *
 * 1. the bearer header, attached once rather than at ~30 call sites;
 * 2. status mapping, so a 403 (module off) is distinguishable from a 401 (signed out) and a 400
 *    keeps the backend's stable error code (`tax_id_required`, `id_taken`, …);
 * 3. the single place that reports an expired session, via [SessionTokens.invalidate].
 */
class DashboardHttpClient(
    baseUrl: String,
    private val tokens: SessionTokens,
    engine: HttpClientEngine? = null,
    requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val root = baseUrl.trimEnd('/')

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    private val configure: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            this.requestTimeoutMillis = requestTimeoutMillis
            connectTimeoutMillis = requestTimeoutMillis
            socketTimeoutMillis = requestTimeoutMillis
        }
    }

    val client: HttpClient = if (engine != null) HttpClient(engine, configure) else HttpClient(configure)

    /**
     * Runs one request and returns the response only when the status is a success. Pass
     * [authenticated] = false for the sign-in endpoints, which have no token yet and whose 401 means
     * "wrong password", not "your session ended".
     */
    suspend fun call(
        method: HttpMethod,
        path: String,
        body: Any? = null,
        query: List<Pair<String, String?>> = emptyList(),
        authenticated: Boolean = true,
    ): HttpResponse {
        val token = if (authenticated) tokens.current() ?: throw ApiException(AppError.Unauthorized) else null
        val response = try {
            client.request("$root/${path.trimStart('/')}") {
                this.method = method
                token?.let { bearerAuth(it) }
                query.forEach { (name, value) -> value?.let { parameter(name, it) } }
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw ApiException(AppError.Offline(failure.message))
        }
        if (response.status.isSuccess()) return response
        if (response.status.value == STATUS_UNAUTHORIZED && authenticated) tokens.invalidate()
        throw ApiException(failureFor(response.status.value, runCatching { response.bodyAsText() }.getOrNull()))
    }

    /** Maps a failing status, reading the `{"error": "code"}` body the dashboard returns on 400s. */
    fun failureFor(status: Int, body: String?): AppError = when (status) {
        STATUS_UNAUTHORIZED -> AppError.Unauthorized
        STATUS_FORBIDDEN -> AppError.Forbidden
        STATUS_NOT_FOUND -> AppError.NotFound
        in STATUS_SERVER_ERROR..599 -> AppError.Unavailable(status)
        else -> AppError.Rejected(errorCode(body), status)
    }

    private fun errorCode(body: String?): String {
        val text = body?.trim().orEmpty()
        if (!text.startsWith("{")) return ""
        return runCatching {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
    }

    fun close() = client.close()

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
        private const val STATUS_UNAUTHORIZED = 401
        private const val STATUS_FORBIDDEN = 403
        private const val STATUS_NOT_FOUND = 404
        private const val STATUS_SERVER_ERROR = 500
    }
}

/** Decodes a successful response, reporting a malformed body as a failure rather than a crash. */
suspend inline fun <reified T> HttpResponse.decode(): T = try {
    body()
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    throw ApiException(AppError.Offline(failure.message))
}

suspend inline fun <reified T> DashboardHttpClient.get(path: String, vararg query: Pair<String, String?>): T =
    call(HttpMethod.Get, path, query = query.toList()).decode()

suspend inline fun <reified T> DashboardHttpClient.post(path: String, body: Any? = null): T =
    call(HttpMethod.Post, path, body = body).decode()

suspend inline fun <reified T> DashboardHttpClient.put(path: String, body: Any? = null): T =
    call(HttpMethod.Put, path, body = body).decode()

suspend inline fun <reified T> DashboardHttpClient.patch(path: String, body: Any? = null): T =
    call(HttpMethod.Patch, path, body = body).decode()

/** For 204s and endpoints whose body the app ignores. */
suspend fun DashboardHttpClient.send(
    method: HttpMethod,
    path: String,
    body: Any? = null,
    vararg query: Pair<String, String?>,
) {
    call(method, path, body = body, query = query.toList())
}

/** Sign-in and the other endpoints that run before there is a token. */
suspend inline fun <reified T> DashboardHttpClient.postUnauthenticated(path: String, body: Any? = null): T =
    call(HttpMethod.Post, path, body = body, authenticated = false).decode()
