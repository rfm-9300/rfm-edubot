package com.rfm.edubot.mobile.core.data

import com.rfm.edubot.mobile.core.common.Outcome
import com.rfm.edubot.mobile.core.model.Account
import com.rfm.edubot.mobile.core.model.AppNotification
import com.rfm.edubot.mobile.core.model.AssistantThread
import com.rfm.edubot.mobile.core.model.AssistantThreadDetail
import com.rfm.edubot.mobile.core.model.Notifications
import com.rfm.edubot.mobile.core.model.Overview
import com.rfm.edubot.mobile.core.model.Persona
import com.rfm.edubot.mobile.core.model.PersonaTest
import com.rfm.edubot.mobile.core.model.PersonaTestMessage
import com.rfm.edubot.mobile.core.model.WebWidget
import com.rfm.edubot.mobile.core.network.AssistantApi
import com.rfm.edubot.mobile.core.network.NotificationsApi
import com.rfm.edubot.mobile.core.network.OverviewApi
import com.rfm.edubot.mobile.core.network.PersonaApi
import com.rfm.edubot.mobile.core.network.SettingsApi

class OverviewRepository(api: OverviewApi, cache: SnapshotCache) {
    val overview = CachedResource(
        key = "overview",
        serializer = Overview.serializer(),
        cache = cache,
        fetch = { api.overview(extended = true) },
    )
}

class NotificationsRepository(
    private val api: NotificationsApi,
    cache: SnapshotCache,
) {
    val notifications = CachedResource(
        key = "notifications",
        serializer = Notifications.serializer(),
        cache = cache,
        fetch = { api.notifications() },
    )

    suspend fun markRead(notification: AppNotification): Outcome<Unit> {
        if (notification.read) return Outcome.Success(Unit)
        val done = apiCall { api.markRead(notification.id) }
        if (done is Outcome.Success) {
            notifications.mutate { current ->
                Notifications(
                    items = current.items.map { if (it.id == notification.id) it.copy(read = true) else it },
                    unread = (current.unread - 1).coerceAtLeast(0),
                )
            }
        }
        return done
    }

    suspend fun markAllRead(): Outcome<Unit> {
        val done = apiCall { api.markAllRead() }
        if (done is Outcome.Success) {
            notifications.mutate { current ->
                Notifications(items = current.items.map { it.copy(read = true) }, unread = 0)
            }
        }
        return done
    }
}

class AssistantRepository(private val api: AssistantApi) {
    suspend fun threads(): Outcome<List<AssistantThread>> = apiCall { api.threads() }

    suspend fun thread(threadId: String): Outcome<AssistantThreadDetail> = apiCall { api.thread(threadId) }

    suspend fun createThread(title: String): Outcome<AssistantThread> = apiCall { api.createThread(title) }

    suspend fun send(threadId: String, content: String): Outcome<AssistantThreadDetail> =
        apiCall { api.sendMessage(threadId, content) }

    /** Confirms or cancels a tool call the assistant proposed; both return the whole thread. */
    suspend fun decide(threadId: String, actionId: String, confirm: Boolean): Outcome<AssistantThreadDetail> =
        apiCall { if (confirm) api.confirmAction(threadId, actionId) else api.cancelAction(threadId, actionId) }
}

class PersonaRepository(
    private val api: PersonaApi,
    cache: SnapshotCache,
) {
    val persona = CachedResource(
        key = "persona",
        serializer = Persona.serializer(),
        cache = cache,
        fetch = { api.persona() },
    )

    suspend fun save(compiledInstructions: String): Outcome<Persona> = store { api.updatePersona(compiledInstructions) }

    suspend fun addSource(content: String): Outcome<Persona> = store { api.addSource(content) }

    suspend fun deleteSource(id: String): Outcome<Persona> {
        val done = apiCall { api.deleteSource(id) }
        return when (done) {
            is Outcome.Failure -> done
            is Outcome.Success -> persona.refresh()
        }
    }

    suspend fun rebuild(): Outcome<Persona> = store { api.rebuild() }

    /** Sends a trial conversation through the compiled prompt and returns the bot's reply. */
    suspend fun test(messages: List<PersonaTestMessage>): Outcome<String> = apiCall { api.test(PersonaTest(messages)) }

    private suspend fun store(block: suspend () -> Persona): Outcome<Persona> {
        val saved = apiCall { block() }
        saved.valueOrNull?.let { persona.put(it) }
        return saved
    }
}

class SettingsRepository(private val api: SettingsApi) {
    suspend fun webWidget(): Outcome<WebWidget> = apiCall { api.webWidget() }

    suspend fun updateLocale(locale: String): Outcome<String> = apiCall { api.updateLocale(locale) }

    suspend fun account(): Outcome<Account> = apiCall { api.account() }
}
