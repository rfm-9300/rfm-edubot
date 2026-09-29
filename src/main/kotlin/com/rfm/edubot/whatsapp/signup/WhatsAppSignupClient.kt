package com.rfm.edubot.whatsapp.signup

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.SecureRandom

// Meta's register call often runs past the shared Graph client's 15 s request timeout.
private const val REGISTER_TIMEOUT_MS = 60_000L

class WhatsAppSignupClient(
    private val configProvider: () -> AppConfig.WhatsAppConfig,
    private val httpClient: HttpClient,
) {
    constructor(config: AppConfig.WhatsAppConfig, httpClient: HttpClient) : this({ config }, httpClient)

    private val config: AppConfig.WhatsAppConfig get() = configProvider()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger("WhatsAppSignupClient")
    private val random = SecureRandom()

    data class Result(
        val phoneNumberId: String,
        val accessToken: String,
        val wabaId: String,
        val displayPhoneNumber: String?,
        val verifiedName: String?,
    )

    @Serializable
    private data class AccessTokenResponse(val access_token: String? = null, val token_type: String? = null)

    @Serializable
    private data class RegisterRequest(val messaging_product: String = "whatsapp", val pin: String)

    @Serializable
    private data class SetPinRequest(val pin: String)

    @Serializable
    private data class GraphSuccessResponse(val success: Boolean? = null)

    @Serializable
    private data class PhoneNumberResponse(
        val id: String? = null,
        val display_phone_number: String? = null,
        val verified_name: String? = null,
        val quality_rating: String? = null,
        val platform_type: String? = null,
        val is_pin_enabled: Boolean? = null,
    )

    @Serializable
    private data class GraphErrorEnvelope(val error: GraphError? = null)

    @Serializable
    private data class GraphError(
        val message: String? = null,
        val type: String? = null,
        val code: Int? = null,
        @SerialName("error_subcode") val errorSubcode: Int? = null,
        val fbtrace_id: String? = null,
    )

    suspend fun connect(code: String, wabaId: String, phoneNumberId: String): Result {
        val token = exchangeCode(code)
        val phone = confirmPhoneNumber(phoneNumberId, token)
        if (phone.platform_type == "CLOUD_API") {
            log.info("WhatsApp phone number already on Cloud API, skipping register: phoneNumberId={}", phoneNumberId)
        } else {
            // A PIN left by an earlier attempt would make register fail, and wrong guesses lock the number.
            val pin = randomPin()
            if (phone.is_pin_enabled == true) setPin(phoneNumberId, token, pin)
            registerPhoneNumber(phoneNumberId, token, pin)
        }
        subscribeWaba(wabaId, token)
        log.info(
            "WhatsApp Embedded Signup success: wabaId={} phoneNumberId={} displayPhoneNumber={} verifiedName={}",
            wabaId,
            phoneNumberId,
            phone.display_phone_number,
            phone.verified_name,
        )
        return Result(
            phoneNumberId = phone.id ?: phoneNumberId,
            accessToken = token,
            wabaId = wabaId,
            displayPhoneNumber = phone.display_phone_number,
            verifiedName = phone.verified_name,
        )
    }

    private suspend fun exchangeCode(code: String): String {
        val response = httpClient.submitForm(
            url = graphUrl("oauth/access_token"),
            formParameters = Parameters.build {
                append("client_id", config.embeddedSignup.appId)
                append("client_secret", config.appSecret)
                append("code", code)
            },
        )
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw SignupException("token_exchange_failed", response.status.value, graphError(text))
        return runCatching { json.decodeFromString(AccessTokenResponse.serializer(), text).access_token }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: throw SignupException("token_exchange_missing_token", response.status.value, null)
    }

    private suspend fun setPin(phoneNumberId: String, token: String, pin: String) {
        val response = httpClient.post(graphUrl(phoneNumberId)) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(SetPinRequest(pin)), ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw SignupException("phone_pin_reset_failed", response.status.value, graphError(text))
        log.info("WhatsApp phone number had a two-step PIN, replaced it before register: phoneNumberId={}", phoneNumberId)
    }

    private suspend fun registerPhoneNumber(phoneNumberId: String, token: String, pin: String) {
        val response = httpClient.post(graphUrl("$phoneNumberId/register")) {
            bearerAuth(token)
            timeout { requestTimeoutMillis = REGISTER_TIMEOUT_MS }
            contentType(ContentType.Application.Json)
            setBody(TextContent(json.encodeToString(RegisterRequest(pin = pin)), ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) return
        val error = graphError(text)
        if (error?.message?.contains("already registered", ignoreCase = true) == true) {
            log.info("WhatsApp phone number already registered: phoneNumberId={}", phoneNumberId)
            return
        }
        throw SignupException("phone_register_failed", response.status.value, error)
    }

    private suspend fun subscribeWaba(wabaId: String, token: String) {
        val response = httpClient.post(graphUrl("$wabaId/subscribed_apps")) {
            bearerAuth(token)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw SignupException("waba_subscribe_failed", response.status.value, graphError(text))
        val success = runCatching { json.decodeFromString(GraphSuccessResponse.serializer(), text).success }.getOrNull()
        if (success == false) throw SignupException("waba_subscribe_not_successful", response.status.value, null)
    }

    private suspend fun confirmPhoneNumber(phoneNumberId: String, token: String): PhoneNumberResponse {
        val response = httpClient.get(graphUrl(phoneNumberId)) {
            bearerAuth(token)
            url.parameters.append("fields", "display_phone_number,verified_name,quality_rating,platform_type,is_pin_enabled")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw SignupException("phone_confirm_failed", response.status.value, graphError(text))
        return runCatching { json.decodeFromString(PhoneNumberResponse.serializer(), text) }
            .getOrElse { throw SignupException("phone_confirm_invalid_response", response.status.value, null) }
    }

    private fun graphUrl(path: String) = "https://graph.facebook.com/${config.apiVersion}/$path"

    private fun randomPin(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    private fun graphError(text: String): SignupException.GraphErrorInfo? = runCatching {
        val error = json.decodeFromString(GraphErrorEnvelope.serializer(), text).error ?: return@runCatching null
        SignupException.GraphErrorInfo(
            message = error.message,
            type = error.type,
            code = error.code,
            subcode = error.errorSubcode,
            traceId = error.fbtrace_id,
        )
    }.getOrNull()
}

class SignupException(
    val reason: String,
    val statusCode: Int,
    val graphError: GraphErrorInfo?,
) : RuntimeException(reason) {
    data class GraphErrorInfo(
        val message: String?,
        val type: String?,
        val code: Int?,
        val subcode: Int?,
        val traceId: String?,
    )
}
