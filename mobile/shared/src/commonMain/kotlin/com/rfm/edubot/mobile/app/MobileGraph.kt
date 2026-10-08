package com.rfm.edubot.mobile.app

import com.rfm.edubot.mobile.core.common.DeviceSigner
import com.rfm.edubot.mobile.core.common.LocationProvider
import com.rfm.edubot.mobile.core.common.SnapshotStore
import com.rfm.edubot.mobile.core.common.TokenStore
import com.rfm.edubot.mobile.core.common.VoiceInput
import com.rfm.edubot.mobile.core.data.AgentsRepository
import com.rfm.edubot.mobile.core.data.AssistantRepository
import com.rfm.edubot.mobile.core.data.BookingsRepository
import com.rfm.edubot.mobile.core.data.CrmRepository
import com.rfm.edubot.mobile.core.data.InboxRepository
import com.rfm.edubot.mobile.core.data.NotificationsRepository
import com.rfm.edubot.mobile.core.data.OverviewRepository
import com.rfm.edubot.mobile.core.data.PersonaRepository
import com.rfm.edubot.mobile.core.data.SessionRepository
import com.rfm.edubot.mobile.core.data.SettingsRepository
import com.rfm.edubot.mobile.core.data.SnapshotCache
import com.rfm.edubot.mobile.core.data.TimeClockRepository
import com.rfm.edubot.mobile.core.network.DashboardHttpClient
import com.rfm.edubot.mobile.core.network.KtorAgentsApi
import com.rfm.edubot.mobile.core.network.KtorAssistantApi
import com.rfm.edubot.mobile.core.network.KtorBookingsApi
import com.rfm.edubot.mobile.core.network.KtorCrmApi
import com.rfm.edubot.mobile.core.network.KtorInboxApi
import com.rfm.edubot.mobile.core.network.KtorNotificationsApi
import com.rfm.edubot.mobile.core.network.KtorOverviewApi
import com.rfm.edubot.mobile.core.network.KtorPersonaApi
import com.rfm.edubot.mobile.core.network.KtorSessionApi
import com.rfm.edubot.mobile.core.network.KtorSettingsApi
import com.rfm.edubot.mobile.core.network.KtorTimeClockApi
import com.rfm.edubot.mobile.core.network.SessionTokens

/**
 * Builds the object graph once per app launch.
 *
 * Screens take the repository they need and nothing else. Previously each one received the whole
 * `DashboardApi` plus the raw bearer token, which meant every screen could call anything and had to
 * handle auth itself.
 *
 * There is no engine override here on purpose: the HTTP layer's own tests live in `core:network`
 * with the mock engine, and screen tests build a repository over a fake rather than a fake server.
 */
class MobileGraph(
    baseUrl: String,
    tokenStore: TokenStore,
    snapshotStore: SnapshotStore,
    val voiceInput: VoiceInput,
    val location: LocationProvider,
    val signer: DeviceSigner,
) {
    private val tokens = SessionTokens(tokenStore)
    private val http = DashboardHttpClient(baseUrl = baseUrl, tokens = tokens)
    private val cache = SnapshotCache(snapshotStore, http.json)

    val session = SessionRepository(KtorSessionApi(http), tokens, cache)
    val overview = OverviewRepository(KtorOverviewApi(http), cache)
    val inbox = InboxRepository(KtorInboxApi(http), cache)
    val crm = CrmRepository(KtorCrmApi(http), cache)
    val bookings = BookingsRepository(KtorBookingsApi(http), cache)
    val agents = AgentsRepository(KtorAgentsApi(http), cache)
    val assistant = AssistantRepository(KtorAssistantApi(http))
    val persona = PersonaRepository(KtorPersonaApi(http), cache)
    val notifications = NotificationsRepository(KtorNotificationsApi(http), cache)
    val settings = SettingsRepository(KtorSettingsApi(http))

    private var timeClock: TimeClockRepository? = null

    /** The signed-in employee's clock; whoever signs in next on the same phone gets a fresh one. */
    fun timeClock(employeeId: String): TimeClockRepository =
        timeClock?.takeIf { it.employeeId == employeeId }
            ?: TimeClockRepository(KtorTimeClockApi(http), cache, employeeId).also { timeClock = it }

    fun close() = http.close()
}
