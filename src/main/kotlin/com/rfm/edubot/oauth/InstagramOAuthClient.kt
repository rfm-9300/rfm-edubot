package com.rfm.edubot.oauth

import com.rfm.edubot.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Instagram-Login OAuth token exchange (docs/plan-instagram-oauth-onboarding.md §6.2):
 *   1. code -> short-lived token + user id  (POST api.instagram.com/oauth/access_token, form-encoded)
 *   2. short-lived -> long-lived token (~60d) (GET graph.instagram.com/access_token, ig_exchange_token)
 *   3. confirm account id + username          (GET graph.instagram.com/{ver}/me)
 *
 * Any non-2xx aborts and returns null; the caller redirects to an error page and never persists a
 * partial binding. Logs ids/usernames only, never token bodies.
 */
class InstagramOAuthClient(
    private val configProvider: () -> AppConfig.InstagramConfig,
    private val httpClient: HttpClient,
) {
    constructor(config: AppConfig.InstagramConfig, httpClient: HttpClient) : this({ config }, httpClient)

    private val config: AppConfig.InstagramConfig get() = configProvider()
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger("InstagramOAuthClient")

    data class Result(val igId: String, val accessToken: String, val username: String?)

    @Serializable
    private data class ShortLivedResponse(val access_token: String? = null, val user_id: Long? = null)

    @Serializable
    private data class LongLivedResponse(val access_token: String? = null, val expires_in: Long? = null)

    @Serializable
    private data class MeResponse(val user_id: String? = null, val username: String? = null, val id: String? = null)

    /** Runs the full code -> long-lived token exchange. Returns null on any failure. */
    suspend fun exchange(code: String): Result? {
        val short = postShortLived(code) ?: return null
        val shortToken = short.access_token ?: run {
            log.error("Instagram OAuth: short-lived response had no access_token")
            return null
        }
        val longToken = exchangeLongLived(shortToken) ?: return null
        val me = fetchMe(longToken)
        val igId = me?.user_id ?: short.user_id?.toString() ?: run {
            log.error("Instagram OAuth: could not resolve Instagram account id")
            return null
        }
        // Each Instagram-Login account must be explicitly subscribed to the app's `messages`
        // webhook; without this no live DMs are delivered (the app-level config / Test button work
        // regardless). Non-fatal: log and continue — onboarding still binds the channel.
        subscribeMessages(longToken)
        log.info("Instagram OAuth success: igId={} username={}", igId, me?.username)
        return Result(igId = igId, accessToken = longToken, username = me?.username)
    }

    private suspend fun postShortLived(code: String): ShortLivedResponse? {
        val response = httpClient.submitForm(
            url = "https://api.instagram.com/oauth/access_token",
            formParameters = Parameters.build {
                append("client_id", config.appId)
                append("client_secret", config.appSecret)
                append("grant_type", "authorization_code")
                append("redirect_uri", config.redirectUri)
                append("code", code)
            },
        )
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.error("Instagram short-lived token error: status={} body={}", response.status.value, text)
            return null
        }
        return runCatching { json.decodeFromString(ShortLivedResponse.serializer(), text) }.getOrNull()
    }

    private suspend fun exchangeLongLived(shortToken: String): String? {
        val response = httpClient.get("https://graph.instagram.com/access_token") {
            url.parameters.append("grant_type", "ig_exchange_token")
            url.parameters.append("client_secret", config.appSecret)
            url.parameters.append("access_token", shortToken)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.error("Instagram long-lived token error: status={} body={}", response.status.value, text)
            return null
        }
        return runCatching { json.decodeFromString(LongLivedResponse.serializer(), text).access_token }.getOrNull()
    }

    /** Subscribe the account to the app's `messages` webhook. Returns false on failure (non-fatal). */
    private suspend fun subscribeMessages(longToken: String): Boolean {
        val response = httpClient.submitForm(
            url = "https://graph.instagram.com/${config.graphVersion}/me/subscribed_apps",
            formParameters = Parameters.build {
                append("subscribed_fields", "messages")
                append("access_token", longToken)
            },
        )
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.error("Instagram subscribe_apps failed: status={} body={}", response.status.value, text)
            return false
        }
        log.info("Instagram account subscribed to messages webhook")
        return true
    }

    private suspend fun fetchMe(longToken: String): MeResponse? {
        val response = httpClient.get("https://graph.instagram.com/${config.graphVersion}/me") {
            url.parameters.append("fields", "user_id,username")
            url.parameters.append("access_token", longToken)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.warn("Instagram /me lookup failed: status={} body={}", response.status.value, text)
            return null
        }
        return runCatching { json.decodeFromString(MeResponse.serializer(), text) }.getOrNull()
    }
}
